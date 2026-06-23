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
 * v3 — batch fetch(`hibernate.default_batch_fetch_size`, 또는 연관 `@BatchSize`).
 *
 * <p><b>코드 경로는 v1과 동일</b>(findByUserId + 루프 접근). 유일한 차이는 <b>측정 런에서만 토글되는
 * 전역 변수 = batch fetch size</b>다. 커밋 baseline은 unset을 유지하고(연관에 batch를 박으면 v1 순수
 * N+1 baseline이 자동 배치로 오염 — R1), v3 측정 런에서만 size를 켠 뒤 원복한다.
 *
 * <p>batch 토글 시 LAZY secondary SELECT들이 IN절로 묶여 {@code 1 + 2⌈N/b⌉}로 수렴(쿼리 수는 실측 확정).
 * N+1을 없애진 않지만 라운드트립을 소수로 압축한다.
 */
@Service
@RequiredArgsConstructor
public class OrderQueryServiceV3 {

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
        List<OrderItem> items = order.getOrderItems();
        int itemCount = items.size();
        String firstProductName = items.stream()
                .min(Comparator.comparing(OrderItem::getId))
                .map(item -> item.getProduct().getName())
                .orElse(null);
        return new OrderSummaryResponse(
                order.getId(), order.getStatus(), order.getTotal(),
                order.getCreatedAt(), itemCount, firstProductName);
    }
}
