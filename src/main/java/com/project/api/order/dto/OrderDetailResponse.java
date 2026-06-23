package com.project.api.order.dto;

import com.project.domain.order.Order;
import com.project.domain.order.OrderStatus;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
public class OrderDetailResponse {

    private final Long orderId;
    private final OrderStatus status;
    private final Integer total;
    private final LocalDateTime createdAt;
    private final List<OrderItemResponse> items;

    /**
     * 헤더 전용 생성자. v5 DTO Projection이 JPQL 생성자 표현식
     * {@code select new ...OrderDetailResponse(o.id, o.status, o.total, o.createdAt)}으로 호출하고,
     * 라인은 별도 쿼리로 채워 {@link #attachItems(List)}로 붙인다(상세 2회 고정).
     */
    public OrderDetailResponse(Long orderId, OrderStatus status, Integer total, LocalDateTime createdAt) {
        this.orderId = orderId;
        this.status = status;
        this.total = total;
        this.createdAt = createdAt;
        this.items = new ArrayList<>();
    }

    /** v5: 별도 라인 쿼리 결과를 헤더에 결합한다. */
    public void attachItems(List<OrderItemResponse> lines) {
        this.items.addAll(lines);
    }

    /** v1~v4: 적재된 Order 엔티티에서 매핑(orderItems·product 접근 → N+1 경로). */
    public static OrderDetailResponse from(Order order) {
        OrderDetailResponse response = new OrderDetailResponse(
                order.getId(), order.getStatus(), order.getTotal(), order.getCreatedAt());
        List<OrderItemResponse> lines = order.getOrderItems().stream()
                .map(OrderItemResponse::from)
                .toList();
        response.attachItems(lines);
        return response;
    }
}
