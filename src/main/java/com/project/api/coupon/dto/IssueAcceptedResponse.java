package com.project.api.coupon.dto;

/**
 * 비동기 발급 접수(202) 응답. 202는 "접수"지 "발급"이 아니다 (P1) —
 * 발급 여부는 {@code GET /api/v6/coupons/issue-status/{requestId}} 로 조회한다 (T7).
 */
public record IssueAcceptedResponse(
        String requestId,
        String status
) {
    public static IssueAcceptedResponse accepted(String requestId) {
        return new IssueAcceptedResponse(requestId, "ACCEPTED");
    }
}
