package com.project.api.coupon.dto;

/**
 * 스톨 데모 결과(정규 측정 아님 — fencing 부재 실증용).
 * {@code released=false} = compare-del이 자기 토큰 아님을 보고 해제 거부(오해제 거부 ✓) —
 * 그럼에도 actual&gt;total이 나면 "안전한 release로도 fencing 없이는 동시 점유를 못 막는다"는 증거.
 */
public record StallDemoResult(String token, boolean locked, boolean released) {
}
