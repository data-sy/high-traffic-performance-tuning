package com.project.service.product.cache;

import com.project.infrastructure.cache.CacheEnvelope;
import com.project.infrastructure.cache.CachePins;
import com.project.infrastructure.cache.CachedProductList;
import com.project.infrastructure.cache.LookAsideCache;
import com.project.infrastructure.cache.OriginRecomputeCounter;
import com.project.infrastructure.cache.StampedeBarrier;
import com.project.infrastructure.cache.TtlJitter;
import com.project.service.product.ProductService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 캐시 v5 — 널 캐싱 + TTL 지터 (페네트레이션·어밸런치 방어, 상시 위생).
 *
 * <p>v2(고정 TTL look-aside)에서 <b>적재 정책과 지터만</b> 바꾼다(락 없음 = v2 와 동일). v3 single-flight·v4
 * 논리만료와 <b>직교</b> — herd 자체(v3)·만료 대기(v4)의 대체가 아니라, 트래픽 성격과 무관하게 깔아두는 기본 방어다.
 *
 * <ul>
 *   <li><b>페네트레이션(Q8 = 널 캐싱):</b> 미존재 키의 빈 결과를 짧은 {@code NULL_TTL_MS} 로 적재해 반복 직격을
 *       흡수({@code EMPTY_AS_MISS=false} — v2 의 정반대, 단일 변수 = 적재 정책). <b>single-flight 와 직교</b>이라
 *       널 적재 <i>전</i> 창에 동시 도착한 요청은 전원 미스→직격(첫 만료창 herd), 적재 <i>후</i> 도착분만 널 적중.
 *       즉 '첫 1회'가 아니라 '첫 만료창 herd 후 0'(축B 게이트). 창당 1 까지 줄이려면 single-flight 결합 필요(별도 칸).</li>
 *   <li><b>어밸런치:</b> 값 적재 TTL 에 지터(±{@code TTL_JITTER_RATIO})를 더해 동일 시각 일괄 만료를 분산.</li>
 * </ul>
 */
@Service
@Profile("phase3-8")
public class ProductListCacheServiceV5 extends AbstractProductListCacheService {

    public ProductListCacheServiceV5(LookAsideCache cache,
                                     OriginRecomputeCounter recomputeCounter,
                                     StampedeBarrier barrier,
                                     ProductService productService) {
        super(cache, recomputeCounter, barrier, productService);
    }

    @Override
    public int version() {
        return 5;
    }

    @Override
    public CachedProductList get(String category) {
        String fk = key(category);
        synchronizeArrival(fk); // 만료창 동기 도착(배리어 무장 시)

        CacheEnvelope env = cache.get(fk);
        if (env != null) {
            // 널 적중도 흡수(페네트레이션) — 적재된 "빈 결과" 사실을 그대로 반환.
            return env.nullEntry() ? CachedProductList.empty() : env.payload();
        }

        // 미스 → 오리진 재계산. 락 없음 = 첫 만료창은 v2 처럼 herd(직교·v5 는 herd 안 닫음).
        CachedProductList loaded = loadFromOrigin(fk, category);

        if (loaded.isEmpty()) {
            // EMPTY_AS_MISS=false → 빈/널을 짧은 NULL_TTL 로 적재(페네트레이션 흡수). v2 와의 단일 변수.
            cache.putNull(fk, CachePins.NULL_TTL_MS);
            return loaded;
        }
        // 값 적재 = 지터 TTL(어밸런치 분산). v2 의 고정 TTL 과의 차이.
        cache.putValue(fk, loaded, TtlJitter.apply(CachePins.TTL_BASE_MS));
        return loaded;
    }
}
