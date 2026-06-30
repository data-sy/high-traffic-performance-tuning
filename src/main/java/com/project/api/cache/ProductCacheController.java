package com.project.api.cache;

import com.project.api.product.dto.ProductListResponse;
import com.project.infrastructure.cache.CachePins;
import com.project.infrastructure.cache.CachedProductList;
import com.project.service.product.cache.ProductListCacheServiceV1;
import com.project.service.product.cache.ProductListCacheServiceV2;
import com.project.service.product.cache.ProductListCacheServiceV3;
import com.project.service.product.cache.ProductListCacheServiceV4;
import com.project.service.product.cache.ProductListCacheServiceV5;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 캐시 사다리 엔드포인트 {@code /api/cache/v{n}/products} (Q2 확정 경로) — 기존 {@code /api/v1/products} 미수정·무오염.
 *
 * <p>측정 전용(@Profile phase3-8). category 단위(page1/size20 핀) — 핫 키 카디널리티 5. 응답은 캐시 안전
 * 형태 {@link CachedProductList} 를 응답 경계에서 {@link PageImpl} 로 복원해 기존 v1 과 동일한 형태로 돌려준다.
 *
 * <p>v3~v5 엔드포인트는 각 Step 에서 이 컨트롤러에 추가된다.
 */
@RestController
@Profile("phase3-8")
public class ProductCacheController {

    private final ProductListCacheServiceV1 v1;
    private final ProductListCacheServiceV2 v2;
    private final ProductListCacheServiceV3 v3;
    private final ProductListCacheServiceV4 v4;
    private final ProductListCacheServiceV5 v5;

    public ProductCacheController(ProductListCacheServiceV1 v1,
                                  ProductListCacheServiceV2 v2,
                                  ProductListCacheServiceV3 v3,
                                  ProductListCacheServiceV4 v4,
                                  ProductListCacheServiceV5 v5) {
        this.v1 = v1;
        this.v2 = v2;
        this.v3 = v3;
        this.v4 = v4;
        this.v5 = v5;
    }

    @GetMapping("/api/cache/v1/products")
    public Page<ProductListResponse> v1(@RequestParam String category) {
        return toPage(v1.get(category));
    }

    @GetMapping("/api/cache/v2/products")
    public Page<ProductListResponse> v2(@RequestParam String category) {
        return toPage(v2.get(category));
    }

    @GetMapping("/api/cache/v3/products")
    public Page<ProductListResponse> v3(@RequestParam String category) {
        return toPage(v3.get(category));
    }

    @GetMapping("/api/cache/v4/products")
    public Page<ProductListResponse> v4(@RequestParam String category) {
        return toPage(v4.get(category));
    }

    @GetMapping("/api/cache/v5/products")
    public Page<ProductListResponse> v5(@RequestParam String category) {
        return toPage(v5.get(category));
    }

    private Page<ProductListResponse> toPage(CachedProductList cached) {
        List<ProductListResponse> items = cached.items() == null ? List.of() : cached.items();
        return new PageImpl<>(items, PageRequest.of(CachePins.PAGE, CachePins.SIZE), cached.total());
    }
}
