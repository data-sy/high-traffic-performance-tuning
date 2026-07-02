package com.project.api.coupon;

import com.project.api.coupon.dto.CouponIssueRequest;
import com.project.api.coupon.dto.CouponIssueResponse;
import com.project.service.coupon.CouponDbExceptionClassifier;
import com.project.service.coupon.occupancy.CouponOccupancyServiceR0;
import com.project.service.coupon.occupancy.CouponOccupancyServiceR2;
import com.project.service.coupon.occupancy.CouponOccupancyServiceR3;
import com.project.service.coupon.occupancy.CouponOccupancyServiceR4;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * phase 3-9 임계 구간 점유 사다리 — R0~R4 벤치마크 표면. 기존 {@code /api/v{n}}(v1~v5) 미오염,
 * 신규 네임스페이스 {@code /api/r{n}}로 in-lock 왕복 수만 바꾼 칸들을 독립 측정한다.
 *
 * <p><b>배선 계약(스펙 §배선 계약 — 503=0 성립 조건):</b>
 * <ol>
 *   <li>이 컨트롤러는 {@code com.project.api.coupon} 패키지 안에 있어 {@link CouponExceptionHandler}
 *       ({@code @RestControllerAdvice(basePackages="...coupon")}) 커버를 받는다 — 409/503이 500으로 새지 않음.</li>
 *   <li>각 R 경로를 {@code issueViaV3}식 분류 경계로 감싼다({@link #classifyBoundary}):
 *       {@code try{...}catch(DataAccessException|TransactionException e){throw classify(e);}}
 *       → ⓐ 풀 고갈(POOL_TIMEOUT)·ⓑ 행락 타임아웃(LOCK_WAIT_TIMEOUT, 1205) 503 발화.</li>
 *   <li>R4의 1062→{@code DuplicateIssueException} 번역은 서비스 안에서(분류 경계 앞) 수행 → 409.</li>
 * </ol>
 *
 * <p><b>R1은 코드 동일 칸</b>(풀 스윕 진단): 별도 서비스 없이 R0 서비스에 위임한다("코드 동일, 풀만 변경").
 */
@RestController
@RequestMapping("/api")
public class CouponOccupancyController {

    private final CouponOccupancyServiceR0 r0Service;
    private final CouponOccupancyServiceR2 r2Service;
    private final CouponOccupancyServiceR3 r3Service;
    private final CouponOccupancyServiceR4 r4Service;

    public CouponOccupancyController(CouponOccupancyServiceR0 r0Service,
                                     CouponOccupancyServiceR2 r2Service,
                                     CouponOccupancyServiceR3 r3Service,
                                     CouponOccupancyServiceR4 r4Service) {
        this.r0Service = r0Service;
        this.r2Service = r2Service;
        this.r3Service = r3Service;
        this.r4Service = r4Service;
    }

    /** R0 — 현행 v3 충실 복제(기준선). 왕복 4문. */
    @PostMapping("/r0/coupons/issue")
    public CouponIssueResponse issueR0(@RequestBody CouponIssueRequest request) {
        return classifyBoundary(() -> r0Service.issue(request.couponId(), request.userId()));
    }

    /** R1 — 시도 1 · 풀 스윕(진단). 코드 동일 = R0 서비스 위임, 풀만 env로 변경(부팅 assert 대조). 왕복 4문. */
    @PostMapping("/r1/coupons/issue")
    public CouponIssueResponse issueR1(@RequestBody CouponIssueRequest request) {
        return classifyBoundary(() -> r0Service.issue(request.couponId(), request.userId()));
    }

    /** R2 — 시도 2 · 락 밖 군더더기 제거. 왕복 4문(불변). */
    @PostMapping("/r2/coupons/issue")
    public CouponIssueResponse issueR2(@RequestBody CouponIssueRequest request) {
        return classifyBoundary(() -> r2Service.issue(request.couponId(), request.userId()));
    }

    /** R3 — 시도 3 · 원자 수축(existsBy 유지). 왕복 3문. */
    @PostMapping("/r3/coupons/issue")
    public CouponIssueResponse issueR3(@RequestBody CouponIssueRequest request) {
        return classifyBoundary(() -> r3Service.issue(request.couponId(), request.userId()));
    }

    /** R4 — 시도 4 · existsBy → UNIQUE 백스톱. 왕복 2문(정본 정량점). */
    @PostMapping("/r4/coupons/issue")
    public CouponIssueResponse issueR4(@RequestBody CouponIssueRequest request) {
        return classifyBoundary(() -> r4Service.issue(request.couponId(), request.userId()));
    }

    /**
     * v3 컨트롤러 {@code issueViaV3}의 분류 경계 복제 — @Transactional 밖에서 DB 실패를 잡아 ⓐ/ⓑ로 분류
     * (tx-begin/첫 쿼리 풀 고갈까지 포착). 분류기가 던지는 PoolTimeout/LockWait(plain RuntimeException)는
     * 여기 catch에 안 걸리고 어드바이스로 → 503.
     */
    private CouponIssueResponse classifyBoundary(java.util.function.Supplier<CouponIssueResponse> call) {
        try {
            return call.get();
        } catch (DataAccessException | TransactionException e) {
            throw CouponDbExceptionClassifier.classify(e);
        }
    }
}
