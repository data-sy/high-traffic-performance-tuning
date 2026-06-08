package com.project.domain.coupon;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

    /** 같은 사용자 중복 발급 차단(단건 재요청 검증용). 부하 경로는 유니크 userId라 안 탐. */
    boolean existsByCouponIdAndUserId(Long couponId, Long userId);

    /** 정합성 단일 진실 소스 = actual_issued. coupon.issued(카운터)는 lost update로 부정확. */
    long countByCouponId(Long couponId);
}
