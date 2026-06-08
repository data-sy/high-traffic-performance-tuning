package com.project.service.coupon;

import com.project.api.coupon.dto.CouponIssueResponse;
import com.project.domain.coupon.Coupon;
import com.project.domain.coupon.CouponIssue;
import com.project.domain.coupon.CouponIssueRepository;
import com.project.domain.coupon.CouponRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * v1 — No Lock (기준선). 락이 전혀 없어 초과 발급(over-issuance)을 재현한다.
 *
 * <p>경쟁 지점: {@code coupon.issue()}의 read-check(issued &lt; total)-increment가 비원자라,
 * 동시 다수 트랜잭션이 stale {@code issued}를 읽고 모두 통과 → coupon_issue 행수 &gt; total_qty.
 * (@Transactional은 있다 — 초과는 트랜잭션 부재가 아니라 동시성 제어 부재에서 난다.)
 */
@Service
public class CouponIssueServiceV1 {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;

    public CouponIssueServiceV1(CouponRepository couponRepository,
                                CouponIssueRepository couponIssueRepository) {
        this.couponRepository = couponRepository;
        this.couponIssueRepository = couponIssueRepository;
    }

    @Transactional
    public CouponIssueResponse issue(Long couponId, Long userId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new IllegalArgumentException("coupon not found: " + couponId));

        if (couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)) {
            throw new DuplicateIssueException(couponId, userId);
        }

        coupon.issue();                                       // 한도 초과면 CouponSoldOutException(409)
        couponIssueRepository.save(new CouponIssue(couponId, userId));
        return CouponIssueResponse.from(coupon);
    }
}
