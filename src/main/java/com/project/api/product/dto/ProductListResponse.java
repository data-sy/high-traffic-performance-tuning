package com.project.api.product.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    // Phase 3-8(DR-2): 캐시 적재값이 List<ProductListResponse> 로 직렬화·역직렬화되므로 Jackson 계약 필요.
    // no-arg 가 없어 역직렬화에 실패하던 것을 @JsonCreator 로 닫는다(캐시 외 기존 응답 직렬화엔 무영향).
    @JsonCreator
    public ProductListResponse(
            @JsonProperty("id") Long id,
            @JsonProperty("name") String name,
            @JsonProperty("price") Integer price,
            @JsonProperty("category") String category,
            @JsonProperty("createdAt") LocalDateTime createdAt) {
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
