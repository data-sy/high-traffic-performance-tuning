package com.project.service.coupon.async;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * v6a 워커 — 처리만 (T1: 파사드와 <b>빈 분리</b>로 self-invocation 회피, v2 교훈 재사용).
 * {@code @Async("couponExecutor")} — 기본 executor 금지 (T2), 큐 = JVM 힙 (앱 사망 시 대기분 통째 증발).
 */
@Service
public class CouponIssueWorkerV6a {

    private final CouponIssueProcessor processor;

    public CouponIssueWorkerV6a(CouponIssueProcessor processor) {
        this.processor = processor;
    }

    @Async("couponExecutor")
    public void processAsync(String requestId, Long couponId, Long userId) {
        processor.process(requestId, couponId, userId);
    }
}
