package com.project.service.product.cache;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * v3 single-flight 락 대기 초과 폴백 (Step C·DR-4).
 *
 * <p>폴백 정책 핀(고정): <b>503</b>. 직접 재계산(herd 회귀)도, 스테일 제공(v4 전용)도 아니다 — v3 비홀더는
 * '순수 블록'으로 고정해야 축 A2 의 v3=C-1 이 성립하기 때문이다. 불변 {@code LOCK_WAIT_MS > 홀더 완료 전 구간
 * p99}(DR-4)가 지켜지면 이 폴백은 발화하지 않는 예외 경로다 — 발화하면 {@code http_req_failed>0} 로 런 무효를 드러낸다.
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class CacheLockWaitException extends RuntimeException {
    public CacheLockWaitException(String message) {
        super(message);
    }
}
