package com.project.infrastructure.cache;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 1차 구조 지표 A — 오리진(DB) 재계산 호출 수 카운터 (M1).
 *
 * <p>캐시 추상화의 오리진 로더({@code Supplier})가 실제 repo 쿼리를 실행하는 <b>진입점에서만</b> +1 한다
 * (캐시 적중·락 대기·직렬화 비용에선 증가 안 함 — "오리진 증폭"의 순수 측정). 부하·런별 잡음과 무관한 구조량.
 *
 * <p><b>카운트 위치 계약(line 105·DR-3):</b> 로더 1회 진입 = +1. {@code Page} 가 SELECT + 별도 COUNT(*)
 * 2 SQL 을 내더라도 카운터는 <b>로더 람다 진입 기준 1회</b>로 센다(COUNT 를 이중 카운트하지 않음). COUNT 비용은
 * populate 창/락 점유시간 회계(전 구간 실측 p99)에만 들어간다.
 *
 * <p>측정 전용(@Profile phase3-8) — 본체 부팅엔 로드되지 않는다(v1 무오염).
 */
@Component
@Profile("phase3-8")
public class OriginRecomputeCounter {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    /** 오리진 로더 진입 시 1회 호출. key = 캐시 키(prefix 포함 또는 카테고리 — 호출측 일관). */
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

    /** 런 간 격리 — 하네스가 측정 시작마다 reset. */
    public void reset() {
        counters.clear();
    }
}
