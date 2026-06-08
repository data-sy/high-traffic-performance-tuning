package com.project.service.coupon;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

/**
 * v3 DB 실패를 503 ⓐ/ⓑ로 분류(예외 체인 실측). <b>v3 경로 전용</b> — v3 컨트롤러 경계에서만 호출하여,
 * v1/v2의 무락 풀 누수가 ⓐ로 위장되는 오염을 막는다(스펙 §Step B·핸들러 핀).
 *
 * <ul>
 *   <li>ⓐ HikariCP 풀 고갈(connection-timeout 초과) → {@code SQLTransientConnectionException} → POOL_TIMEOUT</li>
 *   <li>ⓑ MySQL 행락 대기 타임아웃(error 1205, innodb_lock_wait_timeout 초과) → LOCK_WAIT_TIMEOUT</li>
 * </ul>
 * 미분류면 원예외를 그대로 반환 → 어드바이스 미포착 → 500(k6 'other').
 */
public final class CouponDbExceptionClassifier {

    private static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;

    private CouponDbExceptionClassifier() {
    }

    public static RuntimeException classify(RuntimeException e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof SQLTransientConnectionException) {
                return new PoolTimeoutException(c);                 // ⓐ
            }
            if (c instanceof SQLException sql && sql.getErrorCode() == MYSQL_LOCK_WAIT_TIMEOUT) {
                return new LockWaitException(c);                    // ⓑ
            }
        }
        return e;                                                  // 미분류 → 500 other
    }
}
