# Phase 3-7 N+1 — 측정 결과

> 측정일 2026-06-23. 정본 스펙: `specs/phase3/phase3-7-n-plus-one.md`. 환경: Spring Boot 3.5.10 / Hibernate 6.6.41 / MySQL 8.0.
> **1차 지표 = 쿼리 수(격리 VU=1 런).** p95(부하 런)는 보조 — 무인덱스 풀스캔으로 절대치가 부풀려짐(EXPLAIN 백스톱 §5).
> 데이터 규모: orders 500,000 / order_item 1,500,000 / product 1,000,000 / users ~1,000.

## 1. 테스트 픽스처
- 목록 사용자 **userId=1 → N=575 주문, 1,702 아이템, 1,700 distinct product**(1차 캐시 dedup ≈0).
- 상세 주문 **orderId=94 → M=5 아이템**.

## 2. R1 버전 격리 + baseline assert (PASS)
- v1·v4·v5: baseline 빌드(LAZY · `default_batch_fetch_size` unset) 단일 부팅에서 측정.
- v3: `--spring.jpa.properties.hibernate.default_batch_fetch_size=100` 부팅(소스 무변경 전역 토글).
- v2: 엔티티 EAGER 소스 토글(`Order.orderItems`·`OrderItem.product`) + 리빌드/부팅 → 측정 후 **`git checkout` 원복**(LAZY 복귀 검증).
- **baseline assert**: v1 측정 직전 엔티티 fetch=LAZY 확인(EAGER 부재) + batch 프로퍼티 미설정 확인. 불일치 시 abort. → 통과.

## 3. 쿼리 수 (격리 런 · 1차 지표) — **실측 확정**

방법: `org.hibernate.SQL: DEBUG` 로그의 statement 이벤트 수(VU=1 단발 요청, 인터리브 없음). R2 2단계 분리.

| 버전 | 전략 | 목록 쿼리수 (N=575) | 상세 쿼리수 (M=5) | 비고 |
|---|---|---|---|---|
| **v1** | LAZY + 루프 | **1150** | **7** | 1+2N(=1151)에서 product 1건 1차캐시 dedup → 1150. 상세 2+M=7. **N+1 재현** |
| **v2** | 정통 EAGER | **576** | **1** | 목록 = 1+N(컬렉션 secondary SELECT N, product는 EAGER@ManyToOne **JOIN 흡수** → 별도 SELECT 0). **EAGER로도 목록 N+1 잔존**·구조는 1+N(≠v1 1+2N). 상세 = find() by PK가 EAGER **흡수** → 단일 쿼리, **N+1 미발생** |
| **v3** | batch (size=100) | **27** | **3** | secondary SELECT를 IN절 배치. 1150→27(목록). 공식 1+2⌈N/b⌉은 근사 — 실측 27(Hibernate 배치가 product 집합 전반을 IN 로딩). N+1 → 소수 쿼리 |
| **v4** | Fetch Join | **1** | **1** | `join fetch … distinct` 단일 쿼리. 추가 SELECT 0 |
| **v5** | DTO Projection | **2** | **2** | 헤더 투영 1 + 라인요약 IN 투영 1(목록) / 헤더 1 + 라인 1(상세). 엔티티 적재 0 |

**교훈(실측 확정):**
1. v1: N+1 재현. 목록 1+2N(대표상품 LAZY @ManyToOne=별도 SELECT), 상세 2+M.
2. v2: **EAGER는 N+1을 없애지 않고 시점만 옮긴다.** 파생쿼리(목록)는 EAGER 컬렉션을 secondary SELECT로 채워 N+1 잔존, 단 @ManyToOne EAGER=JOIN 흡수라 1+2N이 아닌 **1+N**. `find()` by PK(상세)는 즉시 조인 흡수 → **N+1 미발생**. → "EAGER+find()는 흡수, EAGER+쿼리는 못 흡수".
3. v3/v4/v5: N+1 해소. v4가 쿼리 최소(1), v5는 2지만 엔티티 미적재.

## 4. p95 부하 런 (보조 지표) — 목록 경로, VUS=8(<pool=10), userId 랜덤 분산(R3)

비포화 게이트: `http_req_failed` 0, Hikari `pending` max 0 — 전 버전 통과.
상세 경로 p95는 측정 안 함(M≈5라 워밍 노이즈 지배 → 쿼리 수로만 가름, R3 #7).

| 버전 | 목록 p95 | 완료 요청수/30s | 비고 |
|---|---|---|---|
| **v1** | **단일 콜드 요청 86–154s** (8VU/30s 부하 **0건 완료**, 전부 interrupted) | 0 | N+1 ×풀스캔 = 운영 불가 |
| **v3** | **3347 ms** | 103 | 27쿼리, 단 컬렉션 IN 배치도 풀스캔(무인덱스)이라 6회 1.5M 스캔 |
| **v4** | **1586 ms** | 152 | 단일 조인(1 스캔) + 전체 엔티티 하이드레이션 |
| **v5** | **470 ms** | 459 | 투영 2쿼리·엔티티 미적재 → 전송·하이드레이션 최소 → **최속** |

→ 순서 **v5 < v4 < v3 ≪ v1**. v5(투영)가 쿼리 수(2)는 v4(1)보다 많아도 **전송량·적재(축②③)**가 작아 가장 빠름.

## 5. EXPLAIN 백스톱 — p95 캐비엇 (`explain/explain-backstop.txt`)
- **`orders.user_id`·`order_item.order_id` 무인덱스**(@ForeignKey(NO_CONSTRAINT)이 FK 인덱스 생성 안 함) → 목록 헤더·컬렉션 로드가 **풀스캔**(orders 498k / order_item 1.5M, type=ALL). product PK는 const(정상).
- ∴ p95 절대치 = **N+1 왕복 수 × 풀스캔 비용**이라 부풀려짐. **인덱스가 있어도 v1의 왕복 수(1150)는 동일** — 쿼리 수가 N+1의 인덱스-무관 불변량이라 1차 지표로 둔다.
- 스펙 **스키마 변경 0** 규칙 → 인덱스 추가 안 함(캐비엇 기록).

## 6. 결론 — 경로별 배선 (우승 기준 = 예측 가능성)
- **목록 + 페이징(실무 조회 기본): v3 @BatchSize.** fetch join은 1:N+페이징에서 메모리 페이징(HHH000104)이라 막힘(아래 §7) → IN 배치가 부작용 없이 거의 모든 조회에 꽂힌다. 비페이징 풀목록 p95는 v3가 v4/v5보다 느리지만, **페이징 시 N이 작아져** 쿼리·메모리가 page size에 고정(예측 가능).
- **단건 상세: v4 Fetch Join.** 쿼리 1회, 컬렉션 하나라 distinct로 카테시안 곱 통제.
- **조회 전용 핫패스·대용량: v5 DTO Projection.** 전송량·적재까지 깎음(목록 p95 최속이 방증).
- 우승은 "가장 빠름/적은 쿼리"가 아니라 **데이터가 커져도 쿼리·메모리가 고정**되는 구조적 성질로 가른다.

## 7. v4 페이징 함정 (1:N fetch join + Pageable = HHH000104)
- 별도 측정/원리: §Step D N-2. 컬렉션이 `orderItems` 하나뿐이라 `MultipleBagFetchException`은 이 스키마에서 재현 불가(이론 노트). v4 함정은 **1:N fetch join + 실제 페이징**으로 한정.

## 부속 산출물
- `querycount.tsv` (격리 런 원시), `load.tsv` (p95 원시), `k6/` (summary json·로그), `v1-singlereq-timing.txt`, `explain/explain-backstop.txt`, `r3-precheck.md`.
- 측정 스크립트: `scripts/measure/run-nplusone-querycount.sh`, `run-nplusone-load.sh` · k6: `test/load/nplusone-{list,detail}-test.js`.
