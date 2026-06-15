package com.project.api.coupon;

import com.project.api.coupon.dto.CouponErrorResponse;
import com.project.domain.coupon.CouponSoldOutException;
import com.project.service.coupon.DuplicateIssueException;
import com.project.service.coupon.async.QueueFullException;
import com.project.service.coupon.LockNotAcquiredException;
import com.project.service.coupon.LockWaitException;
import com.project.service.coupon.PoolTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 쿠폰 발급 예외 → HTTP status + top-level errorCode 매핑(추출한 하네스 계약).
 * coupon 컨트롤러로 한정(basePackages) — 다른 시나리오에 영향 없음.
 *
 * <p><b>계약:</b> 성공 200 / 한도소진 409(status로 분류) / 락·풀 실패 503(+errorCode로 ⓐ/ⓑ/ⓒ).
 * 모든 503은 valid JSON 바디 동반(k6가 503에서만 res.json('errorCode')를 top-level로 읽음).
 *
 * <p><b>핀 설계 — ⓐ/ⓑ는 타입 예외로 v3에 한정한다:</b> 풀/행락 503은 v3 서비스가 던지는
 * {@link PoolTimeoutException}/{@link LockWaitException}로만 매핑된다. raw {@code DataAccessException}을
 * 전역으로 잡지 않는 이유 — v1/v2의 무락 풀 누수는 스펙대로 500(k6 'other')으로 남겨야 하고,
 * ⓐ(POOL_TIMEOUT)는 v3 분석 신호라 다른 버전으로 새면 안 된다.
 */
@RestControllerAdvice(basePackages = "com.project.api.coupon")
public class CouponExceptionHandler {

    /** 한도 소진 — 409 (전 버전 공통). 하네스는 status로 분류, errorCode는 사람용. */
    @ExceptionHandler(CouponSoldOutException.class)
    public ResponseEntity<CouponErrorResponse> handleSoldOut(CouponSoldOutException e) {
        return error(HttpStatus.CONFLICT, "SOLD_OUT", e.getMessage());
    }

    /** 중복 발급 — 409 (전 버전 공통). 부하 경로는 유니크 userId라 미발생, 단건 재요청 검증 전용. */
    @ExceptionHandler(DuplicateIssueException.class)
    public ResponseEntity<CouponErrorResponse> handleDuplicate(DuplicateIssueException e) {
        return error(HttpStatus.CONFLICT, "ALREADY_ISSUED", e.getMessage());
    }

    /**
     * ⓒ 분산 락 미획득 — 503 LOCK_NOT_ACQUIRED (단일 경로).
     * <b>throw 측 Step E/F:</b> v4(Redisson)/v5(커스텀) 서비스. tryLock 실패와 RedisException을
     * 둘 다 이 단일 코드로 접되 RedisException은 서비스에서 WARN 로그로 구분(별도 errorCode 신설 금지).
     */
    @ExceptionHandler(LockNotAcquiredException.class)
    public ResponseEntity<CouponErrorResponse> handleLockNotAcquired(LockNotAcquiredException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "LOCK_NOT_ACQUIRED", e.getMessage());
    }

    /**
     * ⓐ 풀 고갈 — 503 POOL_TIMEOUT. <b>throw 측 = v3 컨트롤러 경계</b>(issueViaV3→CouponDbExceptionClassifier)가
     * DataAccessException 체인에서 SQLTransientConnectionException 탐지 후 던짐(tx-begin/첫 쿼리 풀 고갈을 @Transactional 밖에서 포착).
     */
    @ExceptionHandler(PoolTimeoutException.class)
    public ResponseEntity<CouponErrorResponse> handlePoolTimeout(PoolTimeoutException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "POOL_TIMEOUT", e.getMessage());
    }

    /**
     * ⓑ 행락 대기 타임아웃 — 503 LOCK_WAIT_TIMEOUT. <b>throw 측 = v3 컨트롤러 경계</b>(CouponDbExceptionClassifier)가
     * DataAccessException 체인에서 MySQL error 1205 탐지 후 던짐.
     */
    @ExceptionHandler(LockWaitException.class)
    public ResponseEntity<CouponErrorResponse> handleLockWait(LockWaitException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "LOCK_WAIT_TIMEOUT", e.getMessage());
    }

    /**
     * 큐 상한 초과 — 429 QUEUE_FULL (phase 3-4 비동기 입구). 백프레셔 발동 관찰치이지
     * 오류가 아니다 (T7). throw 측 = v6 파사드 (v6a AbortPolicy / v6b·v6c 근사 가드).
     */
    @ExceptionHandler(QueueFullException.class)
    public ResponseEntity<CouponErrorResponse> handleQueueFull(QueueFullException e) {
        return error(HttpStatus.TOO_MANY_REQUESTS, "QUEUE_FULL", e.getMessage());
    }

    private ResponseEntity<CouponErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(CouponErrorResponse.of(code, message));
    }
}
