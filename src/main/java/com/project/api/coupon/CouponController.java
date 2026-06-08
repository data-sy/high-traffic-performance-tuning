package com.project.api.coupon;

import com.project.api.coupon.dto.CouponIssueRequest;
import com.project.api.coupon.dto.CouponIssueResponse;
import com.project.service.coupon.CouponIssueServiceV1;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 쿠폰 발급 — 버전별 벤치마크 표면(v1..v5 공존). 각 Step에서 엔드포인트 추가.
 * 프로덕션 무버전 별칭(/api/coupons/issue → v3)은 Step D에서 배선(스펙 §6).
 *
 * <p>하네스 계약: {@code POST /api/{vN}/coupons/issue}, body {couponId, userId}, 성공 200.
 */
@RestController
@RequestMapping("/api")
public class CouponController {

    private final CouponIssueServiceV1 v1Service;

    public CouponController(CouponIssueServiceV1 v1Service) {
        this.v1Service = v1Service;
    }

    @PostMapping("/v1/coupons/issue")
    public CouponIssueResponse issueV1(@RequestBody CouponIssueRequest request) {
        return v1Service.issue(request.couponId(), request.userId());
    }
}
