# Phase 3-4 비동기 발급 — 측정 분석

> 실측 (2026-06). 본체 인프라와 분리된 전용 격리 스택(mysql 3307 / redis 6380, 본체 무접촉)에서 측정.
> 버전별 append.

## 측정 환경 (전 버전 공통 핀)

- 전용 스택: project `phase3-4-async`, mysql `phase3-4-async-mysql-1`(3307)·redis `…-redis-1`(6380),
  볼륨 `phase3-4-async_mysql-data`. 본체 redis 0건 유지 실증(매 런 후 확인).
- ops 핀 전 항목 일치: executor 4/4/10000·AbortPolicy(429)·소비 4·BRPOP 1s·XREADGROUP 1s/10·
  회수 5s/min-idle 10s·graceful 10s·TTL 3600s. MySQL 핀: lock_wait 50·REPEATABLE-READ.
- 부하: 정확성 500VU×2iter=1,000건 / 관찰 warmup 5s+steady 30s 500VU. 유니크 userId `__VU*100000+__ITER`.

---

## v6a — @Async + 인메모리 스레드풀 (큐=JVM 힙)

### T1 (self-invocation 회피) — 스레드명 증거
컨슈머가 `couponExecutor-*`(비동기)에서 실행됨 확인 — `http-nio-*`(동기) 아님. 파사드/워커 빈 분리 작동.
```
[v6-process] thread=couponExecutor-1 ... status=ISSUED
[v6-process] thread=couponExecutor-4 ... status=ISSUED
```
(전체: `results/phase3-4-async/selftest/v6a-thread-evidence.log`)

### 정확성·수렴 축 (1,000건 적재 → 수렴 게이트 → 터미널 회계)
| 지표 | 값 |
|---|---|
| 수렴 | CONVERGED (감지 2s) |
| rows / counter / total_qty | **100 / 100 / 100** (진실 소스 = 행 수, 초과 0) |
| 202 / 429 / other | 1000 / 0 / 0 |
| 상태 분포 | ISSUED 100, SOLD_OUT 900 |
| terminal_count == 202_count | **1000 == 1000 (유실 0)** — 조용한 증발 없음 |
| **E2E 발급 지연** (ISSUED, completedAt−acceptedAt) | **p50 533 / p95 741 / max 758 ms** (n=100) |
| **탈락 통보 지연** (T7, SOLD_OUT) | **p95 1149 / max 1158 ms** (n=900) |
| T9 적재↔커밋 순서 역전 | 56 (입구 비원자 INCR↔적재 잡음 포함) |
→ 판정 **PASS**. 원본: `e2e/v6a-accuracy.json`, `k6/v6a-accuracy.json`, `integrity/v6a-accuracy.txt`, `depth/v6a-accuracy.csv`.

### 백프레셔 관찰 축 (warmup+steady, 수렴 게이트 미적용)
| 지표 | 값 |
|---|---|
| ① 접수 처리량 (steady) | 61.4/s (202 1,842건/30s) |
| ② 발급 완료 처리량 | 62.5/s (35s 창 2,187 커밋) |
| 429 (QUEUE_FULL 백프레셔) | 218,388건 — 깊이 포화 후 입구 거절 폭증 |
| ④ 큐 깊이 피크 | 10,004 (상한 핀 10,000 — 포화 후 429가 자란다) |
| 접수 지연 p95 | **105.5 ms** (라벨: **접수 지연** — 성능 개선 아님, T4) |
→ 원본: `k6/v6a-observe.json`, `depth/v6a-observe.csv`.

### T4 괴리 전시 (비교 규율 — 접수 vs E2E 나란히)
- **접수(202) 지연 p95 ≈ 105 ms** (관찰 축) ↔ **E2E 발급 지연 p95 = 741 ms** (정확성 축).
- 접수는 힙 큐 적재만이라 빠르고, 실제 발급(큐 대기 + v3 FOR UPDATE 처리)은 ~7배 느리다. 이 괴리가
  이번 phase의 전시물 — "202가 빨라졌다"를 "성능 개선"으로 읽으면 안 된다(T4). v3 동기 p95(2,630ms,
  동결본)와의 직접 비교는 **금지**(접수 지연 vs 처리 지연, 의미 다름 — 비교 규율 1).
- 합법 비교축: 정확성(수렴 후 행 수 100, 초과 0)과 E2E 발급 지연·수렴 시간뿐.

### 이 칸이 못 하는 것 (v6b의 근거)
v6a는 큐가 JVM 힙이라 **앱 사망 시 대기분 통째 증발**한다(본격 유실 실험은 v6b/AFTER_POP). 관찰 축
종료 시 깊이 10,004 잔존이 정상(수렴 게이트 미적용).

### 측정 인프라 사고 + 수정 (격리 검증 중 발견)
초기 v6a 정확성 런이 FAIL(terminal=0): 앱의 `StringRedisTemplate`(상태 해시)이 `RedisConfig`의 무인자
`new LettuceConnectionFactory()` 탓에 `spring.data.redis.*`를 무시하고 **본체 redis(6379)** 로 상태 1,003건을
기록(Redisson은 6380 정상). 전용 스택 대체포트(6380) 토폴로지에서만 드러나는 잠복 버그 — 앱이 redis
클라이언트를 둘 가졌고 그중 하나만 격리됐던 것. **수정**: `RedisConfig`가 `spring.data.redis.host/port`를
따르게 변경 → StringRedisTemplate도 6380. 본체 redis 오염분(coupon:issue:* 1,003건) 네임스페이스 한정 DEL로
복원(본체 0건). 재측정 전 단건 검증으로 상태 키가 전용 6380 적재·본체 0건 유지 확인. 위 표는 **수정 후
재측정** 수치. 무효 런 기록: `results/phase3-4-async/INVALID-RUNS.md`.

---

## v6b — Redis List (LPUSH + BRPOP)

### 정확성·수렴 축
| 지표 | 값 |
|---|---|
| 수렴 | CONVERGED |
| rows / counter / total_qty | **100 / 100 / 100** (초과 0) |
| 202 / 429 / other | 1000 / 0 / 0 |
| 상태 분포 | ISSUED 100, SOLD_OUT 900 |
| terminal == 202 | 1000 == 1000 (유실 0) |
| E2E 발급 지연 | p50 467 / p95 671 / max ~ ms (n=100) |
| 탈락 통보 지연 (T7) | p95 1266 / max 1278 ms (n=900) |
| T9 순서 역전 | 99 (입구 비원자 잡음 포함) |
→ 판정 **PASS**. **통제변수 v6a 일치** (executor 4/4·부하 500VU×2iter·핀 동일 — 사다리 내 단일 변수=큐 매체만 차이).

### 백프레셔 관찰 축
| ① 접수 | ② 커밋 | 429 | ④ 깊이 피크 | 접수 지연 p95 |
|---|---|---|---|---|
| 80.1/s | 81.6/s | 205,563 | 10,042 | 100.5 ms (접수 지연 — T4) |

### 카오스 — 유실 재현(T5) + 종료방식 대조(T8) 【이 칸의 핵심】
드레인 구간에서 "꺼낸 직후(커밋 전)" kill. 두 종료방식 대조:

| kill-point=AFTER_POP | 종료방식 | rows | 유실분 | 상태 잔류 | 판정 |
|---|---|---|---|---|---|
| **halt** (kill -9 등가) | 즉사 | 100 | **2** | ACCEPTED 2 | PASS(loss 기록) |
| **sigterm** (graceful) | executor 드레인 후 종료(핀 10s) | 100 | **0** | 없음 | PASS(normal) |

- **유실 증거 (halt)**: ACCEPTED 잔류 2건 = 큐에서 꺼낸 뒤(BRPOP) 커밋 전에 JVM이 즉사해 영구 미처리.
  requestId: `13849945-…`, `e6af0bed-…` (→ `selftest/v6b-chaos-evidence.log`). **고치지 않고 기록** —
  이 유실이 v6c(재전달+멱등 흡수)의 근거다.
- **T8 대조 (핵심 발견)**: 유실은 **큐 매체의 본질이 아니라 *비우아한 종료(halt)*에 특정**된다.
  SIGTERM은 executor가 in-flight를 드레인하고 종료하므로 유실 0. 즉 "Redis List라 유실"이 아니라
  "kill -9면 유실, graceful이면 무손실". rows는 둘 다 100 (진실 소스 = 초과발급 0 불변).
- 부수(측정 아티팩트, 성능 수치 아님 — 동종 지표끼리 병치): 탈락 통보 p95 = halt 21,673ms / sigterm
  13,473ms. 둘 다 앱 사망~재기동 공백이 통보 지연에 가산된 값(SOLD_OUT의 completedAt이 재기동 후라).
  T9 역전 halt 128 / sigterm 287.
→ 원본: `e2e/v6b-chaos-AFTER_POP-{halt,sigterm}.json`, `k6/…`, `depth/…`.

---

## v6c — Redis Stream + 컨슈머 그룹 (XADD / XREADGROUP / XACK)

### 정확성·수렴 축
| 지표 | 값 |
|---|---|
| 수렴 | CONVERGED |
| rows / counter / total_qty | **100 / 100 / 100** (초과 0) |
| 상태 분포 | ISSUED 100, SOLD_OUT 900 / terminal==202 (유실 0) |
| E2E 발급 지연 | p50 659 / p95 825 ms (n=100) |
| 탈락 통보 지연 (T7) | p95 1309 / max 1329 ms (n=900) |
| T9 순서 역전 | 685 (입구 비원자 잡음 포함; 컨슈머 그룹 분배라 v6a/b보다 큼) |
→ 판정 **PASS**. 통제변수 v6a/v6b 일치(consumers 4·부하 동일).

### 백프레셔 관찰 축
| ① 접수 | ② 커밋 | 429 | ④ 깊이 피크(XLEN) | 접수 지연 p95 |
|---|---|---|---|---|
| 82.4/s | 82.1/s | 201,702 | 10,047 | 117.5 ms (접수 지연 — T4) |
※ 깊이 소스 = **XLEN**(XACK 후 XDEL 트림 전제). XINFO GROUPS의 lag는 XDEL tombstone 후 nil이 되어
과소집계(전이 트랩 #4). **caveat**: XACK→XDEL은 비원자(별개 호출)라 사이 크래시 시 acked-but-undeleted
잔류로 XLEN이 **과대** 집계될 수 있다 — 단 관찰 축 지표일 뿐 진실 소스(rows) 무관이고 과대는 보수적
방향(헛 429)이라 유실·초과발급 못 만든다.

### 카오스 — 재전달 주입(T6) + PEL 회수(T5) 【이 칸의 핵심】
"커밋 직후·XACK 직전" kill → 재기동 → 회수 루프가 PEL 재전달 → 같은 (coupon_id,user_id) INSERT가
기존 `UNIQUE(coupon_id,user_id)`에 충돌 → **ISSUED로 멱등 흡수**. 재전달 런 한정 **total_qty=1,100**
(AFTER_COMMIT 훅은 발급 성공 경로에만 있어 qty=100이면 드레인서 발화 불가 — 발급 경로를 드레인까지 살리는 최소 변경).

| 지표 | 값 | 의미 |
|---|---|---|
| rows / ISSUED / total_qty | **1000 / 1000 / 1100** | **rows == ISSUED**(행 수 불변 본질 — 재전달이 행을 안 늘림), rows ≤ total(초과 0) |
| terminal == 202 | 1000 == 1000 (유실 0) | PEL 회수가 전 건을 닫음(v6b halt 유실 2와 대조) |
| **PEL 회수 (T5)** | XAUTOCLAIM 후보 33 → 회수 33 | **회수는 자동이 아님** — 별도 회수 루프가 주기적 실행(`[v6c-claimer] PEL 회수: …`) |
| **유니크 충돌 흡수 (T6)** | IDEMPOTENT-ABSORB **≥1** (실측 1건) | 재전달분 중 halt 전 커밋된 1건이 재INSERT→UNIQUE 충돌→ISSUED 흡수. 나머지 32건은 미커밋이라 정상 재처리(중복 아님) |
| E2E p50/p95 | 7888 / 9042 ms | 사망~재기동~회수 공백 포함(측정 아티팩트) |
→ 성공 시그니처 충족: **rows == ISSUED 상태 수(1000) && 유니크 충돌 로그 ≥ 1**. 충돌 로그가 방어 작동 증거.
증거: `selftest/v6c-chaos-evidence.log`. 원본: `e2e/v6c-chaos-AFTER_COMMIT-halt.json`.

### exactly-once 주장 금지 (P5)
달성한 것은 **"유실 없음(PEL 회수) + 중복은 DB 유니크가 흡수"의 조합**뿐이다. 멱등 키 없는 부수효과
(알림 등)는 범위 밖. v6c가 v6b 유실(halt loss=2)을 닫는 구조 = "회수 + 멱등 흡수"이지 exactly-once 보장이 아니다.

---

## 사다리 요약 (3축 서사 — throughput 헤드라인 금지)

| 축 | v6a (힙) | v6b (List) | v6c (Stream+그룹) |
|---|---|---|---|
| **생존** (정확성: 수렴 후 행 수, 초과 0) | 100/100/100 ✓ | 100/100/100 ✓ | 100/100/100 ✓ |
| **유실** (ungraceful kill) | 앱 사망 시 힙 대기분 증발(구조적) | AFTER_POP halt 유실 2 (sigterm 0) | AFTER_COMMIT 후 미ack도 **PEL 회수로 0** |
| **중복** (재전달) | — | — | 재전달→유니크 충돌→멱등 흡수(행 수 불변) |
| E2E p95 (정확성 축) | 741 ms | 671 ms | 825 ms |

세 칸 모두 **초과발급 0**(진실 소스=행 수) 게이트 통과. 비교는 생존·유실·중복 3축으로 — 큐 매체가
다르므로 throughput 헤드라인은 금지. **표의 E2E p95 행(741/671/825)은 비단조 — 버전 간 직접 순위 비교
금지**(런별 잡음, 큐 매체 우열 아님; 정확성 축 부수치일 뿐 비교축 아님). v3 채택 결정(phase 3-3)을
흔들지 않음: 이 실험은 v3 대체가 아니라 *같은 게이트를 통과하는 다른 구조(큐)의 비용 프로파일 측정*이다.
