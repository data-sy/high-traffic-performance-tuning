#!/usr/bin/env python3
"""phase 3-9 PreRegisteredAdjudicationGate (M2·채점 스텝 게이트, 부팅 빈 아님).

부하 측정 파이프라인의 *채점 스텝*으로 실행(앱 외부, 교차런 throughput 입력). R0·R4 각 ≥3런의
성공 throughput을 받아:
  1) 결합 CV의 신뢰구간 상한 ε_hi(보수적, χ² σ-bound)를 산출
  2) ε_hi ≥ (ρ*−1)/2 = 0.5 면 r 분류를 **하드 거부**하고 판정 보류(밴드 겹침 → 노이즈>신호)
  3) 아니면 r=mean(R4)/mean(R0)를 사전 등록 밴드로 분류
점추정이 아닌 ε_hi로 판정해 소표본 과소추정이 게이트를 조용히 통과하지 못하게 한다(규칙 1).

usage:
  adjudication-gate.py --r0 a,b,c --r4 d,e,f [--r3 g,h,i] [--alpha 0.05] [--json OUT]
"""
import argparse, json, math, sys

# χ²_{α, df} 하측 임계값(P(X≤x)=α). α=0.05. df=n-1. σ 상한용: σ_hi = s·√((n-1)/χ²_{α,n-1}).
CHI2_LOWER_05 = {
    2: 0.10259, 3: 0.35185, 4: 0.71072, 5: 1.14548, 6: 1.63538,
    7: 2.16735, 8: 2.73264, 9: 3.32511, 10: 3.94030, 11: 4.57481,
    12: 5.22603, 13: 5.89186, 14: 6.57063, 15: 7.26094,
}
RHO_STAR = 2.0            # count(R0)/count(R4)
RHO_R3 = 4.0 / 3.0        # count(R0)/count(R3)
EPS_GATE_R4 = (RHO_STAR - 1.0) / 2.0   # 0.5
EPS_GATE_R3 = (RHO_R3 - 1.0) / 2.0     # 0.1667
REFUTE = 1.0 + EPS_GATE_R4             # 1.5


def stats(xs):
    n = len(xs)
    mean = sum(xs) / n
    var = sum((x - mean) ** 2 for x in xs) / (n - 1)   # 표본분산(ddof=1)
    sd = math.sqrt(var)
    cv = sd / mean
    return n, mean, sd, cv


def cv_upper(cv, n, alpha):
    """CV 상한(χ² σ-bound): cv_hi = cv·√((n-1)/χ²_{α,n-1}). 소표본 과소추정 차단."""
    df = n - 1
    chi2 = CHI2_LOWER_05.get(df)
    if chi2 is None:
        raise SystemExit(f"χ² 표에 df={df} 없음(n={n}). α=0.05·df≤15만 지원 — 표 확장 필요.")
    return cv * math.sqrt(df / chi2)


def parse_arr(s):
    return [float(t) for t in s.split(",") if t.strip() != ""]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--r0", required=True)
    ap.add_argument("--r4", required=True)
    ap.add_argument("--r3", default=None)
    ap.add_argument("--alpha", type=float, default=0.05)
    ap.add_argument("--json", default=None)
    a = ap.parse_args()

    r0, r4 = parse_arr(a.r0), parse_arr(a.r4)
    if len(r0) < 3 or len(r4) < 3:
        raise SystemExit(f"R0·R4 각 ≥3런 필요(받음 R0={len(r0)}, R4={len(r4)}).")

    n0, m0, sd0, cv0 = stats(r0)
    n4, m4, sd4, cv4 = stats(r4)
    eps_point = math.sqrt(cv0 ** 2 + cv4 ** 2)
    cv0_hi, cv4_hi = cv_upper(cv0, n0, a.alpha), cv_upper(cv4, n4, a.alpha)
    eps_hi = math.sqrt(cv0_hi ** 2 + cv4_hi ** 2)   # 결합 CV 상한(보수적 quadrature)
    r = m4 / m0

    out = {
        "R0": {"n": n0, "mean": m0, "sd": sd0, "cv": cv0, "cv_hi": cv0_hi, "runs": r0},
        "R4": {"n": n4, "mean": m4, "sd": sd4, "cv": cv4, "cv_hi": cv4_hi, "runs": r4},
        "eps_point": eps_point, "eps_hi": eps_hi,
        "rho_star": RHO_STAR, "eps_gate_r4": EPS_GATE_R4, "refute_threshold": REFUTE,
        "r_observed": r,
    }

    print("═════════ PreRegisteredAdjudicationGate ═════════")
    print(f"  R0: n={n0} mean={m0:.1f}/s CV={cv0*100:.2f}% CV_hi={cv0_hi*100:.2f}%  runs={r0}")
    print(f"  R4: n={n4} mean={m4:.1f}/s CV={cv4*100:.2f}% CV_hi={cv4_hi*100:.2f}%  runs={r4}")
    print(f"  결합 ε 점추정 = {eps_point*100:.2f}%   ε_hi(신뢰상한) = {eps_hi*100:.2f}%")
    print(f"  ρ*={RHO_STAR}  ε-gate=(ρ*−1)/2={EPS_GATE_R4}  반증문턱=1+(ρ*−1)/2={REFUTE}")
    print(f"  관측 r = tput(R4)/tput(R0) = {r:.4f}")

    # ── 밴드 비겹침 자격 게이트(규칙 2) — ε_hi로 집행 ──
    if eps_hi >= EPS_GATE_R4:
        verdict = "판정보류(ADJUDICATION_HELD)"
        reason = (f"ε_hi={eps_hi*100:.2f}% ≥ 50% → 지지밴드[1−ε_hi,1+ε_hi]가 반증밴드[1.5,∞)와 겹침 "
                  f"→ 같은 r이 지지이자 반증(동어반복). 부하 런은 헤드라인을 가를 해상도 없음(노이즈>신호). "
                  f"헤드라인을 구조 카운트+R3 3점 정성으로 정직 후퇴.")
        print(f"  ★ 하드 거부: {verdict}")
        print(f"    {reason}")
        out["verdict"] = verdict
        out["reason"] = reason
    else:
        lo, hi = 1.0 - eps_hi, 1.0 + eps_hi
        if lo <= r <= hi:
            verdict = "H_floor 지지 (헤드라인 입증)"
            reason = f"{lo:.3f} ≤ r ≤ {hi:.3f} → commit floor 지배. 구조적 단축이 throughput으로 번역 안 됨(측정 확인)."
        elif r >= REFUTE:
            verdict = "H_floor 반증·H_왕복 지지 (헤드라인 틀림)"
            reason = f"r ≥ {REFUTE} → 왕복 수가 천장 구속. 헤드라인 반증 — 그대로 기록."
        elif hi < r < REFUTE:
            verdict = "미결 (commit floor 부분 지배)"
            reason = f"{hi:.3f} < r < {REFUTE} → 헤드라인 단언 금지, 혼합 보고."
        else:  # r < lo
            verdict = "이상 신호 (무효 처리)"
            reason = f"r < {lo:.3f} (R4가 R0보다 느림) → 회귀. H_floor 입증으로 분류 금지, 원인 규명 전까지 무효."
        print(f"  ⇒ 판정: {verdict}")
        print(f"    {reason}")
        out["verdict"] = verdict
        out["reason"] = reason

    # ── R3 정량 자격(규칙 5): ε_hi < 0.165 라야 R3 정량 분리 가능 ──
    r3_note = None
    if a.r3:
        r3 = parse_arr(a.r3)
        n3, m3, sd3, cv3 = stats(r3)
        r3_ratio = m0 / m3   # ρ_R3 방향(count(R0)/count(R3)=1.33과 비교)
        out["R3"] = {"n": n3, "mean": m3, "cv": cv3, "runs": r3, "r0_over_r3": r3_ratio}
        if eps_hi < EPS_GATE_R3:
            r3_note = (f"ε_hi={eps_hi*100:.2f}% < 16.5% → R3 정량 분리 합법. "
                       f"tput(R0)/tput(R3)={r3_ratio:.3f} (ρ_R3=1.33 대조).")
        else:
            r3_note = (f"ε_hi={eps_hi*100:.2f}% ≥ 16.5% → R3 **정량 판정 보류**. "
                       f"R0·R3·R4 3점 정성 추세로만 읽음(평평=H_floor 보강 / 기준비 선 단조상승=H_왕복 보강). "
                       f"tput: R0={m0:.1f} R3={m3:.1f} R4={m4:.1f}/s.")
        print(f"  [R3 거울] {r3_note}")
        out["r3_note"] = r3_note

    if a.json:
        with open(a.json, "w") as f:
            json.dump(out, f, ensure_ascii=False, indent=2)
        print(f"  ▸ verdict JSON → {a.json}")


if __name__ == "__main__":
    main()
