package com.project.api.order;

import com.project.api.order.dto.OrderDetailResponse;
import com.project.api.order.dto.OrderSummaryResponse;
import com.project.service.order.OrderQueryServiceV1;
import com.project.service.order.OrderQueryServiceV2;
import com.project.service.order.OrderQueryServiceV3;
import com.project.service.order.OrderQueryServiceV4;
import com.project.service.order.OrderQueryServiceV5;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 주문 조회 경로 N+1 사다리. 모든 버전(v1~v5) 엔드포인트가 공존하여 독립 비교/재측정이 가능하다(CLAUDE.md API 규칙).
 * v1=baseline N+1, v2=EAGER(토글), v3=batch(토글), v4=Fetch Join, v5=DTO Projection.
 *
 * <p>v2/v3은 코드 경로가 v1과 동일하고 거동 차이는 측정 런에서 토글되는 전역 변수(EAGER 매핑 / batch size)에서 나온다(R1).
 * v4·v5는 쿼리 메서드 레벨이라 전역을 안 건드려 영구 공존.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OrderQueryController {

    private final OrderQueryServiceV1 v1Service;
    private final OrderQueryServiceV2 v2Service;
    private final OrderQueryServiceV3 v3Service;
    private final OrderQueryServiceV4 v4Service;
    private final OrderQueryServiceV5 v5Service;

    @GetMapping("/v1/users/{userId}/orders")
    public List<OrderSummaryResponse> getOrdersV1(@PathVariable Long userId) {
        return v1Service.getOrders(userId);
    }

    @GetMapping("/v1/orders/{orderId}")
    public OrderDetailResponse getOrderV1(@PathVariable Long orderId) {
        return v1Service.getOrder(orderId);
    }

    @GetMapping("/v2/users/{userId}/orders")
    public List<OrderSummaryResponse> getOrdersV2(@PathVariable Long userId) {
        return v2Service.getOrders(userId);
    }

    @GetMapping("/v2/orders/{orderId}")
    public OrderDetailResponse getOrderV2(@PathVariable Long orderId) {
        return v2Service.getOrder(orderId);
    }

    @GetMapping("/v3/users/{userId}/orders")
    public List<OrderSummaryResponse> getOrdersV3(@PathVariable Long userId) {
        return v3Service.getOrders(userId);
    }

    @GetMapping("/v3/orders/{orderId}")
    public OrderDetailResponse getOrderV3(@PathVariable Long orderId) {
        return v3Service.getOrder(orderId);
    }

    @GetMapping("/v4/users/{userId}/orders")
    public List<OrderSummaryResponse> getOrdersV4(@PathVariable Long userId) {
        return v4Service.getOrders(userId);
    }

    @GetMapping("/v4/orders/{orderId}")
    public OrderDetailResponse getOrderV4(@PathVariable Long orderId) {
        return v4Service.getOrder(orderId);
    }

    @GetMapping("/v5/users/{userId}/orders")
    public List<OrderSummaryResponse> getOrdersV5(@PathVariable Long userId) {
        return v5Service.getOrders(userId);
    }

    @GetMapping("/v5/orders/{orderId}")
    public OrderDetailResponse getOrderV5(@PathVariable Long orderId) {
        return v5Service.getOrder(orderId);
    }
}
