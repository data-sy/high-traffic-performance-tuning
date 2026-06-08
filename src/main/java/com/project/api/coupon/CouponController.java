package com.project.api.coupon;

import com.project.api.coupon.dto.CouponIssueRequest;
import com.project.api.coupon.dto.CouponIssueResponse;
import com.project.service.coupon.CouponDbExceptionClassifier;
import com.project.service.coupon.CouponIssueServiceV1;
import com.project.service.coupon.CouponIssueServiceV2;
import com.project.service.coupon.CouponIssueServiceV3;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 쿠폰 발급 — 버전별 벤치마크 표면(v1..v5 공존). 각 Step에서 엔드포인트 추가.
 *
 * <p>하네스 계약: {@code POST /api/{vN}/coupons/issue}, body {couponId, userId}, 성공 200.
 *
 * <p><b>프로덕션 배선(스펙 §6):</b> 무버전 {@code POST /api/coupons/issue} → V3 명시 위임
 * (임계영역이 곧 DB 쓰기라 DB 행 락으로 충분; @Primary 대신 명시 위임 — 5서비스 공존 모호성 회피).
 */
@RestController
@RequestMapping("/api")
public class CouponController {

    private final CouponIssueServiceV1 v1Service;
    private final CouponIssueServiceV2 v2Service;
    private final CouponIssueServiceV3 v3Service;

    public CouponController(CouponIssueServiceV1 v1Service,
                            CouponIssueServiceV2 v2Service,
                            CouponIssueServiceV3 v3Service) {
        this.v1Service = v1Service;
        this.v2Service = v2Service;
        this.v3Service = v3Service;
    }

    @PostMapping("/v1/coupons/issue")
    public CouponIssueResponse issueV1(@RequestBody CouponIssueRequest request) {
        return v1Service.issue(request.couponId(), request.userId());
    }

    @PostMapping("/v2/coupons/issue")
    public CouponIssueResponse issueV2(@RequestBody CouponIssueRequest request) {
        return v2Service.issue(request.couponId(), request.userId());
    }

    @PostMapping("/v3/coupons/issue")
    public CouponIssueResponse issueV3(@RequestBody CouponIssueRequest request) {
        return issueViaV3(request);
    }

    /** 프로덕션 무버전 별칭 → V3 (스펙 §6). 하네스 비측정, 운영 트래픽용. */
    @PostMapping("/coupons/issue")
    public CouponIssueResponse issueProduction(@RequestBody CouponIssueRequest request) {
        return issueViaV3(request);
    }

    /**
     * v3 경계: @Transactional 밖에서 DB 실패를 잡아 ⓐ/ⓑ로 분류(tx-begin/첫 쿼리 풀 고갈까지 포착).
     * v1/v2 경로엔 이 catch가 없어 그들의 풀 누수는 500/other로 남는다(ⓐ 오염 방지).
     * 분류기가 던지는 PoolTimeout/LockWait(plain RuntimeException)는 여기 catch에 안 걸리고 어드바이스로 → 503.
     */
    private CouponIssueResponse issueViaV3(CouponIssueRequest request) {
        try {
            return v3Service.issue(request.couponId(), request.userId());
        } catch (DataAccessException | TransactionException e) {
            throw CouponDbExceptionClassifier.classify(e);
        }
    }
}
