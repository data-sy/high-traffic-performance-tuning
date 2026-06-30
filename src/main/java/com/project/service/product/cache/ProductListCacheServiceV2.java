package com.project.service.product.cache;

import com.project.infrastructure.cache.CacheEnvelope;
import com.project.infrastructure.cache.CachePins;
import com.project.infrastructure.cache.CachedProductList;
import com.project.infrastructure.cache.LookAsideCache;
import com.project.infrastructure.cache.OriginRecomputeCounter;
import com.project.infrastructure.cache.StampedeBarrier;
import com.project.service.product.ProductService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 캐시 v2 — look-aside + 고정 TTL (결함 노출 baseline).
 *
 * <p>가장 단순한 캐시: 미스 시 오리진 로더 실행 → 결과를 고정 TTL 로 적재, 적중 시 캐시 반환. <b>락 없음.</b>
 * "캐시 얹었으니 빨라짐"의 직관을 세우되, 세 축의 구조적 한계를 <b>드러낸다(고치지 않는다)</b>:
 * <ul>
 *   <li><b>스탬피드:</b> 핫 키 TTL 만료 직후 배리어로 동기 release 된 C 개 동시 미스 → C 회 오리진 재계산(herd).
 *       락이 없어 만료창당 재계산 ≫1 을 카운터가 기록.</li>
 *   <li><b>페네트레이션:</b> 미존재 카테고리는 빈 Page(null 아닌 합법 적재값) 반환 — 그대로 적재하면 첫 1회 후
 *       적중돼 페네트레이션이 사라진다. 그래서 v2 는 {@code EMPTY_AS_MISS=true} 로 빈/널을 적재 안 하고 매 요청
 *       직격을 노출한다(단일 변수 = 적재 정책, v5 가 닫음).</li>
 *   <li><b>어밸런치:</b> 동일 TTL 일괄 워밍 키들이 동시 만료 → 광역 herd.</li>
 * </ul>
 */
@Service
@Profile("phase3-8")
public class ProductListCacheServiceV2 extends AbstractProductListCacheService {

    public ProductListCacheServiceV2(LookAsideCache cache,
                                     OriginRecomputeCounter recomputeCounter,
                                     StampedeBarrier barrier,
                                     ProductService productService) {
        super(cache, recomputeCounter, barrier, productService);
    }

    @Override
    public int version() {
        return 2;
    }

    @Override
    public CachedProductList get(String category) {
        String fk = key(category);
        synchronizeArrival(fk); // 만료창 동기 도착(배리어 무장 시) — 진입점에서 모았다 동시 release

        CacheEnvelope env = cache.get(fk);
        if (env != null) {
            // 적중. v2 는 빈/널을 적재 안 하므로 nullEntry 는 보통 없음(방어적 처리만).
            return env.nullEntry() ? CachedProductList.empty() : env.payload();
        }

        // 미스 → 오리진 재계산. 락 없음 = 동시 미스 전원이 각자 재계산(herd 노출).
        CachedProductList loaded = loadFromOrigin(fk, category);

        if (loaded.isEmpty() && CachePins.V2_EMPTY_AS_MISS) {
            // 빈/널 결과 적재 안 함 → 다음 요청도 직격(페네트레이션 노출). v5 가 널 캐싱으로 흡수.
            return loaded;
        }
        cache.putValue(fk, loaded, CachePins.TTL_BASE_MS);
        return loaded;
    }
}
