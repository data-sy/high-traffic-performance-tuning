package com.project.service.coupon;

/**
 * ⓐ HikariCP 풀 고갈(connection-timeout 초과) — 503 POOL_TIMEOUT. <b>v3 전용</b>(Step D).
 *
 * <p>v3 서비스가 {@code DataAccessException} 체인에서 {@code SQLTransientConnectionException}를
 * 탐지해 던진다. v1/v2의 풀 누수는 이 예외로 접지 <b>않는다</b> — 무락 버전의 풀 누수는 스펙대로
 * 500(k6 'other' 버킷)으로 남겨야 한다(ⓐ는 v3 분석 신호라 버전 오염 금지).
 */
public class PoolTimeoutException extends RuntimeException {

    public PoolTimeoutException(Throwable cause) {
        super("Connection pool timeout", cause, false, false);
    }
}
