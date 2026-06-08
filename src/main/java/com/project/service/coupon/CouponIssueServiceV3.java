package com.project.service.coupon;

import com.project.api.coupon.dto.CouponIssueResponse;
import com.project.domain.coupon.Coupon;
import com.project.domain.coupon.CouponIssue;
import com.project.domain.coupon.CouponIssueRepository;
import com.project.domain.coupon.CouponRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * v3 — {@code SELECT ... FOR UPDATE} DB 비관적 행 락. <b>v2와 정반대</b>: 락이 곧 DB 행 락이라
 * 커밋 시 풀린다 → 락 획득·검사·발급·저장을 모두 <b>하나의 @Transactional 안</b>에서(단일 빈, 파사드 없음).
 *
 * <p>순서: {@code findByIdForUpdate}로 행 락 획득 <b>후</b> 검사/발급(락 전 비락 조회를 임계 판단에 쓰지 않음).
 * 직렬화 지점이 로컬 모니터(v2) → 공유 DB 행으로 이동 → 다중 인스턴스 경계 초월(개념; 단일 JVM은 미실증).
 *
 * <p>503 ⓐ/ⓑ 분류는 이 메서드가 아니라 <b>컨트롤러 경계</b>에서 한다(ⓐ 풀 고갈이 tx-begin/첫 쿼리에서
 * 날 수 있어 @Transactional 밖에서 잡아야 eager/delayed 획득을 모두 포착 — {@code CouponDbExceptionClassifier}).
 */
@Service
public class CouponIssueServiceV3 {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;

    public CouponIssueServiceV3(CouponRepository couponRepository,
                                CouponIssueRepository couponIssueRepository) {
        this.couponRepository = couponRepository;
        this.couponIssueRepository = couponIssueRepository;
    }

    @Transactional
    public CouponIssueResponse issue(Long couponId, Long userId) {
        Coupon coupon = couponRepository.findByIdForUpdate(couponId)   // 비관적 행 락 (대기 = innodb_lock_wait_timeout 50s)
                .orElseThrow(() -> new IllegalArgumentException("coupon not found: " + couponId));

        if (couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)) {
            throw new DuplicateIssueException(couponId, userId);
        }

        coupon.issue();
        couponIssueRepository.save(new CouponIssue(couponId, userId));
        return CouponIssueResponse.from(coupon);
    }
}
