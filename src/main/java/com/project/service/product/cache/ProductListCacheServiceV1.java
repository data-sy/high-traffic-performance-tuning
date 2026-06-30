package com.project.service.product.cache;

import com.project.infrastructure.cache.CachedProductList;
import com.project.infrastructure.cache.LookAsideCache;
import com.project.infrastructure.cache.OriginRecomputeCounter;
import com.project.infrastructure.cache.StampedeBarrier;
import com.project.service.product.ProductService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 캐시 v1 — 캐시 없음, DB 직격 (baseline·"0층").
 *
 * <p>매 요청 = 오리진 재계산 1. 캐시 사다리의 바닥으로, 동기 부하에서 재계산 수가 곧 동시성과 같다.
 * 같은 배리어/카운터 계측을 거치게 해 v2~v5 와 apples-to-apples 로 비교한다(기존 {@code /api/v1/products}
 * 는 미계측 baseline 그대로 둔다 — 이 경로는 계측된 사다리용 v1).
 */
@Service
@Profile("phase3-8")
public class ProductListCacheServiceV1 extends AbstractProductListCacheService {

    public ProductListCacheServiceV1(LookAsideCache cache,
                                     OriginRecomputeCounter recomputeCounter,
                                     StampedeBarrier barrier,
                                     ProductService productService) {
        super(cache, recomputeCounter, barrier, productService);
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public CachedProductList get(String category) {
        String fk = key(category);
        synchronizeArrival(fk);
        return loadFromOrigin(fk, category); // 캐시 없음 — 매 요청 재계산
    }
}
