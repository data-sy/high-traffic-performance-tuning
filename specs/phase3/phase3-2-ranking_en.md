# Phase 3-2 — Redis Sorted Set 랭킹 최적화

## Context
- Phase 3-1 (Product Listing Index Optimization) 완료 상태
- OrderItem has 150K rows (50K orders × 평균 3 items)
- Product has 100K rows
- `ddl-auto: validate` is active. Do NOT change this.
- Docker containers (MySQL, Redis) are running via `docker compose up -d`.
- Redis 접근 테스트 완료 (RedisTemplate 정상 동작 확인)

## Goal
Redis Sorted Set을 활용하여 실시간 랭킹 조회를 98% 개선 (200ms → 5ms).  
"결과 캐싱(String)" vs "구조 캐싱(Sorted Set)" 차이를 **race condition 재현**으로 증명.

## Important Rules
- Read `CLAUDE.md` first for project conventions.
- Do NOT modify any Entity classes (User, Product, Order, OrderItem).
- Do NOT change `ddl-auto: validate`.
- MySQL runs in Docker. Access via shell function: `run_mysql() { docker exec -i docker-mysql-1 mysql -uroot -proot flashdeal "$@"; }`. Define this at the top of each shell script. Do NOT use `mysql -h127.0.0.1` directly.
- Redis runs in Docker. Access via shell function: `run_redis() { docker exec -i docker-redis-1 redis-cli "$@"; }`.
- All `.sh` files must have executable permission (`chmod +x`).
- Controller → Service → Repository 호출 순서 준수. No direct repository access from controllers.
- Race condition 재현 테스트는 `@SpringBootTest`로 작성, JUnit 5 사용.

---

## Step A: 기본 인프라 구성 (Redis, DTO, Repository)

### Restrictions
- Do NOT modify Entity classes.
- RedisConfig must use `LettuceConnectionFactory` (already in dependencies).
- All Redis keys must use prefix `ranking:` or `cache:`.

### 1. Redis Configuration — `config/RedisConfig.java`
- Location: `src/main/java/com/project/config/RedisConfig.java`
- Create new file
- Annotations: `@Configuration`, `@EnableRedisRepositories`
- Beans:
  - `RedisConnectionFactory redisConnectionFactory()`: return `new LettuceConnectionFactory()`
  - `RedisTemplate<String, Object> redisTemplate()`:
    - KeySerializer: `StringRedisSerializer`
    - ValueSerializer: `GenericJackson2JsonRedisSerializer`

### 2. Redis Repository — `repository/RankingRedisRepository.java`
- Location: `src/main/java/com/project/repository/RankingRedisRepository.java`
- Create new file
- Annotation: `@Repository`
- Field: `RedisTemplate<String, Object> redisTemplate` (injected)
- Constants:
  - `RANKING_KEY = "ranking:products"`
  - `CACHE_KEY = "cache:ranking:top100"`
- Methods:
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
- Location: `src/main/java/com/project/dto/ranking/RankingResponse.java`
- Create new file (record)
- Fields:
  ```java
  List<RankingItemResponse> rankings
  LocalDateTime queriedAt
  ```

### 4. DTO — `dto/ranking/RankingItemResponse.java`
- Location: `src/main/java/com/project/dto/ranking/RankingItemResponse.java`
- Create new file (record)
- Fields:
  ```java
  int rank
  Long productId
  String productName
  Long salesCount
  ```

### 5. Repository Query — `repository/OrderItemRepository.java`
- Location: `src/main/java/com/project/repository/OrderItemRepository.java`
- Modify existing file
- Add method:
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

### Step A Build Check
- Run `./gradlew compileJava` to verify compilation.
- Do NOT run the application or tests. Human will verify runtime behavior.

### Step A Verification (human will verify)
```bash
# Redis 연결 확인
docker exec -i docker-redis-1 redis-cli PING
→ PONG

# MySQL 쿼리 테스트
run_mysql() { docker exec -i docker-mysql-1 mysql -uroot -proot flashdeal "$@"; }
echo "SELECT COUNT(*) FROM order_items;" | run_mysql
→ 150000
```

### Step A Commit
After verification passes, run `/commit` with staged files.

---

## Step B: DB 최적화 실험 (v1, v2)

### Restrictions
- Do NOT create indexes yet. v1 must run WITHOUT any indexes.
- v2 will use the same code as v1, but run AFTER index creation.
- Controller → Service → Repository 순서 준수.

### 1. Controller — `api/ranking/RankingV1Controller.java`
- Location: `src/main/java/com/project/api/ranking/RankingV1Controller.java`
- Create new file
- Annotations: `@RestController`, `@RequestMapping("/api/v1/rankings")`
- Endpoint: `GET /api/v1/rankings`
  - Return: `RankingResponse`
  - Delegate to `RankingV1Service.getTopRankingsFromDB()`

### 2. Service — `service/ranking/RankingV1Service.java`
- Location: `src/main/java/com/project/service/ranking/RankingV1Service.java`
- Create new file
- Annotation: `@Service`
- Dependencies: `OrderItemRepository`, `ProductRepository` (injected)
- Method: `RankingResponse getTopRankingsFromDB()`
  - Call `orderItemRepository.findTopProductsBySales(100)`
  - For each result (Object[] row):
    - Extract `productId` (Long), `salesCount` (Long)
    - Fetch `Product` from `productRepository.findById(productId)`
    - Build `RankingItemResponse(rank, productId, product.getName(), salesCount)`
  - Return `new RankingResponse(rankings, LocalDateTime.now())`

### 3. Controller — `api/ranking/RankingV2Controller.java`
- Location: `src/main/java/com/project/api/ranking/RankingV2Controller.java`
- Create new file
- Annotations: `@RestController`, `@RequestMapping("/api/v2/rankings")`
- Endpoint: `GET /api/v2/rankings`
  - Delegate to `RankingV2Service.getTopRankingsWithIndex()`

### 4. Service — `service/ranking/RankingV2Service.java`
- Location: `src/main/java/com/project/service/ranking/RankingV2Service.java`
- Create new file
- **Identical logic to v1** (will run AFTER index creation)

### 5. Index Experiment Script — `scripts/index/ranking-experiments.sql`
- Location: `scripts/index/ranking-experiments.sql`
- Create new file
- Content:
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

### Step B Build Check
- Run `./gradlew compileJava` to verify compilation.

### Step B Verification (human will verify)
```bash
# 1. v1 실행 (인덱스 없음)
curl http://localhost:8080/api/v1/rankings
→ 200 OK, rankings array with 100 items
→ 응답시간 측정 (~200ms 예상)

# 2. EXPLAIN 실험 1~3 실행
run_mysql() { docker exec -i docker-mysql-1 mysql -uroot -proot flashdeal "$@"; }
cat scripts/index/ranking-experiments.sql | run_mysql
→ 각 EXPLAIN 결과 캡처 (Using filesort, Using temporary 확인)

# 3. EXPLAIN 결과 저장 (증거 확보)
mkdir -p results/explain

# Experiment 1: No index
echo "SELECT oi.product_id, SUM(oi.quantity) as sales_count
FROM order_items oi
GROUP BY oi.product_id
ORDER BY sales_count DESC
LIMIT 100;" | run_mysql -e "EXPLAIN $(cat -)" > results/explain/ranking-experiment1-no-index.txt

# Experiment 2: Single index (after creating idx_orderitem_product_id)
echo "SELECT oi.product_id, SUM(oi.quantity) as sales_count
FROM order_items oi
GROUP BY oi.product_id
ORDER BY sales_count DESC
LIMIT 100;" | run_mysql -e "EXPLAIN $(cat -)" > results/explain/ranking-experiment2-single-index.txt

# Experiment 3: Covering index (after creating idx_orderitem_product_quantity)
echo "SELECT oi.product_id, SUM(oi.quantity) as sales_count
FROM order_items oi
GROUP BY oi.product_id
ORDER BY sales_count DESC
LIMIT 100;" | run_mysql -e "EXPLAIN $(cat -)" > results/explain/ranking-experiment3-covering-index.txt

# 4. v2 실행 (인덱스 적용 후)
curl http://localhost:8080/api/v2/rankings
→ 200 OK, 응답시간 측정 (~150ms 예상)
```

### Step B Commit
After verification passes, run `/commit` with staged files.

---

## Step C: Redis String 캐싱 실험 (v3) + Race Condition 재현

### Restrictions
- v3 must use Redis String caching with 5-minute TTL.
- Race condition test must use `CountDownLatch` for synchronization.
- Test must be in `test/race/` directory (not `test/java/`).

### 1. Controller — `api/ranking/RankingV3Controller.java`
- Location: `src/main/java/com/project/api/ranking/RankingV3Controller.java`
- Create new file
- Annotations: `@RestController`, `@RequestMapping("/api/v3/rankings")`
- Endpoint: `GET /api/v3/rankings`
  - Delegate to `RankingV3Service.getTopRankingsWithStringCache()`

### 2. Service — `service/ranking/RankingV3Service.java`
- Location: `src/main/java/com/project/service/ranking/RankingV3Service.java`
- Create new file
- Dependencies: `RankingRedisRepository`, `OrderItemRepository`, `ProductRepository`, `ObjectMapper` (injected)
- Method: `RankingResponse getTopRankingsWithStringCache()`
  - 1. Check cache: `String cached = redisRepository.getCachedRanking()`
  - 2. If cached != null: parse JSON and return
  - 3. If null: call `orderItemRepository.findTopProductsBySales(100)`
  - 4. Build `RankingResponse` (same logic as v1)
  - 5. Serialize to JSON: `objectMapper.writeValueAsString(response)`
  - 6. Save to cache: `redisRepository.cacheRanking(json)`
  - 7. Return response

### 3. Race Condition Test — `test/race/RankingRaceConditionTest.java`
- Location: `src/test/java/com/project/race/RankingRaceConditionTest.java`
- Create new file
- Annotations: `@SpringBootTest` (no `@Transactional` — test needs true concurrent behavior without transaction isolation wrapping each thread)
- Dependencies: `RankingV3Service`, `RankingRedisRepository` (injected)
- Test Method: `testStringCacheRaceCondition()`
  - Given:
    - Clear Redis cache (`@BeforeEach`)
  - When:
    - Create **100 threads** with `CountDownLatch(1)` (startLatch) + `CountDownLatch(100)` (doneLatch)
    - **Why 100 threads**: Simulates realistic production burst load (e.g., flash sale start). More threads increase the probability of race window overlap, making the problem reproducible and measurable.
    - Each thread checks cache state before calling service:
      ```java
      // ⚠️ Race Window: 100 threads simultaneously see null cache
      String cachedBefore = rankingRedisRepository.getCachedRanking();
      v3Service.getRankings(); // read → compute(DB) → write(cache)
      if (cachedBefore == null) {
          dbQueryCount.incrementAndGet();
          System.out.println("[" + Thread.currentThread().getName() + "] "
              + "Cache was empty, DB query triggered (count: " + count + ") at "
              + System.currentTimeMillis());
      }
      ```
    - Start all threads simultaneously via `startLatch.countDown()`
    - Wait for completion
  - Then:
    - Count how many threads triggered DB queries
    - **Expected**: 1 (if cache was atomic, only 1 thread should query DB)
    - **Actual**: > 1 (multiple threads see null cache simultaneously → all query DB)
    - Print summary: Total threads, Expected DB queries, Actual DB queries, Lost Update occurred
    - **Assertion**: `assertTrue(actualDbQueries > 1)` (race condition 재현 성공)

  - **v3의 한계 → v4 필요성 근거**:
    - 이 테스트는 String 캐싱의 구조적 한계를 증명한다.
    - read → compute → write가 원자적이지 않아, 동시 요청 시 불필요한 DB 쿼리가 발생한다.
    - 이는 단순한 성능 문제가 아니라 캐시 정합성 문제이며, v4(Sorted Set)의 ZINCRBY 원자적 연산이 이를 해결한다.

  - Add comment in test:
    ```java
    /**
     * Race condition test for Redis String caching (v3).
     * This test demonstrates a data integrity issue, NOT a performance issue.
     *
     * String caching problem:
     * - Caches the "entire result" so full recomputation is needed on update
     * - read → compute → write is NOT atomic
     * - Concurrent updates cause Lost Update (earlier write is overwritten)
     *
     * In contrast, Sorted Set (v4):
     * - ZINCRBY is an atomic operation
     * - "Structure caching" so only incremental updates are needed
     * - Completes within Redis alone, no DB access required
     */
    ```

### Step C Build Check
- Run `./gradlew compileJava compileTestJava` to verify compilation.

### Step C Verification (human will verify)
```bash
# 1. v3 실행 (캐시 미스)
curl http://localhost:8080/api/v3/rankings
→ 200 OK, ~20ms (첫 요청은 200ms, 이후 캐시 히트)

# 2. v3 실행 (캐시 히트)
curl http://localhost:8080/api/v3/rankings
→ 200 OK, ~20ms

# 3. Race Condition 테스트 실행 (v3만)
./gradlew test --tests RankingRaceConditionTest.testStringCacheRaceCondition
→ 콘솔 출력: "Expected DB queries: 1, Actual DB queries: > 1" (race condition 재현)
→ 100개 스레드 중 다수가 동시에 cache miss → DB 쿼리 중복 실행

# 4. 테스트 결과 스크린샷 저장 (증거 확보)
mkdir -p results/race-condition
# Terminal output screenshot showing: Expected DB queries: 1, Actual: 15 (example)
# Save as: results/race-condition/v3-lost-update-screenshot.png
```

**Important:** Save the test output screenshot showing Lost Update. This will be compared with v4's success in Step D.

### Step C Commit
After verification passes, run `/commit` with staged files.

---

## Step D: Sorted Set 최종 솔루션 (v4) + 실시간 갱신

### Restrictions
- v4 must use Sorted Set ONLY (no DB access).
- `OrderService` must call `incrementScore()` AFTER order creation.
- `RankingInitializer` must run at application startup.

### 1. Controller — `api/ranking/RankingV4Controller.java`
- Location: `src/main/java/com/project/api/ranking/RankingV4Controller.java`
- Create new file
- Annotations: `@RestController`, `@RequestMapping("/api/v4/rankings")`
- Endpoint: `GET /api/v4/rankings`
  - Delegate to `RankingV4Service.getTopRankingsFromSortedSet()`

### 2. Service — `service/ranking/RankingV4Service.java`
- Location: `src/main/java/com/project/service/ranking/RankingV4Service.java`
- Create new file
- Dependencies: `RankingRedisRepository`, `ProductRepository` (injected)
- Method: `RankingResponse getTopRankingsFromSortedSet()`
  - Call `redisRepository.getTopN(100)`
  - For each `TypedTuple`:
    - Extract `productId` (parse String to Long)
    - Extract `salesCount` (from score)
    - Fetch `Product` name
    - Build `RankingItemResponse(rank++, productId, productName, salesCount)`
  - Return `new RankingResponse(rankings, LocalDateTime.now())`

### 3. Modify OrderService — `service/order/OrderService.java`
- Location: `src/main/java/com/project/service/order/OrderService.java`
- Modify existing method: `createOrder(CreateOrderRequest request)`
- Add after order creation:
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

### 4. Initializer — `initializer/RankingInitializer.java`
- Location: `src/main/java/com/project/initializer/RankingInitializer.java`
- Create new file
- Annotations: `@Component`
- Implements: `ApplicationRunner`
- Dependencies: `OrderItemRepository`, `RankingRedisRepository` (injected)
- Method: `run(ApplicationArguments args)`
  - Clear existing ranking: `redisTemplate.delete(RANKING_KEY)`
  - Query top 100: `findTopProductsBySales(100)`
  - For each row:
    - `incrementScore(productId, salesCount)`
  - Log: `"✅ Ranking initialized: " + count + " products"`

### 5. Recovery Service (주석 포함) — `service/ranking/RankingRecoveryService.java`
- Location: `src/main/java/com/project/service/ranking/RankingRecoveryService.java`
- Create new file
- Annotation: `@Service`
- Dependencies: `OrderItemRepository`, `RankingRedisRepository`, `RedisTemplate` (injected)
- Method: `rebuildRankingFromDB()`
  - JavaDoc:
    ```java
    /**
     * Redis 장애 시 DB 기반 재구축
     * 
     * 실행 시점:
     * - Health Check 실패 감지 시 (Redis 연결 끊김)
     * - Scheduled Task (1시간마다 정합성 검증)
     * 
     * 정합성 보장 전략:
     * 1. DB는 항상 Source of Truth
     * 2. Redis는 읽기 성능을 위한 캐시 레이어
     * 3. DB 저장 성공 + Redis 갱신 실패 시:
     *    → 일시적 불일치 허용 (최대 1시간 drift)
     *    → 배치 작업으로 주기적 재구축
     * 4. Redis 완전 장애 시:
     *    → v1/v2(DB 직접 조회)로 fallback
     *    → 복구 후 rebuildRankingFromDB() 실행
     */
    ```
  - Logic:
    - Delete existing `ranking:products` key
    - Query all products from DB: `findTopProductsBySales(Integer.MAX_VALUE)`
    - Rebuild Sorted Set with all data
    - Call `removeOutOfTop(100)` to keep only top 100
    - Log: `"✅ Ranking rebuilt from DB: " + count + " products"`

### Step D Build Check
- Run `./gradlew compileJava` to verify compilation.

### Step D Verification (human will verify)
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

# 4. Race Condition 테스트 (v3 vs v4 대조)
./gradlew test --tests RankingRaceConditionTest

# v3 vs v4 비교 결과:
# | 항목            | v3 (String Cache)                    | v4 (Sorted Set)                |
# |-----------------|--------------------------------------|--------------------------------|
# | 동시 요청 처리  | 다수 스레드가 DB 중복 쿼리           | ZINCRBY 원자적 연산으로 완결   |
# | DB 쿼리 횟수    | >1 (예: 15/100 스레드)               | 0 (Redis만으로 조회)           |
# | 캐시 정합성     | Lost Update 발생                     | 항상 일관된 결과               |
→ testStringCacheRaceCondition: "Expected DB queries: 1, Actual: > 1" (v3 — race condition 재현)
→ testSortedSetRaceCondition: Sorted Set은 DB 조회 없이 ZINCRBY로 완결 (v4 성공)

# 5. v4 테스트 결과 스크린샷 저장 (증거 확보)
# Terminal output screenshot showing both test results
# Save as: results/race-condition/v4-atomic-success-screenshot.png
```

**Important:** This screenshot should show both tests side-by-side for comparison in documentation.

### Step D Commit
After verification passes, run `/commit` with staged files.

---

## Step E: 부하 테스트 (k6) + 메모리 관리 검증

### Restrictions
- k6 script must test all 4 versions (v1, v2, v3, v4).
- Memory check script must use `run_redis` shell function.

### 1. k6 Script — `test/load/ranking-test.js`
- Location: `test/load/ranking-test.js`
- Create new file
- Content:
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
  
- **검증 포인트**:
  - v1~v3: 쓰기 발생 시 조회 성능 영향 (DB 경합)
  - v4: 쓰기와 읽기가 독립적 (DB 미접근) → p99 안정성

### 2. Memory Check Script — `scripts/measure/ranking-memory-check.sh`
- Location: `scripts/measure/ranking-memory-check.sh`
- Create new file with `chmod +x`
- Content:
  ```bash
  #!/bin/bash
  
  run_redis() {
      docker exec -i docker-redis-1 redis-cli "$@"
  }
  
  echo "=== Before cleanup ==="
  run_redis ZCARD ranking:products
  
  echo ""
  echo "=== Cleanup is automatic in OrderService ==="
  echo "Creating 10 orders to trigger removeOutOfTop(100)..."
  
  echo ""
  echo "=== After cleanup ==="
  run_redis ZCARD ranking:products
  
  echo ""
  echo "=== Memory usage ==="
  run_redis INFO memory | grep used_memory_human
  ```

### Step E Build Check
- Scripts do not require compilation.
- Verify file permissions: `ls -l scripts/measure/ranking-memory-check.sh` → `-rwxr-xr-x`

### Step E Verification (human will verify)
```bash
# 1. k6 테스트 실행 및 결과 저장 (각 버전별)
mkdir -p results/k6

# v1: DB, no index
k6 run test/load/ranking-test.js --summary-export=results/k6/v1-db-no-index.json
→ p50, p95, p99 기록 (예상: ~200ms)

# v2: DB, with index
k6 run test/load/ranking-test.js --summary-export=results/k6/v2-db-with-index.json
→ p50, p95, p99 기록 (예상: ~150ms)

# v3: String cache
k6 run test/load/ranking-test.js --summary-export=results/k6/v3-string-cache.json
→ p50, p95, p99 기록 (예상: ~20ms, cache hit 시)

# v4: Sorted Set
k6 run test/load/ranking-test.js --summary-export=results/k6/v4-sorted-set.json
→ p50, p95, p99 기록 (예상: ~5ms, 항상 일정)

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
# Measurement Environment

## Hardware
- Machine: [Mac model / Linux distro]
- CPU: [processor info]
- RAM: [memory size]

## Software
- Docker: [version]
- MySQL: [version]
- Redis: [version]
- Java: [version]
- Spring Boot: [version]
- k6: [version]

## Test Date
- [YYYY-MM-DD]
EOF
```

### Step E Commit
After verification passes, run `/commit` with staged files.

---

## Final Directory Structure After Phase 3-2

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
│   ├── main/java/com/project/
│   │   ├── config/
│   │   │   └── RedisConfig.java
│   │   ├── repository/
│   │   │   ├── RankingRedisRepository.java
│   │   │   └── OrderItemRepository.java (modified)
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
│   │   │   └── OrderService.java (modified)
│   │   └── initializer/
│   │       └── RankingInitializer.java
│   └── test/java/com/project/race/
│       └── RankingRaceConditionTest.java
└── docs/
    └── 02-ranking-redis-sortedset.md (human task)
```

**Results Directory Details:**
- `results/explain/`: EXPLAIN results from ranking-experiments.sql (Step B)
- `results/k6/`: k6 load test results for v1~v4 (Step E)
- `results/race-condition/`: Race condition test screenshots (Step C, D)
- `results/environment.md`: Measurement environment info (Step E)

**Note:** All measurement results are committed as evidence in the repository.

## Work Order

Complete steps sequentially. **Pause after each step** for human verification before committing.

1. **Step A**: 기본 인프라 구성 (Redis, DTO, Repository)
   → Pause → Human verifies Redis connection and compilation → `/commit`

2. **Step B**: DB 최적화 실험 (v1, v2 + 인덱스 실험)
   → Pause → Human runs EXPLAIN experiments and measures v1 vs v2 → `/commit`

3. **Step C**: Redis String 캐싱 (v3) + Race Condition 재현
   → Pause → Human runs race condition test and verifies data loss → `/commit`

4. **Step D**: Sorted Set 최종 솔루션 (v4) + 실시간 갱신
   → Pause → Human verifies immediate ranking update and no race condition → `/commit`

5. **Step E**: 부하 테스트 (k6) + 메모리 관리 검증
   → Pause → Human runs k6 and memory check → `/commit`

After all steps committed, human will create PR manually or via `/pr`.

## Outside Claude Code Scope (human tasks)

- Run each verification manually (curl, EXPLAIN, k6, race test)
- Execute `ranking-experiments.sql` and interpret EXPLAIN results
- Execute `k6 run ranking-test.js` and compare v1~v4 metrics (p50, p95, p99)
  - **중요**: 쓰기 부하 병행 시 v4의 p99 안정성 확인
- Run `RankingRaceConditionTest`:
  - v3 (`testStringCacheRaceCondition`): 100개 스레드 중 다수가 동시 cache miss → DB 쿼리 중복 실행 (Expected: 1, Actual: >1)
  - v4 (`testSortedSetRaceCondition`): Sorted Set의 ZINCRBY 원자적 연산으로 DB 조회 없이 완결
- Execute `ranking-memory-check.sh` and verify ZCARD ≤ 100
- Write `docs/02-ranking-redis-sortedset.md` troubleshooting document:
  - **핵심 인사이트 (최우선 기술)**:
    ```
    v3는 '조회된 결과'를 저장하기에 갱신 시 DB를 다시 봐야 하지만,
    v4는 '정렬 구조' 자체를 저장하기에 DB 조회 없이 원자적(Atomic)으로 점수만 올리면 된다.
    
    → "어떻게 저장할까"가 아니라 "어떻게 갱신하고 조회할까" 패턴을 먼저 분석해야 함
    ```
  - "결과 캐싱 vs 구조 캐싱" 비교 표:
    | 항목 | v3 (String 캐싱) | v4 (Sorted Set) |
    |------|------------------|-----------------|
    | 저장 대상 | 조회 결과 전체 (JSON) | 정렬 구조 (score + member) |
    | 갱신 방식 | DB 재조회 → 전체 덮어쓰기 | ZINCRBY로 증분 갱신 |
    | DB 의존성 | 매번 필요 | 불필요 (Redis만으로 완결) |
    | 동시성 | Race Condition 발생 | 원자적 연산 보장 |
    | 메모리 | 고정 크기 | ZREMRANGEBYRANK로 관리 |
  - **Race Condition 시퀀스 다이어그램** (v3의 Lost Update 흐름 시각화):
    ```
    Thread A        Cache        DB           Thread B
       |              |           |              |
       |--read(null)->|           |              |
       |              |<-null-----|              |
       |              |           |--read(null)->|
       |              |           |<----null-----|
       |              |           |              |
       |--------query DB--------->|              |
       |<----100 items------------|              |
       |              |           |              |
       |              |           |--------query DB--------->
       |              |           |<----100 items------------|
       |              |           |              |
       |--write(100)->|           |              |
       |              |           |              |
       |              |           |--write(100)->|  ⚠️ Overwrite!
       |              |           |              |
    
    결과: 200개 주문이 발생했지만 캐시엔 100만 기록됨
    ```
  - ZREMRANGEBYRANK 메모리 최적화 전략
  - v1→v2→v3→v4 진화 과정 및 면접 대비 Q&A
  - **정합성 보장 전략** 설명:
    - DB = Source of Truth
    - Redis = Performance Layer
    - 1시간 배치로 drift 보정
- Merge PR to main
