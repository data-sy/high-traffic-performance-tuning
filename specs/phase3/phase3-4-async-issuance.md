# Phase 3-4 — 비동기 발급 (큐 구조 사다리 v6a → v6c)

> 역방향(as-built) 스펙 — 구현·머지 후 산출물 기준으로 작성. 출처: 회고 `docs/reports/phase3-4-async-issuance.md`, 지도 `docs/plan/study-async-expedition-map.html`, `results/phase3-4-async/`.

## Context
- Phase 3-3 (동시성 제어 — 쿠폰 발급 분산 락, v1~v5) 완료 상태. 발급 경로는 **v3(`SELECT ... FOR UPDATE`)** 로 결정됨 — 이 phase는 그 결정을 **흔들지 않는다**.
- `Coupon`/`CouponIssue` 엔티티는 Phase 2에서 정의됨. `coupon(total_qty=100, issued)`, `coupon_issue UNIQUE(coupon_id, user_id)`.
- 발급 정합성의 단일 진실 소스는 `SELECT COUNT(*) FROM coupon_issue WHERE coupon_id=?` 행 수다 (Phase 3-3 계승). `coupon.issued` 카운터·상태 해시는 보조·관측용.
- `ddl-auto: validate` 활성. 변경 금지.
- Docker 컨테이너 (MySQL, Redis) 실행 중. **측정은 본체 인프라와 분리된 전용 격리 스택**(별도 compose 프로젝트·볼륨·포트)에서 수행 — 측정·reset이 본체 데이터에 닿지 않게 격리.
- Phase 3-3의 v3 발급 경로(`CouponIssueServiceV3.issue(couponId, userId)`)·k6/셸 측정 도구가 확인 완료된 상태에서 시작.

## Goal
동기 발급(Phase 3-3 v3)을 **큐 뒤로 미뤘을 때**(접수 `202` → 큐 → 컨슈머가 v3 경로로 발급)의 비용·실패를 측정한다. 큐 매체를 **JVM 힙(v6a) → Redis List(v6b) → Redis Stream(v6c)** 으로 한 칸씩 올리며, 같은 게이트(**초과발급 0**)를 통과하는 세 구조의 **생존·유실·중복 프로파일**을 통제변수 핀으로 격리 측정한다.

- **v6a** 큐 = JVM 힙 (`@Async` ThreadPoolTaskExecutor 대기열)
- **v6b** 큐 = Redis List (`LPUSH` / `BRPOP`)
- **v6c** 큐 = Redis Stream + 컨슈머 그룹 (`XADD` / `XREADGROUP` / `XACK` / PEL 회수)

**채택 결정이 아니다.** v6a/b/c 중 하나를 "고르는" 게 산출물이 아니라, "큐를 쓰면 무엇을 더 떠안는가"의 비용·실패 프로파일을 남기는 것이다. 컨슈머의 발급 처리 경로는 세 버전 모두 v3를 그대로 재사용한다.

## Important Rules
- 먼저 `CLAUDE.md`를 읽고 프로젝트 컨벤션을 확인할 것.
- **관계 추가 절대 금지**: `Coupon`/`CouponIssue`에 어떤 연관도 추가하지 말 것. `couponId`/`userId`는 `Long`만.
- 발급 경로는 **Fork B(출구 판정)** 로 고정: 입구에서 즉시 발급하지 않고 `202`만 돌려준 뒤, 재고 판정·발급은 **컨슈머가 DB 앞에서**(v3 `FOR UPDATE`) 한다. **Fork A(입구 Redis 선판정 카운터)는 범위 밖** — 이중 진실 소스 문제는 별도 주제.
- 발급 사실의 **진실 소스는 행 수**다. 상태 해시(ACCEPTED/ISSUED/SOLD_OUT/FAILED)는 *관측 전용*. 상태 카운트와 행 수가 어긋나면 행 수가 이긴다.
- **`@Async` self-invocation 회피**: 파사드(적재 빈)와 워커(`@Async` 빈)를 **반드시 다른 빈으로 분리**한다 (자기 호출은 프록시 미적용 — Phase 3-3 v2 교훈 재사용). 처리 스레드명이 `couponExecutor-*`(비동기)여야 하고 `http-nio-*`(동기)면 분리 실패.
- **`@Async`는 전용 executor 지정 필수** (`@Async("couponExecutor")`). 기본 executor 금지.
- **통제변수 규율** — 값이 정답이 아니라 "전 버전 동일"이 핵심. **소비 동시성 4**(v6a 풀 / v6b 워커 스레드 / v6c 컨슈머 c1~c4)를 전 버전에 일치시켜, 사다리 내 단일 변수 = **큐 매체만 차이**로 격리한다.
- **비교 합법 축은 정확성(초과발급 0) 하나**다. 버전 간 E2E 지연·throughput 직접 비교 금지(큐 매체 confound + 런별 잡음). Phase 3-3 v3(동기) 동결본과 직접 비교 금지(접수 지연 vs 처리 지연 — 의미 다름, 참조만).
- MySQL은 Docker 컨테이너에서 실행. 셸 함수로 접근. 모든 `.sh`는 실행 권한.

---

## Step A: 공통 인프라 — executor·핀·상태 해시·DTO·ops 표면

> 측정 유효성을 먼저 깔고 시작한다. 비동기는 응답(202)과 실제 발급이 분리돼 측정을 깨뜨리기 쉽다 — 수치를 내기 전에 그 수치를 신뢰할 수 있는 상태부터 만든다.

### 제한사항
- 엔티티·관계 추가 금지. v3 발급 서비스(`CouponIssueServiceV3`)는 재사용만, 수정 금지.
- 파사드/워커 빈 분리 원칙 준수.

### 1. 핀 단일 소스 — `AsyncPins`
- 위치: `infrastructure/asyncqueue/AsyncPins.java`
- Phase 3-4 신규 핀의 단일 소스 (상수 클래스). 측정 하네스의 핀 표와 1:1.
- 핀 값:
  - `EXECUTOR_CORE = 4`, `EXECUTOR_MAX = 4` (**max=core 고정** — 관찰 축 큐 만석 구간에서 v6a만 소비 동시성이 증원되는 오염 방지)
  - `EXECUTOR_QUEUE_CAPACITY = 10_000`, `REJECTION_POLICY = "AbortPolicy"`
  - `QUEUE_CAP = 10_000` (v6b/v6c 입구 거절 임계 — v6a queueCapacity와 일치)
  - `CONSUMERS = 4` (전 버전 소비 동시성)
  - `BRPOP_TIMEOUT_MS = 1_000`, `XREAD_BLOCK_MS = 1_000`, `XREAD_COUNT = 10`
  - `XAUTOCLAIM_INTERVAL_MS = 5_000`, `XAUTOCLAIM_MIN_IDLE_MS = 10_000`
  - `GRACEFUL_SHUTDOWN_SEC = 10`, `STATUS_TTL_SEC = 3_600`

### 2. 전용 executor — `AsyncExecutorConfig`
- 위치: `infrastructure/asyncqueue/AsyncExecutorConfig.java`
- `@Configuration @EnableAsync`. `@Bean("couponExecutor") ThreadPoolTaskExecutor`:
  - corePoolSize/maxPoolSize/queueCapacity = `AsyncPins`의 4/4/10000
  - threadNamePrefix `couponExecutor-` (T1 검증: 처리 스레드명이 이 접두여야 비동기)
  - `RejectedExecutionHandler` = `ThreadPoolExecutor.AbortPolicy` (큐 만석 + 풀 만석 → 파사드가 `429 QUEUE_FULL`로 매핑)
  - graceful: `setWaitForTasksToCompleteOnShutdown(true)`, `setAwaitTerminationSeconds(GRACEFUL_SHUTDOWN_SEC)`
- 메모: Java 풀은 core가 차면 큐에 먼저 줄을 세우고 큐가 가득 차야 max로 증원한다 — max=core로 그 증원 단계를 봉인했다.

### 3. 부팅 핀 assert — `AsyncPinsBootAssert`
- 위치: `infrastructure/asyncqueue/AsyncPinsBootAssert.java`
- `@EventListener(ApplicationReadyEvent.class)`에서 `couponExecutor` 실제 풀의 core/max/queueCapacity/rejectionPolicy를 `AsyncPins` 기대값과 대조 → 불일치 시 `IllegalStateException`으로 **기동 실패**.
- "executor 빈이 있다"가 아니라 "핀 값 그대로 만들어졌다"를 기동 시점에 확정한다 (런타임 재대조는 하네스 `assert_ops_pins`가 측정마다 수행 — 이중 게이트).

### 4. 상태 해시 — `IssueStatusRepository`
- 위치: `infrastructure/asyncqueue/IssueStatusRepository.java` (`StringRedisTemplate`)
- 키: `coupon:issue:status:{requestId}`(해시), `coupon:issue:seq`(INCR 시퀀스)
- **관측 전용**. 발급 사실의 진실 소스는 행 수.
- 기록 순서 계약: 파사드가 **선생성(`createAccepted` = ACCEPTED) → 큐 적재 → 적재 실패 시 삭제(`deleteOnReject`)** 순서를 지키고, 적재 성공 후 status를 다시 쓰지 않는다 — ACCEPTED를 덮는 방향은 워커의 터미널 기록(`complete`) 단방향뿐이다.
- 메서드: `nextSeq()`(INCR), `createAccepted(requestId, version, userId, enqueueSeq, acceptedAt)`(TTL `STATUS_TTL_SEC`), `deleteOnReject(requestId)`, `complete(requestId, status, completedAt)`, `find(requestId)`.
- `userId`·`enqueueSeq` 필드는 적재 seq vs 커밋 순서 역전 산출(T9)의 조인 키.

### 5. DTO
- 위치: `api/coupon/dto/`
- `IssueAcceptedResponse(requestId, status)` — 정적 팩토리 `accepted(requestId)` → status `"ACCEPTED"`. **202는 "접수"지 "발급"이 아니다.**
- `IssueStatusResponse(requestId, status, acceptedAt, completedAt)` — `from(requestId, hash)`로 상태 해시를 그대로 비춘다. `completedAt`은 터미널 도달 전 null.
- 요청은 기존 `CouponIssueRequest(couponId, userId)` 재사용.

### 6. 카오스 스위치 — `ChaosKillSwitch`
- 위치: `infrastructure/asyncqueue/ChaosKillSwitch.java`
- kill 지점 변수화: `AFTER_POP`(꺼낸 직후·처리 시작 전 — v6b 유실 재현), `AFTER_COMMIT`(v3 커밋 직후·터미널 기록/XACK 전 — v6c 재전달 주입).
- `arm(point)`로 무장, `maybeHalt(point)`가 무장된 지점에서 `Runtime.getRuntime().halt(137)` (= `kill -9` 등가, JVM 즉사 — 셧다운 훅·finally 모두 건너뜀). SIGTERM(graceful) 대조는 하네스가 프로세스에 신호를 보내는 방식이라 이 스위치와 무관. kill 대상은 앱 JVM뿐.

### 7. ops 표면 — `CouponAsyncOpsController`
- 위치: `api/coupon/CouponAsyncOpsController.java` (`/api/v6/ops`) — 측정 하네스 전용, 사용자 트래픽 아님.
- `GET /pins` — 신규 핀 런타임 실측값(executor 값은 설정 echo가 아니라 **실제 풀에서 읽음**). `chaosArmed`도 노출(직전 카오스 런의 잔존 무장 검출 — 평시 null).
- `GET /depth` — v6a 깊이 소스 (`queueSize + activeCount`).
- `POST /chaos/arm?point=` — 카오스 무장.

### Step A Build Check
- `./gradlew compileJava`. 앱 기동 시 `AsyncPinsBootAssert`가 핀을 검증(불일치면 기동 실패).

---

## Step B: v6a — 큐 = JVM 힙 (`@Async` ThreadPool)

### 설계 의도
가장 단순한 비동기 — 큐를 외부 인프라 없이 JVM 힙(executor 대기열)에 둔다. 입구 즉시 발급을 끊고 `202`로 떼어내는 비동기의 기본형을 세우고, "앱이 죽으면 대기분이 통째 증발한다"는 힙 큐의 구조적 한계를 노출해 v6b(큐를 프로세스 밖으로)의 근거를 만든다.

### 구현된 컴포넌트와 경로
- `service/coupon/async/CouponIssueFacadeV6a.java` — 적재만 하고 `requestId` 반환. `nextSeq` → `createAccepted` → `worker.processAsync(...)`(다른 빈 → 프록시 경유 보장) → `TaskRejectedException`(AbortPolicy, 큐 만석) 시 `deleteOnReject` 후 `QueueFullException`.
- `service/coupon/async/CouponIssueWorkerV6a.java` — `@Async("couponExecutor")` 메서드 하나. **파사드와 빈 분리**로 self-invocation 회피. `CouponIssueProcessor`에 위임.
- `service/coupon/async/CouponIssueProcessor.java` (v6 공통) — `v3Service.issue(...)`로 발급(FOR UPDATE + 커밋), 결과를 상태 해시 터미널로 기록. 상태 전이 ACCEPTED → ISSUED | SOLD_OUT | FAILED. 카오스 훅(`AFTER_POP`/`AFTER_COMMIT`) 포함.
- `service/coupon/async/QueueFullException.java` — `429 QUEUE_FULL` 매핑(백프레셔, 오류 아님).
- API: `POST /api/v6a/coupons/issue` → `202 {requestId, status:ACCEPTED}` (`CouponAsyncController`).

### 큐 생존범위 · 유실 양상 · ack 유무
- **생존**: 큐 = JVM 힙. 앱 사망 시 대기분 통째 증발(구조적).
- **유실**: 구조적 (ack 개념 자체가 없음).
- **ack**: 없음.

### 이 칸이 못 하는 것
- 앱 사망 시 대기분을 보존하지 못한다 — 큐가 프로세스 안에 있기 때문. 본격 유실 실험은 v6b로 넘어간다.

---

## Step C: v6b — 큐 = Redis List (`LPUSH` / `BRPOP`)

### 설계 의도
큐를 프로세스 밖(Redis)으로 옮겨 앱 재시작에도 큐가 살아남게 한다. 단 List는 ack가 없으므로 "꺼낸 직후 ~ 커밋 전"에 워커가 죽으면 그 건이 유실되는 at-most-once 구간이 생긴다 — 이 유실을 **의도적으로 재현·기록**해 v6c(ack+재전달)의 근거를 만든다.

### 구현된 컴포넌트와 경로
- `infrastructure/asyncqueue/IssueListQueueRepository.java` — `offer`(LLEN 선검사 = 비원자 근사 가드 → 상한 초과 시 false, `LPUSH`), `poll`(`BRPOP` 1s). 꺼내는 순간 큐에서 삭제(ack 없음).
- `service/coupon/async/CouponIssueFacadeV6b.java` — v6a와 동형 계약: `createAccepted` → `IssueMessage` JSON 적재 → 실패 시 `deleteOnReject` + `QueueFullException`. (`IssueMessage(requestId, couponId, userId)` record)
- `service/coupon/async/CouponQueueWorkerV6b.java` — `SmartLifecycle`. `start()`에서 `CONSUMERS`(4)개 백그라운드 스레드가 `BRPOP` 루프 → `CouponIssueProcessor.process`. 일시 Redis 오류는 로그 후 짧은 대기·재시도(루프 생존). **T8 graceful**: `stop()`이 새 pop을 멈추고 in-flight를 핀 시간(10s)까지 `join` 대기.
- API: `POST /api/v6b/coupons/issue`.

### 큐 생존범위 · 유실 양상 · ack 유무
- **생존**: 큐는 프로세스 밖(Redis). 앱이 죽어도 *적재된* 큐는 외부 보존.
- **유실**: `BRPOP`으로 꺼낸 뒤(ack 없음) 커밋 전에 워커가 죽으면 그 건 영구 미처리(at-most-once). 단 **종료 방식에 특정** — `kill -9`(halt)면 유실, graceful(SIGTERM)이면 executor 드레인으로 유실 0.
- **ack**: 없음.

### 이 칸이 못 하는 것
- 꺼낸 메시지를 보호하지 못한다(ack 없음) — halt 유실을 구조적으로 닫지 못한다. 유실을 닫으려면 ack + 재전달이 필요 → v6c.

---

## Step D: v6c — 큐 = Redis Stream + 컨슈머 그룹

### 설계 의도
ack + 재전달로 유실 자체를 닫는다. `XREADGROUP`이 메시지를 **PEL(Pending Entries List)에 등록**한 채 전달하므로 `XACK` 전에 죽어도 메시지가 잔류한다 — 회수 루프가 재처리한다. 그 대가는 **재전달 = 중복**이고, 그 중복을 `UNIQUE(coupon_id, user_id)`가 최후 방어선으로 **멱등 흡수**한다(at-least-once → 행 수 불변).

### 구현된 컴포넌트와 경로
- `infrastructure/asyncqueue/IssueStreamQueueRepository.java` — `offer`(미처리 깊이 가드 → `XADD`), `unprocessedDepth`(`XLEN` — ack 완료분을 `XDEL`로 트림하므로 XLEN == lag + pending), `ensureGroup`(NOGROUP 자가 복구), `readGroup`(`XREADGROUP` block 1s/count 10), `ack`(`XACK` + `XDEL` 트림), `pending`(`XPENDING`), `claim`(`XCLAIM` min-idle).
- `service/coupon/async/CouponIssueFacadeV6c.java` — v6a/b 동형 계약, `Map` 필드로 `XADD`.
- `service/coupon/async/CouponStreamWorkerV6c.java` — `SmartLifecycle`. `CONSUMERS`(4)개 컨슈머 `c1~c4` + **별도 claimer 스레드**.
  - **XACK 시점 계약**: 처리 결과가 무엇이든(ISSUED·SOLD_OUT·FAILED·중복 흡수) 터미널 상태 기록 **후** XACK. (ISSUED만 ack하면 탈락 ~900건이 PEL에 잔류해 회수 루프가 무한 재전달·깊이가 0이 안 됨.)
  - **PEL 회수는 자동이 아니다**: claimer가 주기(5s)마다 `XPENDING`으로 PEL을 보고 min-idle(10s) 경과분만 `XCLAIM`해 재처리 — 재전달이 여기서 태어난다.
- `service/coupon/async/CouponIssueProcessor.java`(공통) — 재전달분의 INSERT 충돌(`DuplicateIssueException`/`DataIntegrityViolationException`)을 catch해 `[IDEMPOTENT-ABSORB]` 로그 후 status를 ISSUED로 흡수. **행 수는 절대 늘지 않는다.**
- API: `POST /api/v6c/coupons/issue`.

### 큐 생존범위 · 유실 양상 · ack 유무
- **생존**: 미ack 메시지는 PEL에 잔류 — 앱이 죽어도 사라지지 않음.
- **유실**: `AFTER_COMMIT` halt(미ack 크래시)에도 PEL 회수로 **0**.
- **ack**: 있음(`XACK`). 회수: `XPENDING` + `XCLAIM`(min-idle) — **자동이 아니라 회수 루프가 수행**.
- **중복**: 재전달 발생 → `UNIQUE(coupon_id, user_id)` 충돌 → ISSUED로 멱등 흡수(행 수 불변).

### 이 칸이 못 하는 것
- **exactly-once가 아니다.** 달성한 것은 "유실 없음(PEL 회수) + 중복은 DB 유니크가 흡수"의 조합뿐. 멱등 키 없는 부수효과(알림 등)는 범위 밖. DLQ·Kafka도 백로그.

---

## 측정 계획 (as-built)

> 측정 하네스: `scripts/measure/run-coupon-async.sh`, `test/load/coupon-async-test.js`. 결과: `results/phase3-4-async/`.

### 측정 유효성 — 수렴 게이트 (비동기의 핵심)
- 기존 판정("부하 종료 직후 행 수 == 100")은 비동기에서 거짓이 된다 — 부하가 끝나도 컨슈머가 큐를 소비 중이라 행 수가 100 미만이다. 버그가 아니라 **판정 시점의 의미가 바뀐 것**.
- 그래서 하네스에 **수렴 게이트**를 박는다: `큐 깊이 == 0 && rows(coupon_issue) 연속 3회 동일(1s 폴링) && 타임아웃 120s 내`. 부하 종료 직후가 아니라 **수렴 후** 판정한다. 초과 시 `NON_CONVERGED`(별도 FAIL 사유 — "부하 부족"이 아니라 "컨슈머 정지/회수 부재" 신호로 우선 해석).
- 깊이 소스 3종: v6a = `queueSize + activeCount`(ops/depth), v6b = `LLEN`, v6c = `XLEN`.
- 통제변수 핀은 ops 엔드포인트 런타임 assert(`assert_ops_pins`)와 인프라 격리 assert(컨테이너 compose 프로젝트 라벨 대조)로 강제 — 불일치 시 측정 abort.
- 자가 검증 2건: 고의 핀 불일치 → abort(exit 2), 고의 미수렴(컨슈머 없는 큐) → `NON_CONVERGED` 분류.

### 202 의미 변화 (T4 — 라벨 규율)
- `202`는 "발급"이 아니라 "**접수**"다. 발급 여부는 `GET /api/v6/coupons/issue-status/{requestId}`로 조회.
- 부하기(k6)가 재는 지연은 전부 **접수 지연**(큐 적재만이라 빠름, ~100ms). 실제 **E2E 발급 지연**(큐 대기 + v3 FOR UPDATE 처리)은 상태 해시의 `completedAt − acceptedAt`에서 산출(~700ms대, 접수의 ~7배). "202가 빨라졌다"를 "성능 개선"으로 읽으면 안 된다.

### 측정 모드
1. **정확성·수렴 축** (`accuracy <v6a|v6b|v6c>`): per-vu-iterations 500VU × 2iter = 1,000건 적재 → 수렴 게이트 → 터미널 회계. 판정: `rows == issued_counter == total_qty(100)`(초과 0) && `202_count == terminal_count`(조용한 증발 0). 부수치로 E2E 발급 지연(ISSUED p50/p95)·탈락 통보 지연(SOLD_OUT p95)·수렴 시간·T9 순서 역전.
2. **백프레셔 관찰 축** (`observe`): warmup 5s + steady 30s, 500VU, `total_qty` 대폭 상향. **수렴 게이트 미적용** — 깊이가 상한(10,000)을 치고 `429 QUEUE_FULL` 버킷이 자라는 것 자체가 결과. 접수율 ≈ 발급률(소비 동시성 4에 묶임).
3. **카오스 축** (`chaos <v6b|v6c> <AFTER_POP|AFTER_COMMIT> <halt|sigterm>`): 1,000건 적재(202 확정) → 드레인 구간 kill 무장 → 재기동 → 수렴 게이트 → 회계.
   - **v6b AFTER_POP halt** = 유실 기대(loss 모드, `유실분 := 202 − terminal ≥ 1`을 *기록*, 고치지 않음). **sigterm** = 유실 0 기대(normal 모드) — 종료 방식 대조.
   - **v6c AFTER_COMMIT halt** = 재전달 기대(redelivery 모드, `rows == ISSUED 수` && `rows ≤ total` && 멱등 흡수 로그 ≥ 1). 재전달 런 한정 `total_qty=1,100`(AFTER_COMMIT 훅은 발급 *성공* 경로에만 있어 qty=100이면 드레인 시점에 소진돼 발화 불가 — 발급 경로를 드레인까지 살리는 최소 변경).

### k6 버킷 (`coupon-async-test.js`)
- 응답을 `202`(접수) / `429`(QUEUE_FULL, 백프레셔) / `other`(비-HTTP·미분류 5xx — 분모 클린)로 분리 카운트. observe 프로필은 steady-only 메트릭 별도 집계(warmup 제외).
- 유니크 userId `__VU * 100000 + __ITER`(Phase 3-3 계승), 시나리오별 base로 충돌 방지.

### 실측 결과 요약 (게이트 통과 — `results/phase3-4-async/analysis.md`)
- **정확성(비교 합법 축)**: v6a/v6b/v6c 세 칸 모두 `rows/counter/total = 100/100/100`, 초과 0, `terminal == 202 == 1000`(증발 0).
- **유실(핵심 발견)**: 유실은 큐 매체가 아니라 *종료 방식*에 특정된다 — 같은 v6b가 `kill -9` 유실 2 / SIGTERM 유실 0. v6c는 PEL 회수로 유실 0.
- **중복**: v6c 재전달분(회수 33건) 중 halt 전 *이미 커밋된* 1건만 유니크 충돌 → 멱등 흡수, 행 수 불변(나머지 32건은 미커밋이라 정상 재처리).
- E2E p95(741/671/825 ms)은 **비단조 — 버전 간 순위 비교 금지**(런별 잡음, 정확성 축 부수치).

---

## Phase 3-4 최종 디렉토리 구조
```
scripts/
├── coupon/
│   └── reset-coupon.sql              (Phase 3-3 재사용 — 시드/리셋)
└── measure/
    └── run-coupon-async.sh           (accuracy / observe / chaos / selftest-*)

test/
└── load/
    └── coupon-async-test.js          (v6a/b/c 파라미터화, 202/429/other 버킷)

results/
└── phase3-4-async/
    ├── analysis.md                   (버전별 측정 분석 — 1차 출처)
    ├── INVALID-RUNS.md               (무효 런 보존: redis 격리 누수·포트 충돌)
    ├── k6/                           (v6{a,b,c}-{accuracy,observe,chaos-*}.json)
    ├── integrity/                    (v6{a,b,c}-accuracy.txt — total/counter/actual)
    ├── e2e/                          (터미널 회계 + E2E/탈락통보 지연 JSON)
    ├── depth/                        (수렴/관찰 깊이 시계열 CSV)
    └── selftest/                     (스레드명·카오스·핀·미수렴 증거 로그)

src/main/java/com/project/
├── api/coupon/
│   ├── CouponAsyncController.java    (POST /api/v6{a,b,c}/coupons/issue, GET /api/v6/coupons/issue-status/{id})
│   ├── CouponAsyncOpsController.java (/api/v6/ops: pins, depth, chaos/arm — 하네스 전용)
│   └── dto/
│       ├── IssueAcceptedResponse.java
│       └── IssueStatusResponse.java
├── service/coupon/async/
│   ├── CouponIssueFacadeV6a.java     (큐=JVM 힙)
│   ├── CouponIssueFacadeV6b.java     (큐=Redis List)
│   ├── CouponIssueFacadeV6c.java     (큐=Redis Stream)
│   ├── CouponIssueProcessor.java     (v6 공통 — v3 경로 재사용 + 멱등 흡수 + 카오스 훅)
│   ├── CouponIssueWorkerV6a.java     (@Async 워커)
│   ├── CouponQueueWorkerV6b.java     (BRPOP 워커, SmartLifecycle)
│   ├── CouponStreamWorkerV6c.java    (XREADGROUP 컨슈머 c1~c4 + claimer)
│   ├── IssueMessage.java             (v6b/v6c 큐 메시지 record)
│   └── QueueFullException.java       (429 QUEUE_FULL)
├── service/coupon/
│   └── CouponIssueServiceV3.java     (Phase 3-3 — 발급 경로 재사용, 수정 없음)
└── infrastructure/asyncqueue/
    ├── AsyncPins.java                (핀 단일 소스)
    ├── AsyncExecutorConfig.java      (couponExecutor — 전용 풀 + AbortPolicy + graceful)
    ├── AsyncPinsBootAssert.java      (부팅 핀 assert)
    ├── ChaosKillSwitch.java          (kill 지점 변수화 — Runtime.halt)
    ├── IssueStatusRepository.java    (상태 해시 — 관측 전용)
    ├── IssueListQueueRepository.java (v6b — LPUSH/BRPOP)
    └── IssueStreamQueueRepository.java (v6c — XADD/XREADGROUP/XACK/XCLAIM)
```

## 결과물 위치
- **측정 분석(1차 출처)**: `results/phase3-4-async/analysis.md` — v6a/b/c 정확성·관찰·카오스 + 사다리 요약.
- **무효 런 보존**: `results/phase3-4-async/INVALID-RUNS.md` — redis 격리 누수(StringRedisTemplate가 본체 6379로 기록한 잠복 버그, 별도 수정)·포트 충돌.
- **회고**: `docs/reports/phase3-4-async-issuance.md` — 3축 서사·측정 규율·메타 회고.
- **설계 지도**: `docs/plan/study-async-expedition-map.html`.

## 측정 규율 (이 phase의 전제)
1. 전 버전 **초과발급 0**이 통과 게이트.
2. v6c를 **exactly-once로 주장하지 않는다**(유실 없음 + 유니크 멱등 흡수의 조합일 뿐).
3. **버전 간 E2E 지연·throughput 직접 비교 금지**(큐 매체 confound + 런별 잡음).
4. **Phase 3-3 v3 동결본과 직접 비교 금지**(접수 지연 vs 처리 지연 — 의미 다름, 참조만).
5. 비교 합법 축은 **정확성(초과발급 0)** 하나다.
```
