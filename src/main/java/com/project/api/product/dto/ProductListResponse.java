package com.project.api.product.dto;

import com.project.domain.product.Product;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class ProductListResponse {

    private final Long id;
    private final String name;
    private final Integer price;
    private final String category;
    private final LocalDateTime createdAt;

    public ProductListResponse(Long id, String name, Integer price, String category, LocalDateTime createdAt) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.category = category;
        this.createdAt = createdAt;
    }

    public static ProductListResponse from(Product product) {
        return new ProductListResponse(
                product.getId(),
                product.getName(),
                product.getPrice(),
                product.getCategory(),
                product.getCreatedAt()
        );
    }
}
