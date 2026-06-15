package com.project.service.coupon.async;

import java.util.UUID;

import com.project.infrastructure.asyncqueue.IssueStatusRepository;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

/**
 * v6a 파사드 — 적재만 하고 202 (큐 = couponExecutor 대기열 = JVM 힙).
 *
 * <p>기록 순서 계약: seq 발급 → 해시 선생성(ACCEPTED) → 큐 적재 → 적재 실패 시
 * 해시 삭제 후 429. 적재 성공 후 status는 다시 쓰지 않는다 (워커 터미널 기록 단방향).
 */
@Service
public class CouponIssueFacadeV6a {

    private final CouponIssueWorkerV6a worker;
    private final IssueStatusRepository statusRepository;

    public CouponIssueFacadeV6a(CouponIssueWorkerV6a worker, IssueStatusRepository statusRepository) {
        this.worker = worker;
        this.statusRepository = statusRepository;
    }

    public String enqueue(Long couponId, Long userId) {
        String requestId = UUID.randomUUID().toString();
        long seq = statusRepository.nextSeq();
        statusRepository.createAccepted(requestId, "v6a", userId, seq, System.currentTimeMillis());
        try {
            worker.processAsync(requestId, couponId, userId);   // 다른 빈 → 프록시 경유 보장 (T1)
        } catch (TaskRejectedException e) {                     // AbortPolicy — 큐 10,000 만석 (T2/T7)
            statusRepository.deleteOnReject(requestId);
            throw new QueueFullException("v6a");
        }
        return requestId;
    }
}
