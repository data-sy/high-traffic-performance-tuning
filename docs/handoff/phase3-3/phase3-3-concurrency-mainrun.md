# Phase 3-3 — 동시성 제어 (쿠폰 발급 락 사다리) · 본 실행 개정 스펙

> **상태**: ✅ 확정 — 별도 세션 `/audit-doc` 검증 통과(Healthy: High 0 / Medium 0). 핸드오프 배치본
> (`handoff/phase3-3/`). 메모리 `spec-draft-workflow`.
> **대상 브랜치**: `phase/3-3-concurrency-lock` (본 실행). 이 확정 스펙은 리허설 워크트리
> `sandbox/phase3-3-dryrun`에서 작성·검증되어, Step 1 핸드오프로 본 실행에 전달된다.
> **개정 출처**: 샌드박스 리허설(v1~v5 + 통제 재실험 3-3-2)이 5세션에 걸쳐 잡은 발견 9건·통제 설계·
> 정성 결론을 본 스펙에 흡수해 정확도를 끌어올렸다. 단 **리허설의 측정 숫자는 본 실행 결과로 승격하지
> 않는다** — 아래 본문은 "무엇을 만들고·어떻게 재고, 어떤 함정을 미리 피하는가"만 규정한다.

---

## 0. 본 실행 컨텍스트 (리허설과의 관계 — 정독)

- **v1~v5는 본 실행에서 새로 생성하는 산출물이다.** 리허설 워크트리에 구현·측정이 존재하지만, 본 실행
  (`phase/3-3-concurrency-lock`)은 스펙→하네스→락 구현→런→분석을 **처음부터 재실행**한다. 이 스펙의 어떤
  문장도 "v1~v5가 이미 구현됨"을 전제하지 않는다 — Step A~G가 생성한다.
- **하네스는 비파괴 복사로 재사용한다(재구축 금지).** 리허설에서 검증·일반화가 끝난 측정 하네스
  (`test/load/coupon-test.js`, `scripts/measure/run-coupon-concurrency.sh`, `scripts/coupon/reset-coupon.sql`,
  `analysis.md` 템플릿)는 Step 1 핸드오프 묶음(`handoff/phase3-3/`)으로 전달되며, 본 실행은 이를 정규 위치로
  복사해 **무수정 사용**한다. 락 구현·런·분석만 본 레포에서 신규 생성한다.
  - **핸드오프 미수령 시 대체 경로**: `handoff/phase3-3/`가 없으면 정규 위치 원본(`test/load/coupon-test.js`,
    `scripts/measure/run-coupon-concurrency.sh`, `scripts/coupon/reset-coupon.sql`)을 직접 복사·무수정 사용한다(동일 결과).
- **샌드박스 격리 장치는 본 실행에 가져오지 않는다.** G0 조건부 게이트, `docker_sandbox-*` 프로젝트, per-worktree
  `--worktree` 훅, fresh-volume/gradle-wrapper 우발 처치는 리허설 전용이었다. 본 실행은 기본 컨테이너
  (`docker-mysql-1`, `docker-redis-1` — 하네스가 `COMPOSE_PROJECT_NAME=docker` export로 타겟, §7)·기본 훅·본 레포
  볼륨을 쓴다. (단 사전 검증 게이트 §2는 유지 — 격리가 아니라 "환경이 측정 가능한 상태인가"를 확인하는 용도.)
- **3-3-2 통제 재실험은 별도로 두지 않는다.** 그 결론(통제 후 락-위치 비용이 작은 단조 비용으로 수렴 =
  phase3-3의 throughput 격차는 경합전략 교란 / 갱신 스포크는 정상부하에서 격리 불가)은 **Step G 해석 원리로
  흡수**한다. 본 실행은 v1~v5만 돌리고, bw(유한대기) 변형은 만들지 않는다.

---

## 1. 현재 상태 / 전제

- Phase 1(셋업), Phase 2(도메인 엔티티) 완료. 시나리오 ①(인덱스)·③(랭킹)·모니터링 인프라 완료.
- `Coupon`, `CouponIssue` 엔티티는 Phase 2에서 이미 정의됨 (§3 "엔티티 현황" — 신규 생성 금지).
- `ddl-auto: validate` 활성. **변경 금지** (단, 사전 게이트 §2의 fresh-volume 우발 대비는 예외 — 커밋 금지).
- Docker 컨테이너(MySQL, Redis) 실행 중. Redis 인프라는 시나리오 ③에서 구축됨
  (`infrastructure/redis/RedisConfig.java`, Lettuce). **Redisson 없음 → v4에서 추가**
  (`org.redisson:redisson-spring-boot-starter`).

---

## 2. 사전 검증 게이트 (v1 측정 진입 전 1회 — 직접 수행)

본 실행 진입 시 아래를 실측·출력하고 모두 통과해야 측정을 시작한다. (격리 가드가 아니라 "측정 가능 상태"
확인. 리허설 발견1/2가 본 레포에선 재발 안 함을 보증하는 스모크.)

1. **포트 free + 타겟 컨테이너 실존(한 쌍)**: 3306/6379가 본 실행 컨테이너에만 바인딩(리허설 컨테이너 잔존
   없음). + `COMPOSE_PROJECT_NAME=docker` export 상태에서 `run_mysql`/`run_redis` 타겟(`docker-mysql-1`/
   `docker-redis-1`)이 `docker ps`에 실존(리허설 default `docker_sandbox-*`를 직격하지 않음 — §7 전이 트랩).
2. **스키마 스모크**: `SHOW TABLES`에 `coupon`/`coupon_issue` 포함 전체 6테이블 존재 + `validate`로 정상 부팅.
   - **우발 대비(발견1)**: 만약 볼륨이 비어 `validate` 부팅이 실패하면(테이블 0개), 1회 부트스트랩:
     `ddl-auto: validate→create` 토글 → `bootRun` 1회(스키마 생성) → 종료 → `create→validate` 즉시 복구
     (★ `create` 토글은 **절대 커밋 금지**) → `validate` 재부팅 확인. 본 레포 볼륨이 정상 보존돼 있으면 불필요.
3. **하네스 스모크**: 각 버전 엔드포인트 단건 POST `{couponId:1,userId:1}` → 기대 status. 하네스 스크립트가
   `run_mysql`/헬스체크/결과 경로로 정상 동작.
4. **통제변수 핀 대조**: 하네스 런타임 assert가 전역 `innodb_lock_wait_timeout=50` / `REPEATABLE-READ`,
   인프라 핀(§7) 일치를 시작 시 실측·확인(불일치 abort).

---

## 3. 엔티티 현황 (이미 존재 — 절대 신규 생성 금지)
```
Coupon (table: coupon)
  - id (Long, IDENTITY) / name (String)
  - totalQuantity (Integer, col: total_qty)   ← 발급 한도
  - issued (Integer, 기본 0)                    ← 발급 카운터 (lost update로 부정확할 수 있음 — 보조지표)
  - createdAt
  - 비즈니스 메서드: 없음 → issue() 추가 (Step A)

CouponIssue (table: coupon_issue)
  - id (Long, IDENTITY) / couponId (Long, 관계 없음) / userId (Long, 관계 없음) / issuedAt
  - UNIQUE (coupon_id, user_id)   ← 같은 사용자 중복 발급을 DB가 이미 차단

CouponRepository, CouponIssueRepository: 기본 JpaRepository (Step A에서 메서드만 추가)
```

---

## 4. 이 시나리오가 증명하는 문제 (핵심 — 정독 필수)

- `unique(coupon_id, user_id)`는 **"같은 사용자의 중복 발급"을 이미 차단**한다.
- 이 시나리오의 동시성 문제는 **초과 발급(over-issuance)**: **서로 다른** 다수 사용자가 한정 수량 쿠폰에
  동시 요청 → `issued` 카운터의 *read → check(issued < total) → increment*가 비원자라 경쟁 → 실제 발급이
  `total_qty`를 초과.
- **정합성의 단일 진실 소스**: `SELECT COUNT(*) FROM coupon_issue WHERE coupon_id=1` (= `actual_issued`).
  `coupon.issued` 컬럼은 lost update로 부정확하므로 정합성은 **coupon_issue 행 수로 판정**한다.
  - v1: `actual_issued > total_qty` (문제 재현)
  - v2~v5: `actual_issued ≤ total_qty` (**초과 없음 = 정합성 본질**)
    - v2·v3(블로킹): 충분 부하 시 `== total_qty`(한도 소진).
    - v4·v5(fail-fast): 합격식은 **`≤ total`(초과 없음)만 요구**. 지속·충분 부하면 기대값 `== total`이지만,
      공유 정확성 부하(예: 500VU/10s)에서 `< total`은 **정합성 실패가 아니라 fail-fast의 한도 미충전**(대기 큐
      부재 — §발견5 흡수)이다. 미달이면 duration을 늘려(예: 90s) 충전 + 초과 0을 확인한다. 하네스 출력의 FAIL이
      v4/v5의 10s 미충전을 가리키면 정합 실패가 아님 — 해석으로 닫는다.

---

## 5. 목표 + 5버전 사다리 (본 실행에서 생성)

쿠폰 발급 동시성을 점층 전략으로 해결하고 **정합성**과 **처리량/지연**을 측정·비교한다. 개념상 4단계 사다리,
꼭대기(분산 락)를 두 구현으로 → 총 5버전. **전부 코드에 공존**(버전별 Service + 엔드포인트).

- **v1** No Lock — 기준선(초과 발급 재현)
- **v2** `synchronized` — 애플리케이션(JVM) 락
- **v3** `SELECT ... FOR UPDATE` — DB 비관적 행 락
- **v4** Redis 분산 락 — **Redisson `RLock`** (라이브러리, 정답 레퍼런스)
- **v5** Redis 분산 락 — **커스텀 `SET NX` + Lua** (직접 구현, 원리 노출)

---

## 6. 프로덕션 배선 결정 (사다리의 결론 — 본 실행에서 명문화)

> v1~v5는 **벤치마크 실험 표면**이다(독립 비교·재측정용, 전부 공존). 그러나 "실제로 운영에 배선하는 전략"은
> 하나를 골라야 하며, 본 프로젝트의 선택은 **v3 (DB 비관적 행 락)** 이다.

- **선택 = v3.** 정규 발급 경로(`POST /api/coupons/issue` 무버전 별칭, 또는 `@Primary` 발급 서비스)는 v3에 배선.
  v1·v2·v4·v5 엔드포인트는 비교용으로 남기되 운영 트래픽과 무관.
- **근거(맥락 기반 — insight)**:
  - 본 프로젝트는 **단일 스키마 모놀리식**(CLAUDE.md). 임계영역이 곧 DB 쓰기(`coupon_issue` INSERT)라, 락을
    DB 밖(Redis)으로 빼서 얻는 이득(임의 비-DB 임계영역 보호·DB 부하 분리)이 **이 워크로드에선 불필요**하다.
  - **다중 인스턴스 정합**은 v3의 공유 DB 행 락으로 충분히 달성(추가 인프라 0). v4/v5는 Redis **SPOF**와
    **fencing 미해결**(§Step G 3축 — 어느 분산 락도 fencing은 안 줌)을 추가로 떠안는다.
  - v3의 알려진 대가 = **커넥션 풀 압박**(락 대기 중 커넥션 점유). 이는 측정·튜닝 가능한 비용이며(풀 사이즈·
    타임아웃 핀), 본 워크로드 규모에서 수용 가능.
  - 만약 Redis가 이미 핵심 캐시 경로로 깔려 있고 DB 부하 분리가 절실했다면 v4가 합리적 — 즉 이 선택은
    **무조건이 아니라 인프라 맥락의 함수**다. 그 맥락이 바뀌면 v4로 이전.
- **단일 JVM 한계 연동**: 본 측정은 단일 JVM이라 v3의 "인스턴스 경계 초월"은 개념적 근거다. 실증은 보강#1
  (다중 인스턴스, §11)에서. 단 v3는 **DB라는 외부 공유 좌표**에 직렬화하므로 다중 인스턴스 정합이 메커니즘상
  보장된다(v2 JVM 모니터와 달리). 이 보장이 v3를 운영 기본값으로 택한 핵심이다.
- **⚠️ 오독 방지(면접 취약점 — 명시 가드)**: 이 결정은 **"스케일아웃하려면 Redis가 필요하다"는 뜻이 아니다.**
  v3는 공유 DB 행 락이라 **이미 다중 인스턴스 정합**이다(인스턴스를 늘려도 모두 같은 행 락을 경유 → 경계 초월).
  v4/v5가 v3를 넘어 주는 건 정합의 *범위*가 아니라 — *비-DB 임의 임계영역 보호 + DB 부하 분리*뿐이고, 이
  워크로드(임계영역이 곧 DB 쓰기)엔 그 둘이 불필요. 따라서 **Redis는 스케일아웃의 전제가 아니다.** 보강#1
  (다중 인스턴스)의 목적도 "Redis 필요 실증"이 아니라 **v2(JVM 모니터)의 붕괴 실증 + v3~v5의 경계초월 정합
  확인**이다(§11). "제일 고급 기법(분산 락)이라 배포한다"가 아니라 "사다리 끝까지 구현하고도 이 워크로드의
  정답은 DB 행락이라 결론"이 이 시나리오의 판단 축이다.

---

## 7. 중요 규칙 + 통제변수

### 규칙
- 먼저 `CLAUDE.md` 컨벤션 확인.
- **관계 추가 절대 금지**: `Coupon`/`CouponIssue`에 어떤 `@ManyToOne`/`@OneToMany`도 금지. `couponId`/`userId`는
  `Long`만. **Setter 금지** — `issued` 증가는 비즈니스 메서드로만. **엔티티 신규 생성 금지**(이미 존재).
- **구현-측정 한 세트**: 각 버전 구현 직후 바로 측정 → 정합성·성능 확인 → `analysis.md` 기록 → 커밋. 일괄 측정 금지.
- **컨테이너 접근(하네스 정의와 글자 그대로 일치 — 코드 무수정, 타겟은 환경값으로)**: 하네스
  (`run-coupon-concurrency.sh`)는 이미 파라미터화돼 있다 —
  `run_mysql() { docker exec -i "${COMPOSE_PROJECT_NAME:-docker_sandbox}-mysql-1" mysql -uroot -proot flashdeal "$@"; }`,
  `run_redis() { docker exec -i "${COMPOSE_PROJECT_NAME:-docker_sandbox}-redis-1" redis-cli "$@"; }`.
  - **본 실행은 하네스 코드를 수정하지 않는다.** 대신 셋업에서 `COMPOSE_PROJECT_NAME=docker`(본 compose
    프로젝트명)를 **export**해 타겟을 `docker-mysql-1`/`docker-redis-1`로 맞춘다.
  - ⚠️ **리허설 default `docker_sandbox`는 본 실행에 부적합**: 미export 시 `docker_sandbox-mysql-1`을 직격 →
    본 레포 컨테이너 미접촉(또는 잔존 샌드박스 컨테이너 오접촉) → 첫 `run_mysql`에서 abort. (default를 `docker`로
    *바꾸지* 않는 이유: 그건 하네스 파일 수정이라 무수정 원칙에 금이 가고, 샌드박스 다중서버 라운드에서 그
    하네스를 재사용할 때 default=docker가 거꾸로 샌드박스를 직격한다. 환경값 export가 양쪽에 안전.)
- `mysql -h127.0.0.1` 직접 사용 금지. 모든 `.sh`는 `chmod +x`.

### 통제변수 (v1~v5 공통 핀 — 값이 정답이 아니라 "전 버전 동일"이 핵심. 문서 핀 + 런타임 assert)
| 변수 | 핀 값 | 위치 | 비고 |
|------|-------|------|------|
| HikariCP `maximum-pool-size` | 10 | application.yml | v3 풀 고갈 분석의 대상(제거 아닌 측정) |
| HikariCP `connection-timeout` | 30000ms | application.yml | v3 F1 통제변수 |
| Tomcat `threads.max` | 충분히 큰 값(바인딩 제약 X) | application.yml | HTTP 계층 잡음 중화 |
| MySQL `innodb_lock_wait_timeout` | 50s | 하네스 런타임 assert | v3 행락 타임아웃 — assert로 검증 |
| MySQL `transaction_isolation` | REPEATABLE-READ | 하네스 런타임 assert | 동상 |

- **두 타임아웃 핀의 이유(F1 — circularity 방지)**: 기본 `connectionTimeout 30s < innodb_lock_wait_timeout 50s`라
  풀 대기자가 행락 대기자보다 **항상 먼저** 터져 "ⓐ(풀 고갈) 우세"가 *측정*이 아니라 두 기본값 대소관계로
  **선결정**된다. 두 값을 핀하고, ⓐ 선발화가 설정 대소관계의 구조적 귀결임을 Step D에 병기해야 "v3=커넥션 고갈"
  간판이 circular해지지 않는다.
- **풀은 "제거"가 아니라 "드러내라"**: v3는 `@Transactional`이 커넥션을 빌린 뒤 `FOR UPDATE`가 그 커넥션을 쥔 채
  블로킹 → 대기 요청이 커넥션 점유 → 풀 고갈. v4/v5는 락 경합이 tx 밖이라 대기 중 커넥션 비점유. **이 풀 압박
  차이가 "v3=DB 커넥션 고갈" 간판 발견.** over-provision하면 v3 시그니처를 지운다 → 유한값 고정 + 포화 측정.

---

## 8. 확정된 결정 사항

1. **v4 락 방식**: Redisson(v4) → 커스텀 SET NX+Lua(v5). 별도 엔드포인트로 비교.
2. **양축 측정 — 쿠폰 설정이 축마다 다르다 (발견3 흡수)**:
   - **정확성 축**: `constant-vus 500 / 10s`, **`total_qty=100`**. 초과발급 차단 여부(`actual_issued`).
   - **성능 축**: `warmup 5s + steady 30s`(steady-only 집계), **`total_qty=대용량`**(하네스 기본 1억). 락 직렬화 비용.
     - ⚠️ 성능 축에 `total_qty=100`을 쓰면 500VU가 수십 ms 안에 즉시 매진 → steady 전부 409 → 성공 경로
       측정 불가(발견3). 하네스 성능 섹션이 reset 직후 `UPDATE coupon SET total_qty=<대용량>`으로 한도를 키운다.
   - 부하는 **지속형**(VU가 duration 동안 반복; 단발 `per-vu-iterations=1` 금지). fail-fast(v4/v5)는 락이 빈
     틈마다 다음 iteration이 획득해야 한도까지 찬다. 유니크 userId = `__VU * 100000 + __ITER`.
3. **실패 응답(즉시 실패)**: 한도 소진 = `409 SOLD_OUT`, 락 획득 실패 = `503 LOCK_NOT_ACQUIRED`. k6 status로 거절
   원인 분리. 짧은 재시도/대기는 `analysis.md` 참고만.
4. **커밋↔락 해제 순서 + TTL/lease 경계** (분산판 v4·v5):
   - (순서) 락은 **트랜잭션 경계 바깥**(파사드)에서 해제 — 커밋 이후. v2·v4·v5 동형. 락 ⊃ 트랜잭션.
   - (만료) TTL/lease가 트랜잭션 커밋보다 먼저 만료되면 순서를 지켜도 락이 tx *도중* 사라져 → 다른 스레드 획득
     → 미커밋 `issued` 읽음 → 초과 발급(v2 순서 함정과 별개의 "만료-중간탈취" 분산판 함정).
     - **v4(Redisson): 워치독 자동 갱신 채택 (leaseTime 미지정, 발견4 — 원본 F4의 고정lease에서 개정)**. 이유:
       고정 lease는 GC pause/스레드 스톨이 lease를 넘기는 순간 만료-중간탈취 → 초과발급을 *구조적으로* 못 막는다.
       v4의 존재 이유가 분산 경계 정합이라, 정확성을 측정 청결성과 맞바꾸지 않는다. → **v4↔v5 비동형**(워치독 vs
       고정TTL)이 되며, 이 차이는 Step G 갱신 축으로 다룬다(§Step G).
     - **v5(커스텀 `SET NX PX ttl`)**: 고정 ttl 30s 무갱신, `ttl > worst-case 트랜잭션 시간`(PoC 실측 보장).
5. **유니크 userId**: `__VU * 100000 + __ITER` (+ 하네스가 시나리오 base offset을 더해
   `시나리오base + __VU*100000 + __ITER` — warmup/steady가 같은 VU·ITER로 겹쳐 중복발급(409)되는 걸 분리).
6. **문서화**: 각 버전 측정 직후 `results/phase3-3-concurrency/analysis.md`에 한 섹션 append.

---

## 9. Step A~G (본 실행 — 각 Step 완료 후 정지·검증·기록·커밋)

### Step A — 공통 도메인 + DTO + 시드
- `Coupon.issue()`: `issued >= totalQuantity`면 예외(`CouponSoldOutException` 권장), 아니면 `issued++`. **이건 락이
  아니다** — 동시성은 Service 전략의 책임.
- `CouponRepository.findByIdForUpdate` (@Lock PESSIMISTIC_WRITE, v3용).
- `CouponIssueRepository`: `existsByCouponIdAndUserId`, `countByCouponId`.
- DTO(`api/coupon/dto/`): `CouponIssueRequest`(couponId, userId), `CouponIssueResponse`(성공여부 + couponId,
  `issued`(엔티티 필드명과 통일), totalQuantity; 정적 팩토리 `from`). 실패 응답에 `SOLD_OUT`/`LOCK_NOT_ACQUIRED`
  구분 에러코드. **응답에 엔티티 직접 노출 금지.**
- 시드/리셋 `scripts/coupon/reset-coupon.sql`(핸드오프 제공): coupon(id=1, total_qty=100, issued=0) 1건 + coupon_issue 0건.
- 검증: `reset` 후 상태 확인 + `./gradlew compileJava` 성공(bootRun/테스트 직접 실행은 측정 단계에서).

### Step B — v1 No Lock + 하네스 도입 + v1 측정(부하 확정)
- `CouponIssueServiceV1`: 조회 → `existsByCouponIdAndUserId` 거절 → `coupon.issue()` → 저장. **락 없음**(초과 노출).
- 컨트롤러 `POST /api/v1/coupons/issue`.
- **하네스 = 핸드오프 비파괴 복사(재구축 금지)**: `test/load/coupon-test.js`, `scripts/measure/run-coupon-concurrency.sh`를
  정규 위치로 복사·`chmod +x`. **핸드오프(`handoff/phase3-3/`) 미수령 시 정규 위치 원본(`test/load/coupon-test.js`,
  `scripts/measure/run-coupon-concurrency.sh`, `scripts/coupon/reset-coupon.sql`)을 직접 복사**(동일 결과). **하네스 코드는 무수정 — 단 컨테이너 타겟은 환경값으로 맞춘다: 본 실행 셋업에서
  `COMPOSE_PROJECT_NAME=docker` export(§7). 리허설 default `docker_sandbox`는 본 실행에 부적합.** 하네스가 이미
  갖춘 것(리허설 검증됨):
  - 버전 파라미터화(`/api/${VERSION}/coupons/issue`), 양축 분리, `RESULTS_DIR` 파라미터화(발견7),
    503 ⓐ(풀)/ⓑ(행락)/ⓒ(락미획득) 서브분류 키잉, Redis PING + `lock:coupon:*` flush, 통제변수 런타임 assert,
    settle 후 정합성 쿼리(발견3 — k6 graceful-stop in-flight 커밋 누락 방지).
  - **outcome별 분리 메트릭(Blocker — 집계 p95/p99 금지)**: status 태그별 별도 Trend로 성공(200)·한도소진(409)·
    락거절(503) 지연을 셋으로 분리. "거절"=503-only 정의, 409는 한도소진(정상 종료, 버전 의존이라 버전 간 비교
    금지). 집계 `http_req_duration`은 v4/v5의 0-cost 거절이 ~95%를 먹어 p95≈1ms로 찍혀 "분산 락이 2000배
    저지연"이라는 거짓 결론을 낳음.
  - **비-HTTP + 미분류 5xx 별도 버킷**: accept 큐 드롭·리셋·타임아웃·미분류 500(v1은 락이 없어 풀 타임아웃이
    500으로 샘)을 카운트해 거절률 분모를 닫는다.
- **v1 측정 + 부하 확정**: `actual_issued > total_qty`(초과)가 또렷이 재현될 때까지 부하 조정 → 확정 VU·duration을
  k6에 고정. v2~v5 재사용. `analysis.md` v1 섹션 기록(확정 부하, actual/counter/total, 초과량, check-then-act 해석,
  counter≪actual = lost update 증거).
- **측정 범위 Note**: 유니크 userId라 중복 차단 경로는 부하로 안 돌아감 → 초과발급만 대상. 중복 차단은 단건
  재요청(같은 userId → 거절)으로만 검증.

### Step C — v2 synchronized + 측정
- `CouponIssueServiceV2`: 임계영역을 `synchronized`로 보호.
- **⚠️ 트랜잭션 경계 필수(Spring 프록시 함정 — 이 시나리오 핵심 위험)**: `@Transactional` 메서드에 `synchronized`를
  그냥 같이 걸면 프록시 커밋이 모니터 **밖**에서 일어나(모니터 해제 → 다음 스레드 stale issued 읽음 → 커밋) →
  **단일 인스턴스에서도 초과 발급** → v2 서사 붕괴. → `synchronized`는 **트랜잭션 경계 바깥**(파사드)을 감싸고,
  그 안에서 **별도 빈의 `@Transactional` inner** 호출(자기호출은 프록시 미적용 → 반드시 다른 빈). **금지**: 같은
  메서드에 `@Transactional`+`synchronized` 동시. 모니터 = 전용 `private final Object lock` 하나(단일 쿠폰).
- 한계 명시: 올바로 짜도 **단일 JVM에서만 정합**(인스턴스마다 별도 모니터), 직렬화로 처리량 병목 → v3 이행 근거.
- `POST /api/v2/coupons/issue`. 측정 → `analysis.md` v2 섹션(정합 회복, 직렬화 비용, v1 대비).

### Step D — v3 SELECT FOR UPDATE + 측정
- `CouponIssueServiceV3`: `findByIdForUpdate`로 행 락 → 검사·발급·저장. **락 획득 후 검사/발급 순서**(락 전 비락
  조회를 임계 판단에 쓰지 말 것). **트랜잭션 경계 — v2와 정반대**: FOR UPDATE 락은 커밋 시 풀리므로 락 획득·소비를
  모두 *하나의 `@Transactional` 안*에서(단일 빈, 파사드 없음). v2 복사 금지.
- 503 두 갈래 서브분류: ⓐ HikariCP `connectionTimeout` 초과(`SQLTransientConnectionException`=풀 고갈) vs
  ⓑ `innodb_lock_wait_timeout` 초과(MySQL 1205=행락 대기). 미포착은 'other'(5xx) 버킷. 뭉뚱그리면 "v3=커넥션
  고갈" 간판이 가정이 됨 — ⓐ 우세를 실측해야 단정. F1 핀과 병기.
- `POST /api/v3/coupons/issue`. 측정 → `analysis.md` v3 섹션(정합성, DB 행 경합, Actuator
  `hikaricp.connections.pending`/`active` 동시 캡처로 풀 포화 실증, 503 ⓐ/ⓑ 분리 + 핀한 두 타임아웃값 기록).
- **리허설 관측(방향성, 본 실행 재확인 대상 — 숫자 복사 금지)**: 단일 JVM에서 v3 throughput이 v2를 **밑돌지
  않고 오히려 동급 이상**일 수 있음("DB 락이 무조건 느리다"는 통념 반례 — DB 대기열 핸드오프가 JVM 모니터+
  커넥션획득 지연을 안 무는 파이프라이닝 이득 추정). 이 부하에선 503=0(경합을 거절 아닌 지연으로 흡수)일 수
  있음. **방향성만 참고, 본 실행 숫자로 재확인.**

### Step E — v4 Redis 분산 락 (Redisson) + 측정
- **0. 의존성 + PoC**: `org.redisson:redisson-spring-boot-starter` 추가. `RLock` 획득/해제/TTL PoC.
  **lease = 워치독 자동 갱신(leaseTime 미지정)** 확정(결정 #4 / 발견4). worst-case inner tx가 짧음(단일 보유자) 확인.
- `CouponIssueServiceV4`(파사드, 락 ⊃ tx): inner(별도 빈 `@Transactional`) → 커밋 → finally unlock.
  - **⚠️ unlock 가드(v4 헤드라인 503 지표 보호)**: 미획득 락 unlock 금지(Redisson은 미보유 unlock 시
    `IllegalMonitorStateException` → 거절이 500으로 새 거절률 분모 오염). 구조 고정:
    ```
    boolean locked = lock.tryLock(0, unit);   // waitTime=0 즉시 실패 (leaseTime 미지정 → 워치독 자동갱신, §8 #4)
    if (!locked) return 503 LOCK_NOT_ACQUIRED;            // unlock 호출 안 함
    try { innerTx(...); } finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }
    ```
  - **⚠️ tryLock 예외 가드**: `tryLock` 자체도 Redis 일시 끊김 시 `RedisException` → try/catch로 명시적 5xx
    버킷(또는 503)으로 분류(미포착이면 분모 오염, unlock 가드와 동일 클래스).
- `POST /api/v4/coupons/issue`. 측정 → `analysis.md` v4 섹션(정합성 `≤ total`, **락 거절률(503 ⓒ)**, 처리량/지연).
  - **fail-fast 한도 미충전(발견5)**: 공유부하 10s에서 `actual < 100`은 정합 실패가 아니라 대기 큐 부재의 정상
    귀결. duration↑(90s)로 충전 + 초과 0 확인. 단일 핫키+즉시실패라 거절률이 높은 건 당연(분산 락 일반의 성질 아님).

### Step F — v5 커스텀 SET NX + Lua + 측정
- **0. PoC(직렬화 + TTL 경계)**: `setIfAbsent(key, token, ttl)` + Lua compare-and-delete(token 불일치 시 삭제 안 함).
  **⚠️ 직렬화**: 기본 value serializer가 JSON이면 token에 따옴표가 붙어 Lua raw 비교가 *조용히 항상 실패* →
  release 불가 → 매 요청이 TTL까지 락 점유. **락 전용 `StringRedisTemplate`(String serializer) 별도 빈**으로
  acquire/release 일치. **⚠️ TTL 경계**: `ttl > worst-case tx`(만료-중간탈취 = v2 함정의 분산판).
- 분산 락 유틸 `infrastructure/redis/RedisLockRepository.java`: `tryLock(key, token, ttl)`(=SET NX PX),
  `unlock(key, token)`(Lua compare-del). key `lock:coupon:{couponId}`, token UUID.
- `CouponIssueServiceV5`(**v4와 동형 — 파사드 + 가드**): `if(!locked) return 503; try{inner} finally{unlock(key,token)}`.
  커스텀 unlock은 compare-del이라 미보유 시 no-op으로 자연 안전하지만 **구조는 v4와 동일 유지**(① 비교 공정성
  ② 미래 최적화 안전). 고정 30s 무갱신.
- `POST /api/v5/coupons/issue`. 측정 → `analysis.md` v5 섹션(정합성 `≤ total`, 락 거절률, **v4와 비교**).
- **(선택) ★ 의도적 스톨 데모 — fencing 부재**: 전용 엔드포인트 + 셸 타이밍 통제로 `ttl<stall` 인위 주입 →
  두 홀더 동시 점유 → 초과발급 재현. compare-del은 *오해제는 막지만*(release 안전) *동시 점유는 못 막음*
  (mutual exclusion 안전 = fencing token 영역). **이 초과는 데모의 통제 산물 — 정상 v5는 초과 0.** Step G 3축의
  갱신/fencing 축 증거. (정상 측정과 절대 혼동 금지 — 별개 reset·격리.)

### Step G — 종합 비교 + analysis 마무리 (새 구현 없음)
- 모든 `k6/*.json`·`integrity/*.txt` 취합 → 사다리 트레이드오프 표를 실측값으로 완성. **처리량·지연 모두
  outcome별 분리 컬럼**(성공 200 / 한도소진 409 / 락거절 503 각각의 처리량·p95/p99 + 503 거절률(버전 내)).
- **Blocker 가드(거짓 헤드라인 방지)**:
  - 처리량 분리: v4/v5는 요청 대부분이 즉시 503(0-cost)이라 총 iter/s가 인위 폭증 → 단일 컬럼이면 "v4가 100배
    빠르다" 거짓. 성공(200) 처리량과 분리.
  - 지연 분리 + **"성공 지연 단일 우승자 금지"**: v3 성공 지연엔 행락 대기 포함(기다렸다 성공), v4/v5 성공 지연엔
    대기 없음(기다릴 요청은 거절로 빠짐). 블로킹은 경합을 *지연*으로, fail-fast는 *거절*로 배출 — **정책이 다른 두
    숫자**. "성공 지연 + 거절률을 묶어 정책 특성화"(v3=대기 흡수 / v4·v5=저지연·고거절), 단일 우승자 금지.
  - 거절률 분모는 버전마다 자릿수 차 → **버전 내 정책 관찰치로만**, 버전 간 직접 비교 금지(병기 시 절대 카운트).
- **★ throughput 버전 간 직접 비교 금지(Step G 핵심 방어 — 유지)** + 그 *이유*를 통제 재실험 결론으로 명문화
  (3-3-2 흡수):
  - phase3-3의 버전 간 throughput 격차는 **락 위치 때문이 아니라 경합 전략이 같이 움직인 교란**이다(v2/v3=대기
    흡수, v4/v5=fail-fast 거절). 경합을 **유한대기로 통일하면**(리허설 통제 재실험) 격차가 **작은 단조 락-위치
    비용으로 수렴**(락 없음 > JVM ≳ DB > 외부 Redis, near-parity)하고 전부 정합·거절 0이 된다 → 즉 raw 격차의
    지배 변수는 락 기질이 아니라 경합 처리 방식. **따라서 throughput을 "어느 락이 빠른가"로 읽지 않는다.**
  - **갱신 축(v4 워치독 vs v5 고정TTL)은 정상 부하 throughput으로 격리 불가(발견8 — 정직한 음성)**: hold가
    sub-second ≪ TTL이라 고정TTL이 임계영역 중간 만료되지 않음 → 워치독이 막아줄 일이 안 생김 → 정상 부하
    throughput 차이는 갱신 정책이 아니라 **클라이언트 대기 구현(pub/sub vs spin) 아티팩트**. 갱신 축의 진짜 효과는
    **스톨 조건(hold>TTL)의 정합으로만** 드러난다(★스톨 데모). 정상 부하 throughput 차이를 "워치독이 빠르다"로
    읽으면 틀린다.
- **정직한 서사 축 = "어느 락이 빠른가"가 아니라 3겹**:
  1. **정합 범위**: 없음(v1) → 단일 JVM(v2) → 다중·공유 DB행(v3) → 다중·외부 Redis(v4·v5). 분산 정합이 사다리 값.
  2. **경합 흡수**: 무시(v1·초과) → park(v2) → DB큐(v3) → 거절/fail-fast(v4·v5). "직렬화 비용이 어디서 나오나".
  3. **갱신 정책**(v4 vs v5): 워치독 자동갱신 vs 고정TTL. 워치독이 사주는 것 = 만료-중간탈취 차단(스톨 데모로
     격리 증명). **단 둘 다 fencing-safe는 아님** — fencing token(자원 측 단조번호 검증) 없이는 GC pause/네트워크
     단절 시 두 홀더 동시 점유가 어느 분산 락에도 남는다. (이것이 §6 프로덕션 v3 선택의 근거 중 하나.)
- **503 의미 구분(필수)**: v3 503 = DB 대기(ⓐ풀/ⓑ행락 추가 구분) vs v4·v5 503 = 즉시 락 거절(ⓒ). 갈라 적어 오독 방지.
- **범위 한정(필수)**: 모든 결론은 **단일 핫키(couponId=1) + 단일 JVM** 가정에 묶인다. "거절률이 높다"는 단일
  핫키+즉시실패의 귀결이지 분산 락 일반의 성질이 아니다. v4/v5의 본 정당화(다중 인스턴스 정합)는 보강#1에서만
  실증 — 본 측정은 "정합성 + 직렬화 비용 위치 + 갱신 정책 격리"까지만 주장.
- **(선택) 조건부 후속**: v4/v5 거절률이 너무 높아 v3과 깨끗한 지연/처리량 비교가 어려우면 `tryLock` 대기를 짧은
  값으로 준 변형을 추가 측정해 "즉시 실패 vs 짧은 대기" 비교축 추가. 1차 결정(즉시 실패) 유지, 보고서만 진행.
- "프로덕션이라면" 참고(§6 v3 배선 결정 + 재시도/대기·Fallback) 한 단락.

---

## 10. 산출물 / 디렉토리

- **앵커 경로**: `results/phase3-3-concurrency/` (k6 결과 + 정합성 + 분석).
- **출력 형식**: 기존 `results/phase3-1-index-optimization/`·`results/phase3-2-ranking-optimization/` **관례를
  따른다**(예: `k6/<step>.json`, 정성 검증 `*.txt`, 종합 `analysis.md` 또는 `*-comparison.txt`). **구체 파일 목록·
  명명은 본 실행 런 세션이 그 두 디렉토리를 실제로 읽어 맞춘다** — 이 스펙은 *지시와 앵커 경로만* 명시한다.
- **ADR 없음.** 설계 결정(§6·§8)은 스펙 본문 + `analysis.md` 해석에 둔다.
- 구조(개념):
  ```
  scripts/coupon/reset-coupon.sql            (핸드오프)
  scripts/measure/run-coupon-concurrency.sh  (핸드오프, 인자: 버전)
  test/load/coupon-test.js                   (핸드오프)
  results/phase3-3-concurrency/{k6/, integrity/, analysis.md}   (본 실행 생성, 형식은 3-1·3-2 관례)
  src/main/java/com/project/
    api/coupon/CouponController.java + dto/{CouponIssueRequest,CouponIssueResponse}.java
    service/coupon/CouponIssueServiceV1..V5.java
    domain/coupon/{Coupon(issue 추가), CouponRepository(findByIdForUpdate 추가), CouponIssueRepository(exists/count 추가)}.java
    infrastructure/redis/RedisLockRepository(v5).java
      (v4 RedissonClient = redisson-spring-boot-starter 자동구성 — 별도 config 파일 불요)
  ```

---

## 11. 범위 밖 / 보강

> **[2026-08-26 갱신]** 아래 "백로그" 표기는 이 문서 작성 시점(3-3 본 실행 직후) 기준이다. **보강#1은 그 뒤 실행됐다**
> — ROADMAP §Done 「다중 인스턴스 정합 실증 (보강#1)」(2026-06, PR #10): v2 붕괴 재현 + v3~v5 경계초월 정합 확인.
> 단 **재현 자산(토폴로지 compose·LB 설정·k6 산출물)은 이 repo 트리에 없다** — 결론만 ROADMAP에 기록돼 있으므로,
> 토폴로지를 재사용하려는 후속 phase는 **신규 구축을 전제**해야 한다.

- **보강#1 (다중 인스턴스 실증) — 포폴 성장 서사 본편(백로그)**: Docker Compose app N인스턴스 + Nginx LB + 공유
  Redis. v2(synchronized)가 다중 JVM에서 깨짐, v3/v4/v5가 인스턴스 경계를 넘어 정합 유지함을 **실측**. 모니터링
  스택(인스턴스별 발급 집계·풀/LB 헬스) 동반 → 스코프 번짐으로 본 실행에서 제외. 착수 시 결정: 스케일 방식 /
  N>1 throughput 공정성(총 풀 vs 인스턴스당 풀) / 1차 스코프=정합성. (예상 서사 카드 `docs/draft_03-concurrency-
  예상-multiserver.html` — 다중 정합성 beat가 이 보강에서 `추론`→`실측` 승격.)
- 보강#2: Redis 장애 Fallback + 서킷브레이커(v4/v5 → v3 자동 전환).
- 경합 상호작용 실험(유한대기 vs fail-fast — 같은 락에 두 경합): 리허설 통제 재실험이 부분 확인, 본 실행 범위 밖.
- 커밋된 스키마 초기화 경로(Flyway/schema.sql) + `gradle-wrapper.jar` 추적: 본 레포 위생 백로그(발견1·2 뿌리).

---

## 12. 작업 순서 (순차, 각 Step 완료 후 정지·검증·기록·커밋)

1. **사전 검증 게이트(§2)** 통과
2. **Step A** 도메인+DTO+시드 → 검증 → 커밋
3. **Step B** v1 + 하네스 도입(핸드오프 복사) + v1 측정(부하 확정) → 기록 → 커밋
4. **Step C** v2 synchronized → 측정 → 기록 → 커밋
5. **Step D** v3 FOR UPDATE → 측정 → 기록 → 커밋
6. **Step E** v4 Redisson(워치독) → 측정 → 기록 → 커밋
7. **Step F** v5 커스텀 SET NX(고정TTL) + (선택)스톨 데모 → 측정 → 기록 → 커밋
8. **Step G** 종합 비교(3축 서사 + throughput 비교 금지 + §6 프로덕션 결정) → 커밋 → `/pr`

> 각 Step 검증·측정·해석은 직접 수행. PR을 `main`에 머지하는 것도 직접.
