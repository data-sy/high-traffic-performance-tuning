# Phase 3-7 — N+1 해결: 주문 목록(컬렉션)·상세(to-one) v1~v5 사다리 + 경로별 배선

> **확정(2026-06-23).** `/audit-doc`(Healthy) + `/design-review` 3라운드 + **측정 실측으로 표 확정**. 핵심: v2 EAGER 실제 거동 실측 — **목록만 N+1 시연(상세는 `find()`가 흡수), 목록 576=`1+N`(대표상품 EAGER `@ManyToOne`=JOIN 흡수)**, v1 1150(`1+2N`−dedup)과 구조 대비. R1 격리·R2 2단계·R3 부하모델 적용. **전체 측정 결과·p95·EXPLAIN 캐비엇: `results/phase3-7-n-plus-one/results.md`.**

## Context (현재 상태)
- Phase 3-1~3-6 완료. 데이터 스케일업됨(DB 실측): `product` 1,000,000 / `order_item` 1,500,000 / `orders` ~500,000 / `users` ~1,000 (**사용자당 평균 ~500주문, max 584**, 주문당 평균 ~3 아이템).
- `ddl-auto: validate` 활성. **변경 금지.**
- Docker 컨테이너(MySQL, Redis) 실행 중(`docker compose -f docker/docker-compose.yml up -d`).
- 현재 주문 도메인 표면: `OrderService.createOrder()`만 존재. **조회 컨트롤러·조회 서비스·조회용 DTO 없음** → 이번 phase에서 신설.
- 기존 연관관계(확인됨): `Order → OrderItem @OneToMany(mappedBy="order", cascade=ALL)`, `OrderItem → Order @ManyToOne(LAZY)`, `OrderItem → Product @ManyToOne(LAZY)`.

## Goal
주문 **목록**(컬렉션 N+1)과 주문 **상세**(to-one N+1) 두 읽기 경로에서 N+1을 재현하고, **v1~v5 단계**로 해소하며 쿼리 수·응답시간·(v5)전송량·적재비용을 측정한다. **스키마 변경 0** — 기존 엔티티/관계만 사용한다.

**착지점(서사의 핵심):** 단일 정답(v4 "쿼리 1회") 대신, **경로의 성격에 따라 v3·v4·v5를 갈라 쓰는 "경로별 배선"** 결론으로 끝낸다. 우승 기준은 "가장 빠름/가장 적은 쿼리"가 아니라 **예측 가능성**(데이터가 커져도 쿼리·메모리가 고정)이다. → 아래 [경로별 배선] 절.

## 시나리오 서사 (왜 N+1이 터지나)
1. **주문 목록 (컬렉션 N+1):** 손님이 마이페이지 "내 주문 목록"을 연다. 주문 수백 건(실측 평균 ~500건/유저, max 584)을 조회한 뒤, 각 주문의 **요약**(상품 개수 `itemCount` + 대표상품명 `firstProductName`)을 그리려 `order.getOrderItems()`(주문당 컬렉션 SELECT = N회) + 첫 아이템의 `product`(N회)를 루프 접근 → **1 + 2N 쿼리(N≈500)**. *대표상품은 아이템을 id 오름차순 정렬한 첫 항목으로 정의(버전 간 응답 결정성 — 컬렉션 순서가 미정의면 v1/v4/v5 응답이 어긋남).*
2. **주문 상세 (to-one N+1):** 손님이 주문 하나를 펼친다. 주문(1) + 그 주문의 아이템 컬렉션(1) + 아이템 M개의 `product`(M회) → **2 + M 쿼리**. (상세는 *한* 주문만 펼치므로 목록의 폭발과는 별개 — 목록을 상세까지 펼치는 화면은 없음.)

→ 두 경로 모두 **Order 집합 표면**에 살아 기존 시나리오(① 인덱스=Product목록, ③ 랭킹=salesCount, ④ 동시성=Coupon)와 **겹치지 않는다.**

---

## 측정 대상 — 두 엔드포인트 × v1~v5

신설 엔드포인트(모든 버전 코드 공존, `CLAUDE.md` API 규칙):

| 경로 | 화면 | N+1 유형 |
|---|---|---|
| `GET /api/v{n}/users/{userId}/orders` | 주문 목록 | 컬렉션 N+1 (Order→OrderItems) |
| `GET /api/v{n}/orders/{orderId}` | 주문 상세 | to-one N+1 (OrderItem→Product), 목록 결합 시 다단계 |

### v 사다리와 쿼리 수 (목록 경로: 주문 N건; 요약 = itemCount + 대표상품명)

> **실측 확정(2026-06-23, N=575·M=5).** 전체 결과·방법·p95·EXPLAIN 캐비엇: `results/phase3-7-n-plus-one/results.md`.

| 버전 | 전략 | 목록 쿼리 수(공식) | **목록 실측** | **상세 실측** | 핵심 교훈 |
|---|---|---|---|---|---|
| **v1** | LAZY default + 루프 접근 | `1 + 2N` (상한) | **1150** | **7** | N+1 재현(baseline). 컬렉션 N + 대표상품 N(LAZY `@ManyToOne`=별도 SELECT). 1150=1+2N−1(product 1건 1차캐시 dedup). 상세 2+M=7 |
| **v2** | **EAGER (엔티티 즉시로딩)** | **목록 ≈`1+N`** | **576** | **1** | **"EAGER로도 N+1 안 사라진다"** — 파생쿼리는 EAGER 컬렉션을 secondary SELECT N회로 채움(N+1 잔존). 대표상품은 EAGER `@ManyToOne`=**JOIN** 흡수→별도 SELECT 0(∴ 576=1+N, ≠v1 1+2N). **`find()` by PK는 조인 흡수→상세 단일 쿼리(=1), N+1 미발생** — 시연은 목록만(B1·#1) |
| **v3** | `@BatchSize` / `default_batch_fetch_size`(=100) | `1 + 2⌈N/b⌉` | **27** | **3** | IN절로 secondary SELECT 묶기. 1150→27. 공식은 근사(Hibernate 배치가 product 집합 전반 IN 로딩). N+1 → 소수 쿼리 |
| **v4** | Fetch Join (JPQL `join fetch … distinct`) | `1` | **1** | **1** | 단일 쿼리 — **단건 상세에 최적**. 1:N+페이징엔 부적합(메모리 페이징 경고) |
| **v5** | DTO Projection (JPQL `new`, **필요 칸만**) | 헤더1 + 요약 IN 1 · 상세 2 | **2** | **2** | 쿼리 최소화를 양보하고 **전송량·적재비용**을 깎음 — "쿼리 수가 전부가 아니다". 목록 p95 최속(470ms)이 방증. 조회 전용·대용량 |

> **다중 컬렉션 함정 없음(design-review B2):** 이 스키마의 컬렉션은 `Order.orderItems` 하나뿐(OrderItem→Product는 to-one)이라 `MultipleBagFetchException`은 **재현되지 않는다**. v4의 `join fetch orderItems + join fetch product`(컬렉션 1 + to-one 1)는 안전. v4 함정은 **1:N fetch join + 페이징(메모리 페이징)** 하나로 한정.

> **v5는 QueryDSL을 쓰지 않는다.** 학습자료(`nplusone-ladder.html`)는 QueryDSL `Projections`를 쓰지만, 본 프로젝트는 신규 의존성을 추가하지 않고 **JPA 기본기인 JPQL 생성자 표현식**(`select new com.project.api.order.dto.…Response(…)`)으로 동등 효과를 낸다.

> **컨벤션 근거:** v5 도입과 "경로별 배선"(최종 해법 = 최고 번호 아님, 경로의존)은 `CLAUDE.md` API Structure의 **확장 규정(v1~vN 허용)**에 따른다. CLAUDE.md를 이미 갱신함(동시성 v5·N+1 v5 명시, 채택은 워크로드 적합성 기준).

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
- **목록 + 페이징(대부분의 실무 조회):** `v3 @BatchSize`를 기본으로. fetch join은 1:N+페이징에서 **메모리 페이징 경고(HHH000104)**로 막히므로, IN 배치가 부작용 없이 거의 모든 조회에 꽂힌다. *v4 막힘 근거는 **페이징 적용 v4 변종 실측**으로 뒷받침(미측정 시 "원리만" — N-2·#5). 비페이징 목록은 v4도 단일쿼리로 동작하므로 결론은 **페이징 목록**에 한정.*
- **단건 상세(1:N 하나, 화면이 쓸 데이터 확정):** `v4 Fetch Join`. 쿼리 1회, 컬렉션 하나라 `distinct`로 카테시안 곱 통제.
- **조회 전용 핫패스·대용량 응답:** `v5 DTO Projection`. 전송량·적재까지 깎아야 하는 경로에서만, 복잡도를 감수하고 쓴다.

> 우승자 선정 기준 = **예측 가능성**. p95 차이가 측정 노이즈 수준(예: 42 vs 38ms)이면 그걸 우승 근거로 쓰지 않고, "데이터가 커져도 쿼리·메모리가 고정"이라는 구조적 성질로 가른다.

---

## ★ R1 — 버전 격리 (design-review 확정)

**문제:** `v2 EAGER`(`@ManyToOne(fetch=EAGER)`)와 `v3 @BatchSize`는 본래 **엔티티/전역 설정**이라, 전역으로 박으면 **v1 순수 N+1 baseline이 오염**된다(연관에 batch를 달면 v1도 자동 배치 → 1+2N이 깨짐). → 인덱스 시나리오의 "v1 무인덱스 baseline 유지" 함정과 동형.

**확정 해소(혼합):**
- **v4(`join fetch`)·v5(`select new`)는 쿼리 메서드 레벨** → 엔티티/전역을 안 건드려 **영구 공존, 격리 불필요**.
- **v2(EAGER 매핑)·v3(batch 설정)은 전역 변수** → **해당 버전의 단독 측정 런에서만 토글**한다(인덱스 시나리오가 인덱스 SQL을 적용/미적용으로 토글한 것과 동형). 엔드포인트 v1~v5는 코드로 공존하되, v2/v3의 전역 거동은 *측정 시 토글되는 변수*다.
- **v1 baseline 무오염은 런타임 assert로 검증** — v1 측정 직전 "기본 fetch=LAZY · `default_batch_fetch_size` unset"을 확인하고 불일치면 abort(메모리 `dryrun-pin-runtime-assert`).

> **v2 정체성(design-review B1):** v2는 `@EntityGraph`가 **아니다**. EntityGraph는 LEFT JOIN FETCH를 생성 → 단일 쿼리라 v4로 붕괴하고 "EAGER도 N+1" 교훈을 못 보인다. v2는 **정통 엔티티 EAGER**로, 파생쿼리에서 secondary SELECT N번을 그대로 드러내는 게 핵심.

## ★ R2 — 쿼리 수를 어떻게 세나 (design-review C-a 확정)
- **2단계 측정 분리:** Hibernate `Statistics.getPrepareStatementCount()`는 **프로세스 전역 누적**이라 k6 동시부하 중엔 요청당 쿼리 수를 못 뽑는다(여러 요청 인터리브). 따라서:
  - **쿼리 수**(1+2N → 1 등): **단일요청 격리 런(VU=1, 단발 요청)**에서 측정. 또는 요청 스레드 바인딩 `StatementInspector`로 per-request 카운트.
  - **p95**: 별도 **부하 런**(아래 R3 부하 모델 준수)에서 측정.
- 카운트 소스: Statistics(격리 런) 또는 `org.hibernate.SQL: DEBUG` 로그 라인 수. P6Spy 금지(`CLAUDE.md`).
- **v5 추가 지표(축②·③):** 쿼리 수로는 v4↔v5 차이가 안 드러난다. **전송량**(응답 바이트, Statistics fetch row 수)과 **적재 비용**(영속성 컨텍스트 적재 엔티티 수 — v5는 0)을 함께 본다. 대리지표 노출 방법은 R-Q6.

## ★ R3 — 부하 모델: 버퍼 워밍 함정 (design-review C-b 확정)
**위험:** 목록 엔드포인트를 **단일/소수 핫 `userId`**에 집중시키면, 첫 요청이 그 유저의 ~500주문·아이템을 InnoDB 버퍼풀에 적재 → 이후 요청은 디스크 I/O 0(완전 워밍). v1의 ~1,000회 round-trip이 전부 메모리에서 µs급 처리 → **v1 p95 ≈ v4 p95**(차이=round-trip 오버헤드 노이즈)로 1차 지표가 N+1을 못 가르고, "예측 가능성" 논거가 flat 결과의 사후합리화로 전락.

**해소(측정 규칙):**
- **userId를 전체 ~1,000명에 고르게 분산**(워킹셋 > 버퍼풀; orders 500K). k6에서 매 요청 랜덤 userId.
- 구조적 차이(1+2N vs 1)는 **쿼리 수(R2 격리 런)가 데이터 무관하게** 보이므로 1차 근거로 두고 p95는 보조.
- p95가 안 갈리면 "버퍼 워밍 탓"을 **명시적 가설로 기록**하고, 우승 근거는 쿼리·적재의 구조적 고정성으로 둔다(사후합리화 아님).
- **전제 검증(#3):** 측정 전 `innodb_buffer_pool_size` < 워킹셋(order 500K+item 1.5M+product 1M)임을 확인 — 풀이 데이터셋을 다 담으면 분산해도 워밍 후 전량 캐시라 무효 → 그땐 쿼리수로만 가름.
- **목록 vs 상세(#7):** 목록(N≈500)은 statement 수(≈1001 vs 1)가 디스크 I/O와 무관하게 지배 → 워밍돼도 p95가 갈릴 여지 큼. **상세(M≈3, ≈5 vs 1)는 워밍 시 노이즈 → 상세는 쿼리수로만 가른다.**
- **부하점 설정(#4):** v1은 요청당 ~1001쿼리·커넥션 장기 점유라 같은 부하에서 v1만 먼저 Hikari `pending>0`. 모든 버전이 비포화인 부하점을 잡되 그 점에선 v4가 거의 idle이라 p95 분리가 줄 수 있음 — **apples-to-apples는 쿼리수(격리 런)로 확보**, p95는 보조.

---

## Important Rules
- 먼저 `CLAUDE.md`를 읽을 것(엔티티·관계·API·로깅 규칙).
- **엔티티/관계를 영구 수정하지 말 것.** v2 EAGER·v3 batch는 *측정 시 토글되는 전역 변수*로 다루며(R1), 코드 baseline은 LAZY·batch unset로 유지하고 v1 측정 직전 런타임 assert로 검증.
- Controller에서 엔티티 직접 반환 금지 → 반드시 DTO(`api/order/dto/`).
- Controller → Service → Repository 호출 순서 필수.
- 새 관계/엔티티 추가 금지(이 시나리오는 스키마 변경 0).
- **신규 의존성 추가 금지** — v5 DTO Projection은 QueryDSL이 아니라 **JPQL 생성자 표현식**(`select new …Response(…)`)으로 구현한다(`build.gradle`에 **QueryDSL 의존성 없음** — JPA 기본기로 구현).
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

## Step B: v2 (정통 EAGER) — 목록에서 N+1 잔존, 상세는 흡수
- **`@EntityGraph 아님`** (B1). `Order→orderItems`·`OrderItem→Product`를 **`fetch=EAGER`로 토글한 단독 측정 런**에서 측정. 측정 후 LAZY 원복, v1 무오염은 R1 런타임 assert.
- **목록(`findByUserId`, 파생 JPQL):** 쿼리 실행 후 각 Order의 EAGER `orderItems`를 **secondary SELECT N회**로 채움 = **EAGER로도 N+1 잔존**. 단 각 컬렉션 SELECT에 EAGER `@ManyToOne product`(기본 style=JOIN)가 조인 흡수 → 대표상품은 별도 SELECT 아님(∴ ≈`1+N`, v1 `1+2N`과 **구조가 다름**).
- **상세(`findById`=`em.find` by PK):** `find()`는 EAGER를 **즉시 조인/로딩으로 흡수 → N+1 미발생**(~2쿼리). **∴ v2의 N+1 시연은 목록 경로로 한정**(design-review #1, 표 각주 "find() by PK만 조인"과 정합).
- **쿼리 수는 실측으로 확정** — a priori 공식 단정 금지(Hibernate 버전·기본 style 의존). SQL 로그로 표를 채운다.
- 교훈: ① 즉시로딩은 N+1을 *없애지 않고 시점만* 옮긴다(쿼리 경로). ② **EAGER+`find()`는 흡수, EAGER+쿼리는 못 흡수.** ③ LAZY `@ManyToOne`=별도 SELECT vs EAGER=JOIN.

## Step C: v3 (@BatchSize / batch fetch) — R1 토글 격리
- `default_batch_fetch_size`(또는 연관 `@BatchSize`) 토글로 IN절 배치. 단독 측정 런 전후 값 핀 + 런타임 assert(v1 baseline=unset 확인).

## Step D: v4 (Fetch Join) — 단건 상세 최적
- `OrderRepository`에 `@Query("select distinct o from Order o join fetch o.orderItems oi join fetch oi.product where o.id = :id")` 상세, 목록도 동형(단 페이징 미적용). 컬렉션 1 + to-one 1이라 단일 쿼리·`distinct`로 카테시안 곱 통제.
- **함정(한정):** 1:N fetch join + **실제 페이징(`Pageable`) = 메모리 페이징 경고(HHH000104)**. *컬렉션이 하나뿐이라 `MultipleBagFetchException`은 이 스키마에서 재현 불가 — 이론 노트(design-review B2).*
- **N-2:** 경고는 `Pageable`을 줄 때만 발생. 비페이징 v4는 경고 없이 대량 로딩일 뿐 → 결론에선 **페이징 적용 v4 변종을 따로 측정**하거나 "원리로만 설명"임을 명시.

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

## Work Order (완료 — 2026-06-23)
R1·R2·R3는 design-review로 확정. 구현 A~E + 측정 F 완료, 측정으로 표 확정 후 `-draft` 제거.

- [x] 확정본 사람 확인 → `-draft` 제거 (측정 후 사람 승인)
- [x] Step A — v1 baseline + DTO/조회 표면 (`a03cff7`)
- [x] Step B — v2 EAGER (`b6b033d`)
- [x] Step C — v3 batch (`6154d5e`)
- [x] Step D — v4 Fetch Join (`49b80c3`)
- [x] Step E — v5 DTO Projection (`9a71feb`)
- [x] Step F — 측정 (`161cd3d`; 결과 `results/phase3-7-n-plus-one/results.md`)
- [x] `/pr` — PR #16

## Outside Claude Code Scope (human tasks)
- 각 검증 수동 실행, 측정 스크립트 실행·해석(쿼리수 before/after, k6 p95)
- `docs/n-plus-one-troubleshooting-01.md` 트러블슈팅 문서 작성(`docs-conventions`: 한 파일 한 주제)
- PR 머지

---

## 해소된 안건 / 남은 Open Questions

**검증으로 해소(audit-doc·design-review):**
1. **R1 버전 격리** — 확정. v4/v5 쿼리레벨 공존, v2(EAGER)/v3(batch)는 단독 측정 런 토글 + v1 baseline 런타임 assert(위 R1).
2. **R2 쿼리 카운트** — 확정. 쿼리 수=격리 런(VU=1), p95=부하 런 2단계 분리(위 R2).
3. **목록 N 크기** — 해소(audit). 실측 ~500/유저로 추가 시드 불필요. 페이징 시 N 축소 → 비페이징/큰 size.
4. **R-Q4 다중 컬렉션 예외** — 폐기(B2). 컬렉션 하나뿐이라 `MultipleBagFetchException` 재현 불가. v4 함정은 "1:N+페이징"만 유효.
5. **v1 목록 baseline** — 정정(B3). `1+2N`(컬렉션 N + 대표상품 N), v3=`1+2⌈N/b⌉`.
6. **v2 정체성** — 확정(B1). `@EntityGraph` 아님, 정통 엔티티 EAGER.

**구현·측정에서 확정(2026-06-23):**
- **R-Q5 v5 목록 파생값 투영** — (b)로 확정: 헤더 투영 1회 + 라인요약 IN 투영 1회 = 2쿼리(엔티티 적재 0). 라인요약은 product 조인으로 (orderId, itemId, productName) 투영 후 서비스에서 집계.
- **R-Q6 v5 전송량·적재 대리지표** — 목록 p95(v5 470ms 최속)로 간접 입증, 직접 정량(응답 바이트·적재 엔티티 수=0)은 보조 측정으로 범위 한정(`results.md` §4.2·§8).
- **C-c 전송량 공정성** — v5 목록을 측정에 쓰면 필드 집합을 v1~v4와 동일하게 맞추거나(아니면 적은 칸이라 작은 게 당연), 전송량 비교는 동일 DTO인 **상세 경로로 한정**.
