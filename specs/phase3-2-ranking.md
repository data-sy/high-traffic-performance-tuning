# Phase 3-2 — Redis Sorted Set 랭킹 최적화

## 컨텍스트 (Context)
- Phase 3-1 (상품 목록 인덱스 최적화) 완료 상태
- OrderItem 데이터 15만 행 (5만 건의 주문 × 평균 3개 아이템)
- Product 데이터 10만 행
- `ddl-auto: validate` 활성화 상태. 수정 금지
- Docker 컨테이너 (MySQL, Redis)가 `docker compose up -d`를 통해 실행 중
- Redis 접근 테스트 완료 (RedisTemplate 정상 동작 확인)

## 목표 (Goal)
Redis Sorted Set을 활용하여 실시간 랭킹 조회를 98% 개선 (200ms → 5ms).  
"결과 캐싱(String)" vs "구조 캐싱(Sorted Set)" 차이를 **경쟁 상태(race condition) 재현**으로 증명.

## 중요 규칙 (Important Rules)
- 프로젝트 컨벤션을 위해 `CLAUDE.md`를 먼저 읽을 것
- 엔티티 클래스(User, Product, Order, OrderItem)를 수정하지 말 것
- `ddl-auto: validate`를 변경하지 말 것
- MySQL은 Docker에서 실행됨. 다음 쉘 함수를 통해 접근: `run_mysql() { docker exec -i docker-mysql-1 mysql -uroot -proot flashdeal "$@"; }`. 각 쉘 스크립트 상단에 이를 정의할 것. `mysql -h127.0.0.1`을 직접 사용하지 말 것
- Redis는 Docker에서 실행됨. 다음 쉘 함수를 통해 접근: `run_redis() { docker exec -i docker-redis-1 redis-cli "$@"; }`
- 모든 `.sh` 파일은 실행 권한(`chmod +x`)을 가져야 함
- Controller → Service → Repository 호출 순서를 준수할 것. 컨트롤러에서 리포지토리에 직접 접근 금지
- 경쟁 상태 재현 테스트는 `@SpringBootTest`로 작성하며, JUnit 5를 사용함

---

## Step A: 기본 인프라 구성 (Redis, DTO, Repository)

### 제한 사항 (Restrictions)
- 엔티티 클래스를 수정하지 말 것
- RedisConfig는 `LettuceConnectionFactory`를 사용해야 함 (의존성에 이미 포함됨)
- 모든 Redis 키는 `ranking:` 또는 `cache:` 접두사를 사용해야 함

### 1. Redis 설정 — `config/RedisConfig.java`
- 위치: `src/main/java/com/freshmarket/config/RedisConfig.java`
- 새 파일 생성
- 어노테이션: `@Configuration`, `@EnableRedisRepositories`
- 빈(Beans):
  - `RedisConnectionFactory redisConnectionFactory()`: `new LettuceConnectionFactory()` 반환
  - `RedisTemplate<String, Object> redisTemplate()`:
    - KeySerializer: `StringRedisSerializer`
    - ValueSerializer: `GenericJackson2JsonRedisSerializer`

### 2. Redis 리포지토리 — `repository/RankingRedisRepository.java`
- 위치: `src/main/java/com/freshmarket/repository/RankingRedisRepository.java`
- 새 파일 생성
- 어노테이션: `@Repository`
- 필드: `RedisTemplate<String, Object> redisTemplate` (주입)
- 상수:
  - `RANKING_KEY = "ranking:products"`
  - `CACHE_KEY = "cache:ranking:top100"`
- 메서드:
  ```java
  // Sorted Set 연산
  void incrementScore(Long productId, double delta)
  Set<ZSetOperations.TypedTuple<Object>> getTopN(int n)
  Double getScore(Long productId)
  void removeOutOfTop(int topN)
  
  // String 캐싱 연산 (v3용)
  void cacheRanking(String data)
  String getCachedRanking()
  void clearCache()

```

### 3. DTO — `dto/ranking/RankingResponse.java`

* 위치: `src/main/java/com/freshmarket/dto/ranking/RankingResponse.java`
* 새 파일 생성 (record)
* 필드:
```java
List<RankingItemResponse> rankings
LocalDateTime queriedAt

```



### 4. DTO — `dto/ranking/RankingItemResponse.java`

* 위치: `src/main/java/com/freshmarket/dto/ranking/RankingItemResponse.java`
* 새 파일 생성 (record)
* 필드:
```java
int rank
Long productId
String productName
Long salesCount

```



### 5. Repository 쿼리 — `repository/OrderItemRepository.java`

* 위치: `src/main/java/com/freshmarket/repository/OrderItemRepository.java`
* 기존 파일 수정
* 메서드 추가:
```java
@Query(value = """
    SELECT oi.product_id, SUM(oi.quantity) as sales_count
    FROM order_items oi
    GROUP BY oi.product_id
    ORDER BY sales_count DESC
    LIMIT :limit
    """, nativeQuery = true)
List<Object[]> findTopProductsBySales(@Param("limit") int limit);

```



### Step A 빌드 체크

* `./gradlew compileJava`를 실행하여 컴파일을 확인
* 애플리케이션이나 테스트를 실행하지 말 것. 런타임 동작은 사람이 직접 확인 예정

### Step A 검증 (작업자가 확인)

```bash
# Redis 연결 확인
docker exec -i docker-redis-1 redis-cli PING
→ PONG

# MySQL 쿼리 테스트
run_mysql() { docker exec -i docker-mysql-1 mysql -uroot -proot flashdeal "$@"; }
echo "SELECT COUNT(*) FROM order_items;" | run_mysql
→ 150000

```

### Step A 커밋

검증이 통과되면 변경된 파일들을 스테이징하고 `/commit`을 수행

---

## Step B: DB 최적화 실험 (v1, v2)

### 제한 사항 (Restrictions)

* 아직 인덱스를 생성하지 말 것. v1은 인덱스가 없는 상태에서 실행해야 함
* v2는 v1과 동일한 코드를 사용하되, 인덱스 생성 후에 실행함
* Controller → Service → Repository 순서를 준수할 것

### 1. 컨트롤러 — `api/ranking/RankingV1Controller.java`

* 위치: `src/main/java/com/freshmarket/api/ranking/RankingV1Controller.java`
* 새 파일 생성
* 어노테이션: `@RestController`, `@RequestMapping("/api/v1/rankings")`
* 엔드포인트: `GET /api/v1/rankings`
* 반환: `RankingResponse`
* `RankingV1Service.getTopRankingsFromDB()`에 위임



### 2. 서비스 — `service/ranking/RankingV1Service.java`

* 위치: `src/main/java/com/freshmarket/service/ranking/RankingV1Service.java`
* 새 파일 생성
* 어노테이션: `@Service`
* 의존성: `OrderItemRepository`, `ProductRepository` (주입)
* 메서드: `RankingResponse getTopRankingsFromDB()`
* `orderItemRepository.findTopProductsBySales(100)` 호출
* 각 결과(Object[] row)에 대해:
* `productId` (Long), `salesCount` (Long) 추출
* `productRepository.findById(productId)`를 통해 `Product` 조회
* `RankingItemResponse(rank, productId, product.getName(), salesCount)` 생성


* `new RankingResponse(rankings, LocalDateTime.now())` 반환



### 3. 컨트롤러 — `api/ranking/RankingV2Controller.java`

* 위치: `src/main/java/com/freshmarket/api/ranking/RankingV2Controller.java`
* 새 파일 생성
* 어노테이션: `@RestController`, `@RequestMapping("/api/v2/rankings")`
* 엔드포인트: `GET /api/v2/rankings`
* `RankingV2Service.getTopRankingsWithIndex()`에 위임



### 4. 서비스 — `service/ranking/RankingV2Service.java`

* 위치: `src/main/java/com/freshmarket/service/ranking/RankingV2Service.java`
* 새 파일 생성
* **v1과 동일한 로직** (인덱스 생성 후에 실행될 예정)

### 5. 인덱스 실험 스크립트 — `scripts/index/ranking-experiments.sql`

* 위치: `scripts/index/ranking-experiments.sql`
* 새 파일 생성
* 내용:
```sql
-- 실험 1: 인덱스 없음 (기본 상태)
EXPLAIN SELECT oi.product_id, SUM(oi.quantity) as sales_count
FROM order_items oi
GROUP BY oi.product_id
ORDER BY sales_count DESC
LIMIT 100;

-- 실험 2: product_id 단일 인덱스
CREATE INDEX idx_orderitem_product_id ON order_items(product_id);

EXPLAIN SELECT oi.product_id, SUM(oi.quantity) as sales_count
FROM order_items oi
GROUP BY oi.product_id
ORDER BY sales_count DESC
LIMIT 100;

-- 실험 3: 커버링 인덱스 (product_id, quantity)
CREATE INDEX idx_orderitem_product_quantity ON order_items(product_id, quantity);

EXPLAIN SELECT oi.product_id, SUM(oi.quantity) as sales_count
FROM order_items oi
GROUP BY oi.product_id
ORDER BY sales_count DESC
LIMIT 100;

-- 정리 (실험 종료 후 실행)
-- DROP INDEX idx_orderitem_product_id ON order_items;
-- DROP INDEX idx_orderitem_product_quantity ON order_items;

```



### Step B 빌드 체크

* `./gradlew compileJava`를 실행하여 컴파일을 확인

### Step B 검증 (작업자가 확인)

```bash
# 1. v1 실행 (인덱스 없음)
curl http://localhost:8080/api/v1/rankings
→ 200 OK, 100개의 아이템이 포함된 rankings 배열
→ 응답시간 측정 (~200ms 예상)

# 2. EXPLAIN 실험 1~3 실행
run_mysql() { docker exec -i docker-mysql-1 mysql -uroot -proot flashdeal "$@"; }
cat scripts/index/ranking-experiments.sql | run_mysql
→ 각 EXPLAIN 결과 캡처 (Using filesort, Using temporary 확인)

# 3. EXPLAIN 결과 저장 (증거 확보)
mkdir -p results/explain

# 실험 1: 인덱스 없음
echo "SELECT oi.product_id, SUM(oi.quantity) as sales_count
FROM order_items oi
GROUP BY oi.product_id
ORDER BY sales_count DESC
LIMIT 100;" | run_mysql -e "EXPLAIN $(cat -)" > results/explain/ranking-experiment1-no-index.txt

# 실험 2: 단일 인덱스 (idx_orderitem_product_id 생성 후)
echo "SELECT oi.product_id, SUM(oi.quantity) as sales_count
FROM order_items oi
GROUP BY oi.product_id
ORDER BY sales_count DESC
LIMIT 100;" | run_mysql -e "EXPLAIN $(cat -)" > results/explain/ranking-experiment2-single-index.txt

# 실험 3: 커버링 인덱스 (idx_orderitem_product_quantity 생성 후)
echo "SELECT oi.product_id, SUM(oi.quantity) as sales_count
FROM order_items oi
GROUP BY oi.product_id
ORDER BY sales_count DESC
LIMIT 100;" | run_mysql -e "EXPLAIN $(cat -)" > results/explain/ranking-experiment3-covering-index.txt

# 4. v2 실행 (인덱스 적용 후)
curl http://localhost:8080/api/v2/rankings
→ 200 OK, 응답시간 측정 (~150ms 예상)

```

### Step B 커밋

검증이 통과되면 변경된 파일들을 스테이징하고 `/commit`을 수행

---

## Step C: Redis String 캐싱 실험 (v3) + 경쟁 상태(Race Condition) 재현

### 제한 사항 (Restrictions)

* v3는 5분 TTL이 적용된 Redis String 캐싱을 사용해야 함
* 경쟁 상태 테스트는 동기화를 위해 `CountDownLatch`를 사용해야 함
* 테스트는 `test/race/` 디렉토리에 위치해야 함 (`test/java/`가 아님)

### 1. 컨트롤러 — `api/ranking/RankingV3Controller.java`

* 위치: `src/main/java/com/freshmarket/api/ranking/RankingV3Controller.java`
* 새 파일 생성
* 어노테이션: `@RestController`, `@RequestMapping("/api/v3/rankings")`
* 엔드포인트: `GET /api/v3/rankings`
* `RankingV3Service.getTopRankingsWithStringCache()`에 위임



### 2. 서비스 — `service/ranking/RankingV3Service.java`

* 위치: `src/main/java/com/freshmarket/service/ranking/RankingV3Service.java`
* 새 파일 생성
* 의존성: `RankingRedisRepository`, `OrderItemRepository`, `ProductRepository`, `ObjectMapper` (주입)
* 메서드: `RankingResponse getTopRankingsWithStringCache()`
* 1. 캐시 확인: `String cached = redisRepository.getCachedRanking()`


* 2. 만약 캐시가 존재하면: JSON을 파싱하여 반환


* 3. 만약 없으면: `orderItemRepository.findTopProductsBySales(100)` 호출


* 4. `RankingResponse` 생성 (v1과 동일한 로직)


* 5. JSON으로 직렬화: `objectMapper.writeValueAsString(response)`


* 6. 캐시에 저장: `redisRepository.cacheRanking(json)`


* 7. 응답 반환





### 3. 경쟁 상태 테스트 — `test/race/RankingRaceConditionTest.java`

* 위치: `src/test/java/com/freshmarket/race/RankingRaceConditionTest.java`
* 새 파일 생성
* 어노테이션: `@SpringBootTest`, `@Transactional`
* 의존성: `RankingRedisRepository`, `OrderItemRepository`, `ProductRepository`, `ObjectMapper` (주입)
* 테스트 메서드: `testStringCacheRaceCondition()`
* Given (준비):
* Redis 캐시 비우기
* 1개의 상품에 100개의 주문 아이템 생성 (총 수량 = 100)


* When (실행):
* `CountDownLatch(2)`를 사용하여 2개의 스레드 생성
* 각 스레드는 **비원자적 연산**을 수행:
```java
// ⚠️ 경쟁 윈도우(Race Window):
// read(cache) → compute(DB) → write(cache)
// 이 구간이 원자적이지 않아 갱신 손실(Lost Update) 발생

String cached = redisRepository.getCachedRanking(); // T1, T2 동시에 읽음
Thread.sleep(50); // DB 조회 시뮬레이션 (경쟁 상태 유도)
List<Object[]> results = orderItemRepository.findTopProductsBySales(100);
String json = toJson(results);

// 스레드 이름 로그로 경쟁 윈도우 시각화
System.out.println(Thread.currentThread().getName() + " writing cache at " + System.currentTimeMillis());
redisRepository.cacheRanking(json); // T1, T2 순차적 쓰기 → 덮어쓰기 발생

```


* 두 스레드를 동시에 시작
* 완료될 때까지 대기


* Then (검증):
* 최종 캐시 값을 읽음
* `salesCount`를 파싱
* **기대값(Expected)**: 100 (실제 주문량)
* **실제값(Actual)**: 100 이하 (마지막 쓰기가 이전의 쓰기를 덮어씀)
* 출력: `"Expected: 100, Actual: " + salesCount`
* **단언(Assertion)**: `assertThat(salesCount).isLessThan(100)` (경쟁 상태 재현 성공)


* 테스트에 다음 주석 추가:
```java
/**
 * 이 테스트는 성능 테스트가 아니라 **정합성 붕괴 재현 실험**이다.
 * * String 캐싱의 문제:
 * - "결과 전체를 캐싱"하므로 갱신 시 전체 재계산 필요
 * - read → compute → write 구간이 원자적이지 않음
 * - 동시 갱신 시 Lost Update 발생 (먼저 쓴 값이 사라짐)
 * * 반면 Sorted Set(v4)은:
 * - ZINCRBY가 원자적 연산
 * - "구조 캐싱"이므로 증분 갱신만 수행
 * - DB 접근 없이 Redis만으로 완결
 */

```





### Step C 빌드 체크

* `./gradlew compileJava compileTestJava`를 실행하여 컴파일을 확인

### Step C 검증 (작업자가 확인)

```bash
# 1. v3 실행 (캐시 미스)
curl http://localhost:8080/api/v3/rankings
→ 200 OK, ~20ms (첫 요청은 200ms, 이후 캐시 히트)

# 2. v3 실행 (캐시 히트)
curl http://localhost:8080/api/v3/rankings
→ 200 OK, ~20ms

# 3. 경쟁 상태 테스트 실행 (v3만)
./gradlew test --tests RankingRaceConditionTest.testStringCacheRaceCondition
→ 콘솔 출력: "Expected: 100, Actual: < 100" (경쟁 상태 재현)

# 4. 테스트 결과 스크린샷 저장 (증거 확보)
mkdir -p results/race-condition
# 갱신 손실(Lost Update)을 보여주는 터미널 출력 스크린샷 
# 예: Expected: 100, Actual: 85
# 파일명: results/race-condition/v3-lost-update-screenshot.png

```

**중요:** 갱신 손실을 보여주는 테스트 출력 스크린샷을 저장하십시오. 이는 Step D에서 v4의 성공 결과와 비교하는 용도로 사용됩니다.

### Step C 커밋

검증이 통과되면 변경된 파일들을 스테이징하고 `/commit`을 수행

---

## Step D: Sorted Set 최종 솔루션 (v4) + 실시간 갱신

### 제한 사항 (Restrictions)

* v4는 Sorted Set만 사용해야 함 (DB 접근 없음)
* `OrderService`는 주문 생성 직후 `incrementScore()`를 호출해야 함
* `RankingInitializer`는 애플리케이션 시작 시 실행되어야 함

### 1. 컨트롤러 — `api/ranking/RankingV4Controller.java`

* 위치: `src/main/java/com/freshmarket/api/ranking/RankingV4Controller.java`
* 새 파일 생성
* 어노테이션: `@RestController`, `@RequestMapping("/api/v4/rankings")`
* 엔드포인트: `GET /api/v4/rankings`
* `RankingV4Service.getTopRankingsFromSortedSet()`에 위임



### 2. 서비스 — `service/ranking/RankingV4Service.java`

* 위치: `src/main/java/com/freshmarket/service/ranking/RankingV4Service.java`
* 새 파일 생성
* 의존성: `RankingRedisRepository`, `ProductRepository` (주입)
* 메서드: `RankingResponse getTopRankingsFromSortedSet()`
* `redisRepository.getTopN(100)` 호출
* 각 `TypedTuple`에 대해:
* `productId` 추출 (String을 Long으로 파싱)
* `salesCount` 추출 (score로부터)
* `Product` 이름 조회
* `RankingItemResponse(rank++, productId, productName, salesCount)` 생성


* `new RankingResponse(rankings, LocalDateTime.now())` 반환



### 3. OrderService 수정 — `service/order/OrderService.java`

* 위치: `src/main/java/com/freshmarket/service/order/OrderService.java`
* 기존 메서드 수정: `createOrder(CreateOrderRequest request)`
* 주문 생성 로직 뒤에 추가:
```java
// 각 상품별 판매량 Redis 반영
for (OrderItem item : order.getOrderItems()) {
    rankingRedisRepository.incrementScore(item.getProductId(), item.getQuantity());
}

// TOP 100 유지 (메모리 관리)
rankingRedisRepository.removeOutOfTop(100);

// 정합성 전략:
// - DB 저장 성공 + Redis 갱신 실패 시: 배치로 복구 (허용 가능한 일시적 불일치)
// - Redis 장애 시: Health Check가 감지 → rebuildRankingFromDB() 호출
// - 1시간마다 Scheduled Task로 DB와 비교하여 drift 보정

```



### 4. 초기화 객체(Initializer) — `initializer/RankingInitializer.java`

* 위치: `src/main/java/com/freshmarket/initializer/RankingInitializer.java`
* 새 파일 생성
* 어노테이션: `@Component`
* 구현: `ApplicationRunner` 인터페이스
* 의존성: `OrderItemRepository`, `RankingRedisRepository` (주입)
* 메서드: `run(ApplicationArguments args)`
* 기존 랭킹 삭제: `redisTemplate.delete(RANKING_KEY)`
* 상위 100개 조회: `findTopProductsBySales(100)`
* 각 행에 대해:
* `incrementScore(productId, salesCount)`


* 로그: `"✅ Ranking initialized: " + count + " products"`



### 5. 복구 서비스 (주석 포함) — `service/ranking/RankingRecoveryService.java`

* 위치: `src/main/java/com/freshmarket/service/ranking/RankingRecoveryService.java`
* 새 파일 생성
* 어노테이션: `@Service`
* 의존성: `OrderItemRepository`, `RankingRedisRepository`, `RedisTemplate` (주입)
* 메서드: `rebuildRankingFromDB()`
* JavaDoc 내용:
```java
/**
 * Redis 장애 시 DB 기반 재구축
 * * 실행 시점:
 * - Health Check 실패 감지 시 (Redis 연결 끊김)
 * - Scheduled Task (1시간마다 정합성 검증)
 * * 정합성 보장 전략:
 * 1. DB는 항상 신뢰의 원천(Source of Truth)
 * 2. Redis는 읽기 성능을 위한 캐시 레이어
 * 3. DB 저장 성공 + Redis 갱신 실패 시:
 * → 일시적 불일치 허용 (최대 1시간의 편차(drift))
 * → 배치 작업으로 주기적 재구축
 * 4. Redis 완전 장애 시:
 * → v1/v2(DB 직접 조회)로 대체(fallback)
 * → 복구 후 rebuildRankingFromDB() 실행
 */

```


* 로직:
* 기존 `ranking:products` 키 삭제
* DB에서 모든 상품 조회: `findTopProductsBySales(Integer.MAX_VALUE)`
* 전체 데이터를 사용하여 Sorted Set 재구축
* `removeOutOfTop(100)`을 호출하여 상위 100개만 유지
* 로그: `"✅ Ranking rebuilt from DB: " + count + " products"`





### Step D 빌드 체크

* `./gradlew compileJava`를 실행하여 컴파일을 확인

### Step D 검증 (작업자가 확인)

```bash
# 1. 앱 재시작 후 초기화 확인
./gradlew bootRun
→ 콘솔: "✅ Ranking initialized: 100 products"

# 2. v4 실행
curl http://localhost:8080/api/v4/rankings
→ 200 OK, ~5ms

# 3. 주문 생성 후 즉시 반영 확인
curl -X POST http://localhost:8080/api/orders -d '{"userId":1, "items":[{"productId":1, "quantity":5}]}'
run_redis() { docker exec -i docker-redis-1 redis-cli "$@"; }
run_redis ZSCORE ranking:products "1"
→ 증가된 점수 확인

# 4. 경쟁 상태 테스트 (v3 vs v4 대조)
./gradlew test --tests RankingRaceConditionTest
→ testStringCacheRaceCondition: "Expected: 100, Actual: < 100" (v3 실패)
→ testSortedSetRaceCondition: "Expected: 100, Actual: 100" (v4 성공)

# 5. v4 테스트 결과 스크린샷 저장 (증거 확보)
# 두 테스트 결과를 나란히 보여주는 터미널 출력 스크린샷
# 파일명: results/race-condition/v4-atomic-success-screenshot.png

```

**중요:** 이 스크린샷은 나중에 문서화를 위해 두 테스트 결과를 나란히 비교해서 보여주어야 합니다.

### Step D 커밋

검증이 통과되면 변경된 파일들을 스테이징하고 `/commit`을 수행

---

## Step E: 부하 테스트 (k6) + 메모리 관리 검증

### 제한 사항 (Restrictions)

* k6 스크립트는 4개 버전(v1, v2, v3, v4) 모두를 테스트해야 함
* 메모리 체크 스크립트는 `run_redis` 쉘 함수를 사용해야 함

### 1. k6 스크립트 — `test/load/ranking-test.js`

* 위치: `test/load/ranking-test.js`
* 새 파일 생성
* 내용:
```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 50 },  // 워밍업
    { duration: '1m', target: 100 },  // 부하 증가
    { duration: '30s', target: 0 },   // 종료
  ],
};

export default function () {
  const versions = ['v1', 'v2', 'v3', 'v4'];
  const version = versions[Math.floor(Math.random() * versions.length)];

  // 80% 읽기, 20% 쓰기 비율
  if (Math.random() < 0.8) {
    // 읽기: 랭킹 조회
    const res = http.get(`http://localhost:8080/api/${version}/rankings`);

    check(res, {
      'status is 200': (r) => r.status === 200,
      'response time < 500ms': (r) => r.timings.duration < 500,
    });
  } else {
    // 쓰기: 주문 생성 (실시간 랭킹 갱신 트리거)
    const payload = JSON.stringify({
      userId: Math.floor(Math.random() * 1000) + 1,
      items: [
        {
          productId: Math.floor(Math.random() * 100000) + 1,
          quantity: Math.floor(Math.random() * 5) + 1,
        },
      ],
    });

    const res = http.post('http://localhost:8080/api/orders', payload, {
      headers: { 'Content-Type': 'application/json' },
    });

    check(res, {
      'order created': (r) => r.status === 201,
    });
  }

  sleep(1);
}

```


* **검증 포인트**:
* v1~v3: 쓰기 발생 시 조회 성능에 영향 (DB 경합)
* v4: 쓰기와 읽기가 독립적 (DB 접근 없음) → p99 안정성 확보



### 2. 메모리 체크 스크립트 — `scripts/measure/ranking-memory-check.sh`

* 위치: `scripts/measure/ranking-memory-check.sh`
* `chmod +x`를 사용하여 새 파일 생성
* 내용:
```bash
#!/bin/bash

run_redis() {
    docker exec -i docker-redis-1 redis-cli "$@"
}

echo "=== 정리 전 (Before cleanup) ==="
run_redis ZCARD ranking:products

echo ""
echo "=== OrderService에서 자동 정리가 수행됨 ==="
echo "removeOutOfTop(100)을 트리거하기 위해 10개의 주문 생성 중..."

echo ""
echo "=== 정리 후 (After cleanup) ==="
run_redis ZCARD ranking:products

echo ""
echo "=== 메모리 사용량 (Memory usage) ==="
run_redis INFO memory | grep used_memory_human

```



### Step E 빌드 체크

* 스크립트는 컴파일이 필요 없음
* 파일 권한 확인: `ls -l scripts/measure/ranking-memory-check.sh` → `-rwxr-xr-x`

### Step E 검증 (작업자가 확인)

```bash
# 1. k6 테스트 실행 및 결과 저장 (각 버전별)
mkdir -p results/k6

# v1: DB, 인덱스 없음
k6 run test/load/ranking-test.js --summary-export=results/k6/v1-db-no-index.json
→ p50, p95, p99 기록 (예상: ~200ms)

# v2: DB, 인덱스 있음
k6 run test/load/ranking-test.js --summary-export=results/k6/v2-db-with-index.json
→ p50, p95, p99 기록 (예상: ~150ms)

# v3: String 캐시
k6 run test/load/ranking-test.js --summary-export=results/k6/v3-string-cache.json
→ p50, p95, p99 기록 (예상: 캐시 히트 시 ~20ms)

# v4: Sorted Set
k6 run test/load/ranking-test.js --summary-export=results/k6/v4-sorted-set.json
→ p50, p95, p99 기록 (예상: 항상 일정하게 ~5ms)

# 2. 결과 비교 (증거 확보)
python3 -c "
import json
for version in ['v1-db-no-index', 'v2-db-with-index', 'v3-string-cache', 'v4-sorted-set']:
    data = json.load(open(f'results/k6/{version}.json'))
    d = data['metrics']['http_req_duration']
    print(f'{version}: p95={round(d[\"p(95)\"],2)}ms  p99={round(d[\"p(99)\"],2)}ms')
"

# 3. 메모리 체크 스크립트 실행
./scripts/measure/ranking-memory-check.sh
→ ZCARD 결과가 100 이하인지 확인

# 4. environment.md 작성
cat > results/environment.md << 'EOF'
# 측정 환경 (Measurement Environment)

## 하드웨어 (Hardware)
- 기종: [Mac 모델 / Linux 배포판]
- CPU: [프로세서 정보]
- RAM: [메모리 크기]

## 소프트웨어 (Software)
- Docker: [버전]
- MySQL: [버전]
- Redis: [버전]
- Java: [버전]
- Spring Boot: [버전]
- k6: [버전]

## 테스트 날짜 (Test Date)
- [YYYY-MM-DD]
EOF

```

### Step E 커밋

검증이 통과되면 변경된 파일들을 스테이징하고 `/commit`을 수행

---

## Phase 3-2 완료 후 최종 디렉토리 구조

```
high-traffic-performance-tuning/
├── scripts/
│   ├── index/
│   │   └── ranking-experiments.sql
│   └── measure/
│       └── ranking-memory-check.sh
├── test/
│   ├── load/
│   │   └── ranking-test.js
│   └── race/
│       └── RankingRaceConditionTest.java
├── results/
│   ├── explain/
│   │   ├── ranking-experiment1-no-index.txt
│   │   ├── ranking-experiment2-single-index.txt
│   │   └── ranking-experiment3-covering-index.txt
│   ├── k6/
│   │   ├── v1-db-no-index.json
│   │   ├── v2-db-with-index.json
│   │   ├── v3-string-cache.json
│   │   └── v4-sorted-set.json
│   ├── race-condition/
│   │   ├── v3-lost-update-screenshot.png
│   │   └── v4-atomic-success-screenshot.png
│   └── environment.md
├── src/
│   ├── main/java/com/freshmarket/
│   │   ├── config/
│   │   │   └── RedisConfig.java
│   │   ├── repository/
│   │   │   ├── RankingRedisRepository.java
│   │   │   └── OrderItemRepository.java (수정됨)
│   │   ├── dto/ranking/
│   │   │   ├── RankingResponse.java
│   │   │   └── RankingItemResponse.java
│   │   ├── api/ranking/
│   │   │   ├── RankingV1Controller.java
│   │   │   ├── RankingV2Controller.java
│   │   │   ├── RankingV3Controller.java
│   │   │   └── RankingV4Controller.java
│   │   ├── service/ranking/
│   │   │   ├── RankingV1Service.java
│   │   │   ├── RankingV2Service.java
│   │   │   ├── RankingV3Service.java
│   │   │   ├── RankingV4Service.java
│   │   │   └── RankingRecoveryService.java
│   │   ├── service/order/
│   │   │   └── OrderService.java (수정됨)
│   │   └── initializer/
│   │       └── RankingInitializer.java
│   └── test/java/com/freshmarket/race/
│       └── RankingRaceConditionTest.java
└── docs/
    └── 02-ranking-redis-sortedset.md (작업자 수동 작성)

```

**결과 디렉토리 상세:**

* `results/explain/`: ranking-experiments.sql의 EXPLAIN 결과 (Step B)
* `results/k6/`: v1~v4의 k6 부하 테스트 결과 (Step E)
* `results/race-condition/`: 경쟁 상태 테스트 스크린샷 (Step C, D)
* `results/environment.md`: 측정 환경 정보 (Step E)

**참고:** 모든 측정 결과는 저장소에 증거로 커밋됩니다.

## 작업 순서 (Work Order)

단계를 순차적으로 완료하십시오. **각 단계 후에 작업자 검증을 위해 잠시 멈추고**, 커밋을 진행하십시오.

1. **Step A**: 기본 인프라 구성 (Redis, DTO, Repository)
→ 중지 → 작업자가 Redis 연결 및 컴파일 확인 → `/commit`
2. **Step B**: DB 최적화 실험 (v1, v2 + 인덱스 실험)
→ 중지 → 작업자가 EXPLAIN 실험 실행 및 v1 vs v2 측정 → `/commit`
3. **Step C**: Redis String 캐싱 (v3) + 경쟁 상태 재현
→ 중지 → 작업자가 경쟁 상태 테스트 실행 및 데이터 유실 확인 → `/commit`
4. **Step D**: Sorted Set 최종 솔루션 (v4) + 실시간 갱신
→ 중지 → 작업자가 실시간 랭킹 반영 및 경쟁 상태 해결 확인 → `/commit`
5. **Step E**: 부하 테스트 (k6) + 메모리 관리 검증
→ 중지 → 작업자가 k6 및 메모리 체크 실행 → `/commit`

모든 단계가 커밋된 후, 작업자가 직접 또는 `/pr`을 통해 PR을 생성합니다.

## Claude Code 범위를 벗어난 작업 (작업자 수행 과제)

* 각 검증 과정을 수동으로 실행 (curl, EXPLAIN, k6, 경쟁 상태 테스트)
* `ranking-experiments.sql`을 실행하고 EXPLAIN 결과 해석
* `k6 run ranking-test.js`를 실행하고 v1~v4 지표(p50, p95, p99) 비교
* **중요**: 쓰기 부하가 병행될 때 v4의 p99 안정성 확인


* `RankingRaceConditionTest`를 두 번 실행:
* v3: 갱신 손실 발생 확인 (Expected: 100, Actual: 100 이하)
* v4: 원자적 연산으로 정합성 유지 (Expected: 100, Actual: 100)


* `ranking-memory-check.sh`를 실행하고 ZCARD ≤ 100 확인
* `docs/02-ranking-redis-sortedset.md` 문제 해결 문서 작성:
* **핵심 인사이트 (최우선 기술)**:
```
v3는 '조회된 결과'를 저장하기에 갱신 시 DB를 다시 봐야 하지만,
v4는 '정렬 구조' 자체를 저장하기에 DB 조회 없이 원자적(Atomic)으로 점수만 올리면 된다.

→ "어떻게 저장할까"가 아니라 "어떻게 갱신하고 조회할까" 패턴을 먼저 분석해야 함

```


* "결과 캐싱 vs 구조 캐싱" 비교 표:
| 항목 | v3 (String 캐싱) | v4 (Sorted Set) |
|------|------------------|-----------------|
| 저장 대상 | 조회 결과 전체 (JSON) | 정렬 구조 (score + member) |
| 갱신 방식 | DB 재조회 → 전체 덮어쓰기 | ZINCRBY로 증분 갱신 |
| DB 의존성 | 매번 필요 | 불필요 (Redis만으로 완결) |
| 동시성 | 경쟁 상태 발생 | 원자적 연산 보장 |
| 메모리 | 고정 크기 | ZREMRANGEBYRANK로 관리 |
* **경쟁 상태 시퀀스 다이어그램** (v3의 갱신 손실 흐름 시각화):
```
Thread A        캐시(Cache)     DB           Thread B
   |              |           |              |
   |--read(null)->|           |              |
   |              |<-null-----|              |
   |              |           |--read(null)->|
   |              |           |<----null-----|
   |              |           |              |
   |--------DB 쿼리 호출------->|              |
   |<----100개 아이템----------|              |
   |              |           |              |
   |              |           |--------DB 쿼리 호출------->
   |              |           |<----100개 아이템----------|
   |              |           |              |
   |--write(100)->|           |              |
   |              |           |              |
   |              |           |--write(100)->|  ⚠️ 덮어쓰기 발생!
   |              |           |              |

결과: 200개 주문이 발생했지만 캐시엔 100만 기록됨

```


* ZREMRANGEBYRANK 메모리 최적화 전략
* v1→v2→v3→v4 진화 과정 및 면접 대비 Q&A
* **정합성 보장 전략** 설명:
* DB = 신뢰의 원천(Source of Truth)
* Redis = 성능 계층(Performance Layer)
* 1시간 주기 배치로 편차(drift) 보정




* main 브랜치로 PR 머지
