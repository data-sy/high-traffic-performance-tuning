package com.project.api.coupon.dto;

import java.util.Map;

/**
 * 발급 상태 조회(T7) 응답 — 상태 해시(관측 전용)를 그대로 비춘다.
 * ACCEPTED → ISSUED | SOLD_OUT | FAILED. completedAt은 터미널 도달 전 null.
 */
public record IssueStatusResponse(
        String requestId,
        String status,
        Long acceptedAt,
        Long completedAt
) {
    public static IssueStatusResponse from(String requestId, Map<String, String> hash) {
        return new IssueStatusResponse(
                requestId,
                hash.get("status"),
                parseLong(hash.get("acceptedAt")),
                parseLong(hash.get("completedAt")));
    }

    private static Long parseLong(String v) {
        return v == null ? null : Long.valueOf(v);
    }
}
