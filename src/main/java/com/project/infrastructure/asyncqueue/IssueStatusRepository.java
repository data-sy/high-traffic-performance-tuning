package com.project.infrastructure.asyncqueue;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 발급 상태 해시 (관측 전용. 발급 사실의 진실 소스는 coupon_issue 행 수다).
 *
 * <p>기록 순서 계약: 파사드는 <b>선생성(ACCEPTED) → 큐 적재 →
 * 실패 시 삭제</b> 순서를 지키고, 적재 성공 후 status를 다시 쓰지 않는다 — ACCEPTED를 덮는
 * 방향은 워커의 터미널 기록({@link #complete}) 단방향뿐이다.
 *
 * <p>{@code userId} 필드는 T9 역전 산출의 조인 키.
 */
@Repository
public class IssueStatusRepository {

    public static final String STATUS_KEY_PREFIX = "coupon:issue:status:";
    public static final String SEQ_KEY = "coupon:issue:seq";

    private final StringRedisTemplate redis;

    public IssueStatusRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 적재 시퀀스 발급 — INCR과 큐 적재는 비원자라 T9에 입구 잡음이 섞인다. */
    public long nextSeq() {
        Long seq = redis.opsForValue().increment(SEQ_KEY);
        return seq == null ? 0L : seq;
    }

    public void createAccepted(String requestId, String version, Long userId, long enqueueSeq, long acceptedAt) {
        String key = STATUS_KEY_PREFIX + requestId;
        redis.opsForHash().putAll(key, Map.of(
                "status", "ACCEPTED",
                "version", version,
                "userId", String.valueOf(userId),
                "enqueueSeq", String.valueOf(enqueueSeq),
                "acceptedAt", String.valueOf(acceptedAt)));
        redis.expire(key, Duration.ofSeconds(AsyncPins.STATUS_TTL_SEC));
    }

    /** 적재 실패(429) 경로 — 해시 삭제로 회계 모집단(202 수신 requestId)을 고정한다. */
    public void deleteOnReject(String requestId) {
        redis.delete(STATUS_KEY_PREFIX + requestId);
    }

    /** 워커 터미널 기록 — ISSUED / SOLD_OUT / FAILED. completedAt은 커밋(처리 종료) 후 시각. */
    public void complete(String requestId, String status, long completedAt) {
        redis.opsForHash().putAll(STATUS_KEY_PREFIX + requestId, Map.of(
                "status", status,
                "completedAt", String.valueOf(completedAt)));
    }

    /** 상태 조회 (T7 조회 API 경로). 미존재·TTL 만료 시 빈 맵. */
    public Map<String, String> find(String requestId) {
        return redis.opsForHash().entries(STATUS_KEY_PREFIX + requestId).entrySet().stream()
                .collect(Collectors.toMap(e -> String.valueOf(e.getKey()), e -> String.valueOf(e.getValue())));
    }
}
