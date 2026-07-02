package com.project.service.coupon.occupancy;

import com.project.api.coupon.dto.CouponIssueResponse;
import com.project.domain.coupon.Coupon;
import com.project.domain.coupon.CouponIssue;
import com.project.domain.coupon.CouponIssueRepository;
import com.project.domain.coupon.CouponRepository;
import com.project.service.coupon.DuplicateIssueException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * phase 3-9 <b>R0 — 현행 v3 충실 복제</b>(기준선). literal 재사용이 아닌 동일 계측 재구현이라
 * v3 빈을 오염시키지 않고 {@code /api/r0}(및 코드 동일한 {@code /api/r1})로 독립 측정한다.
 *
 * <p><b>임계 구간 in-lock DB 왕복 = 4문</b>(FOR UPDATE SELECT · existsBy SELECT · INSERT coupon_issue ·
 * 커밋 dirty-flush UPDATE coupon). {@code findByIdForUpdate}가 Coupon을 managed로 적재하고
 * {@code coupon.issue()}가 {@code issued}를 증가시키므로 커밋 flush에서 {@code UPDATE coupon} 1문이
 * <b>락 보유 중</b> 추가 발화한다. 임계 구간 = 트랜잭션 전체(락은 커밋 시 해제).
 *
 * <p>순서 게이트(M2): FOR UPDATE SELECT → existsBy SELECT → (issue: managed dirty) → INSERT → 커밋 UPDATE.
 * managed Coupon은 existsBy <b>뒤</b>에서만 수정되어 조기 auto-flush가 없다.
 */
@Service
public class CouponOccupancyServiceR0 {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;

    public CouponOccupancyServiceR0(CouponRepository couponRepository,
                                    CouponIssueRepository couponIssueRepository) {
        this.couponRepository = couponRepository;
        this.couponIssueRepository = couponIssueRepository;
    }

    @Transactional
    public CouponIssueResponse issue(Long couponId, Long userId) {
        Coupon coupon = couponRepository.findByIdForUpdate(couponId)   // ① FOR UPDATE SELECT (행 락)
                .orElseThrow(() -> new IllegalArgumentException("coupon not found: " + couponId));

        if (couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)) {   // ② existsBy SELECT
            throw new DuplicateIssueException(couponId, userId);
        }

        coupon.issue();                                                            // managed dirty
        couponIssueRepository.save(new CouponIssue(couponId, userId));             // ③ INSERT (IDENTITY 즉시)
        return CouponIssueResponse.from(coupon);                                   // ④ 커밋 flush: UPDATE coupon
    }
}
