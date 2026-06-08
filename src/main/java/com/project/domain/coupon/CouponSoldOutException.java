package com.project.domain.coupon;

/**
 * 쿠폰 한도 소진(issued &gt;= totalQuantity). 컨트롤러 어드바이스에서 409로 매핑된다.
 *
 * <p>한도 소진은 고빈도 정상 결과(정확성 축에서 total=100 초과분이 전부 이 경로)라
 * 스택트레이스 채우기 비용이 측정 지연을 오염시킨다 → writableStackTrace=false.
 */
public class CouponSoldOutException extends RuntimeException {

    public CouponSoldOutException(Long couponId) {
        super("Coupon sold out: couponId=" + couponId, null, false, false);
    }
}
