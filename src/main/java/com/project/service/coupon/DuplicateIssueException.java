package com.project.service.coupon;

/**
 * 같은 사용자 중복 발급 — 409 ALREADY_ISSUED. 전 버전 공통(existsByCouponIdAndUserId 거절 경로).
 *
 * <p>부하 경로는 유니크 userId라 이 예외가 안 뜬다 — 단건 재요청(같은 userId 2회) 검증 전용.
 * 409라 하네스 503 버킷을 오염시키지 않는다(부하 중엔 미발생).
 */
public class DuplicateIssueException extends RuntimeException {

    public DuplicateIssueException(Long couponId, Long userId) {
        super("Already issued: couponId=" + couponId + ", userId=" + userId, null, false, false);
    }
}
