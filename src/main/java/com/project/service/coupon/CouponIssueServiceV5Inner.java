package com.project.service.coupon;

import com.project.api.coupon.dto.CouponIssueResponse;
import com.project.domain.coupon.Coupon;
import com.project.domain.coupon.CouponIssue;
import com.project.domain.coupon.CouponIssueRepository;
import com.project.domain.coupon.CouponRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * v5 트랜잭션 inner — 별도 빈(파사드 {@link CouponIssueServiceV5}의 락 안에서 프록시 호출).
 * v4 inner와 동형(비락 findById — 락이 임계영역 보호). 락 ⊃ 트랜잭션.
 */
@Service
public class CouponIssueServiceV5Inner {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;

    public CouponIssueServiceV5Inner(CouponRepository couponRepository,
                                     CouponIssueRepository couponIssueRepository) {
        this.couponRepository = couponRepository;
        this.couponIssueRepository = couponIssueRepository;
    }

    @Transactional
    public CouponIssueResponse issueTx(Long couponId, Long userId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new IllegalArgumentException("coupon not found: " + couponId));

        if (couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)) {
            throw new DuplicateIssueException(couponId, userId);
        }

        coupon.issue();
        couponIssueRepository.save(new CouponIssue(couponId, userId));
        return CouponIssueResponse.from(coupon);
    }
}
