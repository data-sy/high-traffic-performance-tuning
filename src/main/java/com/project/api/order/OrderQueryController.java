package com.project.api.order;

import com.project.api.order.dto.OrderDetailResponse;
import com.project.api.order.dto.OrderSummaryResponse;
import com.project.service.order.OrderQueryServiceV1;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 주문 조회 경로 N+1 사다리. 모든 버전(v1~v5) 엔드포인트가 공존하여 독립 비교/재측정이 가능하다(CLAUDE.md API 규칙).
 * v1=baseline N+1. (v2~v5는 후속 Step에서 추가.)
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OrderQueryController {

    private final OrderQueryServiceV1 v1Service;

    @GetMapping("/v1/users/{userId}/orders")
    public List<OrderSummaryResponse> getOrdersV1(@PathVariable Long userId) {
        return v1Service.getOrders(userId);
    }

    @GetMapping("/v1/orders/{orderId}")
    public OrderDetailResponse getOrderV1(@PathVariable Long orderId) {
        return v1Service.getOrder(orderId);
    }
}
