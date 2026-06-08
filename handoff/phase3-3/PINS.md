# PINS — Phase 3-3 통제 프로필 (본 실행 — 전 버전 동일 핀)

> 비교 유효성의 핵심은 **"값 자체가 정답"이 아니라 "v1~v5 전부 동일"**이다.
> 하네스 런타임 assert가 시작 시 실측·대조해 불일치면 abort한다(아래 ★ 표시).

## 부하 프로필 (양축 — 쿠폰 설정이 축마다 다르다)

| 축 | executor | VU / duration | `total_qty` | 집계 | 측정 대상 |
|----|----------|---------------|-------------|------|-----------|
| **정확성** | `constant-vus` | **500 / 10s** | **100** | 전체 | 초과발급 차단(`actual_issued`) |
| **성능** | warmup→steady | **warmup 5s + steady 30s** | **대용량(1억)** | steady-only | 락 직렬화 비용(throughput/p95/p99/거절률) |

- 부하는 **지속형**(VU가 duration 동안 반복; 단발 `per-vu-iterations=1` 금지). fail-fast(v4/v5)는 락 빈 틈마다 다음 iteration이 획득해야 한도까지 찬다.
- 유니크 userId = `__VU * 100000 + __ITER`.
- 하네스 env: `VUS`/`DURATION`(정확성), `WARMUP`/`STEADY`(성능), `PERF_TOTAL_QTY`(기본 100000000). 기본값으로 돌리면 위 프로필 그대로.

## 인프라 핀 (5버전 공통 고정·기록)

| 변수 | 핀 값 | 강제 방식 |
|------|-------|-----------|
| HikariCP `maximum-pool-size` | **10** | application.yml (v3 풀 고갈 분석 대상 — over-provision 금지) |
| HikariCP `connection-timeout` | **30000ms** | application.yml (v3 F1 통제변수) |
| Tomcat `threads.max` | **200** | application.yml (HTTP 계층 잡음 중화) |
| MySQL `innodb_lock_wait_timeout` | **50s** | ★ 하네스 런타임 assert (전역; v3 행락 타임아웃) |
| MySQL `transaction_isolation` | **REPEATABLE-READ** | ★ 하네스 런타임 assert |

- 두 타임아웃 핀(connTimeout 30s < innodb 50s)은 v3의 "ⓐ 풀 고갈 우세"가 *측정*이 아니라 대소관계의 구조적 귀결이 되지 않게 하는 F1 통제. ⓐ 선발화는 그 설정 대소의 귀결임을 analysis에 병기.

## 성공 시그니처 (정합성 판정 — 정책별로 다름)

- **정합성 단일 진실 소스**: `SELECT COUNT(*) FROM coupon_issue WHERE coupon_id=1` (= `actual_issued`). `coupon.issued` 컬럼은 lost update로 부정확 → 보조지표.
- **v2·v3 (블로킹)**: `actual_issued == issued_counter == total_qty(100)` = PASS.
- **v4·v5 (fail-fast)**: 합격식은 **`actual_issued ≤ total`(초과 0)만 요구**. 지속·충분 부하면 기대값 `== total`이지만, 공유 정확성 부하(500VU/10s)에서 `< total`은 **정합 실패가 아니라 한도 미충전**(대기 큐 부재 — TRANSFERABLE-TRAPS 발견5). 미달이면 duration↑(예: 90s)로 충전 + 초과 0 확인. 하네스 출력의 FAIL이 v4/v5 10s 미충전을 가리키면 정합 실패 아님 — 해석으로 닫는다.

## ★ reset-coupon 필수 게이트 (각 런 — 버전·축 — 시작 전)

> **각 런(버전·축) 시작 전 `reset-coupon.sql` 호출은 필수다.**
> 이유: **성능 축이 `total_qty`를 대용량(1억)으로 `UPDATE`한 뒤 원복하지 않는다**(`run-coupon-concurrency.sh`의 성능 섹션이 reset 직후 `UPDATE coupon SET total_qty=$PERF_TOTAL_QTY`). reset을 건너뛰고 다음 정합성 측정을 돌리면 `total_qty`가 1억인 상태로 돌아 **초과발급 판정 자체가 무력화**(한도가 사실상 무한이라 `actual ≤ total`이 항상 참)된다.
- 하네스는 이미 **각 축 시작에 reset을 실행**한다(정확성 축 1회 + 성능 축 1회 — 스크립트 내장). 정상 하네스 플로우에선 자동 처리.
- **단 축을 분리/수동으로 돌리거나 하네스 밖에서 한 버전을 단독 실행할 때는 reset 선행을 직접 보장**한다. (리허설 스모크가 끝난 뒤 샌드박스 DB가 `total_qty=1억`으로 남아 있던 것이 이 트랩의 실증.)
- `reset-coupon.sql`: `coupon(id=1, total_qty=100, issued=0)` 1행 + `coupon_issue` 0행으로 복원.
