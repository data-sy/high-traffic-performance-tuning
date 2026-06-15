package com.project.service.coupon.async;

import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.infrastructure.asyncqueue.IssueListQueueRepository;
import com.project.infrastructure.asyncqueue.IssueStatusRepository;
import org.springframework.stereotype.Service;

/**
 * v6b 파사드 — 적재만 하고 202 (큐 = Redis List — 앱 밖, 앱 재시작에도 큐 생존).
 * 기록 순서 계약은 v6a와 동형: 선생성 → 적재 → 실패 시 삭제 → 202.
 */
@Service
public class CouponIssueFacadeV6b {

    private final IssueListQueueRepository queue;
    private final IssueStatusRepository statusRepository;
    private final ObjectMapper objectMapper;

    public CouponIssueFacadeV6b(IssueListQueueRepository queue,
                                IssueStatusRepository statusRepository,
                                ObjectMapper objectMapper) {
        this.queue = queue;
        this.statusRepository = statusRepository;
        this.objectMapper = objectMapper;
    }

    public String enqueue(Long couponId, Long userId) {
        String requestId = UUID.randomUUID().toString();
        long seq = statusRepository.nextSeq();
        statusRepository.createAccepted(requestId, "v6b", userId, seq, System.currentTimeMillis());
        if (!queue.offer(toJson(new IssueMessage(requestId, couponId, userId)))) {
            statusRepository.deleteOnReject(requestId);     // 회계 모집단 고정
            throw new QueueFullException("v6b");
        }
        return requestId;
    }

    private String toJson(IssueMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("issue message 직렬화 실패", e);
        }
    }
}
