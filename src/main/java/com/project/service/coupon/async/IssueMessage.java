package com.project.service.coupon.async;

/**
 * v6b/v6c 큐 메시지 (JSON 직렬화). 상태·타임스탬프는 상태 해시가 들고, 메시지는 처리에 필요한
 * 최소 식별자만 나른다.
 */
public record IssueMessage(
        String requestId,
        Long couponId,
        Long userId
) {
}
