package com.project.service.coupon;

import com.project.api.coupon.dto.CouponIssueResponse;
import com.project.domain.coupon.Coupon;
import com.project.domain.coupon.CouponIssue;
import com.project.domain.coupon.CouponIssueRepository;
import com.project.domain.coupon.CouponRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * v4 트랜잭션 inner — 별도 빈(파사드 {@link CouponIssueServiceV4}의 락 안에서 프록시 호출).
 * 락이 임계영역을 보호하므로 여기선 비락 조회(findById)로 충분 — v3와 달리 FOR UPDATE 불필요.
 * 락 ⊃ 트랜잭션: 파사드가 커밋 이후(finally) unlock.
 */
@Service
public class CouponIssueServiceV4Inner {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;

    public CouponIssueServiceV4Inner(CouponRepository couponRepository,
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
