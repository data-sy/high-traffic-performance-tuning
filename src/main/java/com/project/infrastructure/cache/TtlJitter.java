package com.project.infrastructure.cache;

import java.util.concurrent.ThreadLocalRandom;

/**
 * TTL 지터 (v5 어밸런치 방어) — 기준 TTL 에 ±{@code TTL_JITTER_RATIO} 균등 난수를 더해 동일 시각 일괄 만료를 분산.
 *
 * <p>키별로 만료 시각이 흩어져 다수 키 동시 만료(광역 herd)를 막는다. v5 서비스와 ops {@code /warm?version=5} 가
 * 같은 지터를 쓰도록 한 곳에 둔다. (난수는 앱 런타임용 {@link ThreadLocalRandom} — 측정 재현은 핀된 범위로 통제,
 * 개별 키 만료 시각의 미세 분포는 어밸런치 분산의 본질이라 시드 고정 불요.)
 */
public final class TtlJitter {

    /** baseMs 에 ±RATIO 지터 적용. 예: base=10000, ratio=0.2 → [8000, 12000]. */
    public static long apply(long baseMs) {
        double ratio = CachePins.TTL_JITTER_RATIO;
        double factor = 1.0 + ThreadLocalRandom.current().nextDouble(-ratio, ratio);
        return Math.round(baseMs * factor);
    }

    private TtlJitter() {
    }
}
