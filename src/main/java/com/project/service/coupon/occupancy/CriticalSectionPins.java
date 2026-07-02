package com.project.service.coupon.occupancy;

/**
 * phase 3-9 통제변수 <b>단일 소스 핀</b>(M2). 부하 모델·인프라·사전 등록 판정 문턱을 한 곳에 상수로 고정해
 * 하네스·부팅 assert·채점 게이트가 같은 값을 참조하게 한다(3-3 계승: 문서 가정 → 검증된 가정).
 *
 * <p>이 상수들은 코드 사실이지 예측 outcome이 아니다 — 노이즈밴드 ε·관측 r·각 칸 throughput은 <b>실측</b>으로
 * 채운다(M6 단정 금지). 여기 있는 건 (a) 인프라 핀(부팅 assert 대조)과 (b) 판정 <b>경계</b>(구조 카운트비에서
 * 도출된 결정 경계지 예측값 아님)뿐이다.
 */
public final class CriticalSectionPins {

    private CriticalSectionPins() {
    }

    // ── 부하 모델 핀(M3) ──
    public static final int VU = 500;                       // 단일 핫키 폐루프 VU
    public static final long HOT_COUPON_ID = 1L;            // 단일 핫 로우
    public static final String POLICY = "park";            // 유한대기(park)·503=0 정책

    // ── 인프라 핀(부팅 assert 대조 대상) ──
    public static final int HIKARI_MAX_POOL_SIZE = 10;      // R0/R2/R3/R4 기본 풀(R1만 스윕)
    public static final int TOMCAT_THREADS_MAX = 200;       // HTTP 계층 잡음 중화

    // ── R1 풀 스윕 단계(진단 칸) ──
    public static final int[] R1_POOL_SWEEP = {10, 20, 30};

    // ── 노이즈밴드용 반복 횟수(각 ≥3; ε 결합 CV용) ──
    public static final int REPEAT_R0 = 3;
    public static final int REPEAT_R3 = 3;
    public static final int REPEAT_R4 = 3;

    // ── 사전 등록 판정 파생식(구조 카운트비에서 도출된 경계 — 예측 아님, M6) ──
    /** 요청당 in-lock DB 왕복 수(성공 표본, 결정론 프로브로 확정). */
    public static final int COUNT_R0 = 4;
    public static final int COUNT_R2 = 4;
    public static final int COUNT_R3 = 3;
    public static final int COUNT_R4 = 2;
    /** 구조적 기준비 ρ* = count(R0)/count(R4) = 2.0. */
    public static final double RHO_STAR = (double) COUNT_R0 / COUNT_R4;      // 2.0
    /** 중간 칸 기준비 ρ_R3 = count(R0)/count(R3) ≈ 1.33. */
    public static final double RHO_R3 = (double) COUNT_R0 / COUNT_R3;        // 1.333
    /** 밴드 비겹침 자격 게이트: ε_hi < (ρ*−1)/2 = 0.5 라야 판정 유효(아니면 판정 보류). */
    public static final double EPS_GATE_R4 = (RHO_STAR - 1.0) / 2.0;         // 0.5
    /** R3 정량 분리 선검사(더 빡셈): ε < (ρ_R3−1)/2 ≈ 0.165. 미통과 시 R3 정량 판정 보류(정성만). */
    public static final double EPS_GATE_R3 = (RHO_R3 - 1.0) / 2.0;           // 0.1667
    /** H_floor 반증 문턱: r ≥ 1 + (ρ*−1)/2 = 1.5. */
    public static final double REFUTE_THRESHOLD = 1.0 + EPS_GATE_R4;         // 1.5
}
