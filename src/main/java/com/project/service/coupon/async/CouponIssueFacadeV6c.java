package com.project.service.coupon.async;

import java.util.Map;
import java.util.UUID;

import com.project.infrastructure.asyncqueue.IssueStatusRepository;
import com.project.infrastructure.asyncqueue.IssueStreamQueueRepository;
import org.springframework.stereotype.Service;

/**
 * v6c 파사드 — 적재만 하고 202 (큐 = Redis Stream). 기록 순서 계약은 v6a/v6b와 동형.
 */
@Service
public class CouponIssueFacadeV6c {

    private final IssueStreamQueueRepository queue;
    private final IssueStatusRepository statusRepository;

    public CouponIssueFacadeV6c(IssueStreamQueueRepository queue, IssueStatusRepository statusRepository) {
        this.queue = queue;
        this.statusRepository = statusRepository;
    }

    public String enqueue(Long couponId, Long userId) {
        String requestId = UUID.randomUUID().toString();
        long seq = statusRepository.nextSeq();
        statusRepository.createAccepted(requestId, "v6c", userId, seq, System.currentTimeMillis());
        boolean offered = queue.offer(Map.of(
                "requestId", requestId,
                "couponId", String.valueOf(couponId),
                "userId", String.valueOf(userId)));
        if (!offered) {
            statusRepository.deleteOnReject(requestId);     // 회계 모집단 고정
            throw new QueueFullException("v6c");
        }
        return requestId;
    }
}
