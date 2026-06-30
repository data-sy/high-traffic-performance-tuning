# Phase 3-8 — 읽기 캐시 + 스탬피드 방어 분석 (실측)

> 1차 출처. 측정 실행: 2026-06-30, 측정 스택(profile phase3-8, 전용 Redis 6380 + 본체 MySQL 3306 공유).
> 정본 스펙: `specs/phase3/phase3-8-cache-stampede.md`. 하네스: `scripts/measure/run-product-cache.sh`.
> 원시 TSV: `stampede/`, `penetration/`, `avalanche/`. 환경: product 1,000,000(카테고리 5종 × 200,000),
> Hikari pool=10, tomcat threads=200, C=50, TTL_BASE=10s, LOCK_TIMEOUT=200ms, LOCK_WAIT=500ms, STALE_WINDOW=2s.

## 측정 전제 (게이트 통과 기록 — 전부 PASS)
- [x] 핀 게이트: `ops /pins` = cacheRedisPort **6380** · keyPrefix `cache:product:list:` · TTL_BASE **10000ms**
- [x] 인덱스 게이트(AD-1): `idx_product_category_created` 존재 · EXPLAIN **type=ref · no filesort** (`index-gate.txt`)
- [x] 격리 게이트: 본체 Redis(6379)에 `cache:product:list:*` 키 **0**
- [x] 부팅 assert PASS: HikariPool=10 · L2 off · LOCK_WAIT≥LOCK_TIMEOUT · C(50)<tomcat(200) · 직렬화 self-check OK
- [x] 락 리스 불변(실측): v3 홀더 단독 로드 p50 **39ms** < LOCK_TIMEOUT 200ms → v3 재계산=1 로 무캐스케이드 확인

> ⚠️ **측정 활성화 — 환경 잠복 버그 교정.** 1M `product.category` 가 latin1/cp1252 세션으로 적재돼 **이중 인코딩**
> (utf8(전자기기) 바이트→cp1252 해석→utf8 재인코딩)으로 저장됨(실측: latin1 세션 COUNT=200000 / utf8mb4=0).
> charset 미지정 JDBC 는 Korean 0행 매칭 → 모든 버전이 빈 결과→미적재→전원 재계산(거짓 herd). 측정 스택 한정
> `SET NAMES latin1`(+characterEncoding=UTF-8)로 우회해 200000행 매칭 확정. 이전 phase 는 k6 가 status==200 만
> 검사해 미검출(잠복). 정공법(데이터 재인코딩)은 공유 1M 데이터셋 변경이라 범위 밖 — §메타 회고에 기록.

## 비교 합법 축 (두 정식 공동 1차 구조 지표)

### 축 A — 오리진 재계산 수 (만료창당, C=50) — `stampede/*.tsv`
| 버전 | recompute | 기대 | 판정 |
|---|---|---|---|
| v1 (no cache) | **50** | ≈C | ✅ baseline 매 요청 |
| v2 (고정 TTL) | **50** | ≈C herd | ✅ herd 노출 |
| v3 (single-flight) | **1** | ≈1 | ✅ |
| v4 (논리만료+CAS) | **1** | ≈1 | ✅ |
| v5 (null+jitter) | **50** | ≈C | ✅ 스탬피드 직교(herd 안 닫음) |

**핵심 발견:** v2 **50** ↔ v3/v4 **1** — 캐시를 안정화하면 오리진 증폭을 구조적으로 상한(키당 만료창당 ≤1).

### 축 A2 — 만료 전환창 블록 요청 수 (v3 vs v4) — `stampede/*.tsv` blocked 열
| 버전 | blocked | 기대 | 판정 |
|---|---|---|---|
| v2 | **0** | 0(락 없음) | ✅ |
| v3 | **49** | ≈C-1 | ✅ 직렬 대기열 |
| v4 | **0** | ≈0 | ✅ 스테일 즉시 반환 |

**핵심 발견:** v3·v4 는 재계산(둘 다 1)으로는 안 갈리고 **블록 수(49 vs 0)** 로 갈린다 — v4 칸의 존재 이유.

### 축 B — 페네트레이션 (미존재 키, C=50) — `penetration/*.tsv`
| 버전 | window1 | window2 | 기대 | 판정 |
|---|---|---|---|---|
| v2 | 50 | **50** | 매 창 직격 | ✅ |
| v5 | 50 | **0** | w1 herd(직교)→ w2 널 적중 | ✅ |

**핵심 발견:** v5 는 첫 창 herd(50, single-flight 직교)는 못 줄이나 **이후 창 0**(널 캐싱 흡수). v2 는 매 창 직격.

### 축 C — 어밸런치 (5키 동일 TTL vs 지터, C=50 → 키당 10) — `avalanche/*.tsv`
| 버전 | total_recompute | per-key | 비고 |
|---|---|---|---|
| v2 (고정 TTL) | **50** | 전부 10 | 5키 동시 만료 → 광역 herd |
| v5 (지터 ±20%) | **40** | 전자기기=0, 나머지 10 | 지터가 전자기기 TTL(11.3s)을 만료창(10.5s) 밖으로 밀어 생존 |
- **DR-AVAL-1 판정:** 이 런은 키당 배리어 herd=10(>1)이라 **합법 카운트 축 성립**. 다만 5키·단일 만료창이라
  분산 효과는 부분(1/5 생존)으로만 가시 — 규모(키 모집단·지터폭)에 민감. Q10 카운트 축 유효, 효과 크기는 규모 의존.

## 관찰값 (참조 전용 · 버전 간 비교 금지)
- **전 구간 로더 지연:** 단독 홀더 p50 **39ms**(SELECT 1.4ms + COUNT(*) 40ms 지배 — DR-3 확인, 내부 SELECT≠전 구간).
- **herd 비용(v1/v2):** 50 동시 풀로드 → pool=10 포화 → p99 **411ms**. v3 가 제거하는 병리.
- **캐시 적중:** v3 warm hit p50 **0.97ms** / p95 1.33ms (Redis RTT).
- **AD-2 재실측:** 오리진 단건 ≈12.7ms(3-6) 는 SELECT 단독값. Page 경로 전 구간은 COUNT(*) 지배로 **≈39ms**(핀 갱신 근거).

## 락 리스 불변 (DR-4/AD-5)
- 홀더 단독 로드 p50=39ms < LOCK_TIMEOUT 200ms → **불변 성립**(v3 재계산=1 이 무캐스케이드 입증).
- 단, **herd 풀포화 p99=411ms > 200ms** — 홀더가 무관 부하와 풀 경합 시 리스 초과 위험. 격리 v3 측정에선 홀더 단독이라 무관.
  Q3/AD-5: 더 비싼 오리진/풀 경합 환경이면 LOCK_TIMEOUT·LOCK_WAIT 상향 재핀 필요.

## M5 자가검증 (liveness)
- selftest-warm: 따뜻한 캐시 측정 → recompute **0**(herd 미검출) — **PASS**(워밍 함정 드러냄).
- selftest-barrier: 20ms 산포 발사 → recompute **50**(과소관측 안 됨) — **NOTE**: COUNT(*) 지배로 populate 창(~40ms)이
  넓어 20ms 산포로도 herd 유지. 배리어 부재가 *이 워크로드에선* 과소관측을 안 냈다(원리상 더 넓은 산포면 발생). Q1 관련.
- selftest-pins: 실측 port 6380 ≠ 고의기대 → 게이트 abort 동작 **PASS**.

## 경로별 배선 (결론 프레임 — 채택 선언 아님)
- 중간 동시성 핫 경로 → **v3 single-flight**(herd·풀포화 둘 다 닫음)
- 초핫 단일 키(만료창 대기 보이는) → **v4 논리만료+비동기갱신**(블록 49→0)
- TTL 지터·널 캐싱 → **상시 위생**(페네트레이션 w2=0·어밸런치 부분 분산)
- v2 = 드러내는 칸. 우승 기준 = **예측 가능성**(재계산 수가 부하 형상과 무관하게 ≤1로 상한).

## 비준 미결 질문 — 측정 결과
- **Q1(증폭 강도):** 오리진이 12.7ms 가 아니라 COUNT 지배 ~39ms 라 populate 창이 넓어 구조 카운트(재계산 수)로
  충분히 갈렸다(v2=50↔v3=1). 더 비싼 오리진 변종 불요. 단 *지연* 증폭은 herd 풀포화(p99 411ms)로 별도 가시.
- **Q3(핀 실측):** TTL 10s·C 50 으로 만료 전환 관측 가능 확인. LOCK_TIMEOUT 200ms 는 홀더 단독(39ms)엔 충분,
  풀경합 herd(411ms)엔 부족 — 격리 v3 전제에선 OK.
- **Q7(v4):** STALE_WINDOW 2s 로 블록 0·재계산 1 성립.
- **Q10(어밸런치):** 키당 herd=10 으로 합법 카운트 성립했으나 5키·단일 창이라 효과 크기 부분 가시.
- **AD-2(12.7ms):** Page 전 구간 ≈39ms 로 재실측·핀 갱신(COUNT 지배).
