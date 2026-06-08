package com.project.service.coupon;

import com.project.api.coupon.dto.CouponIssueResponse;
import com.project.domain.coupon.Coupon;
import com.project.domain.coupon.CouponIssue;
import com.project.domain.coupon.CouponIssueRepository;
import com.project.domain.coupon.CouponRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * v2 트랜잭션 inner — <b>별도 빈</b>(파사드 {@link CouponIssueServiceV2}와 분리된 빈이어야 프록시 적용).
 *
 * <p>분리 이유(프록시 함정): 파사드의 {@code synchronized} 블록 <i>안에서</i> 이 빈의 프록시를 호출해야
 * {@code @Transactional} 커밋이 모니터 보유 중에 완료된다. 같은 빈 자기호출이면 프록시 미적용 →
 * 커밋이 모니터 밖으로 새 → 다음 스레드가 stale issued 읽음 → 단일 JVM에서도 초과 발급(lost update 재발).
 */
@Service
public class CouponIssueServiceV2Inner {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;

    public CouponIssueServiceV2Inner(CouponRepository couponRepository,
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
