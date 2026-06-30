package com.project.service.product.cache;

import com.project.infrastructure.cache.BlockedRequestCounter;
import com.project.infrastructure.cache.CacheEnvelope;
import com.project.infrastructure.cache.CachePins;
import com.project.infrastructure.cache.CachedProductList;
import com.project.infrastructure.cache.LookAsideCache;
import com.project.infrastructure.cache.OriginRecomputeCounter;
import com.project.infrastructure.cache.StampedeBarrier;
import com.project.infrastructure.redis.RedisLockRepository;
import com.project.service.product.ProductService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 캐시 v3 — single-flight / mutex (thundering herd 차단).
 *
 * <p>미스 시 <b>키당 하나만</b> 오리진을 재계산한다. 동시 미스 중 하나가 분산락(전용 Redis SET NX PX)을 잡아
 * 재계산·적재하고, 나머지는 <b>락 대기 후 캐시 적중(블록)</b>. 만료창당 재계산을 1로 닫는다.
 *
 * <p><b>Q4 결정 = 분산락(RedisLockRepository, 전용 6380 인스턴스 격리).</b> 단일 인스턴스 측정이나 계약은
 * 분산락으로 고정 — 락 리스(LOCK_TIMEOUT_MS=PX)·홀더 크래시 인계 시맨틱이 실재한다. 다중 인스턴스 herd 차단
 * 차이는 백로그(Open Questions).
 *
 * <p>새 실패면(설계 의도대로 들임):
 * <ul>
 *   <li><b>락 점유 = 오리진 로더 전 구간</b>(체크아웃 대기 + SELECT + COUNT + 직렬화 + 적재) — single-flight 정의(DR-1).
 *       락을 내부 SELECT 만 감싸면 비홀더가 미스로 새 재계산 시작.</li>
 *   <li><b>락 리스 불변:</b> {@code LOCK_TIMEOUT_MS}(PX) &gt; 전 구간 실측 p99. 리스가 짧으면 홀더 생존 중 리스 만료
 *       → 이중 재계산(v3 재계산&gt;1). 부팅 가드는 보수값, 수렴 게이트가 실측 재대조.</li>
 *   <li><b>락 대기 폴백:</b> {@code LOCK_WAIT_MS} 초과 시 {@link CacheLockWaitException}(503) — 직접 재계산/스테일
 *       아님(축 A2 v3=C-1 보존, DR-4). 불변 {@code LOCK_WAIT > 홀더 p99} 면 예외 경로.</li>
 *   <li><b>홀더 크래시:</b> finally unlock + 리스 PX 만료로 인계(락 TTL = LOCK_TIMEOUT_MS 계약).</li>
 *   <li><b>입도:</b> 키당 1락(전역락 금지).</li>
 * </ul>
 *
 * <p><b>비홀더는 순수 블록</b>(스테일 제공 안 함 — v4 전용). 빈/널 적재 정책은 v2 와 동일(EMPTY_AS_MISS=true)로 둬
 * v2→v3 단일 변수 = single-flight 만 차이. (페네트레이션엔 무력 — Step C 못 하는 것·v5.)
 */
@Service
@Profile("phase3-8")
public class ProductListCacheServiceV3 extends AbstractProductListCacheService {

    /** 비홀더 대기 폴 간격(ms) — 락 대기 중 캐시 적중 검출 주기. */
    private static final long POLL_INTERVAL_MS = 5;

    private final BlockedRequestCounter blockedCounter;
    private final RedisLockRepository redisLock;

    public ProductListCacheServiceV3(LookAsideCache cache,
                                     OriginRecomputeCounter recomputeCounter,
                                     StampedeBarrier barrier,
                                     ProductService productService,
                                     BlockedRequestCounter blockedCounter,
                                     RedisLockRepository redisLock) {
        super(cache, recomputeCounter, barrier, productService);
        this.blockedCounter = blockedCounter;
        this.redisLock = redisLock;
    }

    @Override
    public int version() {
        return 3;
    }

    @Override
    public CachedProductList get(String category) {
        String fk = key(category);
        synchronizeArrival(fk); // 만료창 동기 도착(배리어 무장 시)

        CacheEnvelope env = cache.get(fk);
        if (env != null) {
            return value(env);
        }
        return singleFlight(fk, category);
    }

    private CachedProductList singleFlight(String fk, String category) {
        String lockKey = fk + "::sflock";
        String token = UUID.randomUUID().toString();
        long deadline = System.currentTimeMillis() + CachePins.LOCK_WAIT_MS;
        boolean counted = false;

        while (System.currentTimeMillis() < deadline) {
            CacheEnvelope env = cache.get(fk);
            if (env != null) {
                return value(env); // 락 대기 후(또는 즉시) 적중 — 블록 흡수
            }
            if (redisLock.tryLock(lockKey, token, CachePins.LOCK_TIMEOUT_MS)) {
                try {
                    CacheEnvelope again = cache.get(fk); // double-check (대기 중 다른 홀더가 채웠을 수 있음)
                    if (again != null) {
                        return value(again);
                    }
                    // 락 전 구간 점유 = single-flight(DR-1). 재계산=1.
                    CachedProductList loaded = loadFromOrigin(fk, category);
                    if (!(loaded.isEmpty() && CachePins.V2_EMPTY_AS_MISS)) {
                        cache.putValue(fk, loaded, CachePins.TTL_BASE_MS);
                    }
                    return loaded;
                } finally {
                    redisLock.unlock(lockKey, token);
                }
            }
            // 락 획득 실패 = 비홀더 블록. 축 A2: 대기 진입 1회만 카운트(폴 반복마다 세지 않음).
            if (!counted) {
                blockedCounter.increment(fk);
                counted = true;
            }
            sleepQuietly();
        }
        // 폴백 핀: 503 (직접 재계산/스테일 아님). 불변 LOCK_WAIT>홀더 p99 면 미발화(DR-4).
        throw new CacheLockWaitException(
                "v3 락 대기 초과(LOCK_WAIT_MS=" + CachePins.LOCK_WAIT_MS + ", key=" + fk + ") — 폴백 503");
    }

    private CachedProductList value(CacheEnvelope env) {
        return env.nullEntry() ? CachedProductList.empty() : env.payload();
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
