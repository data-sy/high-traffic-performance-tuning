# CP-B 인덱스 재측정 게이트 (1M product) — 2026-06-19 15:08

| step | EXPLAIN type | key | rows | Extra(filesort?) | k6 p95(ms) | http_req_failed | reqs(N) | pending(max/>0 samples/연속>0) | 게이트 |
|---|---|---|---|---|---|---|---|---|---|
| 1 no-index | ALL | NULL | 993313 | Using where; Using filesort | 319.6 | 0 | 622 | 0/0/0 | PASS |
| 2 category-only | ref | idx_product_category | 384134 | Using filesort | 12.4 | 0 | 2224 | 0/0/0 | PASS |
| 3 created-at-only | index | idx_product_created_at | 20 | Using where | 7154.6 | 0 | 40 | 0/0/0 | PASS |
| 4 composite | ref | idx_product_category_created | 399796 | NULL | 12.7 | 0 | 2224 | 0/0/0 | PASS |
| 5 reverse-composite | index | idx_product_created_category | 20 | Using where | 7753.2 | 0 | 40 | 0/0/0 | PASS |

## 교차검산 (헤드라인 step1 ≫ step4)
- 교차검산1(EXPLAIN 구조 ↔ k6 p95): step1 type=ALL+filesort 인데 p95 높고, step4 type=ref+no-filesort 인데 p95 낮으면 정합. EXPLAIN(캐시-불변)이 주 증거.
- 교차검산2(비포화): 위 표 게이트 열 — pending 전 샘플 0(연속>0 없음)·failed≈0·stmtTO 0 이면 p95가 순수 쿼리 비용.
