package com.project.service.coupon;

/**
 * ⓐ HikariCP 풀 고갈(connection-timeout 초과) — 503 POOL_TIMEOUT. <b>v3 전용</b>(Step D).
 *
 * <p><b>throw 측 = v3 경로의 컨트롤러 경계</b>({@code CouponDbExceptionClassifier}). @Transactional 밖에서
 * {@code DataAccessException} 체인의 {@code SQLTransientConnectionException}를 탐지(tx-begin/첫 쿼리 풀 고갈까지
 * 포착). v1/v2의 풀 누수는 이 분류를 안 거쳐 스펙대로 500(k6 'other')으로 남는다(ⓐ는 v3 분석 신호라 버전 오염 금지).
 */
public class PoolTimeoutException extends RuntimeException {

    public PoolTimeoutException(Throwable cause) {
        super("Connection pool timeout", cause, false, false);
    }
}
