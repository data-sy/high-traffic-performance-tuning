package com.project.infrastructure.cache;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 2차 구조 지표 A2 — 만료 전환창 대기 큐 깊이 = 그 1회 재계산을 기다려 <b>블록된 요청 수</b> (M1·축A2).
 *
 * <p>재계산 수만으로는 v3·v4 가 둘 다 만료창당 ≈1 로 붕괴해 사다리 칸이 합법 축에서 반증 불가다. 둘을 가르는
 * 신호는 <b>대기한 요청의 수</b>(v2=0 · v3≈C-1 · v4≈0) — 이는 지연 <i>분포</i>(참조 전용·봉인)가 아니라
 * 블록 <b>카운트</b>(구조량·캐시 불변·부하 잡음 무관)다(시간≠개수).
 *
 * <p>카운터는 <b>single-flight 락 대기/블록 진입점</b>(홀더가 아닌 요청이 대기에 들어가는 지점)에서 +1.
 * {@link OriginRecomputeCounter} 와 분리된 별도 카운터다.
 */
@Component
@Profile("phase3-8")
public class BlockedRequestCounter {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    /** 비홀더가 락 대기(블록)에 진입할 때 1회 호출. */
    public void increment(String key) {
        counters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    public long get(String key) {
        AtomicLong c = counters.get(key);
        return c == null ? 0L : c.get();
    }

    public Map<String, Long> snapshot() {
        return counters.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }

    public void reset() {
        counters.clear();
    }
}
