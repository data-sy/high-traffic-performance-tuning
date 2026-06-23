package com.project.service.order;

import com.project.api.order.dto.OrderDetailResponse;
import com.project.api.order.dto.OrderSummaryResponse;
import com.project.domain.order.Order;
import com.project.domain.order.OrderItem;
import com.project.domain.order.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * v1 — LAZY 기본 + 루프 접근. N+1 baseline(의도적 재현).
 *
 * <p>목록: 헤더 1회 + 주문당 orderItems 컬렉션 SELECT(N회) + 대표상품 product SELECT(N회) = 상한 1+2N.
 * 상세: 주문 1 + 컬렉션 1 + 아이템별 product M회 = 2+M.
 *
 * <p>대표상품(firstProductName)은 버전 간 응답 결정성을 위해 OrderItem id 오름차순 첫 항목으로 정의한다.
 */
@Service
@RequiredArgsConstructor
public class OrderQueryServiceV1 {

    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public List<OrderSummaryResponse> getOrders(Long userId) {
        List<Order> orders = orderRepository.findByUserId(userId);
        return orders.stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderDetailResponse getOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        return OrderDetailResponse.from(order);
    }

    private OrderSummaryResponse toSummary(Order order) {
        List<OrderItem> items = order.getOrderItems();           // LAZY → 컬렉션 SELECT
        int itemCount = items.size();
        String firstProductName = items.stream()
                .min(Comparator.comparing(OrderItem::getId))
                .map(item -> item.getProduct().getName())        // LAZY → 대표상품 product SELECT
                .orElse(null);
        return new OrderSummaryResponse(
                order.getId(), order.getStatus(), order.getTotal(),
                order.getCreatedAt(), itemCount, firstProductName);
    }
}
