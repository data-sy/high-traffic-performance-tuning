package com.project.service.coupon.async;

/**
 * 큐 상한 초과 — 429 QUEUE_FULL. 백프레셔 발동이지 오류가 아니다 (T7 — 유한 큐는
 * 결국 입구에서 거절을 부활시킨다). v6a는 AbortPolicy, v6b/v6c는 근사 LLEN/XLEN 가드가 던진다.
 */
public class QueueFullException extends RuntimeException {

    public QueueFullException(String version) {
        super("queue full (cap pinned): " + version);
    }
}
