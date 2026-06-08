package com.project.infrastructure.redis;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;

/**
 * v5 커스텀 분산 락 — {@code SET key token NX PX ttl} 획득 + Lua compare-and-delete 해제.
 *
 * <p>⚠️ <b>직렬화 함정(메모리 v5 핵심):</b> 기본 {@code RedisTemplate}의 value serializer가 JSON이면 토큰에
 * 따옴표가 붙어 Lua의 raw 문자열 비교가 <i>조용히 항상 실패</i> → release 불가 → 매 요청이 TTL까지 락 점유.
 * 그래서 acquire/release를 <b>락 전용 {@link StringRedisTemplate}</b>(String serializer)로 일치시킨다.
 */
@Repository
public class RedisLockRepository {

    /** 자기 토큰일 때만 DEL — 오해제(다른 홀더 락 삭제) 방지. 단 이것은 release 안전일 뿐, 상호배제(fencing) 보장 아님. */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisLockRepository(RedisConnectionFactory connectionFactory) {
        this.redis = new StringRedisTemplate(connectionFactory);
        this.redis.afterPropertiesSet();
    }

    /** SET key token NX PX ttl. 획득 성공 시 true. */
    public boolean tryLock(String key, String token, long ttlMillis) {
        Boolean ok = redis.opsForValue().setIfAbsent(key, token, Duration.ofMillis(ttlMillis));
        return Boolean.TRUE.equals(ok);
    }

    /** compare-and-delete: 자기 토큰이면 해제(true), 토큰 불일치/만료면 no-op(false). */
    public boolean unlock(String key, String token) {
        Long deleted = redis.execute(UNLOCK_SCRIPT, List.of(key), token);
        return Long.valueOf(1L).equals(deleted);
    }
}
