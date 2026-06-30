# Phase 3-8 — 읽기 캐시 + 스탬피드 방어: 상품목록 look-aside v1~v5 사다리 + 경로별 배선

> **확정(2026-06-30).** 설계 검수 통과 + Step A~F 구현·측정 실측으로 표 확정. 핵심 결과: 만료창 동시 미스(C=50)에서 오리진 재계산 **v2 50(herd) → v3/v4 1**, 만료 전환창 블록 요청 **v3 49 → v4 0**, 페네트레이션 2번째 창 **v2 50 → v5 0**. herd의 실제 비용은 커넥션풀 포화(p99 411ms@pool=10). **전체 측정·게이트·락 불변: `results/phase3-8-cache-stampede/analysis.md`, 회고: `docs/reports/phase3-8-cache-stampede.md`.**
>
> **구현 전 선결(완료/유지):** AD-1 라이브 인덱스 `idx_product_category_created (category, created_at DESC)` 복원·EXPLAIN 검증(type=ref·no filesort) — 이 DDL이 init/seed 스크립트에 없어 리셋 시 재드리프트하므로 `scripts/ensure-product-index.sh`로 스크립트화. 측정 격리 스택(전용 Redis 6380 + 본체 MySQL 3306 읽기전용 공유)은 Step A에서 구축.
>
> 로드맵 위치: `ROADMAP.md` Stage 3 **A. 캐시 전략 + 스탬피드 방어** (단일 노드 캐시·회복탄력성). 권장 선행은 N+1(3-7, 완료).
>
> 본문의 `DR-*`·`AD-*`·`Q1~Q10` 라벨은 설계 검수에서 도출한 결정·미결 질문 기록이다(phase 3-7 의 R1~R3 동형).

## Context (현재 상태)
- Phase 3-1~3-7 완료. 데이터 스케일업됨(DB 실측, ROADMAP 정본): `product` 1,000,000 / `order_item` 1,500,000 / `orders` ~500,000 / `users` ~1,000.
- `ddl-auto: validate` 활성. **변경 금지.** 이 phase는 **스키마 변경 0** — 캐시는 Redis에 살고, 신규 엔티티/테이블/관계 없음(3-7과 동형).
- Docker 컨테이너(MySQL, Redis) 실행 중. 측정은 **본체와 분리된 전용 격리 스택**에서 수행하되, **격리 경계는 앱 인스턴스 + 전용 Redis(6380)까지**다(AD-2 명시):
  - **전용 = 앱(측정 스택 JVM) + Redis 6380.** 캐시 사다리가 닿는 건 Redis뿐이고 이 phase는 **스키마 변경 0**이라, MySQL은 **본체 `docker-mysql-1`(localhost:3306)을 읽기전용으로 공유 재사용**한다(전용 MySQL 미신설). `run_mysql() { docker exec -i docker-mysql-1 ... }`이 본체 컨테이너를 가리키는 것은 **이 정책상 정상**(우발적 본체 직격이 아님) — 읽기전용·스키마 무변경이라 본체 데이터 손상 없음.
  - **datasource override 정책(필수 명시):** 측정 스택 `application.yml`은 `spring.datasource.url`을 **본체 3306**으로 두고 `spring.data.redis.port`만 **6380**으로 override한다. "전용 MySQL을 띄우는데 `docker-mysql-1`이 조용히 본체를 가리켜 *다른* 데이터/스케일을 재는" 함정을 막기 위해, 전용 MySQL은 **띄우지 않는다**(띄우면 컨테이너명·데이터 출처가 갈려 측정 무효). boot assert가 datasource=3306·redis=6380을 대조.
- 대상 읽기 경로 표면(확인됨, 코드):
  - `GET /api/v1/products?category={cat}&page={p}&size={s}` → `ProductController.getProducts` → `ProductService.getProducts`(`@Transactional(readOnly=true)`) → `ProductRepository.findByCategoryOrderByCreatedAtDesc(category, pageable)`.
  - 이 쿼리의 *지연*은 **복합 인덱스 `(category, created_at)`**(Phase 3-1 `idx_product_category_created`) 존재 여부에 좌우된다 — 인덱스가 있으면 `type=ref`·no filesort·3-6 실측 단건 ≈12.7ms, **없으면 full scan+filesort로 수백 ms**. **인덱스 존재는 코드로 확인 불가한 DB 상태**다(Product 엔티티에 `@Index`/`@Table(indexes)` 선언 없음 · `ddl-auto=validate`라 앱이 생성 안 함 · 시드/마이그레이션에 영구 생성 DDL 없음 — 인덱스는 `scripts/scale/measure-index.sh`·`run-ts1-k6.sh`가 DROP/CREATE로 토글). 현재 라이브 DB의 `SHOW INDEX FROM product`엔 PRIMARY만 보인다(인덱스 부재). 따라서 "인덱스를 탄다 ≈12.7ms"는 **code-confirmed가 아니라 측정 시점 DB 상태 전제**다(AD-1) — **측정 전 게이트가 `SHOW INDEX`로 인덱스 상태를 확인·기록**해 baseline이 인덱스 부재로 *조용히* 어긋나는 것을 막는다(인덱스 존재=Phase 3-1 정상 baseline / 의도적 부재=Q1 '더 비싼 오리진' 변종 — 어느 쪽이든 *핀·검증된 전제*여야지 가정이면 안 됨). herd 크기·락 리스 p99는 어차피 *이 인덱스 상태에서의* **전 구간 실측 p99**로 핀하므로(line 106·242·M4), 인덱스 상태만 게이트로 고정하면 두 1차 축은 보존된다.
  - **이 경로에 캐시는 없다(확인):** `ProductService`에 `@Cacheable` 없음, Redis 주입 없음, 2차 캐시 미설정. before/after가 깨끗(아래 「baseline 정합」에서 코드로 전수 재확인 과제).
- 카테고리는 **고정 5종**(`CLAUDE.md`): 전자기기·의류·식품·도서·스포츠. → page1 기준 **핫 키 카디널리티 = 5**(자연·핀 가능). 존재하지 않는 카테고리(예: `없는카테고리`)는 **페네트레이션** 입력의 자연 후보.
- 기존 Redis 사용처(본체): 랭킹 Sorted Set, 분산락(`RedisLockRepository`), 비동기 큐(List/Stream) — **모두 단일 `redisConnectionFactory`(=`spring.data.redis.*`)를 공유**한다(코드 확인: `RedisConfig`에 연결 팩토리는 하나, `RedisLockRepository`도 이를 받아 자체 `StringRedisTemplate`를 만든다). 따라서 캐시 격리는 **빈별 포트 분리가 아니라 인스턴스 격리**다 — 측정은 본체와 분리된 **전용 스택**에서 돌고, 그 스택의 `spring.data.redis.*`가 **전용 Redis(포트 6380)**를 가리켜 **측정 스택 전체(캐시·및 만약 운동시키면 락/랭킹/큐)가 6380에 묶인다**. 본체 Redis(6379)와는 **인스턴스 단위로** 무접촉(한 JVM 안에서 캐시만 6380·락/랭킹만 6379로 가르는 빈별 분리는 2nd 팩토리가 없어 불가). 캐시 키는 그 6380 *안에서* **전용 키스페이스(`KEY_PREFIX`)**로 격리한다. (별도 2nd 연결 팩토리 신설 불요 — 단일 팩토리 재사용; phase3-4 누수 버그=StringRedisTemplate 6379 하드코딩 교훈은 "캐시 클라이언트가 `spring.data.redis.*`를 반드시 따른다"로 계승.)

## Goal
읽기 핫 경로(상품목록 page1) 하나에 **look-aside 캐시**를 얹고, 그 캐시가 부하 아래에서 깨지는 **세 축**(스탬피드·페네트레이션·어밸런치)을 **방어 기법을 한 칸씩만 바꿔** 사다리로 올린다. 비교 합법 축은 "캐시 적중 시 빠름"이 아니라 **미스/만료 버스트에서 오리진(DB) 재계산이 어떻게 되나**다.

- **v1** 캐시 없음, DB 직격 (baseline — 동시 부하에서 매 요청이 오리진 재계산)
- **v2** look-aside + 고정 TTL (캐시는 얹되, 만료 버스트에 **스탬피드**·미존재 키에 **페네트레이션**·동시 만료에 **어밸런치**를 *노출하는* 베이스라인)
- **v3** 단일 비행(single-flight)/뮤텍스 — 미스 시 키당 하나만 재계산 → thundering herd 차단
- **v4** 논리 만료 + 비동기 갱신(**CAS/플래그로 단일 refresh 보장**) → 만료 전환점의 직렬화/지연 스파이크 완화 (락 없는 고전 PER은 배리어 동기 고동시성에서 재계산>1로 'recompute≈1' 축을 깨므로 제외 — Q7·DR-B)
- **v5** 페네트레이션·어밸런치 방어: 널(미존재) 결과 캐싱(또는 블룸 필터) + TTL 지터

**채택 결정이 아니다(phase4-1·3-7 규율 계승).** v2~v5 중 하나를 "고르는" 게 산출물이 아니라, **"캐시를 정합·안정하게 만들면 무엇을 더 떠안는가"의 비용·실패 프로파일**을 남기는 것. v2는 **의도적으로 깨지는 칸** — 고치는 게 아니라 드러내는 자리(3-7 v1·phase4-1 v7a와 동형). 닫는 건 **경로별 배선**으로(아래 [경로별 배선]).

### 명시적으로 범위 밖 (백로그 유지)
- **쓰기 경로 캐시 무효화(write-through/write-behind)** — 이 phase는 **읽기 look-aside**로 한정. 상품은 측정 중 불변(읽기 전용 워크로드)이라 무효화 정합은 별도 주제.
- **다계층 캐시(L1 로컬 + L2 Redis, near-cache)** — 단일 계층(Redis look-aside)으로 고정. Caffeine 등 인메모리 near-cache는 백로그.
- **CDN/엣지 캐시·HTTP 캐시 헤더(ETag/Cache-Control)** — 애플리케이션 캐시 계층 밖. 범위 밖.
- **회복탄력성(타임아웃·서킷·벌크헤드)** — ROADMAP Stage 3 **D**(Resilience4j)의 주제. 락 타임아웃 폴백은 이 phase에서 *측정·기록*하되, 서킷브레이커식 일반 회복탄력성은 D로 넘긴다.
- **exactly / "캐시 정합성" 강주장 금지.** 달성하는 것은 "동시 재계산 억제(single-flight) + 만료 분산(jitter) + 페네트레이션 흡수(null-cache)"의 조합으로 **오리진 증폭을 구조적으로 상한**하는 것까지. 강한 일관성(읽기 후 쓰기 가시성 등)은 주장하지 않는다.

## Important Rules
- 먼저 `CLAUDE.md`를 읽고 컨벤션 확인(엔티티·관계 절대 금지·API vN 구조·로깅·Docker `-v` 금지·카테고리명).
- **스키마 변경 0 · 관계 추가 절대 금지.** `Product` 엔티티/관계를 어떤 식으로도 수정하지 않는다(읽기 재사용만). 신규 엔티티/테이블 없음(캐시는 Redis). 만약 비준에서 보조 테이블이 필요해지면 **연관 없는 독립 테이블**·키는 `Long`/`String` 컬럼만(`Order→User` 무관계 규율 동형) — 단, 현재 설계는 **테이블 0**.
- **`ProductService`·`ProductController`·`ProductRepository` 수정 금지(재사용만).** 캐시 버전들은 v1 경로를 **감싸는(wrap)** 신규 빈/엔드포인트로 둔다 — 기존 baseline 경로를 흔들지 않는다(3-7의 "v1 무오염" 규율 동형).
- **비교 합법 축은 *두 개의 정식 공동 1차 구조 지표*다(둘 다 캐시 불변·부하 잡음 무관 카운트):**
  - **(A) 오리진 재계산 수**(만료창당) — v1/v2(증폭) ↔ v3/v4/v5(≤1)를 가른다.
  - **(A2) 만료 전환창 대기 큐 깊이 = 블록된 요청 *수***(시간 아님) — **v3(≈C-1) ↔ v4(≈0)를 가른다.** 재계산 수는 v3·v4 둘 다 ≈1로 *포화*해 사다리 위쪽을 반증 못 하므로, A2는 보조가 아니라 **v4 칸의 존재 이유를 측정하는 정식 축**이다(승격 — A2 가 v3/v4 를 가르는 합법 비교축, DR-1).
  - 캐시 적중 지연·throughput·대기 *시간* 절대값은 **참조 전용**(Redis RTT·락 경합·런별 잡음 confound) — 버전 간 직접 비교 금지(아래 「측정 규율」). **시간≠개수**: 대기 *시간*은 봉인, 대기 *요청 수*는 정식 축 A2.
- **통제변수 핀 고정 + 전 버전 일치 + 부팅 assert.** TTL 기준값·지터 범위·락 타임아웃/대기·핫 키 카디널리티·동시성·페이로드·워밍 상태를 단일 소스 상수(`infrastructure/cache/CachePins.java`) + 부팅 핀 assert로 강제(phase4-1 `EventPins`/`EventPinsBootAssert` 패턴 계승). 사다리 내 **단일 변수 = 방어 기법만 차이**.
- **콜드/만료 전환점 측정 강제.** 캐시가 *이미 따뜻한* 상태로 재면 스탬피드가 안 보인다 — 측정은 콜드 시작 / 만료 전환점을 의도적으로 잡는다(3-7 R3 워밍 함정 계승).
- **전용 격리 Redis(6380)·라벨 격리 assert.** 격리는 **인스턴스 단위**(측정 스택 전체가 전용 6380) — 본체 Redis(6379)와 무접촉. 빈별 포트 분리(캐시만 6380, 락/랭킹 6379)는 단일 연결 팩토리라 불가. 격리 불일치 시 측정 abort(phase4-1·3-4 격리 규율 계승).
- MySQL 접근은 셸 함수 `run_mysql() { docker exec -i docker-mysql-1 mysql -uroot -proot flashdeal "$@"; }`. `mysql -h127.0.0.1` 직접 금지. 모든 `.sh`는 실행권한(`chmod +x`).
- 네이밍·비유는 **젠더중립/중립어**(친족 비유 금지 — 글로벌 컨벤션).
- **단정 금지·실측 인용.** 행 수·키 수·재계산 비용·핀 값을 a priori로 박지 말고 "DB 실측/ROADMAP 확정" 또는 「비준 미결 질문」으로 넘긴다.

---

## 측정 대상 — 캐시 버전 사다리 (대상 엔드포인트 1개)

| 버전 | 전략 | 닫는/노출하는 결함 | 핵심 교훈(가설 — 실측 확정) |
|---|---|---|---|
| **v1** | 캐시 없음, DB 직격 | (baseline) | 동시 부하에서 **매 요청 = 오리진 재계산 1**. 캐시 사다리의 "0층" |
| **v2** | look-aside + **고정 TTL** | 스탬피드·페네트레이션·어밸런치 **노출** | 캐시 적중 시 오리진 0이지만, **만료 전환점에 동시 미스가 일제히 오리진을 증폭**(herd). 미존재 키는 매번 직격. 동시 만료 키는 광역 herd. **의도적으로 깨지는 칸** |
| **v3** | **single-flight / mutex** (락) | thundering herd **차단** | 미스 시 키당 **재계산 1회**, 나머지는 **락 대기 후 적중(블록)**(스테일 제공은 v4 전용·v3 아님). 단, **락 자체가 새 직렬화·새 실패면**(타임아웃 폴백·홀더 크래시·입도)을 들임 |
| **v4** | **논리 만료 + 비동기 갱신**(CAS 단일 refresh) | 만료 전환점 직렬화·지연 스파이크 **완화** | "스테일 제공 중 백그라운드 갱신"으로 만료창의 동시 대기/스파이크 제거 — **단일 refresh를 CAS/플래그로 보장**해 재계산=1·블록≈0. (락 없는 고전 PER은 동기 고동시성에서 C·p개가 동시 조기재계산 → 재계산>1로 이 칸 주장을 깨므로 제외, Q7·DR-B) |
| **v5** | **널 캐싱(또는 블룸) + TTL 지터** | 페네트레이션·어밸런치 **방어** | 미존재 키를 짧은 TTL 널로 흡수(또는 블룸 사전차단), 키별 TTL을 지터로 분산해 동시 만료 광역화 차단. **상시 위생** |

> **버전 번호 충돌 주의:** 상품목록 경로는 이미 인덱스 시나리오(3-1)의 v1~v4(v5)와 엮인다. 본 캐시 사다리의 v1~v5는 **별도 네임스페이스가 필요**하다 — 엔드포인트 경로/번호 최종 확정은 「비준 미결 질문 Q2」(phase4-1 Q7 "버전 번호" 동형). 초안 표기상 "캐시 v1~v5"로 부르되, **신규 엔드포인트는 기존 `/api/v1/products`를 감싸는 별도 경로**(예: `/api/cache/v{n}/products` — 비준 확정)로 둔다.

---

## 경로별 배선 (최종 결론 프레임 — 채택 선언 아님)

방어 기법은 **상호 배타가 아니라 직교**다 — 한 칸이 다음 칸을 폐기하지 않는다. 워크로드 형상으로 갈라 쓴다:

| 축(결함) | 무엇이 터지나 | 닫는 칸 | 비용/새 실패면 |
|---|---|---|---|
| 스탬피드(thundering herd) | 핫 키 만료 순간 동시 미스 → 오리진 증폭 | v3 single-flight | 락 직렬화·타임아웃 폴백·홀더 크래시 |
| 만료 전환 지연 스파이크 | 단일 비행이라도 *그 1회*를 기다리는 대기열 | v4 논리만료+비동기갱신 | 스테일 허용창 튜닝·스테일니스 |
| 페네트레이션 | 미존재 키가 캐시 우회·오리진 직격 | v5 널 캐싱/블룸 | 널 메모리·키 생성 시 정합·블룸 FP |
| 어밸런치 | 다수 키 동시 만료 → 광역 herd | v5 TTL 지터 | 지터 범위가 만료를 충분히 분산하나 |

**권장 배선(가설 — 비준·측정으로 확정):**
- **중간 동시성 핫 경로:** `v3 single-flight`를 기본. herd를 키당 1회로 닫으면 대부분 충분.
- **초핫 단일 키(만료창 대기가 보이는):** `v4 논리만료+비동기갱신`. 만료 전환점의 직렬 대기까지 없애야 하는 경로에서만.
- **TTL 지터·널 캐싱:** **상시 위생**. 어느 배선이든 깔아두는 기본 방어(페네트레이션·어밸런치는 트래픽 성격과 무관하게 상존).
- v2(고정 TTL look-aside)는 **고치는 칸이 아니라 드러내는 칸** — herd/페네트레이션/어밸런치의 baseline.

> 우승 기준 = **예측 가능성**(3-7 계승). "캐시 적중 p95가 더 낮다"는 측정 노이즈일 수 있고 비교 금지 축이다 — 가르는 근거는 **부하 형상이 변해도 오리진 재계산 수가 구조적으로 상한되나**(키당 만료창당 ≤1 등).

---

## ★ 측정 규율 (이 초안의 전제 — 가장 비싼 결함이 여기서 난다)

phase4-1·3-7의 측정 규율을 캐시 주제로 옮긴다. **design-review가 거짓 수렴을 가장 먼저 노리는 자리다.**

### M1 — 비교 합법 축 = 두 정식 공동 1차 구조 지표 (재계산 수 + 블록 큐 깊이)
- N+1의 "쿼리 수", 인덱스의 "EXPLAIN rows"에 해당하는 **캐시 불변(cache-invariant) 1차 지표 = 오리진(DB) 재계산 호출 수 / 키당 만료창당 재계산 수**.
- **카운터를 오리진 로더 진입점에 심는다.** 캐시 추상화의 로더(`Supplier`로 감싼 실제 repo 쿼리 호출) 진입마다 `OriginRecomputeCounter.increment(key)`. 부하·런별 잡음과 **무관하게** 세는 구조량. p99 꼬리는 **보조**.
- **카운트 위치 계약:** 재계산 카운터는 **오리진 로더 1회 진입 = +1**(캐시 적중·락 대기·직렬화 비용에선 증가 안 함). 그래야 "오리진 증폭"의 순수 측정.
- **1 재계산 = 2 SQL 함정(DR-3):** `findByCategoryOrderByCreatedAtDesc`는 `Page` 반환이라 1회 로더가 **SELECT(page) + 별도 COUNT(\*)** 를 실행한다(JPA `Page` 의미·코드 확인). 카운터는 **로더 진입 기준 1회**로 세고 **COUNT 쿼리를 별도 재계산으로 이중 카운트하지 않는다**(진입점이 SQL 실행 콜백이 아니라 로더 람다 진입). 단 *비용*에선 COUNT가 빠지지 않는다 — 아래 "populate 창·락 점유시간"은 SELECT+COUNT 합산 실측이다.
- **populate 창 = 로더 전 구간 실측(DR-1·DR-3, ≠ 내부 SELECT):** 만료창 herd 크기와 락 점유시간을 좌우하는 "오리진 populate 창"은 인용된 단건 SELECT ≈12.7ms가 **아니라** 로더 전 구간 = **Hikari 커넥션 체크아웃 대기 + SELECT + COUNT(\*) + 결과 직렬화**의 *부하 하 실측 p99*다. COUNT는 카테고리당 ~20만 인덱스 엔트리를 세어 단건 SELECT보다 비쌀 수 있고, 스탬피드 동시부하에선 홀더 자신도 커넥션풀 체크아웃 대기를 겪는다. **12.7ms로 핀하면 herd·락리스가 과소추정**돼 거짓 수렴(DR-3) + 락리스 거짓통과(DR-1)를 부른다. 그래서 herd C 핀(축A)과 락리스 불변(Step C·M5)은 **이 전 구간 실측 p99**를 기준으로 핀한다(Q1·Q3 결선).
- **공동 1차 구조 지표 A2(v3 vs v4 분리축·정식 승격) = 만료 전환창 대기 큐 깊이 = 그 1회 재계산을 기다려 *블록된 요청 수*.** 재계산 수만으로는 **v3·v4가 둘 다 만료창당 ≈1로 붕괴**해(v3 single-flight도 1, v4 논리만료+CAS 단일 refresh도 1) 사다리 칸이 합법 축에서 반증 불가다. 둘을 가르는 신호는 **대기한 요청의 *수*(v3=C-1 vs v4≈0)** — 이는 지연 분포(참조 전용·라인274에서 봉인)가 *아니라* **블록 *카운트*(구조량·캐시 불변·부하 잡음 무관)**다(시간≠개수). 카운터를 **single-flight 락 대기/블록 진입점**(홀더가 아닌 요청이 대기에 들어가는 지점)에 심어 만료창당 블록 수를 센다. v2=0(락 없음·전원 herd), v3=C-1(herd를 직렬 대기열로 흡수), v4≈0(유효/스테일 캐시 즉시 반환, 대기 없음). 이게 **v4 칸의 존재 이유(대기열 제거)를 합법 구조 축에서 측정**한다.

### M2 — 핀 단일 소스 + 전 버전 일치 + 부팅 assert
- 통제변수를 `CachePins`(상수 클래스, 측정 하네스 핀 표와 1:1)로 고정하고 **전 버전 일치**:
  - TTL 기준값(`TTL_BASE_MS`), 지터 범위(`TTL_JITTER_RATIO`), 락 타임아웃(`LOCK_TIMEOUT_MS`)·대기(`LOCK_WAIT_MS`), v4 논리만료 리드타임/스테일 허용창(`STALE_WINDOW_MS`), **핫 키 카디널리티**(`HOT_CATEGORIES` + `PAGE`/`SIZE`), 동시성 수준, 페이로드(page1 size=20), 워밍 상태, 널 TTL(`NULL_TTL_MS`), **Hikari 풀 크기**(`HIKARI_MAX_POOL_SIZE`, DR-POOL-1 — 버스트 pending 판정·전 구간 p99가 풀 크기에 종속).
- `CachePinsBootAssert`(`@EventListener(ApplicationReadyEvent.class)`)가 실제 캐시 빈/Redis 클라이언트 설정·전용 Redis 포트(6380)·키 prefix를 `CachePins` 기대값과 대조 → 불일치 시 `IllegalStateException`으로 **기동 실패**. 런타임 재대조는 하네스가 측정마다(이중 게이트, phase4-1 계승).
- 사다리 내 **단일 변수 = 방어 기법만 차이**.

### M3 — 콜드/만료 전환점 측정 (워밍 함정, 3-7 R3 계승)
- 캐시가 *이미 따뜻한* 상태로 측정하면 스탬피드가 안 보인다. 부하 모델은 **동시 미스를 의도적으로 유발**해야 한다:
  - **스탬피드:** 핫 키를 강제 만료(`POST /cache/expire?key=`)시킨 직후 C개 동시 요청을 같은 키에 발사 → 만료창의 오리진 재계산 수를 센다. **herd 크기는 비용 의존적**이다 — look-aside는 미스 후 로더 진입이므로 herd 크기 = "오리진 populate 창(≈12.7ms) 안에 도착해 미스하는 요청 수"에 비례하고, populate 완료 후 도착분은 적중→미스 안 함. 그래서 C개 동시성을 **서버측 배리어(ops `POST /cache/ops/barrier`, A-6)로 동기 release**해 v2 herd가 ≈C로 관측되게 한다. **미동기·소규모 C의 일반 k6 램프면 도착이 창 밖으로 흩어져 v2 herd≪C → "v2가 사실 스탬피드 안 함"으로 거짓 수렴**한다 — 「충분히 큰 C」 단독은 요청 *수*만 보장하고 13ms 창 내 도착 *타이밍*은 보장 못 하므로 배리어가 정본 동기화 수단이다(엔드포인트 없이 서버측 배리어 불가 → A-6에 mechanism으로 명시, DR-A). (Q1의 더 비싼 오리진=인덱스 미적용 토글은 populate 창을 *넓혀* 도착 산포 허용폭을 키우는 직교적 진폭 보강 — 동기화 정본은 여전히 배리어.)
  - **어밸런치:** 다수 키를 **동일 TTL로 일괄 워밍**한 뒤 동시 만료 시점에 광역 부하 → 만료창 전체 오리진 재계산 스파이크. **단 이것이 합법 카운트 축이 되려면 각 키의 populate 창 안 도착이 >1이어야 한다**(per-key herd>1 — 축A 배리어·축B 동시성 핀과 같은 조건). 동시성이 키 모집단 대비 낮으면 키별 herd~1로 v2·v5가 재계산~1로 붕괴하고 차이는 *봉인된 시간 분포*만 남는다 → 그 경우 어밸런치는 참조 전용으로 격하(DR-AVAL-1·축C·Q10).
  - **페네트레이션:** 미존재 카테고리로 지속 부하 → 매 요청 오리진 직격 여부. **빈 Page는 null이 아닌 적재 가능 값**이므로 직격은 v2의 **`EMPTY_AS_MISS=true`(빈/널=미스 취급, 적재 안 함)** 선택에 의존 — v2가 빈 결과를 적재하면 페네트레이션이 사라진다(Step B·축 B 참조).
- 이게 빠지면 측정이 주제를 못 건드린다 — "캐시 적중 빠름"만 재고 끝나는 거짓 수렴.

### M4 — baseline 정합 (measurement-baseline-code-audit 교훈)
- v1/v2가 "캐시 없이도 baseline 상태를 유지하는 근거"를 **코드로 전수 확인**해 적는다:
  - 대상 경로에 **다른 캐시 계층이 없나** — `ProductService`에 `@Cacheable` 없음(확인), Redis 주입 없음(확인).
  - **Hibernate 2차 캐시/쿼리 캐시가 꺼져 있나** — 켜져 있으면 오리진 재계산이 ORM 캐시에 흡수돼 카운터가 거짓 0을 보고한다(**랭킹 v1 인덱스 오염과 동형의 함정** — 문서 리뷰로는 못 잡음). `hibernate.cache.use_second_level_cache`/`use_query_cache` = false 확인을 **부팅 assert/측정 전 게이트**에 넣는다(Q6).
  - **재계산 '수'를 흡수·오염할 다른 클래스(Hibernate L2 외, 이번 라운드 미전수확인 — AD-6):** (a) Spring Data `Pageable`의 **count 쿼리**가 별도로 카운트되면 herd당 재계산이 2로 부풀 수 있다 — 카운터 진입점이 count 쿼리를 포함하지 않는지 코드로 격리 확인. (b) 전용 Redis 직렬화·커넥션 풀 대기가 '오리진 비용'으로 카운터 진입점에 오염되지 않는지 확인(Open Questions 자기-언급 해소). (c) 본체 vs 전용 스택의 `application.yml` override가 L2 설정을 다르게 줄 수 있다 — **전용 스택 yml의 hibernate cache off가 본체와 동일한지 boot assert로 이중 확인**. 미해소 흡수 클래스는 carry_forward 누적(Open Questions).
  - **복합 인덱스의 영향:** 인덱스 `(category, created_at)`는 오리진 쿼리를 *싸게*(≈12.7ms) 만들지만, **per-request 재계산 "수"(1 미스=1 재계산)는 인덱스와 무관**하다 — 인덱스는 증폭의 *지연 서명*만 누그러뜨린다. 그래서 1차 지표를 latency가 아니라 **재계산 수**에 둔다(M1). **단 만료창 herd 크기(동시 미스 수)는 populate 창(≈12.7ms)에 비례해 비용 의존적**이다 — "herd 카운트가 인덱스 무관"은 per-request엔 참이나 *만료창 herd엔 거짓*이니, herd 관측은 **배리어 또는 충분히 큰 C로 창 의존성을 통제**한다(M3 스탬피드·축A 배리어 핀). (오리진이 싸서 *지연* 증폭은 muted일 수 있음 — design-review가 "구조 카운트로 충분한가"를 검증, Q1.) **인덱스 상태 게이트(AD-1):** 인덱스 존재는 code-confirmed 불가한 DB 상태다(현재 라이브 DB엔 부재 · scripts가 토글) — 측정 전 게이트가 `SHOW INDEX FROM product`로 `idx_product_category_created` 상태를 확인·기록한다. 인덱스 부재로 오리진이 수백 ms로 떨어지면 herd·락리스 핀이 *조용히* 어긋나므로(전 구간 실측 p99가 인덱스 상태에 종속), 인덱스 부재를 의도하면 Q1 비싼-오리진 변종으로 *명시·핀*하고 정상 baseline이면 인덱스 존재를 게이트로 강제한다.

### M5 — 측정 유효성 게이트(수렴) + 자가 검증
- 캐시·비동기 갱신(v4)이 끼면 "부하 종료 직후" 판정이 거짓이 된다(백그라운드 갱신 진행 중). **수렴 게이트는 축 성격별로 적용 범위가 갈린다(DR-GATE-1 — 정상상태 게이트를 버스트축에 무차별 적용 금지):**
  - **정상상태 비교축(hit-path 정상 부하·v4 백그라운드 갱신 정착):** 재계산 카운터 연속 안정 && 캐시 워밍 상태 도달 && **비포화**(`http_req_failed≈0`, Hikari `pending==0`, statement-timeout 0) && 타임아웃 내. 수렴 후 판정.
  - **스탬피드·어밸런치·페네트레이션 버스트축(축A·A2·B·C, transient):** herd는 정상상태가 아니라 일시 transient이고, **핀된 Hikari pool=10(over-provision 금지)에서 C>10이면 C-10개 로더가 커넥션 체크아웃에서 대기 → 버스트 동안 Hikari `pending>0`는 필연이자 정상 신호**다(herd가 풀 대기를 유발함을 인정). 따라서 버스트축엔 정상상태 '비포화·연속 안정'을 적용하지 **않고**, 범주가 다른 **버스트 적합 유효성**으로 판정한다: (i) 만료창 내 재계산 델타가 기대값에 수렴(v2≈C herd·v3/v4≈1·축B 창당≤herd), (ii) 런이 타임아웃 내 종료(무한 대기 없음), (iii) `http_req_failed`는 폴백/오류 기준(statement-timeout *실패*는 INVALID지만 풀 체크아웃 *대기* 자체는 INVALID 아님). 이 분리를 명문화해 **구현자가 버스트축에서 게이트를 임의 완화하는 것을 막는다**(완화가 아니라 애초에 적용 범주가 다름). **축C 단서(DR-AVAL-1):** 위 (i) '재계산 델타 기대값 수렴'은 축A·A2·B엔 도착-동기화(배리어/동시성 핀)로 per-key herd>1이 보장돼 합법 카운트지만, **축C(어밸런치)는 배리어가 없어 per-key herd>1이 보장될 때만**(총 동시성 ≥ 키 모집단 × herd) 합법 카운트로 유효하다 — 보장 못 하면 v2·v5가 키별 재계산~1로 붕괴해 차이가 봉인된 시간 분포만 남으므로 축C는 합법 카운트 축에서 빠지고 참조 전용으로 격하(자가검증 (c) 배리어-없는 램프 가드는 축A 전용이라 축C 붕괴를 잡지 못함 — 축C·Q10).
- **락 리스 불변 게이트(v3):** 관측 p99 재계산시간 < `LOCK_TIMEOUT_MS` 확인(위반 시 홀더-생존 리스만료→이중 재계산 → abort). Q1의 비싼 오리진 채택 시 이 불변으로 락 리스 재핀(AD-5).
- 핀은 ops `/cache/pins` 런타임 assert + 전용 Redis 격리 라벨 assert로 강제(불일치 시 abort).
- **자가 검증 3건(phase4-1 계승 + DR-A):** (a) 고의 핀 불일치(예: TTL 다르게) → abort, (b) **고의 워밍 측정**(콜드/만료 전환을 안 잡고 따뜻한 캐시로 측정) → herd가 안 보임을 *검출*해 "측정이 주제를 못 건드림"을 드러냄(M3 워밍 함정의 liveness 증거), (c) **고의 배리어-없는 비동기 램프**(`/barrier` 미사용·도착 산포) → v2 herd≪C로 *과소관측*됨을 *검출*해 "도착 산포로 인한 herd 과소관측"을 드러냄(DR-A liveness 가드). (b)는 *워밍* 함정, (c)는 *도착 타이밍* 함정 — 둘은 다른 거짓수렴 원인이라 각각의 가드가 필요.

### M6 — 규모·경험값은 단정 말고 실측·정본 인용
- 핫 키 5개(카테고리×page1)는 **스탬피드·페네트레이션엔 충분**하나 **어밸런치(다수 키 동시 만료)엔 너무 적을 수 있다** — 어밸런치 데모는 키 모집단 확장(카테고리×페이지 범위)이 필요할 수 있음(Q10). 재계산 비용·키 수·필요 동시성은 a priori로 박지 말고 실측/「비준 미결 질문」으로.

---

## Step A: 공통 인프라 — 캐시 추상화·핀·부팅 assert·재계산 카운터·전용 Redis·ops 표면

> 측정 유효성을 먼저 깐다. "캐시 적중"과 "오리진 재계산"이 분리돼 측정을 깨뜨리기 쉽다(phase4-1의 "발행 ≠ 전달", 3-4의 "202는 접수지 발급 아님"과 동형 함정) — 수치를 내기 전에 그 수치를 신뢰할 상태부터 만든다.

### Restrictions
- `Product` 엔티티·`ProductRepository`·`ProductService`·`ProductController` 수정 금지(재사용만). 캐시는 이들을 **감싸는** 신규 빈으로.
- 엔티티·관계·테이블 추가 0. 캐시는 전용 Redis(6380)·전용 키스페이스에만.
- 캐시 빈은 책임 분리(캐시 추상화 빈 ≠ 락 빈 ≠ 오리진 로더 ≠ ops 빈).
- Controller → Service → Repository 호출 순서 유지. Controller에서 엔티티 직접 반환 금지(DTO만, `api/product/dto/`).

### A-1. 전용 격리 Redis (측정 스택)
- 측정 전용 compose에 **전용 Redis(포트 6380, 별도 프로젝트명·볼륨)** 추가. 본체 Redis(6379)와 물리 분리.
- **격리 단위 = 인스턴스(빈별 포트 분리 아님).** 코드 확인: `RedisConfig`의 연결 팩토리는 **단 하나**(`redisConnectionFactory`, `spring.data.redis.*`)이고 락/랭킹/큐(`RedisLockRepository` 등)가 이를 공유한다. 한 JVM에서 캐시만 6380·락/랭킹만 6379로 빈별 분리는 **불가**(2nd 팩토리 미존재). 따라서 캐시는 **기존 단일 팩토리를 재사용**하되, 측정 스택에선 그 `spring.data.redis.*`가 6380을 가리켜 스택 전체가 6380에 묶인다(본체 6379와 인스턴스 단위 무접촉). **캐시 전용 2nd 팩토리 신설은 불요.**
- 캐시 키 prefix 핀(예: `cache:product:list:`)으로 6380 *안에서* 키스페이스 격리. boot assert가 연결 포트·prefix 확인.
- `RedisConfig` 주석에 박힌 phase3-4 누수 버그(StringRedisTemplate 6379 하드코딩) 재발 방지 — 캐시 클라이언트는 `spring.data.redis.*`(전용 스택 6380)를 **반드시** 따른다(별도 하드코딩 포트 금지).

### A-2. 핀 단일 소스 — `CachePins`
- 위치(제안): `infrastructure/cache/CachePins.java` 상수 클래스. 측정 하네스 핀 표와 1:1.
- 핀 값(초안 — **비준·실측 시 확정**, Q3):
  - **TTL:** `TTL_BASE_MS = 10_000`(만료 전환을 부하 런 안에서 관측 가능하게 — 초안값), `NULL_TTL_MS = 2_000`(널 캐싱, 짧게).
  - **지터(v5):** `TTL_JITTER_RATIO = 0.2`(±20%).
  - **락(v3):** `LOCK_TIMEOUT_MS = 200`(락 리스/TTL — 홀더 크래시 시 인계 한도, **불변: > 전 구간 실측 p99 재계산시간**, 부팅 assert·수렴 게이트 강제, Q3), `LOCK_WAIT_MS = 500`(비홀더 단일 비행 대기 한도, 초과 시 폴백 — Q4). **LOCK_WAIT 불변(DR-4): `LOCK_WAIT_MS > 홀더 완료 전 구간 실측 p99`** — 대기 예산이 홀더의 재계산 완료보다 짧으면 비홀더가 완료 전 폴백 발화 → 폴백이 '직접 재계산'이면 herd 회귀(v3 재계산>1), '503/스테일'이면 다른 의미. **두 불변은 한 쌍**(`LOCK_WAIT_MS ≥ LOCK_TIMEOUT_MS ≥ 전 구간 p99`)으로 부팅 assert·수렴 게이트에 강제하고, Q1 비싼 오리진 채택 시 **함께 재핀**(Q3·Q4).
  - **v4(논리만료+비동기갱신):** `STALE_WINDOW_MS`/논리만료 리드타임(스테일 허용창·갱신 트리거 임계 — 측정으로 핀, Q7). 갱신은 **CAS/플래그 단일 refresh 가드**로 키당 1회만 발화(재계산=1·블록≈0). (`PER_BETA`는 제외된 락 없는 PER 변종 전용 — 본 phase 미사용, DR-B.)
  - **핫 키:** `HOT_CATEGORIES = [전자기기, 의류, 식품, 도서, 스포츠]`, `PAGE = 0`, `SIZE = 20` → 핫 키 5개.
  - **빈/널 적재 정책(페네트레이션 단일 변수):** `EMPTY_AS_MISS`. 빈 Page는 null이 아닌 **적재 가능한 합법 값**이라 적재 여부가 페네트레이션을 가른다 — v2 = `true`(미스 취급·적재 안 함 → 직격 노출), v5 = `false`(짧은 `NULL_TTL_MS`로 적재 → 흡수). 이 정책이 축 B의 단일 변수(Step B·E·축B).
  - **스탬피드 동기화:** `STAMPEDE_CONCURRENCY`(C)와 **서버측 배리어 동시 release**(ops `POST /cache/ops/barrier`, A-6) — herd가 비용 의존적(populate 창)이라 동기 도착을 *메커니즘으로* 보장해야 핀이 유효(M3·M4·축A·Q1). 「큰 C」 단독은 요청 *수*만 보장·도착 *타이밍* 미보장이라 보조일 뿐, 정본 동기화는 배리어다(DR-A).
  - **페네트레이션 동시성:** `PENETRATION_CONCURRENCY`와 발사 정책(배리어 동기 vs 순차) — v5 널 적재 *전* 창의 herd 크기를 결정하므로 핀 필수(축 B 게이트 = 창당 ≤herd → 이후 0, AD-2).
  - **커넥션 풀(DR-POOL-1):** `HIKARI_MAX_POOL_SIZE = 10`(over-provision 금지). 버스트축 유효성('C>10이면 pending>0 정상', M5·축 버스트 게이트)과 전 구간 실측 p99 기반 락 리스/대기 불변(M1·Step C)이 *모두* 풀 크기에 의존한다(전 구간 p99 = 커넥션 체크아웃 대기 + SELECT + COUNT + 직렬화 → 풀 크기가 체크아웃 대기 분포를 좌우) — 그래서 핀 단일 소스에 포함하고 본체 vs 전용 스택 yml 동일. 미핀 시 풀 크기가 기본값에서 드리프트해 락 불변 마진·'버스트 pending>0=정상' 기준이 조용히 어긋남.
  - **격리:** `CACHE_REDIS_PORT = 6380`, `KEY_PREFIX = "cache:product:list:"`.
- **통제변수 규율:** TTL 기준·핫 키 카디널리티·동시성·페이로드를 전 버전 일치. 사다리 내 단일 변수 = **방어 기법만 차이**.

### A-3. 부팅 핀 assert — `CachePinsBootAssert`
- `@EventListener(ApplicationReadyEvent.class)`에서 다음을 대조 → 불일치 시 `IllegalStateException`으로 **기동 실패**(phase4-1 `EventPinsBootAssert` 패턴 계승):
  - 실제 캐시 빈/Redis 클라이언트 설정(연결 **포트=6380**·키 prefix), Hibernate 2차/쿼리 캐시 off(M4·Q6), 핀 상수 정합.
  - **격리 경계(AD-2): `spring.datasource.url`=본체 3306, `spring.data.redis.port`=6380** — datasource가 6380쪽/별도 MySQL을 가리키거나 redis가 6379면 기동 실패(우발적 전용 MySQL·본체 Redis 직격 차단).
  - **캐시 직렬화 라운드트립 self-check(DR-2): 샘플 값 적재→재로드→동치** 실패 시 기동 실패(PageImpl/no-arg 결함을 부팅에서 잡음).
  - **Hikari 풀 크기(DR-POOL-1): 실제 `DataSource`(`HikariDataSource`)의 `maximumPoolSize`가 `CachePins.HIKARI_MAX_POOL_SIZE`(=10)와 일치** — 불일치 시 기동 실패. 전용 스택 yml의 풀 크기가 기본값에서 드리프트하거나 본체와 달라지면 herd의 커넥션 경합 프로파일·전 구간 p99가 조용히 바뀌어 락 불변 마진과 '버스트 pending>0=정상' 기준이 어긋나므로(에러 없이 통과), **본체 vs 전용 yml 풀 크기 동일성도 boot에서 이중확인**(AD-6 흡수-클래스 점검과 동일 위치).

### A-4. 오리진 로더 + 재계산 카운터 — `OriginRecomputeCounter`
- 위치(제안): `infrastructure/cache/OriginRecomputeCounter.java`. 키별 `AtomicLong` 맵(또는 Micrometer 카운터). 오리진 로더(`Supplier`)가 실제 repo 쿼리를 실행하는 진입점에서만 증가(M1).
- 오리진 로더는 기존 `ProductService.getProducts`(또는 `ProductRepository`) 호출을 **감싸는** 람다 — v1 경로 재사용, 미수정.
- **블록 카운터(2차 구조 지표·축 A2):** single-flight 락 대기 진입점에서 증가하는 **별도 카운터**(키별 `AtomicLong`, `OriginRecomputeCounter`와 분리 또는 형제 빈). 대기 *시간*이 아니라 대기 *요청 수*를 세어 v3(C-1) vs v4(≈0)를 가른다. v2/v4는 락 대기가 없어 ≈0.

### A-5. 캐시 추상화 — `LookAsideCache` (전략 슬롯)
- 위치(제안): `infrastructure/cache/LookAsideCache.java`. `get(key, loader)` 단일 진입 + 방어 전략 슬롯(고정TTL / single-flight / 논리만료+비동기갱신 / null+jitter).
- **수제 look-aside로 결정**(Spring `@Cacheable` 아님 — Q5): `@Cacheable`은 single-flight·논리만료+비동기갱신·널 캐싱·재계산 카운터 주입을 깔끔히 못 한다(어노테이션 뒤에 가려짐). 측정 통제(카운터 진입점·락 결선·스테일 제공)를 위해 명시적 추상화로 둔다.
- **캐시 값 형태 = 직렬화 안전 형태(DR-2, 필수):** 오리진 로더가 감싸는 `ProductService.getProducts`의 자연 반환형은 **`Page<ProductListResponse>`(`PageImpl`)**인데, `PageImpl`은 Jackson 안정 라운드트립 계약이 없고 `ProductListResponse`도 **no-arg 생성자가 없다(코드 확인)** → `GenericJackson2JsonRedisSerializer`로 적재한 값을 **역직렬화하다 실패**한다. 그래서 캐시에 적재하는 값은 `PageImpl`이 아니라 **직렬화 안전 형태**로 변환해 넣는다: 전용 캐시 레코드(예: `record CachedProductList(List<ProductListResponse> items, long total){}` — no-arg/Jackson 계약 보장) 또는 `List<ProductListResponse> + total`. 응답 경계에서 다시 `Page`로 복원(`PageImpl(items, pageable, total)`). DTO에 Jackson 계약(`@JsonCreator`/no-arg)을 부여하든 전용 레코드를 쓰든 **둘 중 하나는 필수** — 미적용 시 적재값이 안 읽혀 캐시가 영영 미스.
- **역직렬화 실패 = 조용히 흡수 금지(DR-2 silent 함정):** 역직렬화 예외를 catch해 '미스'로 떨어뜨리면 **매 요청이 재계산**돼 전 버전이 herd처럼 보이고 "적중 시 오리진 0" baseline 축이 *조용히* 오염된다(랭킹 v1 인덱스 오염 동형). 캐시 직렬화 라운드트립은 **부팅 self-check(적재→재로드→동치)** 로 검증하고, 측정 중 역직렬화 실패는 **미스로 흡수하지 말고 측정 abort**(M5 자가검증에 추가).
- 직렬화기 자체는 기존 `RedisConfig`의 `GenericJackson2JsonRedisSerializer` 계열을 전용 스택 클라이언트로 재사용하되, **위 값 형태 변환을 전제로**.

### A-6. ops 표면 — `ProductCacheOpsController`
- 위치(제안): `api/product/ProductCacheOpsController.java`(`/api/cache/ops`) — 측정 하네스 전용.
- `GET /pins` — TTL/지터/락/논리만료(스테일창)/핫키/격리 핀 **런타임 실측값**(설정 echo 아니라 실제 빈/클라이언트에서 읽음). `cacheRedisPort`·`keyPrefix` 노출.
- `GET /recompute` — `OriginRecomputeCounter` 키별 누적(부하와 무관한 구조 1차 지표 소스).
- `GET /blocked` — single-flight 락 대기에 **블록된 요청 수** 키별 누적(M1 2차 구조 지표·축 A2, v3 vs v4 분리). 재계산 카운터와 분리된 별도 카운터 — 대기 *시간*이 아니라 대기 *개수*(v2=0, v3=C-1, v4≈0).
- `POST /expire?key=` / `POST /evict?key=` — 핫 키 강제 만료/축출(스탬피드·어밸런치 전환점 유발, M3). **시맨틱은 버전군별로 갈린다(단일 시맨틱으로 v3·v4를 동시 측정 불가):** 하드TTL군(v2·v3)에선 `/expire`=물리 DEL(키 소멸 → 콜드 미스 유발); 논리만료군(v4)에선 `/expire`=**논리만료 마킹(물리 값 보존)** — v4는 유효/스테일 값이 물리적으로 존재해야 동작하므로 DEL로 값을 지우면 v4도 콜드 미스로 전락해 v3와 구별 불가가 된다. ops가 버전 파라미터로 시맨틱을 분기.
- `POST /warm` — 다수 키 동일 TTL 일괄 워밍(어밸런치 셋업).
- `POST /barrier?key=&n=` — **만료창 동기 도착 *arming* 전용(DR-A·DR-A2-1 — 축A·축A2의 전제). 부하·로더를 받지 않는다.** k6 램프는 도착 *타이밍*을 보장 못 한다(첫 1개가 populate 창 ≈12.7ms 안에 캐시를 채우면 이후 도착분은 적중→herd≪C·block≪C-1로 과소관측 → "v2가 사실 스탬피드 안 함"·"v3 대기열 작음" 거짓 수렴). **`/barrier`는 키 `key`에 대해 기대 도착 수 `n`으로 서버측 latch(`CyclicBarrier`/`CountDownLatch`)를 *무장(arm)*만 한다 — 요청 부하를 이 엔드포인트로 보내지 않는다(이 경로는 어떤 버전 로더도 호출하지 않으므로 카운터에 무영향, version 파라미터 불요).** 실제 C(=n)개 부하는 **버전별 엔드포인트 `/api/cache/v{n}/products`로** 발사하고, 각 요청은 **`ProductListCacheServiceV{n}`의 캐시 get 진입점에서 무장된 latch에 check-in**해 n개가 모일 때까지 블록하다 **동시 release되어 같은 만료창에 동기 진입**한다. 게이팅이 버전별 캐시 get *안*에서 일어나므로 재계산/블록 카운터가 v{n}에 정상 귀속된다(line 105 카운트 위치 계약과 정합). 「충분히 큰 C」 단독은 요청 *수*만 보장하고 *타이밍*은 보장 못 하므로 latch arming이 정본 동기화 수단(엔드포인트 없이 서버측 latch는 불가 — 이 표면에 mechanism으로 명시).
- `GET /state?key=` — 키별 TTL 잔여·존재·널 여부(관찰).

### Step A Build Check
- `./gradlew compileJava`. 기동 시 `CachePinsBootAssert`가 핀·격리·2차캐시off 검증(불일치면 기동 실패).
- `bootRun`/테스트는 사람이 런타임 검증(템플릿 규율).

### Step A Verification (human)
```
전용 스택 기동 → GET /api/cache/ops/pins
→ cacheRedisPort=6380, keyPrefix="cache:product:list:", TTL_BASE_MS=핀값 echo
본체 Redis(6379)에 cache:product:list:* 키 없음 (격리 확인)
GET /api/cache/ops/recompute → 초기 0
```

---

## Step B: 캐시 v2 — look-aside + 고정 TTL (결함 노출 baseline)

### 설계 의도
가장 단순한 캐시 — 키 미스 시 오리진 로더 실행 → 결과를 **고정 TTL**로 적재, 적중 시 캐시 반환. "캐시 얹었으니 빨라짐"의 직관을 세우고, **만료 전환점·미존재 키·동시 만료**에서 오리진이 오히려 증폭/직격되는 구조적 한계를 노출해 v3~v5의 근거를 만든다.

### 컴포넌트 (제안)
- `service/product/cache/ProductListCacheServiceV2.java` — `LookAsideCache.get(key, loader)`, 전략=고정TTL. 락 없음.
- `api/cache/ProductCacheControllerV2`(또는 통합 컨트롤러 vN 파라미터) — `GET /api/cache/v2/products?category=&page=&size=`(경로 확정 Q2).

### 못 하는 것 / 노출하는 것 (고치지 않음)
- **스탬피드:** 핫 키 TTL 만료 직후 C개 동시 미스 → C회 오리진 재계산(herd). 재계산 카운터가 만료창당 ≫1을 *기록*.
- **페네트레이션:** 미존재 카테고리는 `findByCategoryOrderByCreatedAtDesc`가 **null이 아니라 빈 Page**(적재 가능한 *합법 값*)를 반환한다(코드 확인) — 그대로 적재하면 첫 1회 후 적중돼 페네트레이션이 *안* 보인다. 따라서 **v2는 빈/널 결과를 캐시하지 않고 미스로 취급**(`EMPTY_AS_MISS=true` 핀)해 매 요청 오리진 직격을 *노출*한다. **이 페네트레이션 축은 v2의 적재 정책 선택에 의존**(자동 사실 아님 — "미존재 키=적재할 게 없음"은 빈 Page가 null이 아니라 자동이 아니다). v5가 닫는 자리(널 캐싱).
- **어밸런치:** 동일 TTL 일괄 워밍 키들이 동시 만료 → 광역 herd.
- 닫으려면 → v3(single-flight)·v4(논리만료+비동기갱신)·v5(null+jitter).

---

## Step C: 캐시 v3 — single-flight / mutex (thundering herd 차단)

### 설계 의도
미스 시 **키당 하나만 오리진을 재계산**하게 한다(분산락 또는 로컬락). 동시 미스 중 하나가 락을 잡아 재계산·적재하고, 나머지는 **락 대기 후 캐시 적중(블록)**. 만료창당 재계산을 1로 닫는다. (스테일 제공은 v3 정체성이 아니다 — v4 전용. v3 비홀더는 '순수 블록'으로 고정해야 축 A2의 v3=C-1이 성립한다: 스테일 사본을 끼우면 블록이 0으로 떨어져 v3≈v4 거짓 수렴.)

### 핵심 설계 + 새 실패면 (design-review가 적대 검증할 자리)
- 락 매체: **분산락(전용 Redis SET NX, 기존 `RedisLockRepository` 재사용 검토)** vs **로컬 JVM 락** — Q4. 단일 인스턴스 측정이라도 계약을 고정.
- **락을 넣어 herd를 닫았는데 락 자체가 새 직렬화·새 실패면을 들인다:**
  - **락 타임아웃 폴백:** `LOCK_WAIT_MS` 초과 시 — 스테일 제공? 직접 재계산(폭주 회귀)? 503? **정책을 핀으로 고정**하고 측정. **단 불변 `LOCK_WAIT_MS > 홀더 완료 전 구간 p99`(DR-4)가 깨지면** 정상 부하에서도 폴백이 상시 발화해 v3 게이트(재계산≈1)가 무너진다 — 폴백은 *예외 경로*여야 한다(불변 위반 시 abort, M5).
  - **락 홀더 크래시:** 재계산 중 홀더가 죽으면 락 TTL까지 대기 — 누가 이어받나(락 TTL = `LOCK_TIMEOUT_MS` 계약).
  - **락 점유 범위(DR-1, single-flight 정의):** 락은 **오리진 로더 전 구간**(Hikari 커넥션 체크아웃 대기 + SELECT + COUNT + 직렬화 + 적재)을 가로질러 잡혀 있어야 single-flight다. 락을 내부 SELECT만 감싸면 체크아웃 대기·COUNT·직렬화 동안 비홀더가 미스로 새 재계산을 시작한다.
  - **락 리스 불변(`LOCK_TIMEOUT_MS` > 재계산시간 — '재계산시간'=전 구간 실측):** 여기서 "재계산시간"은 내부 SELECT(≈12.7ms)가 아니라 **위 락 점유 전 구간의 부하 하 실측 p99**(M1 populate 창과 동일 정의)다. 리스가 이 전 구간보다 짧으면 홀더가 살아있는데 리스가 먼저 만료 → 다른 요청이 락 획득 → **이중 재계산**(v3 재계산 카운터가 1이 아니라 2+) → 게이트가 조용히 깨진다. **불변: `LOCK_TIMEOUT_MS > 전 구간 실측 p99`를 부팅 assert(`CachePinsBootAssert`)·수렴 게이트(M5)에 강제(위반 시 abort)** — 부하 하 실측이라 부팅 시점엔 핀 보수값으로 가드하고 수렴 게이트에서 실측 재대조. Q1에서 '더 비싼 오리진'(인덱스 미적용 토글 등)을 채택하면 전 구간이 수백 ms로 늘 수 있으므로 락 리스를 그에 맞춰 **재핀**(Q3·Q4 결선).
  - **락 입도:** 키당 1락(전역락 금지 — 전역락은 무관한 키까지 직렬화).
  - **분산락 격리:** 측정 스택의 단일 연결 팩토리가 6380을 가리키므로 락도 그 6380에 산다(인스턴스 격리) — 본체 6379 락 키스페이스와 무접촉. (캐시만 6380·락만 6379 식 빈별 분리 아님.)

### 못 하는 것
- single-flight라도 **그 1회 재계산을 기다리는 대기열**은 남는다 — 만료 전환점의 지연 스파이크는 여전(→ v4).
- 페네트레이션엔 무력(미존재 키는 락 잡아도 적재할 게 없음 → 매번 재계산, Q8/v5에서).

---

## Step D: 캐시 v4 — 논리 만료 + 비동기 갱신 (CAS 단일 refresh, 만료 전환 스파이크 완화)

### 설계 의도
만료창의 **직렬 대기 자체**를 없앤다 — 이 효과의 합법 측정축은 **만료 전환창 대기 큐 깊이(블록 요청 수, M1 2차 구조 지표·축 A2)**다(v3=C-1 → v4≈0). **재계산 수는 v3·v4 둘 다 ≈1이라 이 축으론 안 갈린다** — 그래서 v4의 가치는 재계산 수가 아니라 *블록 수*로 입증한다(지연 분포는 참조 전용·봉인). v4 메커니즘은 **논리 만료 + 비동기 갱신**으로 고정한다(Q7·DR-B):
- **논리 만료 + 비동기 갱신(채택):** 물리 TTL은 길게, 페이로드에 논리 만료 타임스탬프. 논리 만료 후 첫 요청이 **스테일을 즉시 반환**하면서 백그라운드로 갱신 트리거하되, **갱신은 CAS/플래그(단일 refresh 가드)로 키당 하나만** 발화한다 → 재계산=1(축A) · 블록≈0(축A2)이 *둘 다* 성립.
- **락 없는 고전 PER은 제외(DR-B):** PER은 각 요청이 독립적으로 `now - delta*beta*ln(rand) ≥ expiry`를 굴려 *락이 없다* — 배리어로 C개를 PER 윈도에 동시 발사하면 기대 **C·p개가 동시에 true를 굴려 여러 개가 인라인 재계산**을 시작한다(미니 herd) → **v4 재계산이 1이 아니라 여럿**으로 찍혀 'recompute≈1'(M1·표) 주장을 시각적으로 반증한다. 채택하려면 PER 트리거에 single-flight 가드를 더해야 하나 이는 '한 칸만 변경' 규율 위반 → 본 phase는 PER을 백로그로 두고 v4를 **논리만료+CAS 단일 refresh**로 고정한다.

### 핵심 설계 + 트레이드오프
- **측정 트리거 경로(축 A2 분리 — '동일 셋업' 아님):** v4의 블록≈0은 **값이 물리적으로 존재하는 논리만료 전환점**에서만 잡는다 — `/expire`=물리 DEL(하드TTL군 v2·v3)로 값을 지운 직후엔 v4도 콜드 미스로 전락(v3와 구별 불가). v4 측정은 `/expire`=논리만료 마킹(물리 보존) 또는 자연 논리만료 도달을 트리거로 쓴다(ops `/expire` 시맨틱 버전군 분기, 축 A2).
- **갱신 리드타임/스테일 허용창 튜닝:** 논리만료 리드타임이 과하면 만료 전에 너무 자주 재계산(낭비), 약하면 여전히 만료창 herd. **측정으로 핀**(Q7). (제외된 PER의 β도 같은 트레이드오프 축 — 본 phase 미사용.)
- **스테일니스:** "스테일 제공 중 갱신"은 잠깐 옛 데이터를 반환 — 상품목록은 읽기 전용·약한 신선도 요구라 허용 가능(범위 명시). 스테일 허용창을 핀.
- 비동기 갱신은 수렴 게이트(M5)와 상호작용 — "부하 종료 직후" 판정이 거짓(갱신 진행 중) → 수렴 후 판정.

### 못 하는 것
- 페네트레이션·어밸런치는 별도 축(→ v5). v4 논리만료+비동기갱신은 *존재하는 핫 키*의 만료 전환만 다룬다.
- **물리 콜드 미스에는 single-flight 보호가 없다(DR-V4COLD-1, 명문화).** v4의 재계산=1·블록≈0은 **유효/스테일 값이 물리적으로 존재**할 때만 성립한다(축A2). v4엔 single-flight 락이 없어, **물리 콜드 미스(값 자체 부재)에서 C개가 동시 도착하면 흡수할 스테일이 없어 전원이 인라인 로더로 떨어져 herd(재계산=C)로 *조용히 회귀*** 한다. 따라서 **제약: v4는 어떤 축에서도 물리 DEL/`/evict`로 측정하지 않는다.** 이를 (i) ops `/expire`·`/evict`의 버전군 분기(논리만료군 v4 = 논리만료 마킹·물리 보존, A-6·line 191)와 (ii) 측정 게이트가 v4 측정 트리거를 '논리만료 도달(물리 값 존재)'로만 허용(축A2)하는 것으로 강제한다. 향후 어밸런치/페네트레이션에 v4를 편입하거나 콜드 경로에 single-flight를 결합할지는 **백로그 결정**(Open Questions, '한 칸만 변경' 규율과 상충 검토). 본 phase v4는 물리 콜드 herd 클래스를 *측정 범위에서 배제*함으로써 'recompute≈1'(M1·표) 주장을 보존한다.

---

## Step E: 캐시 v5 — 널 캐싱(또는 블룸) + TTL 지터 (페네트레이션·어밸런치 방어)

### 설계 의도
- **페네트레이션:** 미존재 키 결과(빈/널)를 **짧은 TTL(`NULL_TTL_MS`)로 캐싱**해 반복 직격을 흡수. (또는 블룸 필터로 사전 차단 — Q8.) **널 캐싱은 single-flight와 직교** — 널 적재 *전* 창에 동시 도착한 요청은 전원 미스→직격(첫 만료창 herd), 적재 *후* 도착분만 널 적중. 즉 v5의 페네트레이션 흡수는 '첫 1회'가 아니라 '첫 만료창 herd 후 0'이다(축 B 게이트, AD-2). **단일 변수 = 적재 정책:** v2는 빈/널을 미스 취급(`EMPTY_AS_MISS=true`)해 직격을 노출 → v5는 정반대로 빈/널을 짧은 TTL로 *적재*해 흡수(`EMPTY_AS_MISS=false` 상당). 빈 Page는 null이 아닌 합법 값이므로 이 정책이 페네트레이션 축의 단일 변수다.
- **어밸런치:** 키별 TTL에 **지터(±`TTL_JITTER_RATIO`)**를 더해 동일 시각 일괄 만료를 분산.

### 핵심 설계 + 트레이드오프
- **널 캐싱 정합:** 키가 나중에 생기면? — 짧은 널 TTL로 노출창 한정(상품은 측정 중 불변이라 본 phase에선 무해, 일반화는 무효화 정책 필요 — 범위 밖 명시).
- **블룸 FP:** false positive면 존재하는 키를 미존재로 오판 → 캐시/오리진 우회. 널 캐싱 대비 메모리·정확도 트레이드오프(Q8).
- **지터 범위:** 너무 좁으면 만료 분산 부족(어밸런치 잔존), 너무 넓으면 핫 키 신선도 편차. 범위가 만료를 충분히 분산하나를 **측정으로 확인**.

### 못 하는 것
- 강한 일관성·쓰기 무효화는 범위 밖. v5는 **상시 위생**(트래픽 성격 무관 상존 방어)이지 herd 자체(v3)·만료 대기(v4)의 대체가 아니다 — 직교.

---

## 측정 계획 (to-build)

> 측정 하네스(제안): `scripts/measure/run-product-cache.sh`(normal / stampede / penetration / avalanche / selftest-*), `test/load/product-cache-test.js`(캐시 vN 파라미터화). 결과: `results/phase3-8-cache-stampede/`.

### 측정 유효성 — 수렴 게이트 (M5)
- **정상상태 비교축(hit-path·v4 백그라운드 정착):** 재계산 카운터 연속 안정 && 워밍 상태 도달 && 비포화(`http_req_failed≈0`·Hikari `pending==0`·statement-timeout 0) && 타임아웃 내. 수렴 후 판정.
- **버스트축(축A·A2·B·C, transient):** 정상상태 비포화 대신 **버스트 적합 유효성**(만료창 내 재계산 델타가 기대값 수렴 + 런 타임아웃 내 종료) — 핀된 Hikari pool=10(`CachePins.HIKARI_MAX_POOL_SIZE`·A-2·A-3 강제)에서 herd가 `pending>0`를 내는 것은 정상 신호이지 INVALID 사유가 아니다(DR-GATE-1·M5). statement-timeout *실패*만 INVALID. **축C 단서(DR-AVAL-1):** 축C(어밸런치)의 '재계산 델타 기대값 수렴'은 **per-key herd>1(총 동시성 ≥ 키 모집단 × herd)이 보장될 때만** 합법 카운트로 유효하다 — 보장 안 되면 v2·v5가 키별 재계산~1로 붕괴해 차이가 봉인된 시간 분포만 남으므로, 그 경우 축C는 합법 카운트 축에서 빠지고 참조 전용(축C·Q10).
- 핀은 ops `/pins` 런타임 assert + 전용 Redis(6380) 격리 라벨 assert. 자가 검증 2건(고의 핀 불일치 → abort, 고의 워밍 측정 → herd 미검출 *드러냄*).

### 축 A — 스탬피드 (만료 전환 버스트, 비교 합법 축)
- 핫 키 워밍 → `POST /cache/expire?key=` → **`POST /cache/ops/barrier?key=&n=C`로 latch를 arm한 뒤 C개 부하를 `/api/cache/v{n}/products`로 발사**(각 요청이 버전별 캐시 get 진입점에서 latch에 check-in→동시 release되어 같은 만료창 동기 진입) → **만료창의 오리진 재계산 수**(`GET /recompute` 델타, v{n}에 귀속). **C개 동시성은 서버측 latch arming(A-6 `/barrier`)으로 동기화하되 부하는 버전별 엔드포인트로 보낸다**(M3·M4·Q1·DR-A2-1) — 「큰 C」 단독은 요청 *수*만 보장·도착 *타이밍* 미보장이라, 미동기 램프는 도착이 populate 창 밖으로 흩어져 v2 herd가 관측되지 않는다(거짓 수렴, DR-A). Q1의 비싼 오리진은 창을 넓히는 직교적 진폭 보강.
- **게이트(가설):** v2 = 만료창당 재계산 ≫1(≈C, herd) *기록*; v3/v4 = 만료창당 재계산 **≈1**. **이 대조가 이 phase의 핵심 발견**(phase4-1 "아웃박스 대조"와 동형).

### 축 A2 — 만료 전환 대기 큐 (v3 vs v4 분리, 비교 합법 축)
- **셋업은 버전군별로 갈린다(v3·v4 '동일 셋업' 아님).** v2·v3(하드TTL): 워밍 → `/expire`=물리 DEL → **`/barrier`로 latch arm 후 C개 부하를 `/api/cache/v{n}/products`로 발사(버전별 캐시 get 진입점에서 check-in→동시 release)** → 블록 카운터 델타(v{n} 귀속). v4(논리만료+비동기갱신): 워밍 → `/expire`=**논리만료 마킹(물리 보존)** 또는 자연 논리만료 도달 → **`/barrier` arm 후 C개 부하를 `/api/cache/v4/products`로 발사(동일 게이팅)** → 블록 카운터 델타. **v4의 블록 ≈0 게이트는 'DEL 직후'가 아니라 '논리만료 도달 시(유효/스테일 값이 물리 존재)'에만 합법** — DEL로 값을 지운 직후 발사하면 v4도 콜드 미스→락 대기→블록 C-1을 기록해 v3와 거짓 수렴한다. 블록 카운터는 재계산 카운터와 *별도*(M1 2차 구조 지표).
- **게이트(가설):** v2 = 블록 0(락 없음·전원 herd로 직접 재계산); v3 = 블록 ≈C-1(직렬 대기열); v4 = 블록 ≈0(조기/스테일 제공으로 대기 제거). **v3와 v4는 재계산 수(둘 다 ≈1)로는 안 갈리고 이 블록 수로 갈린다** — v4 칸의 존재 이유를 합법 구조 축에서 반증 가능하게 한다.

### 축 B — 페네트레이션 (미존재 키 플러드)
- 미존재 카테고리로 지속 부하 → 오리진 재계산 수.
- **전제 핀:** v2는 빈/널 결과를 **미스로 취급**(`EMPTY_AS_MISS=true`, 적재 안 함)해야 직격이 보인다 — 빈 Page는 합법 적재값이라 v2가 적재하면 v2≈1로 v5와 대조가 사라진다(거짓 수렴). 단일 변수 = **이 적재 정책**(v2=노출 / v5=흡수).
- **게이트(가설):** v2 = 매 요청 재계산(직격) *기록*; v5(널 캐싱/블룸) = **첫 만료창당 ≤herd(아직 널 미적재 창에 동시 도착분은 전원 미스→직격), 이후 창은 0(널 적중)**. v5 널 경로는 single-flight와 **직교**(별도 칸)라 첫 창 herd를 1로 줄이지 않는다 — '재계산 ≈1'이 아니라 '창당 ≤herd → 이후 0'(M3가 스탬피드에 인정한 populate-창 herd가 페네트레이션에도 동일 성립). 창당 1까지 줄이려면 v5 널 경로에 single-flight를 합쳐야 하나 이는 별도 결합 — 본 phase v5는 흡수만 주장. **페네트레이션 부하의 동시성 정책(배리어 동기 vs 순차)을 `CachePins`에 핀**(배리어 고동시성이면 첫 창 herd≈C, 순차 저동시성이면 herd 작음·스트레스 약함, AD-2).

### 축 C — 어밸런치 (다수 키 동일 TTL 동시 만료)
- `POST /warm`으로 다수 키 동일 TTL 워밍 → 동시 만료 시점 광역 부하 → 만료창 전체 오리진 재계산 스파이크.
- **합법 카운트 축 성립 조건(DR-AVAL-1):** 어밸런치가 v2↔v5를 *합법 카운트*(키별 창당 재계산 상한)로 가르려면 **각 키의 populate 창 안에 도착하는 요청이 >1**이어야 한다(스탬피드 축A가 `/barrier`로, 페네트레이션 축B가 `PENETRATION_CONCURRENCY`로 보장한 per-key herd>1과 같은 조건). 어밸런치는 배리어가 없고 '/warm 동일 TTL→자연 동시 만료+광역 부하'에만 의존하므로, **부하 동시성이 키 모집단 대비 낮으면 키별 herd가 ~1로 떨어져 v2·v5 모두 키별 재계산 ~1**이 된다. 이 경우 v2와 v5의 유일한 차이는 재계산이 *언제* 일어나는가(시간 집중 vs 분산)뿐인데, **이는 line 52·274·관찰값에서 봉인한 시간/지연 분포 축(참조 전용·버전 간 비교 금지)**이다 — 구현자가 이를 합법 카운트로 착각해 '지터가 효과 있다'고 거짓 수렴하면 안 된다(DR-A가 축A에서 닫은 도착-동기화 함정과 같은 클래스이며, M5 자가검증 (c) 배리어-없는 램프 가드는 축A 전용이라 이 축C 붕괴를 잡지 못한다).
- **게이트(가설, 조건부):** **per-key herd>1이 보장될 때만**(= 총 동시성 ≥ 키 모집단 × herd, Q10 규모와 결선) v2(고정 TTL) = 좁은 창에 키별 herd *기록* / v5(TTL 지터) = 만료 분산으로 **키별 창당 재계산 상한**이 합법 카운트로 성립. **per-key herd>1을 보장 못 하면 어밸런치는 합법 카운트 축에서 빼고 '시간-집중 지표(참조 전용)'로 격하**한다 — 이때 v2 vs v5 시간 분포 직접 비교 금지. **키 모집단·필요 총 동시성은 Q10**(per-key herd>1 보장 가능 규모인지 포함).

### 관찰값 (참조 전용, 비교 금지)
- 캐시 적중 p95/throughput, Redis RTT, 락 대기 시간, 만료창 지연 분포. **버전 간 직접 비교 금지**(방어 기법 confound + Redis RTT·런별 잡음). 관찰값은 참조만.
- **주의(시간≠개수):** 락 대기 *시간*·만료창 지연 *분포*는 참조 전용(봉인)이지만, 락 대기에 **블록된 요청 *수*는 봉인 대상이 아니라 축 A2의 합법 구조 지표**(만료창당 카운트, 부하 잡음 무관)다. v3 vs v4는 이 *개수*로 가른다.

---

## 디렉토리 구조 (제안 — 비준 후 확정)
```
docker/
└── (측정 전용 compose에 전용 redis(6380) 서비스 추가 — 별도 프로젝트·볼륨·포트)

scripts/measure/
└── run-product-cache.sh            (normal / stampede / penetration / avalanche / selftest-*)

test/load/
└── product-cache-test.js           (캐시 v1~v5 파라미터화)

results/phase3-8-cache-stampede/
├── analysis.md                     (버전별 재계산 수·스탬피드/페네트레이션/어밸런치 분석 — 1차 출처)
├── INVALID-RUNS.md                 (무효 런 보존)
├── recompute/                      (만료창당 재계산 수 회계 — 1차 지표)
├── stampede/                       (만료 전환 버스트 증거)
├── penetration/                    (미존재 키 직격 증거)
└── avalanche/                      (동시 만료 스파이크·지터 분산 증거)

src/main/java/com/project/
├── api/product/
│   └── ProductCacheOpsController.java       (/api/cache/ops: pins, recompute, blocked, expire, evict, warm, barrier, state)
├── api/cache/
│   └── ProductCacheController.java          (/api/cache/v{n}/products — 경로 Q2)        ← 기존 /api/v1/products 미수정
├── service/product/cache/
│   ├── ProductListCacheServiceV2.java       (고정 TTL)
│   ├── ProductListCacheServiceV3.java       (single-flight/mutex)
│   ├── ProductListCacheServiceV4.java       (논리만료+비동기갱신, CAS 단일 refresh)
│   └── ProductListCacheServiceV5.java       (null+jitter)
└── infrastructure/cache/
    ├── CachePins.java                       (핀 단일 소스)
    ├── CachePinsBootAssert.java             (부팅 핀·격리·2차캐시off assert)
    ├── LookAsideCache.java                  (look-aside 추상화 + 전략 슬롯)
    └── OriginRecomputeCounter.java          (오리진 재계산 1차 지표 카운터)
```
> 신규 엔티티/테이블/관계 **0**. `Product`·`ProductService`·`ProductController`·`ProductRepository` **미수정**(재사용).

## 측정 규율 (이 phase의 전제 — 요약)
1. 비교 합법 축은 **두 정식 공동 1차 구조 지표**: (A) **오리진 재계산 수**(만료창당, v1/v2↔v3/v4/v5) + (A2) **만료 전환창 대기 큐 깊이(블록 요청 *수*, 시간 아님, v3↔v4)**. v3·v4는 재계산 수가 둘 다 ≈1로 포화하므로 A2가 v4의 존재 이유를 재는 정식 축(M1·축A2). 캐시 적중 지연·throughput·대기 *시간*은 참조 전용·직접 비교 금지.
2'. **로더 비용 회계(DR-1·DR-3):** 1 재계산 = 로더 1진입(카운트), 단 `Page`는 SELECT+COUNT 2 SQL이라 **populate 창·락 점유시간 = 로더 전 구간(체크아웃+SELECT+COUNT+직렬화) 실측 p99**로 핀(내부 SELECT 12.7ms 아님). 락 리스·LOCK_WAIT 불변은 이 전 구간 실측 기준(한 쌍: `LOCK_WAIT ≥ LOCK_TIMEOUT ≥ 전 구간 p99`).
2. 1차 지표 = 재계산 카운터(구조량, 부하·인덱스 무관). p99 꼬리는 보조.
3. 통제변수 핀(`CachePins`) + 부팅 assert + 런타임 assert, 전 버전 일치(단일 변수=방어 기법).
4. 콜드/만료 전환점 측정 강제(워밍 함정). 자가 검증으로 워밍 측정 *드러냄*.
5. v2는 **결함 baseline** — herd/페네트레이션/어밸런치를 *기록*하지 *수정*하지 않는다.
6. 전용 Redis(6380)·격리 라벨 assert. **인스턴스 단위 격리**(측정 스택 전체가 6380, 빈별 포트 분리 아님 — 단일 연결 팩토리). 본체 Redis(6379) 무접촉.

---

## 비준 미결 질문 (audit-doc / design-review가 해소할 것)
- **Q1 (대상 엔드포인트·증폭 강도):** 상품목록 page1을 대상으로 확정하되, **복합 인덱스로 오리진이 싸서(≈12.7ms) 지연 증폭이 muted**일 수 있다 — "오리진 재계산 수(구조 카운트)"만으로 스탬피드를 충분히 가르나, 아니면 더 비싼 오리진 변종(예: 인덱스 미적용 토글·무거운 집계)이 필요한가? (**도착 *타이밍* 함정은 ops `/barrier` 동기화 메커니즘으로 닫혔다 — DR-A·A-6. Q1은 그와 직교한 *진폭* 질문**: 배리어로 동기 도착을 보장해도 오리진이 싸면 *지연* 증폭이 muted일 수 있어 더 비싼 오리진이 필요한가의 문제다.) (design-review가 측정 타당성으로 판정. 후보 트레이드오프: 랭킹 top-N은 이미 Redis라 "캐시 추가" 서사 흐림 / 상품 상세 단건은 쿼리가 더 싸 증폭 약함.) **LOCK_TIMEOUT 결합(AD-5):** 더 비싼 오리진을 채택하면 재계산 시간이 락 리스(`LOCK_TIMEOUT_MS`)를 넘을 수 있다 — 불변 `LOCK_TIMEOUT_MS > p99 재계산시간`을 깨면 v3 이중 재계산. 비싼 오리진 채택 시 락 리스를 그에 맞춰 재핀(Q3·Q4).
- **Q2 (버전 번호·경로 — phase4-1 Q7 동형):** 캐시 사다리 v1~v5를 인덱스 시나리오 v1~v4(v5)와 어떻게 격리하나. 신규 엔드포인트 경로(`/api/cache/v{n}/products` 제안) 최종 확정 + `ROADMAP.md` 마스터 체크리스트·머메이드 노드 반영.
- **Q3 (핀 값 실측 확정):** `TTL_BASE_MS`/`NULL_TTL_MS`/`TTL_JITTER_RATIO`/`LOCK_TIMEOUT_MS`/`LOCK_WAIT_MS`/`STALE_WINDOW_MS`(v4 논리만료 리드타임)/동시성 C — 초안값을 측정으로 핀(만료 전환이 부하 런 안에 관측 가능한 TTL인지 등).
- **Q4 (락 매체):** v3 single-flight를 **분산락(전용 Redis SET NX, 기존 `RedisLockRepository` 재사용)** vs **로컬 JVM 락** 중 무엇으로? 단일/다중 인스턴스 적합성·락 타임아웃 폴백 정책(스테일/직접재계산/503) 확정.
- **Q5 (캐시 추상화):** 수제 `LookAsideCache`(결정) vs Spring `@Cacheable`. single-flight·논리만료+비동기갱신·널 캐싱·재계산 카운터 주입 통제를 위해 수제로 두는 게 맞는지 최종 확인.
- **Q6 (baseline 정합):** Hibernate 2차 캐시/쿼리 캐시가 꺼져 있나(켜져 있으면 재계산이 ORM 캐시에 흡수돼 카운터 거짓 0 — 랭킹 v1 오염 동형). `ProductService` 외 다른 캐시 계층 부재를 코드로 전수 확인. **추가 흡수 클래스(AD-6):** Hibernate L2 외에도 (a) `Pageable` count 쿼리의 별도 카운트, (b) 직렬화/풀 대기 오염, (c) 전용 vs 본체 yml의 L2 설정 차이를 코드/boot assert로 전수 확인 — 미해소분은 carry_forward.
- **Q7 (v4 메커니즘 — DR-B 결선·rev.3에서 좁힘):** v4 = **논리 만료 + 비동기 갱신, 단일 refresh를 CAS/플래그로 보장**으로 고정(재계산=1·블록≈0 둘 다 성립). **락 없는 고전 PER은 배리어 동기 고동시성에서 C·p개가 동시 조기재계산 → 재계산>1로 'recompute≈1' 축을 깨므로 제외**(채택하려면 single-flight 가드 결합 = '한 칸만 변경' 규율 위반 → 백로그). 남는 핀: 논리만료 리드타임·스테일 허용창 초안값(carry_forward).
- **Q8 (v5 페네트레이션 메커니즘):** 널 캐싱 vs 블룸 필터. 널 캐싱의 키-생성 정합(짧은 TTL로 충분한가) vs 블룸 FP·메모리 트레이드오프.
- **Q9 (격리 인프라):** 측정 격리 스택 전용 Redis(6380) 추가 — 본체 compose와 포트·프로젝트명·볼륨 격리, 기동 시간·헬스체크. **격리는 인스턴스 단위**(스택 전체가 6380): 코드의 단일 `redisConnectionFactory`를 재사용하고 측정 스택 `spring.data.redis.port=6380`으로 묶는다 — 캐시 전용 2nd 팩토리 신설 불요, 빈별 포트 분리(캐시 6380/락 6379)는 불가. phase3-4 격리 규율과 동일 수준 보장.
- **Q10 (어밸런치 규모·도착 동기화 — DR-AVAL-1 결선):** 핫 키 5개(카테고리×page1)는 스탬피드/페네트레이션엔 충분하나 어밸런치(다수 키 동시 만료)엔 부족할 수 있다 — 키 모집단을 카테고리×페이지 범위로 확장해야 하나, 필요 규모는? **추가로: 어밸런치가 합법 카운트 축이려면 각 키의 populate 창 안 도착이 >1(per-key herd>1)이어야 하는데, 이는 총 동시성 ≥ 키 모집단 × herd를 요구한다** — 이 규모가 실현 가능한가, 아니면 어밸런치를 '시간-집중 지표(참조 전용)'로 격하하고 합법 카운트 축에서 빼야 하나(DR-AVAL-1·축C). (DB 실측·측정으로 확정.)

## Open Questions
- [ ] v3 락 홀더 크래시 시 인계 시맨틱 — 락 TTL 만료까지 대기 vs 페일오버. 측정에서 재현할지.
- [ ] v4 스테일 제공의 신선도 상한 — 상품목록 읽기 전용 가정이 깨지는 워크로드(가격 변동 등)에서의 일반화는 범위 밖, 명시.
- [ ] 다중 인스턴스에서 single-flight(분산락) vs 로컬락의 herd 차단 차이 — 본 phase는 단일 인스턴스 측정, 다중은 백로그(동시성 보강#1 패턴 재사용 가능성).
- [ ] 캐시 직렬화 비용·전용 Redis 커넥션 풀이 "오리진 비용"으로 오염되지 않게 카운터 진입점 격리 재확인(M1).
- [ ] Spring Data `Pageable` count 쿼리가 herd당 재계산을 2로 부풀리지 않게 카운터 진입점이 count 쿼리를 제외하는지 코드 확인(AD-6).
- [ ] 전용 스택 `application.yml`의 Hibernate L2/쿼리캐시 off가 본체와 동일한지 boot assert 이중 확인(AD-6).
- [ ] 재계산 카운터를 Micrometer로 노출해 Grafana 캐시 대시보드(만료창당 재계산 시계열)로 전시 보강할지 — 범위 밖 후보.
- [ ] (carry_forward, DR-AVAL-1) 축C 어밸런치 도착-동기화 = DR-A 미닫힘 클래스 — per-key herd>1(배리어/동시성 핀) 보장 없으면 어밸런치 v2↔v5 차이가 봉인된 시간 분포로 붕괴해 합법 카운트 축 아님. per-key 동기화 메커니즘 추가 vs 어밸런치를 '시간-집중 지표(참조 전용)'로 격하 중 택일은 백로그(Q10 규모·필요 총 동시성 결선).
- [ ] (carry_forward, DR-V4COLD-1) v4 물리 콜드 미스 herd 클래스 — 본 phase는 v4를 물리 DEL/`/evict`로 측정하지 않음으로 배제(Step D 못 하는 것). v4 콜드 경로에 single-flight 결합 여부는 백로그('한 칸만 변경' 규율과 상충 검토).
- [ ] (carry_forward, DR-TOMCAT-1) 서버측 latch(`/barrier`) herd 천장 = Tomcat 스레드 천장. `/barrier` n=C 무장→C개가 캐시 get 진입점에서 동시 check-in→동시 release하려면 C개가 *동시에 Tomcat 워커 스레드를 점유한 채* 진입점에 도달해야 한다. 그런데 `server.tomcat.threads.max: 200`(phase3-3 'do not remove' 통제 핀, 측정 스택은 datasource/redis만 override해 그대로 상속)이라 C가 200에 근접/초과하면 201번째+ 요청이 accept 큐에 갇혀 진입점에 영영 미도달 → latch가 n을 못 채워 release 안 됨 → park된 ≤200개가 무한 대기 → '타임아웃 내 종료' 게이트로 abort. 즉 herd C는 ~200으로 천장이 박히고, Q10 어밸런치 합법 카운트('총 동시성 ≥ 키모집단 × herd')는 단일 인스턴스 200스레드 천장에서 실현 불가일 공산(이는 어밸런치 격하 결론을 *강화*하나, spec은 격하 사유를 '도착-동기화 부재'로만 귀속하고 *스레드 천장*이라는 코드-근거 사유를 누락). **C < tomcat.threads.max 불변이 CachePins/boot assert에 cross-assert돼 있지 않음** — 백로그.
- [ ] (carry_forward, FR-4 — DR-AVAL-1 subtractive 잔여) 축C 어밸런치는 per-key herd>1 보장 시에만 합법 카운트, 미보장 시 '시간-집중 지표(참조 전용)'로 격하. 축A의 도착-동기화 거짓수렴은 M5 자가검증(c) 배리어-없는 램프 가드라는 *자동* liveness 가드로 잡지만, **축C에는 동등한 자동 가드가 없다** — 텍스트 금지('지터 효과있다 거짓수렴 금지')와 본 carry_forward만 존재. 구현자가 per-key herd>1 미충족 상태에서 축C 시간분포로 v5 우위를 보고해도 자동 abort가 발화 안 함. 축C 거짓수렴용 자동 가드 추가는 백로그.
- [x] **닫음(설계 검수):** A2 정식 축 승격(DR-1) · 로더 비용 회계·락리스 전 구간 정의(DR-1/DR-3) · 캐시 값 직렬화 안전 형태·silent 미스 금지(DR-2) · LOCK_WAIT 불변(DR-4) · 격리 MySQL 정책(AD-2). **구현·측정에서 확인 완료**: Pageable count 쿼리는 카운터 진입점(로더 람다)에서 제외(로더 1진입=재계산 1), 전용 yml L2 off boot assert 확인, 직렬화/풀 대기 비오염 — 전부 실측 PASS(analysis.md).

## 참조
- Phase 3-7 스펙: `specs/phase3/phase3-7-n-plus-one.md` (vN 사다리·경로별 배선·측정 2단계 분리·워밍 함정 R3·baseline 정합)
- 측정 결과: `results/phase3-8-cache-stampede/analysis.md` · 회고: `docs/reports/phase3-8-cache-stampede.md`
- 로드맵: `ROADMAP.md` Stage 3 A (캐시 전략 + 스탬피드 방어)
