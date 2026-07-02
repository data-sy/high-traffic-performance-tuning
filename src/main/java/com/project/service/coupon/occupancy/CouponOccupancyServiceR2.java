package com.project.service.coupon.occupancy;

import com.project.api.coupon.dto.CouponIssueResponse;
import org.springframework.stereotype.Service;

/**
 * phase 3-9 <b>R2 — 락 밖 군더더기 제거</b>(시도 2). {@code FOR UPDATE}·managed 엔티티·왕복 4문은 유지하고,
 * 락 안 <b>비-DB 작업</b>(사전검증·응답/DTO 조립)만 임계 구간 밖으로 뺀다.
 *
 * <p><b>H_floor 대조군:</b> 뺀 것은 DB 미접촉 sub-ms뿐이라 commit floor에 묻혀 throughput은 R0와 노이즈밴드
 * 안이어야 정상이다. R2가 유의하게 오르면 '군더더기'가 실은 sub-ms가 아니었다는 뜻(관측치로 기록).
 *
 * <p>이 아우터는 <b>비트랜잭셔널</b>이다 — 임계 구간(단일 tx)은 {@link CouponOccupancyServiceR2Inner}가 쥐고,
 * 사전검증·DTO 조립만 여기(락 밖)서 한다. 별도 tx를 열지 않아 커넥션 체크아웃은 여전히 1회.
 */
@Service
public class CouponOccupancyServiceR2 {

    private final CouponOccupancyServiceR2Inner inner;

    public CouponOccupancyServiceR2(CouponOccupancyServiceR2Inner inner) {
        this.inner = inner;
    }

    public CouponIssueResponse issue(Long couponId, Long userId) {
        // ── 락 밖: 사전검증(비-DB sub-ms) ──
        if (couponId == null || userId == null) {
            throw new IllegalArgumentException("couponId/userId required");
        }

        // ── 임계 구간(단일 tx, 왕복 4문) ──
        CouponOccupancyServiceR2Inner.IssuedSnapshot snap = inner.issueInLock(couponId, userId);

        // ── 락 밖: 응답 DTO 조립(비-DB) ──
        return new CouponIssueResponse(true, snap.couponId(), snap.issued(), snap.totalQuantity());
    }
}
