# Phase 4-1 — 이벤트 기반 비동기: 발급 도메인 이벤트 파이프라인 (Outbox → Kafka → DLQ) [DRAFT]

> **초안(-draft).** 정방향(to-build) 스펙 — 아직 구현 전. `spec-draft-workflow` 규율: 이 초안은 다른 세션의 `/audit-doc`(코드베이스 대조)·`design-review`(목표 달성·측정 타당성·실패모드 적대 검증)를 통과한 뒤 `-draft` 접미사를 떼고 확정한다. 문서 끝 「비준 미결 질문」을 먼저 해소할 것.
>
> 로드맵 위치: `ROADMAP.md` Stage 4 capstone **F (Kafka + Outbox)**. 이번 phase는 그중 **이벤트 파이프라인 슬라이스(Outbox+Kafka+DLQ)** 로 한정하고, **읽기/쓰기 분리(replica routing)는 백로그 유지**.

## Context
- Phase 3-4 (비동기 발급 — 큐 사다리 v6a/v6b/v6c) 완료. v6c는 Redis Stream + 컨슈머 그룹으로 **큐 안 메시지의 유실을 PEL 회수로 닫고**, 재전달 중복은 `UNIQUE(coupon_id, user_id)`로 멱등 흡수한다.
- **v6c의 구조적 미달(이 phase의 출발점):** 컨슈머 처리 경로(`CouponIssueProcessor.process`)는 `v3Service.issue()`로 `coupon_issue` 행을 커밋한 뒤(45행) 상태 해시만 기록한다(60행) — **발급 사실을 외부에 알리는 도메인 이벤트 발행이 없다.** v6c 스펙 163행이 "멱등 키 없는 부수효과(알림 등)는 범위 밖"으로 명시 보류한 그 지점이다.
- 발급에 다운스트림 부수효과(알림·집계 등)를 붙이려면 "DB 커밋"과 "이벤트 발행"이라는 **두 시스템 쓰기**가 생긴다. 이 둘이 한 트랜잭션이 아니면 사이에서 죽을 때 불일치가 난다(**dual-write 문제**) — v6c의 PEL 회수가 닫는 축("큐 안 메시지")과 **다른 축**("커밋 ↔ 발행")이다.
- 발급 정합성의 단일 진실 소스는 여전히 `SELECT COUNT(*) FROM coupon_issue WHERE coupon_id=?` 행 수(Phase 3-3·3-4 계승). 이 phase가 새로 다루는 **이벤트 전달 정합성**의 진실 소스는 컨슈머측 효과 테이블 행 수(아래 Step A-7).
- `Coupon`/`CouponIssue`/`CouponIssueServiceV3`는 수정하지 않는다(재사용만).
- **측정은 본체와 분리된 전용 격리 스택**(별도 compose 프로젝트·볼륨·포트, Kafka 포함)에서 수행. Phase 3-4 격리 규율 계승.

## Goal
발급 성공을 **다운스트림 도메인 이벤트(`CouponIssued`)로 발행**하는 파이프라인을 세우고, **"DB 커밋과 이벤트 발행의 원자성"** 을 단일 변수로 격리해 사다리로 올린다. 발행 매체는 Kafka로 고정하고, 발행 *방식*만 한 칸씩 바꾼다.

- **v7a** 직접 발행 — 커밋 직후 `KafkaTemplate.send()` (dual-write **결함을 노출**하는 baseline)
- **v7b** 트랜잭셔널 아웃박스 — 발급 트랜잭션 안에서 `outbox` 행을 함께 커밋, 별도 **폴링 릴레이**가 발행 (커밋↔발행 원자성을 닫음)
- **v7c** 아웃박스 + **DLQ 컨슈머 회복탄력성** — poison 메시지를 N회 재시도 후 Dead Letter Topic으로 격리·재처리 (컨슈머측 실패가 정상 트래픽을 막지 못하게)

**채택 결정이 아니다(Phase 3-4 규율 계승).** v7a/b/c 중 하나를 "고르는" 게 산출물이 아니라, "발행을 정합적으로 만들면 무엇을 더 떠안는가"의 비용·실패 프로파일을 남기는 것. v7a는 의도적으로 깨지는 baseline이고, 발급 처리 경로는 세 버전 모두 v3를 재사용한다.

### 명시적으로 범위 밖 (백로그 유지)
- **읽기/쓰기 분리(replica routing)** — 로드맵 F의 나머지 절반. 별도 phase.
- **CDC 릴레이(Debezium 등)** — 이 phase의 릴레이는 **폴링**으로 고정. CDC는 인프라(Kafka Connect)가 무거워 백로그.
- **exactly-once 주장 금지** — 달성하는 것은 "발행 원자성(아웃박스) + 멱등 컨슈머(`UNIQUE(event_id)`)의 조합 = effectively-once 부수효과"뿐. Kafka EOS(트랜잭셔널 프로듀서/read-process-write)는 별도 주제.
- 멱등 키를 못 붙이는 부수효과(외부 비멱등 API 호출 등)는 범위 밖.

## Important Rules
- 먼저 `CLAUDE.md`를 읽고 컨벤션 확인.
- **관계 추가 절대 금지:** `Coupon`/`CouponIssue`에 어떤 연관도 추가하지 않는다. 신규 테이블(`outbox`, `coupon_issued_event_log`)도 **연관 없는 독립 테이블** — 키는 `Long`/`String` 컬럼만(프로젝트 `Order→User` 무관계 규율과 동형).
- **엔티티 규율 준수:** 신규 엔티티도 `@GeneratedValue(IDENTITY)`, `@NoArgsConstructor(PROTECTED)`, `@Getter`, **No Setter(비즈니스 메서드)**, `@CreatedDate + @Column(updatable=false)`, `@EntityListeners(AuditingEntityListener.class)`.
- **`CouponIssueServiceV3` 수정 금지.** v7b의 아웃박스 INSERT는 v3의 트랜잭션에 **참여**시켜 원자성을 얻는다(아래 설계). v3 본문을 고치지 않는다.
- **발급 사실의 진실 소스 = `coupon_issue` 행 수.** 이벤트 전달의 진실 소스 = `coupon_issued_event_log` 행 수. 상태 해시·outbox status는 *관측/운영 보조*.
- **v7a는 고치지 않는다.** dual-write 불일치를 *측정·기록*하는 baseline이다(Phase 3-4의 v6b 유실 재현과 동형 — 결함을 닫는 게 아니라 드러내는 칸).
- **비교 합법 축은 정합성**(발급 행 ↔ 이벤트 전달 행 일치, 초과/유실/유령 0). 버전 간 발행 지연·throughput **직접 비교 금지**(발행 매체/방식 confound + 런별 잡음). 관찰값은 참조만.
- **프로듀서 내구성 핀 고정:** `acks=all`, `enable.idempotence=true`. 이 값이 안 잡히면 "발행했다"의 의미가 흔들려 측정이 깨진다(Step A-3 boot assert로 강제).
- 측정은 **전용 격리 스택**에서. 본체 데이터·본체 Kafka(있다면)에 닿지 않게 compose 프로젝트 라벨로 격리 assert.
- 네이밍은 젠더중립/중립어(친족 비유 금지 — 글로벌 컨벤션).

---

## Step A: 공통 인프라 — Kafka·아웃박스·핀·이벤트·효과로그·ops 표면

> 측정 유효성을 먼저 깐다. "발행했다"와 "전달·처리됐다"가 분리돼 측정을 깨뜨리기 쉽다(Phase 3-4의 "202는 접수지 발급 아님"과 동형 함정) — 수치를 내기 전에 그 수치를 신뢰할 상태부터 만든다.

### 제한사항
- 엔티티·관계 추가는 신규 독립 테이블 2종만. v3 발급 서비스 재사용·수정 금지.
- 발행/릴레이/컨슈머 빈은 책임 분리(발급 트랜잭션 빈 ≠ 릴레이 빈 ≠ 컨슈머 빈).

### 1. Kafka 격리 스택 + 토픽
- 측정 전용 compose에 **단일 브로커 Kafka(KRaft 모드, Zookeeper 없음)** 추가. 별도 프로젝트명·볼륨·포트.
- 토픽(핀): `coupon.issued`(메인), `coupon.issued.DLT`(Dead Letter). 파티션 수·복제 인자는 핀으로 고정(단일 브로커이므로 RF=1, 파티션은 컨슈머 동시성과 맞춤 — A-2 참조).
- 토픽은 명시 생성(auto-create 금지 — 핀 검증 대상). boot assert가 토픽 존재·파티션 수를 확인.

### 2. 핀 단일 소스 — `EventPins`
- 위치(제안): `infrastructure/eventbus/EventPins.java` 상수 클래스. 측정 하네스 핀 표와 1:1.
- 핀 값(초안 — 비준 시 확정):
  - **프로듀서:** `ACKS = "all"`, `ENABLE_IDEMPOTENCE = true`
  - **릴레이(v7b/v7c):** `RELAY_POLL_INTERVAL_MS = 200`, `RELAY_BATCH_SIZE = 100`, `RELAY_CLAIM = "FOR UPDATE SKIP LOCKED"`(다중 릴레이 인스턴스 안전 — 단일 인스턴스라도 계약 고정)
  - **컨슈머:** `CONSUMER_CONCURRENCY = 4`(Phase 3-4 소비 동시성 4 계승), `MAIN_PARTITIONS = 4`(동시성과 맞춤)
  - **DLQ(v7c):** `RETRY_ATTEMPTS = 3`, `RETRY_BACKOFF_MS = 500`, `DLT_SUFFIX = ".DLT"`
  - **운영:** `OUTBOX_RETENTION_*`, `GRACEFUL_SHUTDOWN_SEC = 10`(Phase 3-4 계승)
- **통제변수 규율:** 소비 동시성 4·파티션 4를 전 버전 일치. 사다리 내 단일 변수 = **발행 방식만 차이**.

### 3. 부팅 핀 assert — `EventPinsBootAssert`
- `@EventListener(ApplicationReadyEvent.class)`에서 실제 프로듀서 설정(`acks`/`enable.idempotence`)·컨슈머 동시성·DLT 핸들러 구성·토픽 존재를 `EventPins` 기대값과 대조 → 불일치 시 `IllegalStateException`으로 **기동 실패**. (런타임 재대조는 하네스 `assert_event_pins`가 측정마다 — 이중 게이트, Phase 3-4 `AsyncPinsBootAssert` 패턴 계승.)

### 4. 도메인 이벤트 — `CouponIssuedEvent`
- 위치(제안): `infrastructure/eventbus/CouponIssuedEvent.java` (record).
- 필드: `eventId`(String, UUID — **멱등 키이자 진실 조인 키**), `couponId`(Long), `userId`(Long), `issuedAt`(epoch ms). 직렬화는 JSON(스키마 단순 — Avro/Schema Registry는 범위 밖).
- **`eventId` 생성 시점 계약:** 발급 트랜잭션 안에서 1회 생성·고정(재발행/재전달돼도 동일 `eventId`). v7b는 outbox 행에 박아 영속화. 이 키가 컨슈머 멱등의 근거다.

### 5. 아웃박스 테이블/엔티티 — `OutboxEvent` (v7b/v7c)
- 위치(제안): `domain/outbox/OutboxEvent.java` — **위치는 비준 질문(Q3)**.
- 컬럼(독립 테이블, 무관계): `id`(IDENTITY), `event_id`(String, **UNIQUE**), `aggregate_type`(String, "Coupon"), `aggregate_id`(Long, couponId), `event_type`(String, "CouponIssued"), `payload`(JSON String), `status`(PENDING/SENT/FAILED), `created_at`(@CreatedDate, updatable=false), `sent_at`(nullable).
- 비즈니스 메서드: `markSent(epochMs)`(status→SENT, sent_at 기록). No Setter.
- `OutboxRepository`(Spring Data): `findTopNByStatusOrderByIdAsc`(릴레이 배치 픽업, `SKIP LOCKED`), `markSent`.

### 6. 카오스 스위치 — `ChaosPublishSwitch` (Phase 3-4 `ChaosKillSwitch` 패턴 계승)
- kill 지점 변수화:
  - `AFTER_COMMIT_BEFORE_PUBLISH` — **v7a 핵심**: 커밋 직후·`KafkaTemplate.send()` 전 `Runtime.halt(137)`. dual-write 유실 재현.
  - `POISON_MESSAGE` — **v7c 핵심**: 특정 마킹 이벤트(예: userId 특정 대역)에서 컨슈머가 `throw` → 재시도 소진 → DLT 격리 관찰. (halt 아니라 throw — 프로세스는 살아야 정상 트래픽 처리 지속 확인.)
- 평시 무장 해제. ops 표면이 잔존 무장 노출(직전 카오스 런 누수 검출).

### 7. 컨슈머측 효과 테이블 — `CouponIssuedEventLog` + 멱등 컨슈머
- 위치(제안): `domain/coupon/CouponIssuedEventLog.java`(엔티티) + `service/coupon/event/CouponIssuedEventConsumer.java`(컨슈머).
- 컬럼(독립 테이블, 무관계): `id`(IDENTITY), `event_id`(String, **UNIQUE** — 멱등 흡수), `coupon_id`(Long), `user_id`(Long), `processed_at`(@CreatedDate, updatable=false).
- **이벤트 전달 정합성의 진실 소스 = 이 테이블 행 수.** 컨슈머는 `event_id`로 INSERT → at-least-once 재전달의 중복은 `UNIQUE(event_id)` 충돌로 흡수(v6c 멱등 흡수와 동형). 이 효과 로그가 "부수효과(알림 등)"의 측정 가능한 대역(stand-in)이다.
- 컨슈머는 `@KafkaListener(topics="coupon.issued", concurrency=CONSUMER_CONCURRENCY)`. 처리 = 효과 로그 INSERT(+ poison 훅).

### 8. ops 표면 — `CouponEventOpsController`
- 위치(제안): `api/coupon/CouponEventOpsController.java`(`/api/v7/ops`) — 측정 하네스 전용.
- `GET /pins` — 프로듀서/릴레이/컨슈머/DLQ 핀 런타임 실측값(설정 echo 아니라 실제 빈/클라이언트에서 읽음). `chaosArmed` 노출.
- `GET /outbox/depth` — `SELECT COUNT(*) WHERE status='PENDING'`(릴레이 미발행 적체).
- `GET /lag` — 메인 토픽 컨슈머 그룹 lag, DLT 토픽 메시지 수(관찰/수렴 게이트 소스).
- `POST /chaos/arm?point=` — 카오스 무장.

### Step A Build Check
- `./gradlew compileJava`. 기동 시 `EventPinsBootAssert`가 핀·토픽 검증(불일치면 기동 실패).

---

## Step B: v7a — 직접 발행 (dual-write 결함 baseline)

### 설계 의도
가장 단순한 발행 — 발급 커밋 직후 컨슈머 경로에서 곧장 `KafkaTemplate.send(event)`. "발행도 했으니 끝"의 직관을 세우고, **커밋과 발행이 두 시스템이라 그 사이에서 죽으면 불일치**라는 구조적 한계를 노출해 v7b(아웃박스)의 근거를 만든다.

### 구현된 컴포넌트와 경로 (제안)
- `service/coupon/event/CouponIssuePublisherV7a.java` — 발급 처리 후 `eventId` 생성 → `KafkaTemplate.send("coupon.issued", event)`. **커밋과 send는 분리된 두 작업**(원자성 없음). `ChaosPublishSwitch.maybeHalt(AFTER_COMMIT_BEFORE_PUBLISH)`를 커밋과 send 사이에 둔다.
- 발급 처리 자체는 v6의 `CouponIssueProcessor`(v3 재사용) 경로를 따른다 — **발행 한 줄을 그 뒤에 붙이는 형태**. (정확한 결선은 비준 질문 Q2.)

### 못 하는 것 / 노출하는 것
- **유실:** 커밋 성공 + send 전 halt → `coupon_issue` 행은 있는데 이벤트 미발행 → 효과 로그 누락(유령의 반대 = **유실**). 기록만, 고치지 않음.
- **유령(역방향):** send 성공 + 후속 롤백 시나리오는 이 경로에선 커밋이 send보다 앞서므로 주된 모드는 유실. (유령 모드 재현이 필요한지는 Q4.)
- 닫으려면 커밋과 발행을 한 트랜잭션에 → v7b.

---

## Step C: v7b — 트랜잭셔널 아웃박스 + 폴링 릴레이

### 설계 의도
발행을 DB 트랜잭션 안으로 끌어들여 커밋↔발행을 원자화한다. 발급 트랜잭션에서 `coupon_issue` INSERT와 **같은 트랜잭션으로 `outbox` 행을 INSERT**(둘 다 커밋되거나 둘 다 안 됨). 별도 **폴링 릴레이**가 `outbox`(PENDING)를 읽어 Kafka로 발행하고 `SENT` 마킹 — 발행은 커밋과 분리됐지만 **outbox 행이 영속**이라 유실되지 않는다(at-least-once: 발행 후 마킹 전 죽으면 재발행 → 중복 → 컨슈머 `UNIQUE(event_id)`가 흡수).

### 핵심 설계 — v3 트랜잭션에 아웃박스 참여 (v3 미수정)
- 새 빈 `CouponIssueWithOutboxServiceV7b`(`@Transactional`)가:
  1. `v3Service.issue(couponId, userId)` 호출 — v3의 `@Transactional(REQUIRED)`이 **바깥 트랜잭션에 참여**(새 트랜잭션 안 염, 같은 커넥션/커밋 경계).
  2. 같은 트랜잭션에서 `outboxRepository.save(new OutboxEvent(eventId, ...))`.
  3. 메서드 반환 시 둘이 **한 번에 커밋**.
- 이렇게 v3 본문을 고치지 않고 원자성을 얻는다. **단, v6c의 멱등 흡수 catch(`DuplicateIssueException`)와 트랜잭션 경계의 상호작용은 위험 지점** — 재전달분이 발급은 흡수되는데 outbox는 어떻게 되는지(흡수 시 outbox 미기록이 맞는지)를 비준에서 검증(Q1).
- `service/coupon/event/OutboxRelay.java` — `@Scheduled`(또는 `SmartLifecycle` 루프, `RELAY_POLL_INTERVAL_MS`) → `findTopNByStatus(PENDING, BATCH, SKIP LOCKED)` → `KafkaTemplate.send` → `markSent`. 발행 후 마킹 전 크래시 = 재발행(at-least-once).

### 못 하는 것
- **컨슈머측 실패엔 무력:** 발행은 보장하나, 컨슈머가 poison 메시지에 계속 실패하면 그 파티션이 막히거나 무한 재시도된다 — 정상 이벤트까지 지연. 컨슈머 실패 격리가 필요 → v7c.
- exactly-once 아님(at-least-once + 멱등 흡수). 릴레이 폴링 간격만큼 발행 지연(CDC 아님).

---

## Step D: v7c — 아웃박스 + DLQ 컨슈머 회복탄력성

### 설계 의도
컨슈머 처리 실패를 격리한다. poison 메시지를 `RETRY_ATTEMPTS`회 백오프 재시도 후에도 실패하면 **Dead Letter Topic(`coupon.issued.DLT`)으로 보내** 메인 토픽 처리를 막지 않게 한다. DLT는 사후 조사·재처리 대상. 정상 이벤트는 poison과 무관하게 계속 흐른다.

### 구현된 컴포넌트와 경로 (제안)
- Spring Kafka `@RetryableTopic`(또는 `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`) 구성: `attempts=RETRY_ATTEMPTS`, `backoff=RETRY_BACKOFF_MS`, DLT 자동 라우팅. (둘 중 어느 메커니즘이 측정 통제에 유리한지는 Q5.)
- 컨슈머는 v7b와 동일(`CouponIssuedEventConsumer`) + poison 훅(`ChaosPublishSwitch.POISON_MESSAGE`).
- `service/coupon/event/DltInspectorController` 또는 ops `/api/v7/ops/dlt` — DLT 메시지 수·재처리 트리거(측정 하네스 전용).

### 못 하는 것 / 이 칸의 한계
- **여전히 exactly-once 아님.** 멱등 컨슈머로 effectively-once 부수효과까지.
- poison의 *근본 원인*은 고치지 않는다(격리·재처리만). 자동 재처리 정책(지수 백오프 DLT 재투입 등)은 백로그.
- replica routing·CDC 릴레이·Schema Registry 모두 범위 밖.

---

## 측정 계획 (to-build)

> 측정 하네스(제안): `scripts/measure/run-coupon-event.sh`, `test/load/coupon-event-test.js`. 결과: `results/phase4-1-event/`.

### 측정 유효성 — 수렴 게이트 (Phase 3-4 계승·확장)
- 비동기 발행도 "발급 종료 직후" 판정이 거짓이 된다(릴레이·컨슈머가 아직 처리 중). 수렴 게이트: **`outbox PENDING == 0` && `메인 토픽 lag == 0` && `coupon_issued_event_log` 행 수 연속 3회 동일(1s 폴링) && 타임아웃 내**. 수렴 후 판정. 초과 시 `NON_CONVERGED`(컨슈머/릴레이 정지 신호로 우선 해석).
- 핀은 ops `/pins` 런타임 assert + 스택 격리 라벨 assert로 강제(불일치 시 abort).
- 자가 검증 2건(Phase 3-4 계승): 고의 핀 불일치 → abort, 고의 미수렴(컨슈머 정지) → `NON_CONVERGED`.

### 축 1 — 정합성 (normal, 비교 합법 축)
- 발급 N건 → 각 발급당 이벤트 1 → 전달·처리 → `coupon_issued_event_log` N행.
- **게이트:** `coupon_issue 행수(=발급) == event_log 행수(=전달·처리)` && 유령 0(event_log에 대응 발급 없는 행 0) && 중복 흡수 로그 ≥ 관측(at-least-once 정상 작동 증거). v7a/v7b/v7c 모두 무카오스 normal에선 통과 기대.

### 축 2 — 카오스: 커밋↔발행 원자성 (`AFTER_COMMIT_BEFORE_PUBLISH` halt)
- 발급 드레인 중 halt 무장 → 재기동 → 수렴 게이트 → 회계.
- **v7a 직접발행 = 불일치 기대**(유실: `발급 행 − event_log 행 ≥ 1`을 *기록*, 고치지 않음).
- **v7b/v7c 아웃박스 = 불일치 0 기대**(outbox 행이 같은 tx로 커밋 → 재기동 후 릴레이가 발행 → event_log 수렴). **이 대조가 이 phase의 핵심 발견.**

### 축 3 — DLQ: poison 격리 (`POISON_MESSAGE` throw)
- 정상 이벤트 + poison 이벤트(마킹 대역) 혼재 적재.
- **v7b(DLT 없음) = poison이 재시도/적체로 정상 처리 지연**(메인 lag이 안 빠지거나 무한 재시도 로그) — 기록만.
- **v7c(DLT) = 게이트:** 정상 이벤트 전부 처리(메인 lag 0 수렴), poison은 `RETRY_ATTEMPTS`회 후 DLT에 격리(DLT 수 == poison 수), 메인 토픽 진행이 poison에 안 막힘. DLT 재처리 트리거 시 성공.

### 관찰값 (참조 전용, 비교 금지)
- 릴레이 발행 지연(outbox `sent_at − created_at`), E2E 전달 지연(`event_log.processed_at − issue 커밋`), 컨슈머 lag 시계열. **버전 간 직접 비교 금지**(발행 방식 confound + 런별 잡음).

---

## 디렉토리 구조 (제안 — 비준 후 확정)
```
docker/
└── (측정 전용 compose에 kafka 서비스 추가 — KRaft 단일 브로커, 별도 프로젝트·볼륨·포트)

scripts/measure/
└── run-coupon-event.sh              (normal / chaos-publish / chaos-poison / selftest-*)

test/load/
└── coupon-event-test.js             (v7a/b/c 파라미터화)

results/phase4-1-event/
├── analysis.md                      (버전별 정합성·카오스·DLQ 분석 — 1차 출처)
├── INVALID-RUNS.md                  (무효 런 보존)
├── consistency/                     (발급 행 ↔ event_log 행 회계)
├── chaos/                           (publish halt·poison 증거)
└── lag/                             (outbox depth·컨슈머 lag 시계열)

src/main/java/com/project/
├── api/coupon/
│   └── CouponEventOpsController.java       (/api/v7/ops: pins, outbox/depth, lag, chaos/arm, dlt)
├── service/coupon/event/
│   ├── CouponIssuePublisherV7a.java        (직접 발행)
│   ├── CouponIssueWithOutboxServiceV7b.java (v3 tx 참여 + outbox INSERT)
│   ├── OutboxRelay.java                     (폴링 릴레이 → Kafka)
│   ├── CouponIssuedEventConsumer.java       (@KafkaListener, 멱등 INSERT)
│   └── (DLT 구성 — @RetryableTopic 또는 DefaultErrorHandler)
├── domain/outbox/
│   └── OutboxEvent.java                     (독립 테이블, 무관계)        ← 위치 Q3
├── domain/coupon/
│   └── CouponIssuedEventLog.java            (독립 테이블, 무관계, 전달 진실 소스)
└── infrastructure/eventbus/
    ├── EventPins.java                       (핀 단일 소스)
    ├── EventPinsBootAssert.java             (부팅 핀·토픽 assert)
    ├── CouponIssuedEvent.java               (도메인 이벤트 record)
    ├── KafkaProducerConfig.java             (acks=all, idempotence — 핀 적용)
    └── ChaosPublishSwitch.java              (AFTER_COMMIT_BEFORE_PUBLISH / POISON_MESSAGE)
```

## 측정 규율 (이 phase의 전제)
1. 정합성 게이트(발급 행 ↔ event_log 행 일치, 유실·유령·초과 0)가 통과 기준.
2. v7b/v7c를 **exactly-once로 주장하지 않는다**(아웃박스 발행 원자성 + 멱등 컨슈머 흡수의 조합 = effectively-once 부수효과).
3. **버전 간 발행/전달 지연·throughput 직접 비교 금지**(발행 방식 confound + 런별 잡음).
4. v7a는 **결함 baseline** — 카오스 축에서 불일치를 *기록*하지 *수정*하지 않는다.
5. 비교 합법 축은 **정합성** 하나.

---

## 비준 미결 질문 (audit-doc / design-review가 해소할 것)
- **Q1 (정확성 위험):** v7b에서 재전달분의 발급 멱등 흡수(`DuplicateIssueException` catch)와 outbox INSERT의 상호작용 — 흡수된 발급은 outbox에 행을 남기는가/안 남기는가? "발급 1건 = 이벤트 1건" 불변을 깨지 않는 결선은? (트랜잭션 경계·catch 위치 코드로 검증.)
- **Q2 (결선):** v7a/v7b의 발행을 기존 `CouponIssueProcessor`(v6 공통)에 끼울지, v7 전용 처리 빈을 새로 둘지. v6c 경로를 건드리면 Phase 3-4 동결본이 흔들릴 위험.
- **Q3 (배치):** `OutboxEvent` 엔티티 위치 — `domain/outbox/` vs `infrastructure/`. 프로젝트는 "엔티티=domain, 인프라 관심사=infrastructure"인데 아웃박스는 둘 다 걸침.
- **Q4 (범위):** v7a의 실패 모드를 유실만으로 둘지, 유령(send 후 롤백)까지 재현할지. 유령은 별도 결선이 필요.
- **Q5 (메커니즘):** DLT를 `@RetryableTopic`(별도 재시도 토픽 생성) vs `DefaultErrorHandler + DeadLetterPublishingRecoverer`(인메모리 백오프 후 DLT) 중 무엇으로? 측정 통제(재시도 횟수·격리 시점 관측)에 유리한 쪽.
- **Q6 (인프라):** 측정 격리 스택에 Kafka 추가 시 본체 compose와의 격리(포트·프로젝트명·볼륨)·기동 시간·헬스체크. Phase 3-4 격리 규율과 동일 수준 보장.
- **Q7 (버전 번호):** 전역 vN 규약상 이벤트 파이프라인을 v7a/b/c로 두는 게 맞는지(별도 시나리오라 새 라인 시작이 맞는지), `ROADMAP.md` 마스터 체크리스트·머메이드에 노드 추가 반영.

## 참조
- Phase 3-4 스펙: `specs/phase3/phase3-4-async-issuance.md` (큐 사다리·격리·수렴 게이트·카오스 규율의 원형)
- 로드맵: `ROADMAP.md` Stage 4 capstone F (이번 phase는 그 이벤트 파이프라인 슬라이스)
