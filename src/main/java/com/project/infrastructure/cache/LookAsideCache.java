package com.project.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.api.product.dto.ProductListResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * look-aside 캐시 추상화 (A-5) — 전용 Redis(6380) 저장 + 직렬화 안전 형태(DR-2).
 *
 * <p>방어 전략(고정TTL / single-flight / 논리만료+비동기갱신 / null+jitter)은 버전 서비스가 이 빈의
 * 기본 연산(put/get/null/논리만료/delete/ttl)을 조합해 구현한다 — 이 빈은 전략-중립 저장소다(책임 분리:
 * 캐시 추상화 ≠ 락 ≠ 오리진 로더 ≠ ops).
 *
 * <p><b>직렬화(DR-2):</b> {@code PageImpl}/{@code GenericJackson2JsonRedisSerializer} 다형(@class) 경로는
 * PageImpl no-arg 결함·LocalDateTime 모듈 누락으로 라운드트립이 깨진다. 그래서 값을 {@link CacheEnvelope}
 * 로 변환해 <b>타입 명시 JSON 문자열</b>로 저장하고({@code StringRedisTemplate}), 읽을 때
 * {@code readValue(json, CacheEnvelope.class)} 로 복원한다 — 다형 타입 비의존 + JavaTimeModule 로 LocalDateTime
 * 안전. 부팅 self-check(적재→재로드→동치)로 검증하고, <b>역직렬화 실패는 미스로 흡수하지 않고 예외로 던진다</b>
 * (silent 미스 흡수 금지 — 호출측/측정이 abort).
 */
@Component
@Profile("phase3-8")
public class LookAsideCache {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public LookAsideCache(RedisConnectionFactory connectionFactory) {
        // 캐시 클라이언트는 spring.data.redis.*(전용 스택 6380)를 따르는 단일 팩토리 재사용(별도 하드코딩 포트 금지·A-1).
        this.redis = new StringRedisTemplate(connectionFactory);
        this.redis.afterPropertiesSet();
        this.mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())                       // LocalDateTime 안전 라운드트립
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    public String keyPrefix() {
        return CachePins.KEY_PREFIX;
    }

    /** 캐시 키 = prefix + suffix(예: 카테고리). page/size 는 핀(page1/size20)이라 키에 포함 안 함. */
    public String fullKey(String suffix) {
        return CachePins.KEY_PREFIX + suffix;
    }

    // ── 읽기 ──────────────────────────────────────────────────────────────────────
    /** 엔벨로프 조회. 미존재면 null. <b>역직렬화 실패는 예외(silent 미스 흡수 금지·DR-2).</b> */
    public CacheEnvelope get(String fullKey) {
        String json = redis.opsForValue().get(fullKey);
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, CacheEnvelope.class);
        } catch (Exception e) {
            throw new CacheDeserializationException(
                    "캐시 역직렬화 실패(DR-2 — 미스로 흡수 금지): key=" + fullKey, e);
        }
    }

    public boolean exists(String fullKey) {
        return Boolean.TRUE.equals(redis.hasKey(fullKey));
    }

    /** 잔여 물리 TTL(ms). 키 없음 = -2, TTL 없음 = -1(Redis PTTL 의미). */
    public long pttlMs(String fullKey) {
        Long ms = redis.getExpire(fullKey, java.util.concurrent.TimeUnit.MILLISECONDS);
        return ms == null ? -2L : ms;
    }

    // ── 쓰기 ──────────────────────────────────────────────────────────────────────
    /** 일반 값 적재(하드 TTL 군 v2·v3·v5). 논리 만료 없음. */
    public void putValue(String fullKey, CachedProductList payload, long ttlMs) {
        CacheEnvelope env = new CacheEnvelope(payload, false, 0L, nowMs());
        store(fullKey, env, ttlMs);
    }

    /** 널/빈 결과 적재(v5 페네트레이션 흡수). payload=null, nullEntry=true. */
    public void putNull(String fullKey, long ttlMs) {
        CacheEnvelope env = new CacheEnvelope(null, true, 0L, nowMs());
        store(fullKey, env, ttlMs);
    }

    /** v4 — 긴 물리 TTL + 논리 만료 시각. 논리 만료 후 첫 요청이 스테일 반환 + 백그라운드 단일 refresh. */
    public void putLogical(String fullKey, CachedProductList payload, long physicalTtlMs, long logicalExpiryAtMs) {
        CacheEnvelope env = new CacheEnvelope(payload, false, logicalExpiryAtMs, nowMs());
        store(fullKey, env, physicalTtlMs);
    }

    /** v4 논리 만료 마킹(물리 값 보존·물리 DEL 아님, A-6 /expire 버전군 분기). 잔여 물리 TTL 유지. */
    public void markLogicalExpired(String fullKey) {
        CacheEnvelope env = get(fullKey);
        if (env == null) {
            return;
        }
        long remaining = pttlMs(fullKey);
        long ttl = remaining > 0 ? remaining : CachePins.TTL_BASE_MS;
        CacheEnvelope marked = new CacheEnvelope(env.payload(), env.nullEntry(), nowMs() - 1, env.storedAtEpochMs());
        store(fullKey, marked, ttl);
    }

    /** 물리 삭제(하드 TTL 군 /expire·/evict → 콜드 미스 유발). */
    public void delete(String fullKey) {
        redis.delete(fullKey);
    }

    private void store(String fullKey, CacheEnvelope env, long ttlMs) {
        try {
            String json = mapper.writeValueAsString(env);
            redis.opsForValue().set(fullKey, json, Duration.ofMillis(ttlMs));
        } catch (Exception e) {
            throw new CacheDeserializationException("캐시 직렬화 실패: key=" + fullKey, e);
        }
    }

    private long nowMs() {
        return System.currentTimeMillis();
    }

    // ── 부팅 self-check (DR-2) ────────────────────────────────────────────────────
    /** 샘플 값 적재→재로드→동치(JSON 재직렬화 비교). 실패 시 예외 → 기동 실패. */
    public void selfCheckRoundTrip() {
        String key = fullKey("__selfcheck__");
        ProductListResponse sample =
                new ProductListResponse(1L, "selfcheck", 1000, "전자기기", LocalDateTime.of(2026, 6, 30, 0, 0, 0));
        CachedProductList payload = new CachedProductList(List.of(sample), 1L);
        try {
            String before = mapper.writeValueAsString(payload);
            putValue(key, payload, 5_000);
            CacheEnvelope back = get(key);
            if (back == null || back.payload() == null) {
                throw new CacheDeserializationException(
                        "캐시 self-check 실패(DR-2): 재로드 결과가 null — back=" + back, null);
            }
            String after = mapper.writeValueAsString(back.payload());
            if (!before.equals(after)) {
                throw new CacheDeserializationException(
                        "캐시 self-check 실패(DR-2): 적재→재로드 동치 깨짐\n  before=" + before + "\n  after =" + after, null);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new CacheDeserializationException("캐시 self-check 직렬화 실패(DR-2)", e);
        } finally {
            delete(key);
        }
    }

    /** 역직렬화/직렬화 실패 — 미스로 흡수하지 않고 호출측/측정이 abort 하도록 던지는 예외(DR-2). */
    public static class CacheDeserializationException extends RuntimeException {
        public CacheDeserializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
