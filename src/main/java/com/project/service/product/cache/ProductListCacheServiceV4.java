package com.project.service.product.cache;

import com.project.infrastructure.cache.CacheEnvelope;
import com.project.infrastructure.cache.CachePins;
import com.project.infrastructure.cache.CachedProductList;
import com.project.infrastructure.cache.LookAsideCache;
import com.project.infrastructure.cache.OriginRecomputeCounter;
import com.project.infrastructure.cache.StampedeBarrier;
import com.project.service.product.ProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 캐시 v4 — 논리 만료 + 비동기 갱신 (CAS 단일 refresh, 만료 전환 스파이크 완화).
 *
 * <p>만료창의 <b>직렬 대기 자체</b>를 없앤다. 이 효과의 합법 측정축은 <b>만료 전환창 대기 큐 깊이(블록 요청
 * 수, 축 A2)</b>다: v3=C-1 → v4≈0. 재계산 수는 v3·v4 둘 다 ≈1 이라 이 축으론 안 갈린다.
 *
 * <p>메커니즘(Q7·DR-B 고정 = 논리만료+비동기갱신, 락 없는 고전 PER 제외):
 * <ul>
 *   <li>물리 TTL 은 길게({@code V4_PHYSICAL_TTL_MS}), 페이로드에 논리 만료 시각({@code logicalExpiryAtEpochMs}).</li>
 *   <li>논리 만료 후 첫 요청들은 <b>스테일을 즉시 반환</b>(대기 0)하면서 백그라운드 갱신을 트리거한다.</li>
 *   <li>갱신은 <b>CAS/플래그 단일 refresh 가드</b>로 키당 하나만 발화 → 재계산=1 · 블록≈0 이 둘 다 성립.</li>
 * </ul>
 *
 * <p><b>물리 콜드 미스엔 single-flight 보호 없음(DR-V4COLD-1).</b> 재계산=1·블록≈0 은 유효/스테일 값이
 * 물리적으로 존재할 때만 성립한다. 그래서 v4 는 어떤 축에서도 물리 DEL/evict 로 측정하지 않는다(ops 가 강제).
 * 물리 콜드 경로는 기능적 폴백(인라인 로드)일 뿐 — 측정 범위 밖. 콜드 경로 single-flight 결합은 '한 칸만 변경'
 * 규율과 상충(백로그).
 */
@Service
@Profile("phase3-8")
public class ProductListCacheServiceV4 extends AbstractProductListCacheService {

    private static final Logger log = LoggerFactory.getLogger(ProductListCacheServiceV4.class);

    /** 키별 단일 refresh 가드(CAS) — true 면 그 키의 갱신이 진행 중. */
    private final Map<String, AtomicBoolean> refreshGuards = new ConcurrentHashMap<>();
    private final ThreadPoolTaskExecutor refreshExecutor;

    public ProductListCacheServiceV4(LookAsideCache cache,
                                     OriginRecomputeCounter recomputeCounter,
                                     StampedeBarrier barrier,
                                     ProductService productService,
                                     @Qualifier("cacheRefreshExecutor") ThreadPoolTaskExecutor refreshExecutor) {
        super(cache, recomputeCounter, barrier, productService);
        this.refreshExecutor = refreshExecutor;
    }

    @Override
    public int version() {
        return 4;
    }

    @Override
    public CachedProductList get(String category) {
        String fk = key(category);
        synchronizeArrival(fk); // 만료창 동기 도착(배리어 무장 시)

        CacheEnvelope env = cache.get(fk);
        if (env == null) {
            // 물리 콜드 미스 — v4엔 single-flight 없음(DR-V4COLD-1). 측정에선 이 경로 안 탐(ops 가 물리 DEL 금지).
            // 기능적 폴백: 인라인 로드 + 논리 적재.
            CachedProductList loaded = loadFromOrigin(fk, category);
            storeLogical(fk, loaded);
            return loaded;
        }

        long now = System.currentTimeMillis();
        if (env.isLogicallyExpired(now)) {
            // 스테일 즉시 반환(대기 0) + 백그라운드 단일 refresh(CAS). 블록 카운터 미증가 → 축A2 v4≈0.
            triggerAsyncRefresh(fk, category);
            return value(env);
        }
        // 유효 적중
        return value(env);
    }

    /** CAS 단일 refresh — 키당 하나만 발화(나머지는 스테일 반환, 블록 0). 재계산=1(백그라운드 로더 1진입). */
    private void triggerAsyncRefresh(String fk, String category) {
        AtomicBoolean guard = refreshGuards.computeIfAbsent(fk, k -> new AtomicBoolean(false));
        if (!guard.compareAndSet(false, true)) {
            return; // 이미 갱신 진행 중 — skip(블록 없음)
        }
        try {
            refreshExecutor.execute(() -> {
                try {
                    CachedProductList loaded = loadFromOrigin(fk, category); // 재계산=1
                    storeLogical(fk, loaded);
                } catch (Exception e) {
                    log.error("[v4] 비동기 갱신 실패 key={} — 스테일 유지(물리 TTL까지)", fk, e);
                } finally {
                    guard.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            // executor 거부(큐 만석 등) → 가드 원복(다음 요청이 재시도). 측정 유효성은 수렴 게이트가 판정.
            guard.set(false);
            log.warn("[v4] 갱신 작업 거부 key={} — 가드 원복", fk, rejected);
        }
    }

    /** v4 적재 = 긴 물리 TTL + 논리 만료 시각(now + 논리 수명). 논리 만료 후 STALE_WINDOW 동안 물리 값 생존. */
    private void storeLogical(String fk, CachedProductList payload) {
        long logicalExpiryAt = System.currentTimeMillis() + CachePins.V4_LOGICAL_LIFE_MS;
        cache.putLogical(fk, payload, CachePins.V4_PHYSICAL_TTL_MS, logicalExpiryAt);
    }

    private CachedProductList value(CacheEnvelope env) {
        return env.nullEntry() ? CachedProductList.empty() : env.payload();
    }
}
