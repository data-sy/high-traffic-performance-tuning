package com.project.service.coupon.occupancy;

import com.project.domain.coupon.Coupon;
import com.project.domain.coupon.CouponIssue;
import com.project.domain.coupon.CouponIssueRepository;
import com.project.domain.coupon.CouponRepository;
import com.project.service.coupon.DuplicateIssueException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * phase 3-9 R2 임계 구간(단일 @Transactional). R0와 <b>왕복 4문·순서 동일</b>(FOR UPDATE SELECT →
 * existsBy SELECT → managed dirty → INSERT → 커밋 UPDATE) — 단일변수는 "락 밖 비-DB 작업 제거"뿐이다.
 *
 * <p><b>커넥션 체크아웃 1회</b>: 별도 tx로 나누지 않고 이 인너 하나만 트랜잭셔널이라 pending 제2변수 오염이 없다.
 * DTO 조립·응답 조립은 {@link CouponOccupancyServiceR2}(락 밖)로 옮겼고, 여기서는 커밋 후 필요한 원시값만
 * 스냅샷으로 반환한다(managed Coupon을 tx 밖으로 흘리지 않음).
 *
 * <p><b>flush 순서 게이트(M2, R2 필수):</b> managed Coupon({@code coupon.issue()})은 existsBy SELECT
 * <b>뒤</b>에서만 건드린다 — 앞에서 건드리면 {@code FlushModeType.AUTO}가 existsBy 직전 조기 UPDATE flush를
 * 유발해 4문 카운트/순서가 조용히 어긋난다.
 */
@Service
public class CouponOccupancyServiceR2Inner {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;

    public CouponOccupancyServiceR2Inner(CouponRepository couponRepository,
                                         CouponIssueRepository couponIssueRepository) {
        this.couponRepository = couponRepository;
        this.couponIssueRepository = couponIssueRepository;
    }

    /** 임계 구간 = 이 메서드 전체. 반환은 락 밖 DTO 조립용 원시 스냅샷. */
    @Transactional
    public IssuedSnapshot issueInLock(Long couponId, Long userId) {
        Coupon coupon = couponRepository.findByIdForUpdate(couponId)   // ① FOR UPDATE SELECT
                .orElseThrow(() -> new IllegalArgumentException("coupon not found: " + couponId));

        if (couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)) {   // ② existsBy SELECT
            throw new DuplicateIssueException(couponId, userId);
        }

        coupon.issue();                                                            // managed dirty (existsBy 뒤)
        couponIssueRepository.save(new CouponIssue(couponId, userId));             // ③ INSERT (IDENTITY 즉시)
        return new IssuedSnapshot(coupon.getId(), coupon.getIssued(), coupon.getTotalQuantity());
        // ④ 커밋 flush: UPDATE coupon (락 보유 중)
    }

    /** 락 밖 DTO 조립을 위해 커밋 시점 원시값만 담는 스냅샷(managed 엔티티 유출 방지). */
    public record IssuedSnapshot(Long couponId, Integer issued, Integer totalQuantity) {
    }
}
