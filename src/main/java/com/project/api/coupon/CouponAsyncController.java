package com.project.api.coupon;

import java.util.Map;

import com.project.api.coupon.dto.CouponIssueRequest;
import com.project.api.coupon.dto.IssueAcceptedResponse;
import com.project.api.coupon.dto.IssueStatusResponse;
import com.project.infrastructure.asyncqueue.IssueStatusRepository;
import com.project.service.coupon.async.CouponIssueFacadeV6a;
import com.project.service.coupon.async.CouponIssueFacadeV6b;
import com.project.service.coupon.async.CouponIssueFacadeV6c;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비동기 발급 표면 (phase 3-4, v6a→v6c — 각 CP에서 엔드포인트 추가).
 *
 * <p>하네스 계약: {@code POST /api/{v6x}/coupons/issue} → 202 {requestId, status} / 429 QUEUE_FULL.
 * 상태 조회는 버전 공통 {@code GET /api/v6/coupons/issue-status/{requestId}} (T7 — 1급 산출물).
 */
@RestController
@RequestMapping("/api")
public class CouponAsyncController {

    private final CouponIssueFacadeV6a v6aFacade;
    private final CouponIssueFacadeV6b v6bFacade;
    private final CouponIssueFacadeV6c v6cFacade;
    private final IssueStatusRepository statusRepository;

    public CouponAsyncController(CouponIssueFacadeV6a v6aFacade,
                                 CouponIssueFacadeV6b v6bFacade,
                                 CouponIssueFacadeV6c v6cFacade,
                                 IssueStatusRepository statusRepository) {
        this.v6aFacade = v6aFacade;
        this.v6bFacade = v6bFacade;
        this.v6cFacade = v6cFacade;
        this.statusRepository = statusRepository;
    }

    @PostMapping("/v6a/coupons/issue")
    public ResponseEntity<IssueAcceptedResponse> issueV6a(@RequestBody CouponIssueRequest request) {
        String requestId = v6aFacade.enqueue(request.couponId(), request.userId());
        return ResponseEntity.accepted().body(IssueAcceptedResponse.accepted(requestId));
    }

    @PostMapping("/v6b/coupons/issue")
    public ResponseEntity<IssueAcceptedResponse> issueV6b(@RequestBody CouponIssueRequest request) {
        String requestId = v6bFacade.enqueue(request.couponId(), request.userId());
        return ResponseEntity.accepted().body(IssueAcceptedResponse.accepted(requestId));
    }

    @PostMapping("/v6c/coupons/issue")
    public ResponseEntity<IssueAcceptedResponse> issueV6c(@RequestBody CouponIssueRequest request) {
        String requestId = v6cFacade.enqueue(request.couponId(), request.userId());
        return ResponseEntity.accepted().body(IssueAcceptedResponse.accepted(requestId));
    }

    /** 202를 받은 사용자가 발급 여부를 알게 되는 경로 (T7). 미존재·TTL 만료는 404. */
    @GetMapping("/v6/coupons/issue-status/{requestId}")
    public ResponseEntity<IssueStatusResponse> status(@PathVariable String requestId) {
        Map<String, String> hash = statusRepository.find(requestId);
        if (hash.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(IssueStatusResponse.from(requestId, hash));
    }
}
