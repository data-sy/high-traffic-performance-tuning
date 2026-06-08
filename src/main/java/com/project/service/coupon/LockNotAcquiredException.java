package com.project.service.coupon;

/**
 * 분산 락(v4 Redisson / v5 커스텀) 미획득 — 즉시 실패(fail-fast). 어드바이스에서 503 LOCK_NOT_ACQUIRED로 매핑.
 *
 * <p><b>계약(추출 고정):</b> v4/v5 서비스는 tryLock 실패와 Redis 연결 예외(RedisException) <i>둘 다</i>
 * 이 단일 예외로 접는다. RedisException(인프라 장애)은 서비스에서 WARN 로그로 구분하되,
 * 별도 errorCode를 신설하지 않는다(동결 하네스가 모르는 문자열 → 미분류 503으로 샘).
 *
 * <p>고빈도 fail-fast 거절 경로 → writableStackTrace=false.
 */
public class LockNotAcquiredException extends RuntimeException {

    public LockNotAcquiredException(Long couponId) {
        super("Lock not acquired: couponId=" + couponId, null, false, false);
    }
}
