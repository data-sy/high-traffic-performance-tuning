package com.project.api.order.dto;

import com.project.domain.order.OrderItem;
import lombok.Getter;

@Getter
public class OrderItemResponse {

    private final Long productId;
    private final String productName;
    private final Integer price;
    private final Integer quantity;

    /**
     * 전 버전 공용 생성자. v5 DTO Projection은 이 시그니처를 JPQL 생성자 표현식
     * {@code select new ...OrderItemResponse(oi.product.id, oi.product.name, oi.price, oi.quantity)}으로
     * 직접 호출한다(엔티티 영속성 적재 0).
     */
    public OrderItemResponse(Long productId, String productName, Integer price, Integer quantity) {
        this.productId = productId;
        this.productName = productName;
        this.price = price;
        this.quantity = quantity;
    }

    /** v1~v4: 적재된 엔티티에서 매핑(접근 시 product LAZY 로딩 → N+1 경로). */
    public static OrderItemResponse from(OrderItem orderItem) {
        return new OrderItemResponse(
                orderItem.getProduct().getId(),
                orderItem.getProduct().getName(),
                orderItem.getPrice(),
                orderItem.getQuantity()
        );
    }
}
