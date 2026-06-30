package com.project.infrastructure.cache;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Redis 에 실제로 저장되는 캐시 엔트리 (값 + 메타).
 *
 * <ul>
 *   <li>{@code payload} — 적재된 상품목록(직렬화 안전 형태). 널 엔트리면 {@code null}.</li>
 *   <li>{@code nullEntry} — 미존재/빈 결과를 짧은 TTL 로 흡수한 널 캐싱 마커(v5 페네트레이션 방어).
 *       {@code true} 면 "오리진에 물어봐도 빈 결과"라는 사실을 캐싱한 것 — 적중으로 친다.</li>
 *   <li>{@code logicalExpiryAtEpochMs} — v4 논리 만료 시각(epoch ms). {@code 0} 이면 논리 만료 없음
 *       (하드 TTL 군 v2·v3·v5). v4 는 물리 TTL 을 길게 두고 이 시각으로 논리 만료를 판정해
 *       "스테일 즉시 반환 + 백그라운드 단일 refresh" 를 한다.</li>
 *   <li>{@code storedAtEpochMs} — 적재 시각(관찰/디버그용).</li>
 * </ul>
 *
 * <p>{@code LookAsideCache} 가 이 레코드를 JSON 문자열로 직렬화해 저장하고, 읽을 때
 * {@code readValue(json, CacheEnvelope.class)} 로 타입 명시 역직렬화한다(다형 @class 비의존).
 */
public record CacheEnvelope(
        CachedProductList payload,
        boolean nullEntry,
        long logicalExpiryAtEpochMs,
        long storedAtEpochMs) {

    @JsonCreator
    public CacheEnvelope(
            @JsonProperty("payload") CachedProductList payload,
            @JsonProperty("nullEntry") boolean nullEntry,
            @JsonProperty("logicalExpiryAtEpochMs") long logicalExpiryAtEpochMs,
            @JsonProperty("storedAtEpochMs") long storedAtEpochMs) {
        this.payload = payload;
        this.nullEntry = nullEntry;
        this.logicalExpiryAtEpochMs = logicalExpiryAtEpochMs;
        this.storedAtEpochMs = storedAtEpochMs;
    }

    /** v4 논리 만료 판정: 논리 만료 시각이 설정돼 있고(>0) 현재가 그 시각 이상이면 스테일. */
    public boolean isLogicallyExpired(long nowEpochMs) {
        return logicalExpiryAtEpochMs > 0 && nowEpochMs >= logicalExpiryAtEpochMs;
    }
}
