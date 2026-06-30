package com.project.infrastructure.cache;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.project.api.product.dto.ProductListResponse;

import java.util.List;

/**
 * 캐시 적재용 직렬화 안전 페이로드 (DR-2).
 *
 * <p>오리진 로더가 감싸는 {@code ProductService.getProducts} 의 자연 반환형은 {@code Page<ProductListResponse>}
 * (= {@code PageImpl})인데, {@code PageImpl} 은 Jackson 안정 라운드트립 계약이 없어 역직렬화에 실패한다.
 * 그래서 캐시에는 {@code PageImpl} 대신 이 전용 레코드(items + total)를 넣고, 응답 경계에서 다시
 * {@code PageImpl(items, pageable, total)} 로 복원한다.
 *
 * <p>역직렬화는 {@code ObjectMapper.readValue(json, CacheEnvelope.class)} 로 타입을 명시해 수행하므로
 * 다형 타입(@class) 의존 없이 안전하다(LookAsideCache). 그래도 {@code @JsonCreator}/{@code @JsonProperty}
 * 를 명시해 -parameters 플래그 유무와 무관하게 record 역직렬화를 보장한다.
 */
public record CachedProductList(List<ProductListResponse> items, long total) {

    @JsonCreator
    public CachedProductList(
            @JsonProperty("items") List<ProductListResponse> items,
            @JsonProperty("total") long total) {
        this.items = items;
        this.total = total;
    }

    public static CachedProductList empty() {
        return new CachedProductList(List.of(), 0L);
    }

    /**
     * 빈/널 판정 — 페네트레이션 적재 정책의 단일 변수(빈 Page 는 null 이 아닌 합법 값).
     * <b>{@code @JsonIgnore} 필수:</b> 없으면 Jackson 이 no-arg boolean getter 를 "empty" 프로퍼티로 직렬화하나
     * {@code @JsonCreator} 는 items/total 만 알아 역직렬화에서 UnrecognizedPropertyException(부팅 self-check 가 검출).
     */
    @JsonIgnore
    public boolean isEmpty() {
        return items == null || items.isEmpty();
    }
}
