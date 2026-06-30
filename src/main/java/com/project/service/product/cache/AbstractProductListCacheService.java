package com.project.service.product.cache;

import com.project.api.product.dto.ProductListResponse;
import com.project.infrastructure.cache.CachePins;
import com.project.infrastructure.cache.CachedProductList;
import com.project.infrastructure.cache.LookAsideCache;
import com.project.infrastructure.cache.OriginRecomputeCounter;
import com.project.infrastructure.cache.StampedeBarrier;
import com.project.service.product.ProductService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * 캐시 버전 서비스 공통 골격 — 키 빌드 · 배리어 동기 도착 check-in · 오리진 로더(재계산 카운터 진입점).
 *
 * <p>사다리 내 단일 변수 = 방어 기법만 차이. 공통(키·로더·카운트 위치·동기 도착)은 여기 한 곳에 두고
 * 각 버전은 {@link #get(String)} 의 전략만 다르게 구현한다.
 *
 * <p><b>키 규약:</b> 캐시 키 = prefix + 카테고리 (page1/size20 은 핀이라 키 미포함·ops ?key=category 와 정합).
 * 로더는 핀된 PAGE/SIZE 로 page1 핫 경로만 적재한다(핫 키 카디널리티 = 5).
 */
public abstract class AbstractProductListCacheService {

    protected final LookAsideCache cache;
    protected final OriginRecomputeCounter recomputeCounter;
    protected final StampedeBarrier barrier;
    private final ProductService productService;

    protected AbstractProductListCacheService(LookAsideCache cache,
                                              OriginRecomputeCounter recomputeCounter,
                                              StampedeBarrier barrier,
                                              ProductService productService) {
        this.cache = cache;
        this.recomputeCounter = recomputeCounter;
        this.barrier = barrier;
        this.productService = productService;
    }

    public abstract int version();

    /** 캐시 get 진입점 — 버전별 방어 전략. category 단위(page1 핀). */
    public abstract CachedProductList get(String category);

    protected String key(String category) {
        return cache.fullKey(category);
    }

    /**
     * 만료창 동기 도착 — 무장된 latch 에 check-in(무장 안 됐으면 no-op). 캐시 get <b>진입점</b>에서 호출해
     * 재계산/블록 카운터가 이 버전에 정상 귀속되게 한다(DR-A2-1).
     */
    protected void synchronizeArrival(String fullKey) {
        barrier.checkIn(fullKey);
    }

    /**
     * 오리진 로더 — <b>1 진입 = 재계산 +1</b>(M1 카운트 위치 계약). {@code Page} 가 SELECT + COUNT 2 SQL 을
     * 내더라도 로더 람다 진입 기준 1회만 센다(COUNT 이중 카운트 금지·DR-3). populate 창/락 점유시간(전 구간
     * 실측 p99 = 체크아웃 대기 + SELECT + COUNT + 직렬화)은 카운트가 아니라 측정 비용 회계로 별도 핀.
     */
    protected CachedProductList loadFromOrigin(String fullKey, String category) {
        recomputeCounter.increment(fullKey);
        Page<ProductListResponse> page =
                productService.getProducts(category, PageRequest.of(CachePins.PAGE, CachePins.SIZE));
        return new CachedProductList(page.getContent(), page.getTotalElements());
    }
}
