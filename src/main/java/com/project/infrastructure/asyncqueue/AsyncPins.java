package com.project.infrastructure.asyncqueue;

/**
 * Phase 3-4 신규 핀의 단일 소스 (핀 표와 1:1).
 * 하네스 {@code assert_ops_pins}가 {@code GET /api/v6/ops/pins} 실측값을 이 값들과 대조하고
 * 불일치 시 측정을 중단한다.
 */
public final class AsyncPins {

    /** 소비 동시성 4 통일 — max=core 고정 (관찰 축 큐 만석 구간에서 v6a만 증원되는 오염 방지). */
    public static final int EXECUTOR_CORE = 4;
    public static final int EXECUTOR_MAX = 4;
    public static final int EXECUTOR_QUEUE_CAPACITY = 10_000;
    /** 거부 정책 — 429 QUEUE_FULL 매핑 (런타임 assert로 강제). */
    public static final String REJECTION_POLICY = "AbortPolicy";

    /** v6b/v6c 큐 상한 (v6a queueCapacity와 일치 — 입구 거절 임계 통일, 근사 가드). */
    public static final int QUEUE_CAP = 10_000;
    /** 전 버전 소비 동시성 (v6a 풀 / v6b 워커 스레드 / v6c 컨슈머 c1~c4). */
    public static final int CONSUMERS = 4;

    public static final int BRPOP_TIMEOUT_MS = 1_000;
    public static final int XREAD_BLOCK_MS = 1_000;
    public static final int XREAD_COUNT = 10;
    public static final int XAUTOCLAIM_INTERVAL_MS = 5_000;
    public static final int XAUTOCLAIM_MIN_IDLE_MS = 10_000;

    /** graceful shutdown 대기 (T8). */
    public static final int GRACEFUL_SHUTDOWN_SEC = 10;
    /** 상태 해시 TTL — 런 간 격리는 TTL이 아니라 하네스 키 reset이 담당. */
    public static final int STATUS_TTL_SEC = 3_600;

    private AsyncPins() {
    }
}
