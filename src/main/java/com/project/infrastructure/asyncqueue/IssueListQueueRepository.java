package com.project.infrastructure.asyncqueue;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * v6b 큐 = Redis List. LPUSH 적재 / BRPOP 소비 — <b>꺼내는 순간 큐에서 사라진다(ack 없음)</b>.
 * 꺼낸 직후 ~ 커밋 전 사이에 워커가 죽으면 그 건은 유실된다 (at-most-once 구간) — 이 유실의
 * 의도적 재현·기록이 v6b 칸의 핵심 실험이다 (T5).
 *
 * <p>큐 상한은 LLEN 선검사 — 비원자 근사 가드 (정합성 아닌 백프레셔 목적, 레이스 허용).
 */
@Repository
public class IssueListQueueRepository {

    public static final String QUEUE_KEY = "coupon:issue:queue";

    private final StringRedisTemplate redis;

    public IssueListQueueRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @return 적재 성공 여부 — 상한(근사) 초과면 false (파사드가 429로 매핑) */
    public boolean offer(String messageJson) {
        Long depth = redis.opsForList().size(QUEUE_KEY);
        if (depth != null && depth >= AsyncPins.QUEUE_CAP) {
            return false;
        }
        redis.opsForList().leftPush(QUEUE_KEY, messageJson);
        return true;
    }

    /** BRPOP — 타임아웃(핀 1s) 내 메시지 없으면 null. 블로킹 명령은 Lettuce 전용 커넥션 사용. */
    public String poll() {
        return redis.opsForList().rightPop(QUEUE_KEY, Duration.ofMillis(AsyncPins.BRPOP_TIMEOUT_MS));
    }
}
