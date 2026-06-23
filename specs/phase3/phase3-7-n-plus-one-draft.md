# Phase 3-7 — N+1 해결: 주문 목록(컬렉션)·상세(to-one) v1~v5 사다리 + 경로별 배선  **[DRAFT]**

> **DRAFT 상태** — 이 문서는 초안이다. 확정 전 다른 세션에서 `/audit-doc`(가정 ↔ 코드 대조)와 `/design-review`(측정 타당성·실패 모드)로 검증한 뒤 `-draft` 접미사를 떼고 확정한다. 특히 아래 **「설계 리스크 R1: 버전 격리」**가 핵심 검증 대상이다.

## Context (현재 상태)
- Phase 3-1~3-6 완료. 데이터 스케일업됨(DB 실측): `product` 1,000,000 / `order_item` 1,500,000 / `orders` ~500,000 / `users` ~1,000 (**사용자당 평균 ~500주문, max 584**, 주문당 평균 ~3 아이템).
- `ddl-auto: validate` 활성. **변경 금지.**
- Docker 컨테이너(MySQL, Redis) 실행 중(`docker compose up -d`).
- 현재 주문 도메인 표면: `OrderService.createOrder()`만 존재. **조회 컨트롤러·조회 서비스·조회용 DTO 없음** → 이번 phase에서 신설.
- 기존 연관관계(확인됨): `Order → OrderItem @OneToMany(mappedBy="order", cascade=ALL)`, `OrderItem → Order @ManyToOne(LAZY)`, `OrderItem → Product @ManyToOne(LAZY)`.

## Goal
주문 **목록**(컬렉션 N+1)과 주문 **상세**(to-one N+1) 두 읽기 경로에서 N+1을 재현하고, **v1~v5 단계**로 해소하며 쿼리 수·응답시간·(v5)전송량·적재비용을 측정한다. **스키마 변경 0** — 기존 엔티티/관계만 사용한다.

**착지점(서사의 핵심):** 단일 정답(v4 "쿼리 1회") 대신, **경로의 성격에 따라 v3·v4·v5를 갈라 쓰는 "경로별 배선"** 결론으로 끝낸다. 우승 기준은 "가장 빠름/가장 적은 쿼리"가 아니라 **예측 가능성**(데이터가 커져도 쿼리·메모리가 고정)이다. → 아래 [경로별 배선] 절.

## 시나리오 서사 (왜 N+1이 터지나)
1. **주문 목록 (컬렉션 N+1):** 손님이 마이페이지 "내 주문 목록"을 연다. 주문 수백 건(실측 평균 ~500건/유저, max 584)을 조회한 뒤, 각 주문의 "상품 N개 / 대표상품명" 요약을 보여주려 `order.getOrderItems()`를 루프에서 접근 → 주문 1건마다 `order_item` SELECT = **1 + N 쿼리(N≈500)**.
2. **주문 상세 (to-one N+1):** 손님이 주문 하나를 펼친다. 담긴 아이템 M개의 상품명·가격을 그리려 `item.getProduct().getName()`을 루프 접근 → 아이템 1개마다 `product` SELECT = **1 + M 쿼리**. (목록에서 상세 결합 시 **1 + N + N×M** 폭발)

→ 두 경로 모두 **Order 집합 표면**에 살아 기존 시나리오(① 인덱스=Product목록, ③ 랭킹=salesCount, ④ 동시성=Coupon)와 **겹치지 않는다.**

---

## 측정 대상 — 두 엔드포인트 × v1~v5

신설 엔드포인트(모든 버전 코드 공존, `CLAUDE.md` API 규칙):

| 경로 | 화면 | N+1 유형 |
|---|---|---|
| `GET /api/v{n}/users/{userId}/orders` | 주문 목록 | 컬렉션 N+1 (Order→OrderItems) |
| `GET /api/v{n}/orders/{orderId}` | 주문 상세 | to-one N+1 (OrderItem→Product), 목록 결합 시 다단계 |

### v 사다리와 예상 쿼리 수 (목록: 주문 N건, 주문당 아이템 M개 가정)

| 버전 | 전략 | 목록 쿼리 수 | 핵심 교훈 |
|---|---|---|---|
| **v1** | LAZY default + 루프 접근 | `1 + N + N×M` | N+1 폭발 재현(baseline) |
| **v2** | EAGER (즉시로딩) | (R1 참조) | **"EAGER로 바꿔도 N+1 안 사라진다"** — 파생쿼리에선 secondary SELECT N번. 컬렉션 join 시 행 곱·`MultipleBagFetchException` 함정 |
| **v3** | `@BatchSize` / batch fetch | `1 + ⌈N/b⌉ + ⌈N×M/b⌉` | IN절로 secondary SELECT 묶기. N+1 → 소수 쿼리 |
| **v4** | Fetch Join (JPQL `join fetch`) | `1` (컬렉션은 `distinct`) | 단일 쿼리 착지 — **단건 상세에 최적**. 단 1:N+페이징·다중컬렉션 함정 |
| **v5** | DTO Projection (JPQL `new` 생성자 표현식, **필요 칸만**) | `2` (헤더+라인 분리, **데이터 무관 고정**) | 쿼리 1회를 양보하고 **전송량·적재비용**을 깎음 — "쿼리 수가 전부가 아니다". 조회 전용·대용량 경로 |

> **v5는 QueryDSL을 쓰지 않는다.** 학습자료(`nplusone-ladder.html`)는 QueryDSL `Projections`를 쓰지만, 본 프로젝트는 신규 의존성을 추가하지 않고 **JPA 기본기인 JPQL 생성자 표현식**(`select new com.project.api.order.dto.…Response(…)`)으로 동등 효과를 낸다.

---

## 경로별 배선 (최종 결론 프레임)

v4 "쿼리 1회"가 사다리의 끝이 아니다. **세 축**으로 보면 우승자가 경로마다 갈린다:

| 축 | 무엇 | 줄이는 칸 |
|---|---|---|
| ① 쿼리 횟수 | DB 왕복 수 | v3(IN 묶기) → v4(단일 조인) |
| ② 전송량 | 실어 오는 행·컬럼 크기 (카테시안 곱·`SELECT *`이 부풀림) | v5(필요 칸만) |
| ③ 적재 비용 | 영속성 컨텍스트 1차 캐시/dirty checking 메모리 (읽기 전용엔 순수 낭비) | v5(엔티티 미적재) |

→ v4는 축① 최강이지만 축②·③을 못 건드린다. v5는 축①을 한 칸(2회) 양보하고 축②·③을 가져간다.

**경로별 권장 배선:**
- **목록 + 페이징(대부분의 실무 조회):** `v3 @BatchSize`를 기본으로. fetch join은 1:N+페이징에서 메모리 페이징 경고·다중컬렉션 예외(R-Q4)로 막히므로, IN 배치가 부작용 없이 거의 모든 조회에 꽂힌다.
- **단건 상세(1:N 하나, 화면이 쓸 데이터 확정):** `v4 Fetch Join`. 쿼리 1회, 컬렉션 하나라 `distinct`로 카테시안 곱 통제.
- **조회 전용 핫패스·대용량 응답:** `v5 DTO Projection`. 전송량·적재까지 깎아야 하는 경로에서만, 복잡도를 감수하고 쓴다.

> 우승자 선정 기준 = **예측 가능성**. p95 차이가 측정 노이즈 수준(예: 42 vs 38ms)이면 그걸 우승 근거로 쓰지 않고, "데이터가 커져도 쿼리·메모리가 고정"이라는 구조적 성질로 가른다.

---

## ★ 설계 리스크 R1 — 버전 격리 (핵심 검증 대상)

**문제:** `v2 EAGER`와 `v3 @BatchSize`는 본래 **엔티티/연관 전역 어노테이션**(`@ManyToOne(fetch=EAGER)`, `@BatchSize`)이다. 전역으로 박으면 **v1의 순수 N+1 baseline이 오염**된다(예: 연관에 `@BatchSize`를 달면 v1도 자동 배치되어 더 이상 1+N이 아니게 됨). 모든 버전이 코드에 공존해야 하는 규칙(`CLAUDE.md`)과 충돌. → 인덱스 시나리오에서 "v1이 무인덱스 baseline을 유지하는가"를 코드로 전수 확인했던 것과 같은 함정.

**해소 옵션 (design-review에서 택1 또는 보완):**
- **옵션 A — 쿼리 레벨로만 변주(권장 후보):** 엔티티는 LAZY·깨끗하게 고정. 차이는 **리포지토리 메서드**로만 발생.
  - v1: 무fetch 조회 + 서비스 루프 접근 → N+1
  - v2: `@EntityGraph(attributePaths=...)`로 강제 즉시로딩 → 컬렉션 fetch의 행 곱/`distinct` 필요/다중 컬렉션 `MultipleBagFetchException` 함정 시연 (= "EAGER의 대가"를 쿼리 레벨로 재현)
  - v4: JPQL `join fetch`
  - **단, v3 @BatchSize만 전역 속성 필요** → `spring.jpa.properties.hibernate.default_batch_fetch_size`를 **v3 측정 런에서만 토글**(인덱스 시나리오가 SQL 스크립트를 적용/미적용으로 토글한 것과 동형). v3는 "코드 엔드포인트 + 측정시 config 토글" 조합.
- **옵션 B — 사다리 재정의:** v2/v3을 엔티티 전역 토글로 두되, **각 버전 측정은 단독 런으로 격리**하고 v1 baseline은 "@BatchSize 미적용 상태"임을 런타임 assert로 검증(`default_batch_fetch_size` unset 확인). (메모리 `dryrun-pin-runtime-assert` 패턴)
- **옵션 C — EAGER 함정을 엔티티로 정직하게:** v2에서 실제 `fetch=EAGER`로 바꾸면 전역 오염 → 비권장.

> **v4·v5는 R1 무관(오염 없음).** 둘 다 **리포지토리 쿼리 메서드 레벨**(v4=`join fetch`, v5=`select new …`)이라 엔티티/전역 속성을 안 건드린다. 전역 토글이 필요한 건 v3(@BatchSize)뿐 → "v3 측정 런에서만 토글 + 런타임 assert"가 격리의 전부다.

> 이 R1이 정해지기 전에는 Step 구현 세부를 확정하지 않는다. **design-review 1순위 안건.**

## 설계 리스크 R2 — 측정의 "쿼리 수"를 무엇으로 세나
- 1차 지표는 **응답시간(k6 p95)**, 2차는 **발생 SQL 쿼리 수**.
- 쿼리 수 카운트 방법(검증 대상): (a) Hibernate `Statistics`(`getPrepareStatementCount()`)를 액추에이터로 노출, 또는 (b) `org.hibernate.SQL: DEBUG` 로그 라인 수 집계. P6Spy는 금지(`CLAUDE.md`). → design-review에서 (a) 권장 여부 확인.
- **v5 추가 지표(축②·③):** 쿼리 수만으로는 v4↔v5의 차이가 안 드러난다. v5의 가치 입증을 위해 **전송량**(응답 바이트 또는 Statistics의 `getEntityLoadCount()`/fetch row 수)과 **적재 비용**(영속성 컨텍스트 적재 엔티티 수 — v5는 0)을 함께 본다. → design-review에서 측정 가능한 대리지표 확정.

---

## Important Rules
- 먼저 `CLAUDE.md`를 읽을 것(엔티티·관계·API·로깅 규칙).
- **엔티티(`Order`/`OrderItem`/`Product`)와 기존 관계를 수정하지 말 것** — R1 해소가 옵션 A로 확정될 경우. (옵션 B 확정 시 별도 명시)
- Controller에서 엔티티 직접 반환 금지 → 반드시 DTO(`api/order/dto/`).
- Controller → Service → Repository 호출 순서 필수.
- 새 관계/엔티티 추가 금지(이 시나리오는 스키마 변경 0).
- **신규 의존성 추가 금지** — v5 DTO Projection은 QueryDSL이 아니라 **JPQL 생성자 표현식**(`select new …Response(…)`)으로 구현한다(`build.gradle`엔 `spring-boot-starter-data-jpa`만 존재).
- MySQL 접근은 셸 함수 `run_mysql() { docker exec -i docker-mysql-1 mysql -uroot -proot flashdeal "$@"; }`. `mysql -h127.0.0.1` 직접 금지.
- 모든 `.sh`는 실행권한(`chmod +x`).

---

## Step A: 조회 DTO + 리포지토리 조회 메서드 (v1 baseline)
### A-1. DTO 신설 (`api/order/dto/`)
- `OrderSummaryResponse` — 목록용: `orderId`, `status`, `total`, `createdAt`, `itemCount`(int), `firstProductName`(String). 엔티티 미노출.
- `OrderDetailResponse` + 중첩 `OrderItemResponse` — 상세용: 주문 헤더 + `List<OrderItemResponse>{ productId, productName, price, quantity }`.
### A-2. `OrderRepository` — v1 조회 메서드
- `List<Order> findByUserId(Long userId)` (무fetch).
- `Optional<Order> findById(Long id)` (기본 제공).
### A-3. `OrderQueryServiceV1` (`service/order/`)
- `@Transactional(readOnly=true)`. 목록/상세를 **LAZY 루프 접근**으로 조립 → N+1 의도적 발생.
### A-4. `OrderQueryController` — v1 엔드포인트
- `GET /api/v1/users/{userId}/orders`, `GET /api/v1/orders/{orderId}`.

## Step B: v2 (EAGER/EntityGraph) — R1 확정안 반영
- (R1 옵션 A 가정) `@EntityGraph(attributePaths={"orderItems"})` 목록 메서드 + 상세는 `{"orderItems","orderItems.product"}`. 컬렉션 fetch 행 곱·`distinct` 필요·다중컬렉션 예외를 검증 시나리오에 명시.

## Step C: v3 (@BatchSize / batch fetch) — R1 확정안 반영
- (R1 옵션 A 가정) `default_batch_fetch_size` 토글로 IN절 배치. 측정 런 전후 값 핀 + 런타임 assert.

## Step D: v4 (Fetch Join) — 단건 상세 최적
- `OrderRepository`에 `@Query("select distinct o from Order o join fetch o.orderItems oi join fetch oi.product where o.userId = :userId")` 목록, 상세도 동형. 단일 쿼리 착지.
- **함정 명시:** 1:N fetch join + 페이징 = 메모리 페이징 경고, 1:N 둘 동시 fetch = `MultipleBagFetchException`(R-Q4). → "단건 상세엔 최적, 목록+페이징엔 부적합"을 결론에서 v3와 대비.

## Step E: v5 (DTO Projection) — 조회 전용·대용량
- **QueryDSL 미사용** — JPQL 생성자 표현식으로 필요 칸만 투영, 엔티티 영속성 적재 0.
- **상세** (`v5`가 가장 깔끔하게 드러나는 경로): 헤더 1회 + 라인 1회 = **2회 고정**.
  - 헤더: `@Query("select new com.project.api.order.dto.OrderDetailResponse(o.id, o.status, o.total, o.createdAt) from Order o where o.id = :id")`
  - 라인: `@Query("select new com.project.api.order.dto.OrderItemResponse(oi.product.id, oi.product.name, oi.price, oi.quantity) from OrderItem oi where oi.order.id = :id")` → 서비스에서 헤더에 set.
- **목록:** 헤더 투영(1회) + 라인 요약은 v3 기법(@BatchSize/IN)으로 2단계 로딩 — 경로별 배선상 목록의 기본은 v3이므로, v5 목록은 "헤더만 투영해도 전송량이 주는가"를 보조 측정(필수 아님, design-review에서 범위 확정).
- `itemCount`·`firstProductName`(목록 요약 파생값)의 투영 방법은 R-Q5에서 확정.

## Step F: 측정 자동화
- `scripts/measure/` — 각 vN에 대해 (1) 쿼리 수(R2 확정 방법) (2) k6 부하 p95 수집, (3) **v4↔v5는 전송량·적재 대리지표(R2 추가)도** 수집. 비포화 게이트(`http_req_failed≈0`, Hikari `pending==0`) 재사용.
- `test/load/` — k6 스크립트(목록·상세 각각, vN step 태깅).
- `results/phase3-7-n-plus-one/` — vN별 쿼리수 + k6 + (옵션) Grafana 캡처.

---

## Work Order
순차 진행, **각 Step 후 일시정지 → 사람 검증 → `/commit`**. R1·R2가 design-review에서 확정된 뒤 Step B~ 세부를 갱신한다.
1. **R1·R2 확정** (design-review) → 이 문서에서 `-draft` 제거
2. Step A (v1 baseline + DTO/조회 표면) → 검증 → commit
3. Step B (v2) → 검증 → commit
4. Step C (v3) → 검증 → commit
5. Step D (v4) → 검증 → commit
6. Step E (v5 DTO Projection) → 검증 → commit
7. Step F (측정) → 검증 → commit
8. `/pr`

## Outside Claude Code Scope (human tasks)
- 각 검증 수동 실행, 측정 스크립트 실행·해석(쿼리수 before/after, k6 p95)
- `docs/n-plus-one-troubleshooting-01.md` 트러블슈팅 문서 작성(`docs-conventions`: 한 파일 한 주제)
- PR 머지

---

## Open Questions (design-review 안건)
1. **R1 버전 격리** — 옵션 A/B/C 중 택1. v1 baseline 무오염을 어떻게 보장·검증할 것인가(런타임 assert?).
2. **R2 쿼리 수 카운트** — Hibernate `Statistics` 액추에이터 노출 vs SQL 로그 집계.
3. **목록 N 크기** — ~~열림~~ **[audit-doc로 해소]** DB 실측 평균 ~500주문/유저(max 584)라 **추가 헤비유저 시드 불필요** — N≈500 컬렉션 N+1이 기본 재현됨. 단 목록에 페이징을 적용하면 N이 page size로 제한되어 N+1 폭이 줄어드므로, **측정은 비페이징 또는 충분히 큰 page size로** 수행한다.
4. **컬렉션+to-one 동시 fetch join**의 `MultipleBagFetchException`/카테시안 — v4에서 한 쿼리로 갈지, 목록은 `@BatchSize`(v3 기법)와 fetch join을 섞을지(2단계 로딩). (= R-Q4)
5. **v5 목록 파생값 투영** — 목록 요약의 `itemCount`/`firstProductName`은 집계/상관 서브쿼리가 필요하다. JPQL 생성자 표현식으로 (a) `count`/`min` 집계를 헤더 쿼리에 포함할지, (b) 목록 v5는 헤더만 투영하고 요약은 v3식 2단계 배치로 채울지 확정. (= R-Q5)
6. **v5 전송량·적재 대리지표** — 축②·③을 로컬에서 측정 가능한 값(응답 바이트, Statistics load count, 적재 엔티티 수)으로 어떻게 노출·집계할지(R2 연계).
