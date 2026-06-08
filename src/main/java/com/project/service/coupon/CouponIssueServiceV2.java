package com.project.service.coupon;

import com.project.api.coupon.dto.CouponIssueResponse;
import org.springframework.stereotype.Service;

/**
 * v2 — {@code synchronized} 애플리케이션(JVM) 락. 파사드: 모니터가 트랜잭션 경계를 <b>감싼다</b>(락 ⊃ tx).
 *
 * <p>구조 핵심: {@code synchronized}는 여기(tx 밖)에 두고, 실제 트랜잭션은 {@link CouponIssueServiceV2Inner}
 * (별도 빈) 프록시 호출로 모니터 안에서 시작·커밋. → 커밋 완료 후 모니터 해제 = 다음 스레드는 항상
 * 커밋된 issued를 읽음 → 정합 회복.
 *
 * <p>한계: <b>단일 JVM에서만 정합</b>(인스턴스마다 별도 모니터) + 직렬화로 throughput 병목 → v3 이행 근거.
 * 경합 흡수 = park(무한 대기), 거절 없음(503=0 기대).
 */
@Service
public class CouponIssueServiceV2 {

    private final CouponIssueServiceV2Inner inner;
    private final Object lock = new Object();   // 단일 쿠폰 전용 모니터

    public CouponIssueServiceV2(CouponIssueServiceV2Inner inner) {
        this.inner = inner;
    }

    public CouponIssueResponse issue(Long couponId, Long userId) {
        synchronized (lock) {                       // 트랜잭션 경계 바깥
            return inner.issueTx(couponId, userId); // 별도 빈 프록시 → 커밋이 모니터 안에서 완료
        }
    }
}
