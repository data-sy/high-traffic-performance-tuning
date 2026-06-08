package com.project.service.coupon;

import com.project.api.coupon.dto.CouponIssueResponse;
import com.project.infrastructure.redis.RedisLockRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * v5 — 커스텀 분산 락(SET NX + Lua compare-del). <b>v4와 동형 파사드</b>, 단 <b>고정 TTL 30s 무갱신</b>
 * (v4 워치독과 비동형 — Step G 갱신 축). fail-fast 단발(대기 없음) → 미획득 시 503 ⓒ.
 *
 * <p>구조를 v4와 동일 유지(① 비교 공정성 ② 미래 최적화 안전): {@code if(!locked) 503; try{inner} finally{unlock}}.
 * 커스텀 unlock은 compare-del이라 미보유 시 자연 no-op이지만 finally에서 무조건 호출(동형 유지).
 * TTL 30s &gt; worst-case tx(수 ms) — 정상 경로에선 보유 중 만료 없음(스톨 데모만 ttl&lt;stall로 만료 유도).
 */
@Service
public class CouponIssueServiceV5 {

    private static final long TTL_MILLIS = 30_000L;   // 고정 TTL, 무갱신

    private final RedisLockRepository lockRepository;
    private final CouponIssueServiceV5Inner inner;

    public CouponIssueServiceV5(RedisLockRepository lockRepository, CouponIssueServiceV5Inner inner) {
        this.lockRepository = lockRepository;
        this.inner = inner;
    }

    public CouponIssueResponse issue(Long couponId, Long userId) {
        String key = "lock:coupon:" + couponId;
        String token = UUID.randomUUID().toString();

        boolean locked = lockRepository.tryLock(key, token, TTL_MILLIS);
        if (!locked) {
            throw new LockNotAcquiredException(couponId);
        }

        try {
            return inner.issueTx(couponId, userId);
        } finally {
            lockRepository.unlock(key, token);   // compare-del: 자기 토큰만 해제
        }
    }
}
