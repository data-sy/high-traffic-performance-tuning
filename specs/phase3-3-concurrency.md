# Phase 3-3 — 동시성 제어 (쿠폰 발급 분산 락)

> ✅ **결정 반영본** — 초안의 "열린 결정 사항" 6건이 모두 확정됨 (아래 "확정된 결정 사항" 참조).
> **핵심 변경**: ① v4를 Redisson(v4)·커스텀(v5)으로 분리 ② Step B~F를 "하나 구현 → 하나 측정 → 기록"으로 재배치 ③ 모든 버전 Step에 `analysis.md` append.

## 현재 상태
- Phase 1(셋업), Phase 2(도메인 엔티티) 완료
- 시나리오 ①(인덱스, PR #3), ③(랭킹, PR #5), 모니터링 인프라(PR #6) 완료
- `Coupon`, `CouponIssue` 엔티티는 **Phase 2에서 이미 정의됨** (아래 "엔티티 현황" 참조 — 신규 생성 아님)
- `ddl-auto: validate` 활성 상태. 변경 금지
- Docker 컨테이너 (MySQL, Redis) 실행 중 (`docker compose up -d`)
- Redis 인프라는 시나리오 ③에서 구축됨: `infrastructure/redis/RedisConfig.java`, `RankingRedisRepository.java` (RedisTemplate 사용 가능, Lettuce)
- 의존성: `spring-boot-starter-data-redis` 존재. **Redisson 없음 → v4(Redisson)에서 추가** (`org.redisson:redisson-spring-boot-starter`)

## 엔티티 현황 (이미 존재 — 절대 신규 생성하지 말 것)
```
Coupon (table: coupon)
  - id (Long, IDENTITY)
  - name (String)
  - totalQuantity (Integer, col: total_qty)   ← 발급 한도
  - issued (Integer, 기본 0)                    ← 발급 카운터
  - createdAt
  - 비즈니스 메서드: 없음 → issue() 추가 필요 (Step A)

CouponIssue (table: coupon_issue)
  - id (Long, IDENTITY)
  - couponId (Long)   ← 관계 없음, Long only (CLAUDE.md)
  - userId (Long)     ← 관계 없음, Long only
  - issuedAt
  - UNIQUE (coupon_id, user_id)   ← 같은 사용자 중복 발급을 DB가 이미 차단

CouponRepository, CouponIssueRepository: 기본 JpaRepository (커스텀 메서드 없음)
```

## 이 시나리오가 증명하는 문제 (핵심 — 정독 필수)
- `unique(coupon_id, user_id)` 제약이 **"같은 사용자의 중복 발급"은 이미 차단**한다.
- 이 시나리오가 드러내는 동시성 문제는 **초과 발급(over-issuance)**이다: **서로 다른** 다수 사용자가 한정 수량 쿠폰에 동시 요청 → `issued` 카운터의 *read → check(issued < total) → increment*가 원자적이지 않아 경쟁 → 실제 발급 건수가 `total_qty`를 초과.
- **정합성의 단일 진실 소스**: `SELECT COUNT(*) FROM coupon_issue WHERE coupon_id = ?`. `coupon.issued` 컬럼은 lost update로 부정확할 수 있으므로 **발급 정합성은 coupon_issue 행 수로 판정**한다.
  - 문제 재현(v1): `count(coupon_issue) > total_qty`
  - 정합성 충족(v2~v5): `count(coupon_issue) ≤ total_qty` (**초과 없음 = 정합성 본질**)
    - v2·v3: 대기/직렬화로 충분한 부하 시 `== total_qty`(한도 소진)
    - v4·v5: 합격식은 **`≤ total`(초과 없음)만 요구**하되, 지속 부하면 기대값은 **`== total`**(락 빈 틈마다 다음 iteration이 충전). `< total`이면 정상 경합이 아니라 부하/duration 부족 신호 → 재측정. 락 거절률을 함께 측정

## 목표
쿠폰 발급 동시성을 점층 전략으로 해결하고, **정합성**(발급 수 ≤ total_qty)과 **처리량/지연**을 측정·비교한다. 개념상 4단계 사다리이며, 꼭대기(분산 락)를 두 가지로 구현해 총 5버전.

- **v1** No Lock (기준선 — 초과 발급 재현)
- **v2** `synchronized` (애플리케이션 락)
- **v3** `SELECT ... FOR UPDATE` (DB 비관적 락)
- **v4** Redis 분산 락 — **Redisson `RLock`** (라이브러리, 정답 레퍼런스)
- **v5** Redis 분산 락 — **커스텀 `SET NX` + Lua** (직접 구현, 원리 노출)

## 중요 규칙
- 먼저 `CLAUDE.md`를 읽고 프로젝트 컨벤션을 확인할 것
- **관계 추가 절대 금지**: `Coupon`/`CouponIssue`에 `@ManyToOne`·`@OneToMany` 등 어떤 관계도 추가하지 말 것. `couponId`/`userId`는 `Long`만
- **엔티티에 Setter 금지**. `issued` 증가는 `Coupon`의 비즈니스 메서드로만
- 엔티티(`Coupon`, `CouponIssue`) 신규 생성 금지. 이미 존재. **비즈니스 메서드·Repository 메서드만 추가**
- **v1~v5는 모든 버전이 코드에 공존**한다 (독립 비교용). **버전별 Service + 버전별 엔드포인트**로 분기
- **구현-측정 한 세트 원칙**: 각 버전을 구현한 직후 바로 부하 측정 → 정합성·성능 확인 → `analysis.md` 기록 → 커밋. 전부 만든 뒤 일괄 측정하지 않는다
- MySQL은 Docker 컨테이너에서 실행. 셸 함수로 접근: `run_mysql() { docker exec -i docker-mysql-1 mysql -uroot -proot flashdeal "$@"; }`. 각 셸 스크립트 상단에 정의. `mysql -h127.0.0.1` 직접 사용 금지
- 모든 `.sh` 파일은 실행 권한 필수 (`chmod +x`)

---

## ✅ 확정된 결정 사항
1. **v4 락 방식**: Redisson(v4) 먼저 → 커스텀 SET NX+Lua(v5) 추가. 둘을 별도 엔드포인트로 비교
2. **측정 파라미터**: `total_qty=100`, 동시 ~1000요청. **부하는 단발이 아니라 지속형**(VU가 duration 동안 반복 요청)이어야 함 — 즉시 실패 정책의 v4/v5는 락이 빈 틈마다 다음 iteration이 획득해야 발급 수가 한도까지 찬다. 단발(VU당 1요청)이면 v4/v5는 대부분 즉시 503으로 튕겨 한도 미달 → v3(블로킹)과 비교가 불공정. **v1(Step B)에서 확정** 후 v2~v5 재사용
3. **실패 응답**: 즉시 실패. **한도 소진 = `409`(`SOLD_OUT`), 락 획득 실패 = `503`(`LOCK_NOT_ACQUIRED`)** 로 구분 (k6 status로 거절 원인 분리). 짧은 재시도/대기는 `analysis.md`에 참고만
4. **커밋↔락 해제 순서 + TTL/lease 경계** (v2·v4·v5 공통, 분산판은 v4·v5):
   - (순서) 락은 **트랜잭션 경계 바깥**(파사드 패턴)에서 해제 — 커밋 이후에 풀린다. v2·v4·v5 동형. 락 ⊃ 트랜잭션
   - (만료) **TTL/lease는 worst-case 트랜잭션 시간을 넉넉히 초과**해야 한다. 락 TTL이 트랜잭션 커밋보다 먼저 만료되면, 순서를 지켜도 락이 트랜잭션 *도중* 사라져 다른 스레드가 획득 → 미커밋 `issued` 읽음 → 초과 발급. **이것은 v2 함정(순서)과 별개의 "만료-중간-획득" 분산판 경계 함정.**
     - v4(Redisson): **확정 — 고정 leaseTime(워치독 OFF) 채택** (F4). (워치독 자동 연장은 leaseTime 미지정/-1로 가능하나, 본 구현은 고정 lease로 단순화·v5와 동형.) leaseTime을 명시하면 워치독이 꺼지므로 **leaseTime > worst-case 트랜잭션 시간**을 PoC 실측으로 보장. 단일 보유자라 inner tx가 짧아 worst-case가 작아 마진 확보 용이
     - v5(커스텀 `SET NX PX ttl`): ttl > worst-case 트랜잭션 시간
5. **유니크 userId**: `userId = __VU * 100000 + __ITER` 확정
6. **문서화**: 각 버전 측정 직후 `results/phase3-3-concurrency/analysis.md`에 한 섹션 append

---

## 📌 보강 후보 (핵심 5버전 완성 후 선택적)
1. **[강력 추천] 실제 분산 환경** — Docker Compose app 3 인스턴스 + Nginx LB + 공유 Redis. v2 `synchronized`가 다중 JVM에서 실패함을, v3/v4/v5가 인스턴스 경계를 넘어 정합 유지함을 **실측**으로 증명. (+1.5~2세션)
2. **[고급] Redis 장애 Fallback + 서킷브레이커** — Redis 다운 시 v4/v5 → v3 자동 전환 (Resilience4j)
3. **[측정 디테일] v3 HikariCP 풀 모니터링** — `FOR UPDATE` 시 active/waiting 포화 캡처 (Actuator/Micrometer)
4. **[설계] Redis Lua 원자적 카운터** — `coupon:{id}:remaining`을 Lua로 원자 차감(DB 부하 0). v6 후보로 분리 가능

---

## Step A: 공통 도메인 + DTO + 쿠폰 시드

### 제한사항
- `Coupon`/`CouponIssue` 엔티티에 필드·관계 추가 금지. `Coupon`에 **비즈니스 메서드만**
- Controller → Service → Repository 호출 순서 필수
- 응답에 엔티티 직접 노출 금지. DTO 사용

### 1. Coupon — 비즈니스 메서드 추가
- 위치: `domain/coupon/Coupon.java`
- 추가: `public void issue()` — `issued >= totalQuantity`이면 예외(커스텀 `CouponSoldOutException` 권장), 아니면 `issued++`
- 주의: 이 메서드는 **락이 아니다**. 동시성 보장은 Service 계층 전략(v1~v5)의 책임. v1은 이 검사조차 경쟁에 노출

### 2. CouponRepository — 비관적 락 메서드 추가 (v3용)
- 위치: `domain/coupon/CouponRepository.java`
- 추가:
  ```java
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Coupon c where c.id = :id")
  Optional<Coupon> findByIdForUpdate(@Param("id") Long id);
  ```

### 3. CouponIssueRepository — 조회 메서드 추가
- 위치: `domain/coupon/CouponIssueRepository.java`
- 추가: `boolean existsByCouponIdAndUserId(Long couponId, Long userId)`, `long countByCouponId(Long couponId)`

### 4. DTO
- 위치: `api/coupon/dto/`
- `CouponIssueRequest`: `couponId`(Long), `userId`(Long)
- `CouponIssueResponse`: 성공 여부 + `couponId`, `issued`(현재 발급 수 — 엔티티 필드명과 통일, 옛 메모의 무효 필드명 `issuedCount`와 혼동 금지), `totalQuantity` 등. 정적 팩토리 `from(...)`
- 실패 응답: 한도 소진(`SOLD_OUT`)·락 실패(`LOCK_NOT_ACQUIRED`)를 구분할 수 있는 에러 코드 포함 (결정 #3)
- Lombok 컨벤션(`@Getter` + 생성자/`@Builder`) 준수

### 5. 쿠폰 시드 / 리셋 SQL
- 위치: `scripts/coupon/reset-coupon.sql`
  ```sql
  DELETE FROM coupon_issue WHERE coupon_id = 1;
  DELETE FROM coupon WHERE id = 1;
  INSERT INTO coupon (id, name, total_qty, issued, created_at) VALUES (1, '한정 쿠폰', 100, 0, NOW());
  ```
- 측정 스크립트가 각 버전 시작 전 이 리셋을 호출

### Step A 검증 (직접 수행)
```
- reset-coupon.sql 실행 → coupon(id=1, total_qty=100, issued=0) 1건, coupon_issue 0건
- ./gradlew compileJava 성공 (bootRun/테스트는 돌리지 말 것 — 직접 검증)
```
검증 통과 후 `/commit`

---

## Step B: v1 — No Lock (기준선) + 측정 인프라 구축 + 측정

> 측정 도구(k6·셸·analysis.md)를 **여기서 만든다**. 어차피 버전 파라미터화된 공용 도구이고, v1 측정으로 부하 파라미터를 확정해야 v2~v5가 같은 부하로 비교되기 때문.

### 1. CouponIssueServiceV1
- 위치: `service/coupon/CouponIssueServiceV1.java`
- 로직: 쿠폰 조회 → `existsByCouponIdAndUserId` 거절 → `coupon.issue()` → `CouponIssue` 저장. **락·동기화 없음**
- `@Service`, `@Transactional`. 의도적으로 초과 발급에 노출 (문제 재현용)

### 2. 컨트롤러
- 위치: `api/coupon/CouponController.java`
- `POST /api/v1/coupons/issue`, body `CouponIssueRequest`, 반환 `CouponIssueResponse`

### 3. 측정 인프라 (공용 — 여기서 1회 구축)
- **`test/load/coupon-test.js`** (k6)
  - `POST /api/${__ENV.VERSION}/coupons/issue` 부하. 대상 버전은 env로 파라미터화
  - **유니크 userId**: `userId = __VU * 100000 + __ITER` (결정 #5)
  - 부하: **지속형** — VU가 duration 동안 반복 요청(`constant-vus` 또는 `ramping-vus`, **단발 `per-vu-iterations=1` 금지**). 한도(`total_qty`)보다 충분히 많은 동시 VU(~1000 규모, 결정 #2). 지속형이라야 v4/v5(즉시 실패)에서 락이 빈 틈마다 다음 iteration이 획득해 발급 수가 한도까지 찬다. VU·duration은 v1 측정으로 확정
  - status별 카운트 집계 (200 / 409 SOLD_OUT / 503 LOCK_NOT_ACQUIRED 구분 — 결정 #3)
  - **outcome별 지연 Trend 분리 (Blocker — 집계 p95/p99 금지)**: `http_req_duration`을 전체로 집계하면, v4/v5는 요청 ~95%가 즉시 503(<1ms)이라 집계 p95가 거절 구간에 떨어져 p95≈1ms로 찍힌다 → "분산 락이 v3보다 2000배 저지연"이라는 거짓 결론. **status 태그별 별도 Trend**로 `성공(200)`·`한도소진(409)`·`락거절(503)`의 p95/p99를 **셋으로** 나눠 기록한다 (k6 `Trend` + status 태그. ⚠️ 기존 step tagging은 run 단위 `--tag step=...`(CLI 전역)일 뿐 — 요청별 status 조건부 태그 + status별 Trend 메트릭은 여기 `coupon-test.js`에서 신규 작성한다. 재사용 가능한 동급 인프라가 아니라 "k6 태깅 경험"이 있을 뿐). **"거절"은 503-only로 정의**하고 409는 거절이 아닌 한도소진(정상 종료)으로 분리한다. ⚠️ 409 지연은 **버전 의존**이라 버전 간 비교 금지: v2/v3은 직렬화(모니터/행락)를 통과한 *뒤* 한도소진이라 느리고, v4/v5는 락 즉시 획득 직후라 빠르다 — 이 차이를 "거절 p95"에 섞으면 200/503에서 막은 "2000배" 거짓 헤드라인이 409 축에서 재발한다(v2의 409는 거절이 아니라 한도소진이다). RTT 세금은 성공-only 지연으로만 보인다
  - **비-HTTP 실패 + 미분류 5xx도 집계**: accept 큐 드롭·커넥션 리셋·타임아웃은 어느 status 버킷에도 안 들어간다. **미분류 5xx**도 마찬가지로 200/409/503 어디에도 안 들어간다 — 특히 **v1은 락이 없어 503 매핑이 없으므로**, 작은 고정 풀에서 풀 타임아웃(`SQLTransientConnectionException`)이 `500`으로 새어 v1 거절률 분모가 깨진다(락 버전의 미포착 예외도 동류). 이것까지 k6 요약에 카운트(**비-HTTP + 미분류 5xx를 별도 버킷**으로)해야 거절률 분모가 닫힌다 (안 그러면 "503 거절률 N%"가 틀린 숫자가 됨). v4의 tryLock 가드가 말하는 "미분류 5xx 버킷"과 같은 클래스의 일반화
- **인프라 통제변수 (둘은 성격이 정반대라 처방이 다름)**:
  - **HikariCP 풀 = v3의 *내재 비용*이니 "제거"하지 말고 "드러내라".** v3는 `@Transactional`이 커넥션을 빌린 뒤 `FOR UPDATE`가 그 커넥션을 쥔 채 블로킹 → 대기 v3 요청이 커넥션 점유 → 풀 고갈. v4/v5는 락 경합이 트랜잭션 밖이라 대기 중 커넥션 비점유. **이 풀 압박 차이 자체가 "v3=DB 커넥션 고갈"이라는 간판 발견.** 풀(`spring.datasource.hikari.maximum-pool-size`)을 over-provision하면 v3 시그니처를 지우게 됨 → **현실적 유한값으로 고정**하고 포화(waiting 수·Actuator 풀 메트릭)를 측정해 발견으로 제시. (풀을 키워도 v3 성공 처리량은 안 빨라짐 — 쿠폰 행에서 직렬. 바뀌는 건 대기 위치·실패 모드뿐 → "측정 대상"이지 "제거 대상" 아님)
  - **Tomcat maxThreads = 순수 아티팩트니 "중화하고 세라".** 200(기본) 초과 VU가 accept 큐에서 떨어지면 거절률 분모가 깨진다. 락 전략과 무관한 HTTP 계층 잡음이므로 `server.tomcat.threads.max`를 **바인딩 제약이 안 되게 충분히 올려**(모든 요청이 HTTP status를 받게) 중화하고, 그래도 떨어지는 드롭/타임아웃은 위 비-HTTP 카운트로 센다.
  - **타임아웃 두 개도 통제변수로 핀 (F1 — v3 간판의 circularity 방지)**: Hikari `connectionTimeout`(기본 30s)·MySQL `innodb_lock_wait_timeout`(기본 50s)을 **명시값으로 고정·기록**한다. 기본값은 30s<50s라 풀 대기자(대다수)가 행락 대기자(풀 크기만큼 소수)보다 **항상 먼저** 터져 "ⓐ(풀 고갈) 우세"가 *측정*이 아니라 두 기본값의 대소관계로 **선결정**된다 — 누가 `innodb_lock_wait_timeout=1s`로 바꾸면 행락 보유자가 먼저 터져 커넥션을 반납→풀 순환→**ⓑ(행락) 우세로 뒤집혀** "v3=커넥션 고갈" 간판이 무너진다. 또 v3 성공 p95가 풀 대기 시간을 머금어 락 전략이 아닌 타임아웃 기본값의 아티팩트가 된다. → 두 값을 핀하고, ⓐ 선발화가 설정 대소관계의 구조적 귀결임을 Step D에 명시
  - 위 네 값(풀 크기·maxThreads·두 타임아웃) 모두 **5버전 공통으로 고정·기록**. v2(대기 중 커넥션 비점유) vs v3(점유) 차이도 한 줄 기록
- **`scripts/measure/run-coupon-concurrency.sh <version>`**
  - `run_mysql()` 상단 정의, 서버 헬스체크, `mkdir -p`
  - 인자로 받은 한 버전에 대해:
    1. `scripts/coupon/reset-coupon.sql` 실행
    2. `k6 run -e VERSION=<version> test/load/coupon-test.js --summary-export=results/phase3-3-concurrency/k6/<version>.json`
    3. **짧은 settle(1~2s) 후** 정합성 쿼리 → `results/phase3-3-concurrency/integrity/<version>.txt` (F3 — k6 graceful-stop 하드 데드라인에 버려진 in-flight의 서버측 커밋이 COUNT보다 늦게 끝나, v4/v5가 일시적 `<100`으로 오읽혀 "부하 부족 → 재측정" 가드를 오발동하는 좁은 창 방지):
       ```sql
       SELECT
         (SELECT total_qty FROM coupon WHERE id = 1)            AS total_qty,
         (SELECT issued    FROM coupon WHERE id = 1)            AS issued_counter,
         (SELECT COUNT(*) FROM coupon_issue WHERE coupon_id=1)  AS actual_issued;
       ```
- **`results/phase3-3-concurrency/analysis.md`** 생성 (헤더 + v1 섹션부터 append)

### 4. v1 측정 + 부하 파라미터 확정
- `run-coupon-concurrency.sh v1` 실행
- **`actual_issued > total_qty` (초과 발급)가 또렷이 재현될 때까지 부하 조정** → 확정된 VU·duration을 k6 스크립트에 고정
- `analysis.md`에 v1 섹션 기록: 확정 부하, `actual_issued`/`issued_counter`/`total_qty`, 초과량, 해석(왜 초과가 나는가 — check-then-act 경쟁)
- **측정 범위 명시 (Note)**: 부하는 유니크 userId라 `existsByCouponIdAndUserId`가 내내 false → `unique(coupon_id,user_id)`의 "중복 발급 차단" 경로는 부하로 안 돌아간다. 이 시나리오는 **초과 발급만 대상**이고, 중복 차단은 단건 요청으로만 검증(아래 단건 발급 + 같은 userId 재요청 거절 확인)

### Step B 검증 (직접 수행)
```
- compileJava 성공
- 단건: POST /api/v1/coupons/issue {couponId:1,userId:1} → 발급 성공
- run-coupon-concurrency.sh v1 → integrity/v1.txt: actual_issued > total_qty (초과 재현)
- analysis.md에 v1 섹션 기록됨
```
검증 통과 후 `/commit`

---

## Step C: v2 — synchronized + 측정

### 1. CouponIssueServiceV2
- 위치: `service/coupon/CouponIssueServiceV2.java`
- v1과 동일 로직에 임계영역을 `synchronized`로 보호
- **⚠️ 트랜잭션 경계 필수 규칙 (Spring 프록시 함정 — 이 시나리오 핵심 위험)**: `@Transactional` 메서드에 `synchronized`를 그냥 같이 걸면(특히 메서드 레벨) 프록시 커밋이 모니터 **밖**에서 일어난다 (`모니터 해제 → 다음 스레드가 stale issued 읽음 → 커밋`) → **단일 인스턴스에서도 초과 발급** → v2 서사가 깨짐.
  - 따라서 **`synchronized`는 트랜잭션 경계 *바깥*을 감싼다** (결정 #4). 구현:
    - `@Transactional` 없는 outer(파사드) 메서드를 `synchronized`로 잡고, 그 안에서 **별도 빈의 `@Transactional` inner 메서드** 호출 (자기 호출은 프록시 미적용 → 반드시 *다른 빈*)
  - **금지**: 같은 메서드에 `@Transactional` + `synchronized` 동시 부착
  - **모니터 객체**: 전용 `private final Object lock = new Object()` 하나를 잡는다. `synchronized(couponId)`(Long 오토박싱 캐시)·`couponId.toString().intern()` 같은 건 잘못 공유/누수 위험이라 금지. (테스트는 단일 쿠폰이라 전역 모니터로도 정합·측정에 영향 없음 — 키 단위 분산은 본 측정 범위 밖)
- 한계 명시(주석/문서): 올바로 짜도 **단일 JVM에서만 정합, 다중 인스턴스 미보장**(인스턴스마다 별도 모니터), 직렬화로 처리량 병목 → v3/v4/v5 이행 근거

### 2. 컨트롤러
- `POST /api/v2/coupons/issue`

### 3. 측정 + 기록
- `run-coupon-concurrency.sh v2` (v1 확정 부하 그대로)
- `analysis.md` v2 섹션 append: `actual_issued ≤ total`(충분 부하 시 ==), 처리량/지연(직렬화 병목 — p99 등), v1 대비 변화, 해석

### Step C 검증 (직접 수행)
```
- compileJava 성공
- integrity/v2.txt: actual_issued ≤ total_qty (충분한 부하 시 == 100)
- analysis.md v2 섹션 기록
```
검증 통과 후 `/commit`

---

## Step D: v3 — SELECT FOR UPDATE + 측정

### 1. CouponIssueServiceV3
- 위치: `service/coupon/CouponIssueServiceV3.java`
- `couponRepository.findByIdForUpdate(couponId)`로 쿠폰 행 락 획득 후 검사·발급·저장
- **락 획득 후 검사/발급 순서 준수** (락 잡기 전 비락 조회를 임계 판단에 쓰지 말 것 — v1과 같은 창 방지)
- `@Transactional` 필수 (락은 트랜잭션 종료 시 해제)
- 정합성 보장, 단 DB 행 경합 (한계 명시). 대기 타임아웃 시 `503` 매핑하되, **v3 내부 두 원인을 서브분류해 카운트**(Caution): ⓐ HikariCP connectionTimeout 초과(`SQLTransientConnectionException` = 풀 고갈) vs ⓑ `innodb_lock_wait_timeout` 초과(MySQL 1205 = 행락 대기). 이 둘을 뭉뚱그리면 "v3=DB 커넥션 고갈" 간판이 가정이 된다 — ⓐ 우세를 측정으로 실증해야 단정 가능. **단 기본값(connectionTimeout 30s < innodb_lock_wait_timeout 50s)에선 ⓐ가 ⓑ보다 항상 먼저 터져 "ⓐ 우세"가 구조적으로 선결정된다** (F1): 두 타임아웃을 통제변수로 핀(위 "인프라 통제변수")하고, ⓐ 선발화가 설정 대소관계의 귀결임을 병기해야 "실측"이 circular해지지 않는다. (v4/v5의 "즉시 락 거절" 503과도 별개)

### 2. 컨트롤러
- `POST /api/v3/coupons/issue`

### 3. 측정 + 기록
- `run-coupon-concurrency.sh v3`
- `analysis.md` v3 섹션: 정합성, 처리량/지연(DB 행 경합 — v2 대비), **Actuator `hikaricp.connections.pending`/`active` 동시 캡처**(보강후보 #3 연결)로 풀 포화 실증. **503은 ⓐ풀 타임아웃/ⓑ행락 타임아웃으로 갈라 카운트하고, 핀한 두 타임아웃값(connectionTimeout·innodb_lock_wait_timeout)을 함께 기록. "v3=DB 커넥션 고갈" 간판은 ⓐ 우세가 실측될 때만 단정하되, 그 ⓐ 우세가 핀한 타임아웃 대소관계의 구조적 귀결임을 병기**

### Step D 검증
```
- integrity/v3.txt: actual_issued ≤ total_qty (충분한 부하 시 == 100)
- analysis.md v3 섹션 기록
```
검증 통과 후 `/commit`

---

## Step E: v4 — Redis 분산 락 (Redisson) + 측정

> 라이브러리 구현. **정답 레퍼런스** — v5 커스텀의 정합성을 여기에 맞춰 검증.

### 0. 의존성 + PoC (선행)
- `build.gradle`에 `org.redisson:redisson-spring-boot-starter` 추가
- PoC: `RLock` **획득 / 해제 / TTL 처리** 동작 확인. **lease 정책 확정**(결정 #4):
  - 자동 연장(워치독)을 쓰려면 `tryLock(waitTime, **leaseTime 미지정**)` — leaseTime을 주면 워치독이 꺼진다
  - leaseTime을 명시한다면 **leaseTime > worst-case 트랜잭션 시간**을 보장해, 락이 커밋 전에 만료되지 않게 함. **이 값은 PoC에서 실측한 worst-case tx를 초과하도록 검증(가정 금지)** — v4/v5는 락 보유 스레드가 동시 1개뿐이라 inner tx가 풀 경합 없이 짧아 worst-case가 작다(마진 확보 용이)

### 1. CouponIssueServiceV4 (Redisson)
- 위치: `service/coupon/CouponIssueServiceV4.java`
- 구조(결정 #4 — 락은 트랜잭션 바깥, 파사드):
  - inner(별도 빈, `@Transactional`): 조회·검사·발급·저장 (여기서 커밋 완료 후 outer가 해제)
  - **⚠️ unlock 가드 (Caution — v4 간판 지표를 지키는 핵심)**: **획득 못 한 락을 절대 unlock하지 말 것.** Redisson `RLock`은 미보유 상태에서 `unlock()`하면 `IllegalMonitorStateException`을 던진다. 단일 핫키+즉시 실패라 미획득 경로가 *다수*이므로, 이게 새면 거절 요청이 깔끔한 `503`이 아니라 `500`으로 집계돼 **헤드라인 지표인 503 거절률이 통째로 오염**된다. 구조를 다음으로 고정:
    ```
    boolean locked = lock.tryLock(0, leaseTime, unit);
    if (!locked) return 503 LOCK_NOT_ACQUIRED;   // unlock 호출하지 않음
    try { innerTx(...); }                          // 커밋까지 여기서 완료
    finally { lock.unlock(); }                     // 획득 분기 안에서만
    ```
  - **lease는 결정 #4와 맞물림**: 워치독 자동 연장이면 leaseTime 미지정, 명시하면 worst-case 트랜잭션 시간 초과. lease가 트랜잭션 도중 만료되면 다른 스레드가 같은 키를 획득 → 원 스레드의 `finally unlock`이 "내가 안 가진 락"을 풀려다 또 throw → 500. **즉 TTL 경계와 unlock 가드는 같은 함정의 두 얼굴.** (위 스니펫은 leaseTime 명시 = 워치독 OFF 변형이니, 그 값은 PoC 실측 worst-case를 초과해야 한다)
  - **⚠️ tryLock 예외 가드 (Note — unlock 가드와 같은 클래스)**: unlock뿐 아니라 `tryLock` 자체도 Redis 일시 끊김 시 `RedisException`을 던질 수 있다. 미포착이면 거절이 `503`이 아니라 `500`으로 집계돼 **헤드라인 503 거절률 분모가 오염**(unlock 가드와 동일 클래스). tryLock 호출도 try/catch로 감싸 명시적 5xx 버킷(또는 503)으로 분류. (테스트 중 Redis는 가동이라 위험 자체는 낮음 — 보강 #2가 인지)
- 락 획득 실패 → `503 LOCK_NOT_ACQUIRED`, 한도 소진 → `409 SOLD_OUT`

### 2. 컨트롤러
- `POST /api/v4/coupons/issue`

### 3. 측정 + 기록
- `run-coupon-concurrency.sh v4`
- `analysis.md` v4 섹션: 정합성(합격식은 `≤ total` 방어적 유지하되, **지속 부하면 기대값은 정확히 ==100** — 락 1획득=1발급이고 획득 간극이 수 ms라 100회 직렬 획득≈0.5s. **`<100`이면 "정상 경합"이 아니라 부하/duration 부족 신호 → 재측정**), **락 거절률(503 비율)**, 처리량/지연(v3 DB경합 대비), 해석(즉시 실패라 한도 남아도 거절 — 정책 관찰 결과)

### Step E 검증
```
- integrity/v4.txt: actual_issued ≤ total_qty (초과 없음)
- k6 결과에 503(LOCK_NOT_ACQUIRED) 비율 확인 가능
- analysis.md v4 섹션 기록
```
검증 통과 후 `/commit`

---

## Step F: v5 — Redis 분산 락 (커스텀 SET NX + Lua) + 측정

> 직접 구현. v4(Redisson)와 정합성·동작이 일치하는지로 검증.

### 0. PoC (선행 — 직렬화 함정 + TTL 경계 필수 확인)
- `setIfAbsent(key, token, ttl)` 동작, Lua 해제 스크립트가 **token 불일치 시 삭제 안 함** 확인
- **⚠️ 직렬화**: `RedisTemplate` 기본 value serializer가 JSON이면 token에 따옴표가 붙어 Lua raw 비교가 안 맞는다. **락 값 전용 `RedisTemplate<String,String>`(`StringRedisSerializer`)을 별도 빈으로** 두고 검증
- **⚠️ TTL 경계(결정 #4)**: `SET NX PX ttl`의 `ttl > worst-case 트랜잭션 시간`을 보장. ttl이 커밋보다 먼저 만료되면 락이 트랜잭션 도중 사라져 다른 스레드가 획득 → 미커밋 읽음 → 초과 발급 (v2 함정의 분산판)

### 1. 분산 락 유틸
- 위치: `infrastructure/redis/RedisLockRepository.java` (또는 `DistributedLock.java`)
- API: `boolean tryLock(String key, String token, Duration ttl)`(= `SET NX PX`), `void unlock(String key, String token)`(Lua compare-and-delete)
- lock key: `lock:coupon:{couponId}`, token: 요청별 UUID

### 2. CouponIssueServiceV5 (커스텀)
- 위치: `service/coupon/CouponIssueServiceV5.java`
- 구조: **v4와 동형(파사드 + unlock 가드).** `if(!locked) return 503; try{inner} finally{unlock(key,token)}`. 커스텀 `unlock`은 compare-and-delete(내 token일 때만 삭제)라 미보유 시 no-op으로 *자연 안전*하지만, **구조는 v4와 동일하게 유지** — 이유: ① v4와 비교 공정성(구조가 다르면 성능 비교가 오염) ② 미래에 누가 unlock을 "최적화"해도 안전성이 안 깨지게. 즉 가드는 v4 필수·v5 방어적
- 실패 응답 v4와 동일(409/503)

### 3. 컨트롤러
- `POST /api/v5/coupons/issue`

### 4. 측정 + 기록
- `run-coupon-concurrency.sh v5`
- `analysis.md` v5 섹션: 정합성(`≤ total`), 락 거절률, 처리량/지연 — **v4(Redisson)와 비교**(정합성 일치? 성능 차이? 코드 복잡도 차이?), 해석

### Step F 검증
```
- integrity/v5.txt: actual_issued ≤ total_qty (초과 없음, v4와 정합성 일치)
- analysis.md v5 섹션 기록
```
검증 통과 후 `/commit`

---

## Step G: 종합 비교 + analysis 마무리

> 새 구현 없음. Step B~F에서 쌓인 결과를 모아 해석을 완성.

- `results/phase3-3-concurrency/`의 모든 `k6/*.json`·`integrity/*.txt`를 취합
- `analysis.md` 마지막에 **사다리 트레이드오프 표를 실측값으로 완성**. **처리량·지연 모두 outcome별 2컬럼으로 분리**(Blocker):
  | 버전 | actual_issued | 정합성 | 성공 처리량(200/s) | 총 요청 처리량(거절 포함) | 성공 p95/p99 | 한도소진(409) p95/p99 | 락거절(503) p95/p99 | 503 거절률(버전 내) | 비고 |
  - **처리량 분리**: v4/v5는 요청 대부분이 즉시 503(0-cost)이라 총 요청 처리량(iter/s)이 인위적으로 폭증 → 단일 컬럼이면 "v4가 v3보다 100배 빠르다"는 거짓 결론. 성공 발급(200 카운트)과 분리 필수
  - **지연 분리 (Blocker — 처리량과 같은 함정이 헤드라인 지표에서 재발)**: 집계 `http_req_duration` p95/p99는 v4/v5의 0-cost 거절이 ~95%를 먹어 p95≈1ms로 찍힌다 → "분산 락이 v3보다 2000배 저지연"이라는 거짓 결론. status 태그별로 **성공(200)·한도소진(409)·락거절(503)** 지연을 **셋으로** 갈라 기록하고 **"거절"=503-only로 정의**. ⚠️ 409 지연은 버전 의존(v2/v3=직렬화 통과 후 느림 / v4/v5=락 획득 직후 빠름)이라 **버전 간 비교 금지** — 섞으면 "v4가 v2보다 2000배 빨리 거절"(실제론 v2의 409는 거절이 아닌 한도소진)이라는 거짓 헤드라인이 409 축에서 재발. RTT 세금도 성공-only 지연에서만 보인다
- **해석 프레이밍 (reframe — 서사의 축을 바꾼다)**: 단일 쿠폰 임계영역(`coupon_issue` INSERT + `issued` 증가)은 본질적으로 직렬이라, v2~v5의 **성공 발급 처리량은 다 DB 직렬 쓰기 바운드 → 같은 차수**다. 단 완전 동일은 아니고 **v4/v5는 성공 경로마다 Redis 왕복 1회(락 획득)가 critical path에 얹혀 RTT만큼의 작은 세금**이 성공 지연에 더해진다(= 락 비용을 Redis로 옮긴 대가, 분산 락의 핵심 트레이드오프).
- **지연 단일 우승자 금지 (Blocker 해석 — 표만이 아니라 서사를 닫음)**: 분리한 "성공 지연"조차 v3와 v4/v5는 apples-to-apples가 아니다. **v3 성공 지연엔 행락 대기가 포함**(기다렸다 성공)되지만, **v4/v5 성공 지연엔 대기가 없다**(기다릴 요청은 이미 거절로 빠짐). 블로킹은 경합을 *지연*으로 흡수하고 fail-fast는 *거절*로 내보내는, **정책이 다른 두 숫자**다. 따라서 "성공 지연 + 거절률을 묶어 각 정책을 특성화"해야지(v3=타임아웃 임계까지 대기 흡수, 초과 시 503 거절 / v4·v5=저지연·고거절), **지연 단일 우승자를 뽑지 않는다.** 진짜 깨끗한 지연 비교가 필요하면 아래 조건부 변형(v4/v5에 bounded wait → v3와 같은 블로킹 의미)이 그 경로.
- 정직한 서사 축: **"어느 락이 더 빠른가"가 아니라 "직렬화의 비용이 어디서 나오나"** — v2=앱 스레드 park, v3=DB 커넥션 고갈, v4/v5=Redis RTT + 거절.
- **단일-JVM 한계 명시 (Note)**: 핵심 측정은 단일 JVM이라 v4/v5의 본 정당화(다중 인스턴스 정합)는 여기서 실증되지 않는다 — 단일 JVM에선 v2~v5가 다 정합 통과하고 v4/v5는 RTT만큼 오히려 느리다. v4/v5의 우위는 **보강 #1(다중 인스턴스)에서만** 실증되며, 핵심 측정은 "정합성 + 직렬화 비용 위치"까지만 주장한다 (보강 #1은 선택 유지 — 핵심 승격 아님).
- **503 의미 구분 (필수)**: 같은 503이라도 **v3 = DB 대기**(내부적으로 ⓐ풀 타임아웃/ⓑ행락 타임아웃 추가 구분), **v4·v5 = 즉시 락 거절**로 원인이 다르다. 갈라 적어 오독을 막는다
- **거절률 분모 비교 금지 (Caution)**: 503 거절률의 분모(총 iteration)가 버전마다 자릿수로 다르다(v4/v5 폭증·v3 소수). **거절률은 버전 내 정책 관찰치로만 제시하고 버전 간 직접 비교 금지.** 굳이 비교하려면 절대 카운트(성공/거절 건수)를 병기
- **범위 한정 (Note)**: 모든 결론은 **단일 핫키(couponId=1) 가정**에 묶인다. "락 거절률이 높다"는 단일 핫키+즉시 실패의 당연한 귀결이지 분산 락 일반의 성질이 아니다. 다수 쿠폰이면 키 단위 분산으로 양상이 달라짐
- **조건부 후속 측정 (선택)**: v4/v5 거절률이 너무 높아 v3과 깨끗한 지연/처리량 비교가 어려우면 → `tryLock` 대기를 짧은 값(수십~수백 ms)으로 준 변형을 추가 측정해 **"즉시 실패 vs 짧은 대기(=v3과 같은 블로킹 의미)"를 비교축으로 추가**. 1차 결정(즉시 실패)은 유지, 결과 보고서만 진행
- "프로덕션이라면" 참고(재시도/대기, Fallback 등) 한 단락

검증 통과 후 `/commit` → 전체 `/pr`

---

## Phase 3-3 최종 디렉토리 구조
```
scripts/
├── coupon/
│   └── reset-coupon.sql
└── measure/
    └── run-coupon-concurrency.sh   (인자: 버전)

test/
└── load/
    └── coupon-test.js

results/
└── phase3-3-concurrency/
    ├── k6/            (v1~v5.json)
    ├── integrity/     (v1~v5.txt)
    └── analysis.md    (버전별 해석 append + 종합 비교)

src/main/java/com/project/
├── api/coupon/
│   ├── CouponController.java
│   └── dto/
│       ├── CouponIssueRequest.java
│       └── CouponIssueResponse.java
├── service/coupon/
│   ├── CouponIssueServiceV1.java   (No Lock)
│   ├── CouponIssueServiceV2.java   (synchronized, 파사드)
│   ├── CouponIssueServiceV3.java   (FOR UPDATE)
│   ├── CouponIssueServiceV4.java   (Redisson, 파사드)
│   └── CouponIssueServiceV5.java   (커스텀 SET NX, 파사드)
├── domain/coupon/   (기존 — 메서드만 추가)
│   ├── Coupon.java                (issue() 추가)
│   ├── CouponRepository.java      (findByIdForUpdate 추가)
│   └── CouponIssueRepository.java (exists/count 추가)
└── infrastructure/redis/
    ├── RedissonConfig.java        (v4 — Redisson 클라이언트)
    └── RedisLockRepository.java   (v5 — SET NX + Lua, String 전용 template)
```

## 작업 순서
순차 진행. **각 Step 완료 후 정지**하여 직접 검증·측정·기록·커밋.

1. **Step A** 공통 도메인 + DTO + 시드 → 검증 → `/commit`
2. **Step B** v1 + 측정 인프라 + v1 측정(부하 확정) → 기록 → `/commit`
3. **Step C** v2 synchronized → 측정 → 기록 → `/commit`
4. **Step D** v3 FOR UPDATE → 측정 → 기록 → `/commit`
5. **Step E** v4 Redisson → 측정 → 기록 → `/commit`
6. **Step F** v5 커스텀 SET NX → 측정 → 기록 → `/commit`
7. **Step G** 종합 비교 → `/commit` → `/pr`

## Claude Code 범위 밖 (직접 수행)
- 각 Step 검증·측정 직접 실행
- 부하 결과 해석 (정합성 + 버전별 처리량/지연/락 거절률 비교)
- PR을 main에 머지
