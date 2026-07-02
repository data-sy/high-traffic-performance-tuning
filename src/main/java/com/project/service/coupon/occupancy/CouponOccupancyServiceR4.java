package com.project.service.coupon.occupancy;

import com.project.api.coupon.dto.CouponIssueResponse;
import com.project.domain.coupon.CouponIssue;
import com.project.domain.coupon.CouponIssueRepository;
import com.project.domain.coupon.CouponRepository;
import com.project.domain.coupon.CouponSoldOutException;
import com.project.service.coupon.DuplicateIssueException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * phase 3-9 <b>R4 — existsBy → UNIQUE 백스톱</b>(시도 4). 락을 쥔 동안의 실제 DB 왕복(existsBy SELECT)을
 * 통째로 없애고 중복 방어를 {@code UNIQUE(coupon_id,user_id)}에 위임한다 → <b>왕복 3문 → 2문</b>
 * (tryConsume UPDATE · INSERT). <b>사전 등록 판정의 주 시험 칸</b>(정본 정량점).
 *
 * <p><b>에러 계약(필수):</b> 중복은 이제 UNIQUE 위반 = MySQL <b>1062 / {@link DataIntegrityViolationException}</b>.
 * 분류기(1205만)·핸들러 그대로면 500 'other'로 새므로 여기서 <b>1062 → {@link DuplicateIssueException}</b>로
 * 명시 번역한다(→ 핸들러 409). 500-VU 유니크 userId라 부하 중 미발화이나 계약은 코드로 고정.
 * (DuplicateIssueException은 {@code DataAccessException}이 아니라 컨트롤러 classify 경계에 안 걸리고 핸들러로 간다.)
 *
 * <p>소진(affected=0) → 409. 원자성: INSERT 실패 시 tx 롤백이 tryConsume 증가분을 되돌린다(lost-update 흡수 없음).
 */
@Service
public class CouponOccupancyServiceR4 {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;

    public CouponOccupancyServiceR4(CouponRepository couponRepository,
                                    CouponIssueRepository couponIssueRepository) {
        this.couponRepository = couponRepository;
        this.couponIssueRepository = couponIssueRepository;
    }

    @Transactional
    public CouponIssueResponse issue(Long couponId, Long userId) {
        int affected = couponRepository.tryConsume(couponId);          // ① tryConsume UPDATE (X-락)
        if (affected == 0) {
            throw new CouponSoldOutException(couponId);                 // 소진 → 409
        }

        try {
            couponIssueRepository.save(new CouponIssue(couponId, userId));   // ② INSERT (IDENTITY 즉시)
        } catch (DataIntegrityViolationException e) {
            // 1062 UNIQUE 위반 = 중복 발급. 롤백이 tryConsume 증가분을 되돌림(원자).
            throw new DuplicateIssueException(couponId, userId);
        }
        return new CouponIssueResponse(true, couponId, null, null);
    }
}
