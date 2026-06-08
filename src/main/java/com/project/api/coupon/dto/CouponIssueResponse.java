package com.project.api.coupon.dto;

import com.project.domain.coupon.Coupon;

/**
 * 발급 성공(200) 응답. 엔티티 직접 노출 금지 — 정적 팩토리로 변환.
 * (하네스는 200 본문을 읽지 않음 — status·timing만. 이 본문은 사람/감사용.)
 */
public record CouponIssueResponse(
        boolean success,
        Long couponId,
        Integer issued,
        Integer totalQuantity
) {
    public static CouponIssueResponse from(Coupon coupon) {
        return new CouponIssueResponse(true, coupon.getId(), coupon.getIssued(), coupon.getTotalQuantity());
    }
}
