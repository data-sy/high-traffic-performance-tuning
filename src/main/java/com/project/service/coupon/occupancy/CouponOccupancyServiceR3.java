package com.project.service.coupon.occupancy;

import com.project.api.coupon.dto.CouponIssueResponse;
import com.project.domain.coupon.CouponIssue;
import com.project.domain.coupon.CouponIssueRepository;
import com.project.domain.coupon.CouponRepository;
import com.project.domain.coupon.CouponSoldOutException;
import com.project.service.coupon.DuplicateIssueException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * phase 3-9 <b>R3 — 원자 수축, existsBy 유지</b>(시도 3). read-modify-write(FOR UPDATE SELECT→read→커밋 UPDATE)를
 * 조건부 {@code tryConsume} UPDATE 한 문장으로 합친다. 엔티티를 managed로 적재하지 않아 커밋 dirty-flush
 * {@code UPDATE coupon}이 소멸 → <b>왕복 4문 → 3문</b>(tryConsume UPDATE · existsBy SELECT · INSERT).
 *
 * <p><b>단일변수 = 읽고-고치기 합치기</b>. 중복검사(existsBy)는 tryConsume <b>뒤에도 락 안 유지</b>
 * (중복검사 위치 변경은 R4 변수). tryConsume UPDATE 자체가 InnoDB X-락을 잡아 직렬화 지점을 보존한다.
 *
 * <p>소진(affected=0) 경로는 UPDATE 1문+롤백 → 409. 성공 경로만 3문(구조 카운트는 성공 표본으로만, M1).
 *
 * <p><b>응답 계약:</b> 하네스는 200 본문 미독(status·timing만) — R3는 엔티티 미적재라 정확한 {@code issued}를
 * 추가 왕복 없이 알 수 없다. 성공 표식만 반환(왕복 3문 불변 유지가 우선).
 */
@Service
public class CouponOccupancyServiceR3 {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;

    public CouponOccupancyServiceR3(CouponRepository couponRepository,
                                    CouponIssueRepository couponIssueRepository) {
        this.couponRepository = couponRepository;
        this.couponIssueRepository = couponIssueRepository;
    }

    @Transactional
    public CouponIssueResponse issue(Long couponId, Long userId) {
        int affected = couponRepository.tryConsume(couponId);          // ① tryConsume UPDATE (X-락)
        if (affected == 0) {
            throw new CouponSoldOutException(couponId);                 // 소진 → 409 (UPDATE 1문 + 롤백)
        }

        if (couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)) {   // ② existsBy SELECT
            throw new DuplicateIssueException(couponId, userId);        // 롤백이 tryConsume 증가분 되돌림(원자)
        }

        couponIssueRepository.save(new CouponIssue(couponId, userId));  // ③ INSERT (IDENTITY 즉시)
        return new CouponIssueResponse(true, couponId, null, null);
    }
}
