package com.project.service.coupon;

/**
 * ⓑ MySQL 행락 대기 타임아웃(error 1205, innodb_lock_wait_timeout 초과) — 503 LOCK_WAIT_TIMEOUT.
 * <b>v3 전용</b>(Step D).
 *
 * <p><b>throw 측 = v3 경로의 컨트롤러 경계</b>({@code CouponDbExceptionClassifier})에서 {@code DataAccessException}
 * 체인의 {@code SQLException.errorCode == 1205}를 탐지. ⓐ(PoolTimeout)와 갈라 503 서브분류 — F1 핀
 * (connTO 30s &lt; innodb 50s)과 병기해야 "v3=커넥션 고갈" 간판이 circular해지지 않는다.
 */
public class LockWaitException extends RuntimeException {

    public LockWaitException(Throwable cause) {
        super("Row lock wait timeout", cause, false, false);
    }
}
