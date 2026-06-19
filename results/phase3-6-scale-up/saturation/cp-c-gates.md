# CP-C 랭킹 재측정 게이트 (1.5M order_item) — 2026-06-19 15:11

- 부하모델: VU=8 < pool=10 엄격, warmup 15s + steady 60s(v1=420s, 표본확보), N_min=100
- v1 EXPLAIN type=ALL(ALL, 무인덱스 1.5M 풀스캔 백스톱), ZCARD(ZSet)=100

| ver | p95(ms) | N(reqs) | failed | counter Δ | k6 throughput↔counter | pending(max/>0/연속) | stmtTO | 게이트 |
|---|---|---|---|---|---|---|---|---|
| v1 | 39335.7 | 128 | 0 | 128 | ok(N=128~Δ=128) | 0/0/0 | 0 | PASS  |
| v2 | 768.0 | 603 | 0 | 603 | ok(N=603~Δ=603) | 0/0/0 | 0 | PASS  |
| v3 | 12.6 | 4416 | 0 | 4416 | ok(N=4416~Δ=4416) | 0/0/0 | 0 | PASS  |
| v4 | 6.9 | 4584 | 0 | 4584 | ok(N=4584~Δ=4584) | 0/0/0 | 0 | PASS ZCARD=100 http=200 rankings_len=100 |

## 헤드라인 / 교차검산
- 헤드라인: v1(1.5M GROUP BY 풀스캔, EXPLAIN type=ALL) p95 ≫ v4(ZSet) p95 절대 격차. k6 p95 주 증거 + v1 EXPLAIN type=ALL 캐시-불변 백스톱.
- C1 비포화: pending 전 샘플 0(연속>0 없음)·failed≈0·stmtTO 0. v1 N>=N_min(100) 표본 충분.
- 교차검산1: k6 throughput(N) ↔ actuator counter delta 정합(위 xchk).
- 교차검산3: v4 비공집 3종(ZCARD>0·200 비공집·counterΔ>0) — 깨진 v4가 '빠름'으로 가짜 헤드라인 통과 차단.
