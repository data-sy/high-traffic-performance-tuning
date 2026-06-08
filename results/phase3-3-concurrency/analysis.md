# Phase 3-3 — 동시성 제어 (쿠폰 발급) 측정 분석

> 📋 **템플릿** — 본 실행(`phase/3-3-concurrency-lock`)이 각 버전 측정 직후 해당 섹션을 채운다.
> 숫자는 빈칸(`—`)으로 두었다. 형식은 기존 `results/phase3-1-*`·`results/phase3-2-*` 관례를 따르되
> 구체 파일 명명은 런 세션이 그 두 디렉토리를 읽어 맞춘다(스펙 §10).
>
> **정합성 단일 진실 소스**: `SELECT COUNT(*) FROM coupon_issue WHERE coupon_id=1` (= `actual_issued`).
> `coupon.issued` 컬럼은 lost update로 부정확할 수 있어 보조지표로만 본다.

---

## 통제변수 (v1~v5 공통 핀 — 전 버전 동일이 핵심)

| 변수 | 핀 값 | 위치 |
|------|-------|------|
| HikariCP maximum-pool-size | 10 | application.yml |
| HikariCP connection-timeout | 30000ms | application.yml |
| Tomcat threads.max | 200 | application.yml |
| MySQL innodb_lock_wait_timeout | 50s | 하네스 런타임 assert |
| MySQL transaction_isolation | REPEATABLE-READ | 하네스 런타임 assert |

### 하네스 (양축 분리 — PINS.md)
- 정확성 축: `constant-vus 500 / 10s`, `total_qty=100`.
- 성능 축: `warmup 5s + steady 30s`(steady-only), `total_qty=대용량`(1억).
- 성공 시그니처: 블로킹(v2/v3) `actual==counter==total`; fail-fast(v4/v5) `≤total`(초과 0) + 지속부하 시 `==total`.

### 핀 검증 방식 (정직한 비대칭 — "모든 핀을 런타임 실측"이 아님)
- **MySQL 2종(innodb=50/RR)**: 하네스 런타임 assert가 매 측정 시작에 실측·대조(불일치 abort). 결정적.
- **HikariCP pool=10**: negative test로 결정적 실측 — yml을 비기본값 11로 띄워 `hikaricp_connections_max=11`(실 Hikari 메트릭) 확인 → 10 복원. 기본값(10) 충돌을 깨고 바인딩 경로 증명.
- **HikariCP connection-timeout=30000**: pool과 **동일 `spring.datasource.hikari.*` 블록**(같은 바인더)이 바인딩됨이 증명됨 → 형제 키 전이로 닫음(별도 의심 근거 없음).
- **Tomcat threads.max=200**: ⚠️ **런타임 직접 검증 안 함.** micrometer thread 메트릭 미등록(session 메트릭만) + 값이 프레임워크 기본값(200)과 동일하고 역할이 "충분히 큰 값(바인딩 비제약)"이라, 핀이 먹든 기본값으로 떨어지든 결과(200·비제약)가 동일 → **검증으로 구별할 두 시나리오가 없어 검증 동기 부재.** yml 블록 파싱·부팅 정상(바인딩 에러 0)으로 충분. (pool/connTO의 침묵형 위험 = "안 먹으면 값이 달라져 ⓐ/ⓑ 비율 변동"이 Tomcat엔 적용 안 됨.)
- 요약: **핀 3종 중 2종 결정적 실측, Tomcat 1종 무해 미검증** — 방법론의 정직한 비대칭으로 기록.

---

## v1 — No Lock (기준선, 초과 발급 재현)

### 부하 파라미터 (★ 확정 — v2~v5 전이 기준)
- 정확성 축: `constant-vus` **500 VU / 10s**, `total_qty=100`.
- 성능 축: `warmup 5s + steady 30s`(steady-only 집계), `total_qty=100000000`(1억 — 발견3: 100이면 즉시 매진→성공경로 측정 불가).
- 유니크 userId = `시나리오base + __VU*100000 + __ITER` (warmup/steady 겹침 분리).
- → 정확성 축에서 actual=999 ≫ 100로 초과발급이 또렷이 재현됨 → **이 프로필을 v2~v5에 고정 재사용**.

### 결과
**정확성 축** (`integrity/v1.txt`, `k6/v1-accuracy.json`):
| 지표 | 값 |
|------|----|
| total_qty | 100 |
| issued_counter (coupon.issued) | **100** *(lost update로 천장 캡 — 정확성 진실 아님)* |
| **actual_issued (coupon_issue 행수)** | **999** |
| 초과량 | **+899** (10배 초과 발급) |
| 성공(200) / 한도소진(409) / other(비-HTTP·5xx) | 999 / 27654 / **0** |
| 성공 시그니처 (actual==counter==total) | **FAIL** ✓ *(무락이라 FAIL이 정답 — PASS면 버그)* |

**성능 축** (`k6/v1-performance.json`, steady-only):
| 지표 | 값 |
|------|----|
| 성공 throughput (200/s) | **404.0/s** (steady 성공 12120건 / 30s) |
| 성공(200) 지연 p95 / p99 | **1837.9ms / 1935.4ms** |
| steady 200/409/503/other | 12120 / 0 / 0 / 0 (409율 0% · 503율 0%) |

### 해석
- **초과 발급 재현**: check-then-act(`issued < total` → `issued++`)가 비원자 → 500 VU가 stale `issued`를 동시에 읽고 통과 → actual=999. 무락 기준선의 **FAIL이 성공 시그니처**(이후 버전이 이 999를 100으로 잡는다).
- **counter vs actual 갈림 = lost update 증거**: `issued_counter=100`(천장에 캡된 것처럼 보임) ≪ `actual_issued=999`. **`coupon.issued`만 믿으면 "100 발급, 한도 지켜짐"으로 오판** — 실제 행수 999가 진실. 정합성 판정은 항상 coupon_issue 행수로.
- **`other`=0 (이번 런)**: 핸들러 수정(ⓐ/ⓑ를 v3 타입예외로 한정)으로 v1 풀 누수는 503이 아니라 500→other로 새야 하는데, **이번 부하에선 풀 고갈 자체가 미발생**(503=0, other=0)이라 그 경로는 안 탔다. 즉 v1에서 spurious POOL_TIMEOUT(ⓐ) 0 — 전역 핸들러였다면 났을 오염이 없음을 실증(다만 풀 누수 자체가 안 떠 음성 확인). v1 정확성 런에서 other>0이 뜬다면 그것은 무락 풀 누수 500의 자연 귀결이지 버그가 아니다.
- **성능축 = 풀-바운드 기준선**: 무락인데도 p95≈1.8s. total=1억이라 매진 없이 전 요청이 성공 경로(findById+insert+commit)를 타는데, 500 VU가 pool=10을 두고 경합 → 커넥션 대기로 지연 누적(connTO 30s 안이라 503 없이 흡수). throughput 404/s가 이후 버전 비교의 기준선.

### 측정 범위 (Note)
- 유니크 userId라 중복 차단 경로는 부하로 안 돌아감 → 초과발급만 대상. 중복 차단은 단건 재요청(같은 userId 2회 → 409 ALREADY_ISSUED, 스모크 확인)으로만 검증.
- throughput/지연은 **버전 간 직접 비교 금지**(Step G) — outcome별·정책별로 의미가 달라 단일 우승자 금지.

---

## v2 — synchronized (애플리케이션 락)

### 구현 요지
- 파사드 `CouponIssueServiceV2`: `synchronized(lock)`는 트랜잭션 경계 밖. 실제 tx는 **별도 빈**
  `CouponIssueServiceV2Inner`(@Transactional) 프록시 호출 → 커밋이 모니터 안에서 완료. 락 ⊃ 트랜잭션.
- ⚠️ 프록시 함정 회피 검증: 같은 빈 자기호출이면 커밋이 모니터 밖으로 새 lost update 재발 →
  actual&gt;100이 나야 함. **actual=100(PASS)이 분리가 런타임에 올바로 먹었다는 결정적 증거.**

### 결과
| 축 | 지표 | v2 |
|----|------|-----|
| 정확성 | total/counter/actual | 100 / 100 / **100** |
| 정확성 | 성공 시그니처 | **PASS** ✓ *(actual==counter==total)* |
| 정확성 | 성공200 / 한도409 / other | 100 / 11576 / 0 |
| 성능 | 성공 throughput | **263.6/s** (7907건/30s) |
| 성능 | 성공(200) p95 / p99 | **2970.7ms / 3478.2ms** |
| 성능 | 409율 / 503율 (steady) | 0% / **0%** |

### 해석 / 학습 노트
- **정합 회복**: v1의 actual=999 → v2 actual=100. `synchronized` 전직렬화로 초과 0.
- **counter도 진실과 일치(v1과 결정적 차이)**: v1은 counter=100≪actual=999(lost update)였는데, v2는
  counter=100==actual=100. 직렬화가 read-modify-write를 원자화 → `coupon.issued`까지 정확해짐.
- **프록시 함정 회피 실증**: 파사드+별도 빈 분리가 올바라 actual=100(초과 0). 분리 실패였다면 단일 JVM에서도 초과.
- **직렬화 비용**: throughput 404/s(v1) → 263.6/s(v2), p95 1.8s → 3.0s. 전직렬화의 대가.
  (★ 버전 간 직접 비교는 Step G 규율 — 여기선 v2 자체의 정책 특성으로만 기록.)
- **경합 흡수 = park**: 모니터 대기는 무한 park라 거절 없음 → **503=0**(양축). fail-fast(v4/v5)와 대조될 지점.
- **한계**: 단일 JVM에서만 정합(인스턴스마다 별도 모니터) → 다중 인스턴스에선 붕괴 → v3(공유 DB행) 이행 근거.

---

## v3 — SELECT FOR UPDATE (DB 비관적 행 락)

### 구현 요지
- 락 획득·소비를 **하나의 `@Transactional`** 안에서(v2 파사드와 정반대). 503 두 갈래: ⓐ 풀 고갈 / ⓑ 행락 타임아웃.

### 결과
| 축 | 지표 | v3 |
|----|------|-----|
| 정확성 | total/counter/actual | — / — / — → — |
| 성능 | 성공 throughput | — |
| 성능 | 성공(200) p95 / p99 | — |
| 성능 | 503(ⓐ풀/ⓑ행락) / other | — / — |

### 학습 노트
- 직렬화 지점 이동(로컬 모니터→공유 DB행) → 다중 인스턴스 경계 초월(개념; 단일 JVM은 미실증).
- HikariCP `pending`/`active` 동시 캡처로 풀 포화. ⓐ 우세는 핀한 타임아웃 대소(30s<50s)의 구조적 귀결임을 병기.
- (방향성 재확인) v3 throughput이 v2 동급 이상인지 — 통념 반례 가능.

---

## v4 — Redis 분산 락 (Redisson RLock)

### 구현 요지
- 파사드(락 ⊃ tx), **워치독 자동갱신(leaseTime 미지정)**, waitTime=0(fail-fast). unlock 가드 + tryLock 예외 가드.

### 결과
| 축 | 지표 | v4 |
|----|------|-----|
| 정확성 (공유부하 10s) | total/counter/actual | — / — / — |
| 정확성 (재측정 90s, 필요시) | total/counter/actual | — / — / — |
| 성능 | 성공 throughput | — |
| 성능 | 성공(200) p95 / p99 | — |
| 성능 | 503율 / ⓐ/ⓑ/ⓒ 분해 | — |

### 해석 / 학습 노트
- 정확성 `≤ total`(초과 0). 10s 미충전은 fail-fast 정상 귀절(미달≠실패). 거절(ⓒ) 경로 신설.
- 직렬화 좌표 외부(Redis) 이동. 한계: SPOF, **fencing 부재**(워치독은 함정 좁힘, 제거 못 함).

---

## v5 — Redis 분산 락 (커스텀 SET NX + Lua)  ★ 최종

### 구현 요지
- v4와 동형 파사드, **고정 TTL 30s 무갱신**, `StringRedisTemplate` 단일(직렬화 일치), Lua compare-and-delete.

### 결과
| 축 | 지표 | v5 |
|----|------|-----|
| 정확성 | total/counter/actual | — / — / — |
| 성능 | 성공 throughput | — |
| 성능 | 성공(200) p95 / p99 | — |
| 성능 | 503율 / ⓐ/ⓑ/ⓒ | — |

### 해석 / 학습 노트
- v4와 정합성 일치(초과 0). ⚠️ v4↔v5 비동형(워치독 vs 고정TTL) — 성능 차이를 "락 메커니즘"으로만 읽지 말 것.
- (선택) ★스톨 데모: ttl<stall 인위 주입 → 두 홀더 동시 점유 → 초과(통제 산물, 정상 v5는 초과 0) = fencing 영역.

---

## 종합 비교 (Step G) — 사다리 트레이드오프

> ⚠️ **throughput/지연/거절률 버전 간 직접 비교 금지** — 경합 전략이 버전마다 달라(park/DB큐/fail-fast) 단일 컬럼이면 거짓 결론.
> 처리량·지연 모두 **outcome별 분리 컬럼**(성공200 / 한도409 / 락거절503). "거절"=503-only. 409는 버전 의존이라 비교 금지.

| 축 | 지표 | v1 | v2 | v3 | v4 | v5 |
|----|------|----|----|----|----|----|
| 정확성 | actual_issued (total=100) | — | — | — | — | — |
| 정확성 | 초과발급 | — | — | — | — | — |
| 성능 | 성공 throughput | — | — | — | — | — |
| 성능 | 성공 p95 | — | — | — | — | — |
| 성능 | 거절(503)율 | — | — | — | — | — |
| 경합 처리 | — | 무시(초과) | park | DB큐 | fail-fast | fail-fast |
| 정합 범위 | — | 없음 | 단일 JVM | 다중(공유 DB행) | 다중(외부 Redis) | 다중(외부 Redis) |
| 갱신 정책 | — | — | — | — | 워치독 | 고정 30s |
| fencing | — | — | — | — | 부재 | 부재 |

### 서사 축 (3겹 — "어느 락이 빠른가"가 아니다)
1. **정합 범위**: 없음(v1) → 단일 JVM(v2) → 다중·DB행(v3) → 다중·외부 Redis(v4·v5).
2. **경합 흡수**: 무시 → park → DB큐 → 거절/fail-fast. "직렬화 비용이 어디서 나오나".
3. **갱신 정책**(v4 vs v5): 워치독 vs 고정TTL. 워치독이 사주는 것=만료-중간탈취 차단(스톨 데모). 둘 다 fencing-safe 아님.

### 필수 명시
- **단일-JVM 한계**: v4/v5의 본 정당화(다중 인스턴스 정합)는 미실증(보강#1). 단일 JVM에선 v2~v5 다 정합, v4/v5는 RTT만큼 느림.
- **범위 한정**: 모든 결론은 단일 핫키(couponId=1) + 단일 JVM 가정.
- **프로덕션 배선 = v3** (스펙 §6): 임계영역이 곧 DB 쓰기라 v3로 충분, v4/v5는 SPOF+fencing 공백만 추가. "Redis≠스케일아웃 전제".
