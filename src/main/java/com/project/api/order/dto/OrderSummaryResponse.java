package com.project.api.order.dto;

import com.project.domain.order.OrderStatus;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class OrderSummaryResponse {

    private final Long orderId;
    private final OrderStatus status;
    private final Integer total;
    private final LocalDateTime createdAt;
    private final int itemCount;
    private final String firstProductName;

    public OrderSummaryResponse(Long orderId, OrderStatus status, Integer total,
                                LocalDateTime createdAt, int itemCount, String firstProductName) {
        this.orderId = orderId;
        this.status = status;
        this.total = total;
        this.createdAt = createdAt;
        this.itemCount = itemCount;
        this.firstProductName = firstProductName;
    }
}
