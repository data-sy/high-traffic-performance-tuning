package com.project.infrastructure.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 만료창 동기 도착 메커니즘 (A-6 {@code POST /cache/ops/barrier}·DR-A·DR-A2-1).
 *
 * <p>k6 램프는 도착 <i>타이밍</i>을 보장 못 한다 — 첫 1개가 populate 창 안에 캐시를 채우면 이후 도착분은
 * 적중→ herd≪C·block≪C-1 로 과소관측("v2가 사실 스탬피드 안 함"·"v3 대기열 작음" 거짓 수렴). 그래서
 * 서버측 latch 로 C 개를 같은 만료창에 동기 release 한다.
 *
 * <p><b>arming/gating 분리(DR-A2-1):</b> ops {@code /barrier?key=&n=} 는 이 빈의 {@link #arm}만 호출해
 * 무장(부하·로더 미수신). 실제 C 개 부하는 버전별 엔드포인트 {@code /api/cache/v{n}/products} 로 가고, 각
 * 요청이 {@code ProductListCacheServiceV{n}} 의 캐시 get 진입점에서 {@link #checkIn} 으로 latch 에 모였다
 * 동시 release 된다. 게이팅이 버전별 캐시 get <i>안</i>에서 일어나 재계산/블록 카운터가 v{n} 에 정상 귀속된다.
 *
 * <p><b>herd 천장(DR-TOMCAT-1, carry_forward):</b> C 개가 동시에 check-in 하려면 C 개가 동시에 Tomcat
 * 워커 스레드를 점유해야 한다. {@code server.tomcat.threads.max=200} 이라 C 가 200 에 근접/초과하면 latch 가
 * n 을 못 채워 release 안 됨 → 무한 대기 → 타임아웃. 그래서 {@link #BARRIER_TIMEOUT_MS} 로 await 를 끊고,
 * {@link CachePinsBootAssert} 가 C &lt; tomcat threads.max 를 cross-assert 한다.
 */
@Component
@Profile("phase3-8")
public class StampedeBarrier {

    private static final Logger log = LoggerFactory.getLogger(StampedeBarrier.class);

    /** latch 가 n 을 못 채울 때 무한 대기 방지(천장 도달 검출 → 런이 게이트로 abort). */
    public static final long BARRIER_TIMEOUT_MS = 10_000;

    private final Map<String, CyclicBarrier> barriers = new ConcurrentHashMap<>();

    /** key 에 대해 기대 도착 수 n 으로 latch 를 무장(arm). 부하/로더는 받지 않는다. */
    public void arm(String key, int n) {
        barriers.put(key, new CyclicBarrier(n));
        log.info("[barrier] armed key={} n={}", key, n);
    }

    /**
     * 무장된 latch 에 check-in — n 개가 모일 때까지 블록하다 동시 release.
     * 무장 안 된 키면 즉시 통과(배리어 없는 경로 = 정상 진행). 타임아웃/broken 이면 통과하되 런은 게이트에서 판정.
     */
    public void checkIn(String key) {
        CyclicBarrier b = barriers.get(key);
        if (b == null) {
            return;
        }
        try {
            b.await(BARRIER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("[barrier] timeout key={} — latch 가 n 을 못 채움(herd 천장? DR-TOMCAT-1). 통과 후 게이트가 abort 판정.", key);
        } catch (BrokenBarrierException e) {
            log.warn("[barrier] broken key={} — 통과 후 게이트 판정.", key);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 무장 해제(런 종료 후 정리). */
    public void disarm(String key) {
        barriers.remove(key);
    }
}
