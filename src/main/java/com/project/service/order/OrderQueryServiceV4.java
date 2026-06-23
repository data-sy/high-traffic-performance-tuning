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
 * v4 — Fetch Join(JPQL {@code join fetch ... distinct}). 쿼리 메서드 레벨이라 전역을 안 건드려 영구 공존(R1).
 *
 * <p>주문+아이템+상품을 단일 쿼리로 적재 → 매핑 루프에서 추가 SELECT 0(N+1 소멸). 컬렉션 1 + to-one 1이라
 * {@code distinct}로 카테시안 곱을 통제한다. <b>단건 상세에 최적</b>. 목록은 비페이징 단일 쿼리로 동작하나,
 * 1:N fetch join + 실제 페이징은 메모리 페이징(HHH000104)이라 페이징 목록엔 부적합(경로별 배선상 목록 기본은 v3).
 */
@Service
@RequiredArgsConstructor
public class OrderQueryServiceV4 {

    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public List<OrderSummaryResponse> getOrders(Long userId) {
        List<Order> orders = orderRepository.findByUserIdFetch(userId);
        return orders.stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderDetailResponse getOrder(Long orderId) {
        Order order = orderRepository.findDetailByIdFetch(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        return OrderDetailResponse.from(order);
    }

    private OrderSummaryResponse toSummary(Order order) {
        List<OrderItem> items = order.getOrderItems();           // fetch join으로 적재됨 → 추가 SELECT 없음
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
