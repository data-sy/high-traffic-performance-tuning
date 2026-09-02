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
- 성능 축: `warmup 5s + steady 30s`(steady-only), `total_qty=대용량`.
- 성공 시그니처: 블로킹(v2/v3) `actual==counter==total`; fail-fast(v4/v5) `≤total`(초과 0) + 지속부하 시 `==total`.

---

## v1 — No Lock (기준선, 초과 발급 재현)

### 부하 파라미터 (확정)
- executor / VU / duration: —
- 유니크 userId = `__VU * 100000 + __ITER`

### 결과
| 지표 | 값 |
|------|----|
| total_qty | — |
| issued_counter (coupon.issued) | — *(lost update로 부정확)* |
| **actual_issued (coupon_issue 행수)** | — |
| 초과량 | — |
| 성공(200) / 한도소진(409) / other | — / — / — |
| 성공 지연 p95/p99 | — |

### 해석 (왜 초과가 나는가)
- check-then-act(`issued < total` → `issued++`) 비원자 → 다수 트랜잭션이 stale `issued` 읽고 통과 → 초과.
- `issued_counter` ≪ `actual_issued` 격차 = lost update 증거 → 정합성은 coupon_issue 행수로 판정.

### 측정 범위 (Note)
- 유니크 userId라 중복 차단 경로는 부하로 안 돌아감 → 초과발급만 대상. 중복 차단은 단건 재요청으로만 검증.

---

## v2 — synchronized (애플리케이션 락)

### 구현 요지
- 파사드: `synchronized`는 트랜잭션 경계 밖, 실제 tx는 별도 빈 `@Transactional`. 락 ⊃ 트랜잭션(커밋 후 해제).

### 결과
| 축 | 지표 | v2 |
|----|------|-----|
| 정확성 | total/counter/actual | — / — / — |
| 정확성 | 성공 시그니처 | — |
| 성능 | 성공 throughput | — |
| 성능 | 성공(200) p95 / p99 | — |
| 성능 | 409율 / 503율 (steady) | — |

### 해석 / 학습 노트
- 정합 회복(직렬화). 한계: **단일 JVM에서만 정합**(인스턴스마다 별도 모니터), 직렬화 throughput 병목.

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
- **단일-JVM 한계**: v4/v5의 본 정당화(다중 인스턴스 정합)는 **이 측정에서는** 미실증. 단일 JVM에선 v2~v5 다 정합, v4/v5는 RTT만큼 느림.
  - *[2026-08-26 갱신]* 보강#1(다중 인스턴스)은 이후 실행됨 — ROADMAP §Done(2026-06, PR #10). **재현 자산은 트리에 없음**(결론만 기록).
- **범위 한정**: 모든 결론은 단일 핫키(couponId=1) + 단일 JVM 가정.
- **프로덕션 배선 = v3** (스펙 §6): 임계영역이 곧 DB 쓰기라 v3로 충분, v4/v5는 SPOF+fencing 공백만 추가. "Redis≠스케일아웃 전제".
