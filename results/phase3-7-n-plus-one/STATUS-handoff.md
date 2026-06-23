# Phase 3-7 N+1 — 진행 상태 / 재개 핸드오프

> 진행 상태 정본 = 이 디스크 문서(메모리 아님). 정본 스펙: `specs/phase3/phase3-7-n-plus-one.md`.
> 최종 갱신: 2026-06-23 세션. 상태: **구현(A~E) + 측정(F) 완료·커밋. 남은 것 = `-draft` 제거(사람 게이트) → `/pr`.**
>
> **측정 완료** — 전체 결과는 `results.md`. 쿼리수(1차) v1 1150/7 · v2 576/1 · v3 27/3 · v4 1/1 · v5 2/2.
> p95(보조, 목록) v5 470ms < v4 1586ms < v3 3347ms ≪ v1 86–154s(부하 0건 완료). EXPLAIN 백스톱: order_id/user_id 무인덱스 풀스캔 = p95 캐비엇(쿼리수는 무관·유효). 환경 블로커(8080/MySQL 점유)는 사용자가 해소함.

## 완료 — Step A~E (로컬 커밋, 미push)
v1~v5 사다리 구현 + 엔드포인트 공존. 각 Step 컴파일 격리 검증 후 커밋한 사다리:

| commit | step | 내용 |
|---|---|---|
| `a03cff7` | A | 조회 DTO 3종 + `findByUserId` + `OrderQueryServiceV1` + `/api/v1` (N+1 baseline) |
| `b6b033d` | B | v2 정통 EAGER 엔드포인트(거동=측정 토글) |
| `6154d5e` | C | v3 batch fetch 엔드포인트(거동=측정 토글) |
| `49b80c3` | D | v4 Fetch Join (`join fetch … distinct`) |
| `9a71feb` | E | v5 DTO Projection (JPQL `select new`, QueryDSL 미사용) |

설계 확정: v2/v3은 v1과 코드 경로 동일(차이는 전역 토글 = R1) · v4/v5는 쿼리메서드 레벨 영구 공존 ·
**R-Q5 확정**(v5 목록 = 헤더 투영 1 + IN 라인요약 투영 1 = 2쿼리·엔티티 적재 0).

## 완료 — R3 전제 검증
`r3-precheck.md`: 버퍼풀 128MiB ≪ 워킹셋 ~302MiB → **PASS**(분산 시 디스크 I/O 발생, p95 분리 여지).

## 보류 — Step F (측정), 사용자가 "Pause measurement" 선택
### 환경 블로커 (재개 시 먼저 해소)
- 포트 **8080 + MySQL(3306) + Redis(6379)를 다른 프로젝트 `/Users/owner/my-math-teacher/api`가 점유** 중.
  (Spring Security 앱이라 전 경로 401, MySQL에 커넥션풀 10개 ESTABLISHED → 버퍼풀 공유.)
- 4일 전 stale flashdeal bootRun(87276)은 이미 종료됨.
- **재개 옵션:** (a) my-math-teacher 중단 후 8080·무경합 MySQL로 측정(prior phase와 동일·최적), 또는
  (b) flashdeal를 8081로 띄워 공존(비침습; 쿼리수는 무관, p95만 MySQL 버퍼풀 경합 캐비엇).

### 측정 스크립트 (작성 완료, **라이브 미검증** — 재개 시 첫 런에서 검증·보정 필요)
- `test/load/nplusone-list-test.js`, `nplusone-detail-test.js` — userId∈1..1000 / orderId∈1..500k 랜덤 분산(R3), `http_req_failed` 게이트.
- `scripts/measure/run-nplusone-querycount.sh` — 격리 VU=1 단발 요청, `org.hibernate.SQL` 로그 이벤트 카운트(목록·상세). `APP_LOG` 필요.
- `scripts/measure/run-nplusone-load.sh` — k6 p95 + Hikari `pending` 샘플링 비포화 게이트.
- 미작성(재개 시): 상위 오케스트레이터 — 부팅/토글(v2 EAGER 소스 토글+rebuild+revert, v3 batch 프로퍼티 부팅), R1 baseline 런타임 assert, EXPLAIN 백스톱, 표 집계, 재실행 검증.

### 재개 시 측정 To-Do (스펙 Step F + R1/R2/R3)
1. 환경 블로커 해소(위 a/b 택1). 8081 사용 시 k6 스크립트 호스트/`BASE_URL` 조정.
2. baseline 빌드 부팅(LAZY·batch unset) → **R1 런타임 assert**(불일치 abort) → v1·v4·v5 쿼리수(격리) + 상세 N+1 미발생 확인.
3. v3: `--spring.jpa.properties.hibernate.default_batch_fetch_size=N` 부팅 → 쿼리수(1+2⌈N/b⌉ 실측).
4. v2: 엔티티 EAGER 소스 토글(Order.orderItems·OrderItem.product) + rebuild + 부팅 → 목록 N+1 잔존(≈1+N 실측), 상세 흡수 확인 → **git checkout 원복**(trap 보장).
5. 부하 런(p95) 목록·상세 각 버전 + 비포화 게이트(`http_req_failed≈0`, pending==0). 상세는 쿼리수로만 가름(R3 #7).
6. v4↔v5 전송량·적재 대리지표(R2 추가/R-Q6).
7. EXPLAIN 백스톱 · 재실행 검증.
8. 스펙 표(v1~v5 쿼리수) **실측으로 확정** → Work Order #1 사람 확인 → **`-draft` 제거** → `/pr`.

### 미해소 Open Question
- R-Q6 v5 전송량·적재 대리지표 노출 방법 · C-c 전송량 공정성(상세 경로로 한정) — 측정 시 확정.
