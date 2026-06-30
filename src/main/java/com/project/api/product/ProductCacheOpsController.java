package com.project.api.product;

import com.project.api.product.dto.ProductListResponse;
import com.project.infrastructure.cache.BlockedRequestCounter;
import com.project.infrastructure.cache.CacheEnvelope;
import com.project.infrastructure.cache.CachePins;
import com.project.infrastructure.cache.CachedProductList;
import com.project.infrastructure.cache.LookAsideCache;
import com.project.infrastructure.cache.OriginRecomputeCounter;
import com.project.infrastructure.cache.StampedeBarrier;
import com.project.service.product.ProductService;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 측정 하네스 전용 ops 표면 (A-6) — {@code /api/cache/ops/*}.
 *
 * <p>측정 전용(@Profile phase3-8). pins/recompute/blocked(구조 지표 소스) · expire/evict/warm(전환점 유발) ·
 * barrier(동기 도착 arming) · state(관찰) · reset(런 간 격리).
 *
 * <p>키 규약: 모든 표면이 {@code ?key=<category>} 를 받아 {@link LookAsideCache#fullKey} 로 prefix 를 붙인다
 * — 카운터·배리어·저장소가 모두 fullKey 로 일관 귀속(page1/size20 은 핀이라 키에 미포함).
 */
@RestController
@RequestMapping("/api/cache/ops")
@Profile("phase3-8")
public class ProductCacheOpsController {

    private final LookAsideCache cache;
    private final OriginRecomputeCounter recomputeCounter;
    private final BlockedRequestCounter blockedCounter;
    private final StampedeBarrier barrier;
    private final ProductService productService;

    public ProductCacheOpsController(LookAsideCache cache,
                                     OriginRecomputeCounter recomputeCounter,
                                     BlockedRequestCounter blockedCounter,
                                     StampedeBarrier barrier,
                                     ProductService productService) {
        this.cache = cache;
        this.recomputeCounter = recomputeCounter;
        this.blockedCounter = blockedCounter;
        this.barrier = barrier;
        this.productService = productService;
    }

    /** 핀 런타임 실측값(설정 echo 아니라 빈/클라이언트에서 읽은 값). 하네스가 CachePins 기대값과 재대조(이중 게이트). */
    @GetMapping("/pins")
    public Map<String, Object> pins() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cacheRedisPort", CachePins.CACHE_REDIS_PORT);
        m.put("keyPrefix", cache.keyPrefix());
        m.put("ttlBaseMs", CachePins.TTL_BASE_MS);
        m.put("nullTtlMs", CachePins.NULL_TTL_MS);
        m.put("ttlJitterRatio", CachePins.TTL_JITTER_RATIO);
        m.put("lockTimeoutMs", CachePins.LOCK_TIMEOUT_MS);
        m.put("lockWaitMs", CachePins.LOCK_WAIT_MS);
        m.put("staleWindowMs", CachePins.STALE_WINDOW_MS);
        m.put("hotCategories", CachePins.HOT_CATEGORIES);
        m.put("page", CachePins.PAGE);
        m.put("size", CachePins.SIZE);
        m.put("stampedeConcurrency", CachePins.STAMPEDE_CONCURRENCY);
        m.put("penetrationConcurrency", CachePins.PENETRATION_CONCURRENCY);
        m.put("hikariMaxPoolSize", CachePins.HIKARI_MAX_POOL_SIZE);
        m.put("v2EmptyAsMiss", CachePins.V2_EMPTY_AS_MISS);
        m.put("v5EmptyAsMiss", CachePins.V5_EMPTY_AS_MISS);
        return m;
    }

    /** 1차 구조 지표 A — 키별 오리진 재계산 누적. */
    @GetMapping("/recompute")
    public Map<String, Long> recompute() {
        return recomputeCounter.snapshot();
    }

    /** 2차 구조 지표 A2 — 키별 single-flight 락 대기 블록 누적(v3=C-1 vs v4≈0). */
    @GetMapping("/blocked")
    public Map<String, Long> blocked() {
        return blockedCounter.snapshot();
    }

    /**
     * 강제 만료 — 버전군별 시맨틱 분기(A-6·DR-V4COLD-1).
     * 하드TTL군(v2·v3·v5): 물리 DEL(콜드 미스 유발). 논리만료군(v4): 논리 만료 마킹(물리 값 보존).
     * v4 를 DEL 로 지우면 콜드 미스로 전락해 v3 와 구별 불가가 되므로 version 으로 분기한다.
     */
    @PostMapping("/expire")
    public Map<String, Object> expire(@RequestParam String key, @RequestParam(defaultValue = "2") int version) {
        String fk = cache.fullKey(key);
        String semantic;
        if (version == 4) {
            cache.markLogicalExpired(fk);
            semantic = "logical-mark(물리 보존)";
        } else {
            cache.delete(fk);
            semantic = "physical-DEL(콜드 미스)";
        }
        return Map.of("key", fk, "version", version, "semantic", semantic);
    }

    /** 물리 축출(콜드 미스). v4 는 물리 DEL 로 측정하지 않음(DR-V4COLD-1) — version=4 호출은 거부. */
    @PostMapping("/evict")
    public Map<String, Object> evict(@RequestParam String key, @RequestParam(defaultValue = "2") int version) {
        if (version == 4) {
            throw new IllegalArgumentException(
                    "v4 는 물리 DEL/evict 로 측정 금지(DR-V4COLD-1) — 콜드 미스에 single-flight 보호 없어 herd 회귀. /expire?version=4(논리만료) 사용.");
        }
        String fk = cache.fullKey(key);
        cache.delete(fk);
        return Map.of("key", fk, "version", version, "semantic", "physical-DEL");
    }

    /**
     * 일괄 워밍(어밸런치 셋업) — 핫 카테고리 전체를 동일 TTL 로 적재(M3·축C).
     * version=4 면 논리만료 형태로 적재(물리 TTL 길게 + 논리 만료 시각). 그 외는 하드 TTL.
     */
    @PostMapping("/warm")
    public Map<String, Object> warm(@RequestParam(defaultValue = "2") int version) {
        Map<String, Object> warmed = new LinkedHashMap<>();
        for (String category : CachePins.HOT_CATEGORIES) {
            CachedProductList payload = load(category);
            String fk = cache.fullKey(category);
            if (version == 4) {
                // v4 논리 적재 — ProductListCacheServiceV4.storeLogical 과 동일 타이밍(V4_PHYSICAL_TTL/LOGICAL_LIFE).
                long logicalExpiryAt = System.currentTimeMillis() + CachePins.V4_LOGICAL_LIFE_MS;
                cache.putLogical(fk, payload, CachePins.V4_PHYSICAL_TTL_MS, logicalExpiryAt);
            } else if (version == 5) {
                // v5 지터 TTL — 어밸런치 측정 시 동일 시각 일괄 만료를 분산(v2 고정 TTL 과의 단일 변수).
                cache.putValue(fk, payload, com.project.infrastructure.cache.TtlJitter.apply(CachePins.TTL_BASE_MS));
            } else {
                cache.putValue(fk, payload, CachePins.TTL_BASE_MS);
            }
            warmed.put(fk, payload.total());
        }
        return Map.of("version", version, "warmed", warmed);
    }

    /**
     * 만료창 동기 도착 arming 전용(DR-A·DR-A2-1) — latch 무장만. 부하/로더 미수신.
     * 실제 C 개 부하는 {@code /api/cache/v{n}/products} 로 보내고, 각 요청이 버전별 캐시 get 진입점에서 check-in.
     */
    @PostMapping("/barrier")
    public Map<String, Object> barrier(@RequestParam String key, @RequestParam int n) {
        String fk = cache.fullKey(key);
        barrier.arm(fk, n);
        return Map.of("key", fk, "n", n, "armed", true);
    }

    /** 키별 관찰값 — 존재·잔여 물리 TTL·널 여부·논리 만료. */
    @GetMapping("/state")
    public Map<String, Object> state(@RequestParam String key) {
        String fk = cache.fullKey(key);
        CacheEnvelope env = cache.get(fk);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", fk);
        m.put("exists", env != null);
        m.put("pttlMs", cache.pttlMs(fk));
        if (env != null) {
            long now = System.currentTimeMillis();
            m.put("nullEntry", env.nullEntry());
            m.put("logicalExpiryAtEpochMs", env.logicalExpiryAtEpochMs());
            m.put("logicallyExpired", env.isLogicallyExpired(now));
            m.put("total", env.payload() == null ? null : env.payload().total());
        }
        return m;
    }

    /** 런 간 격리 — 카운터 reset(키/캐시값은 expire/evict 로). */
    @PostMapping("/reset")
    public Map<String, Object> reset() {
        recomputeCounter.reset();
        blockedCounter.reset();
        return Map.of("reset", true);
    }

    private CachedProductList load(String category) {
        Page<ProductListResponse> page =
                productService.getProducts(category, PageRequest.of(CachePins.PAGE, CachePins.SIZE));
        List<ProductListResponse> items = page.getContent();
        return new CachedProductList(items, page.getTotalElements());
    }
}
