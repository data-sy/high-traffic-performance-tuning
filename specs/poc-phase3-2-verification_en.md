# PoC — Phase 3-2 Tool Verification

## Goal
Verify that all tools and features needed for Phase 3-2 work correctly in the current environment. Commit the passing state as a reference point to distinguish tool issues from code issues during main work.

## Out of Scope
This PoC is NOT for:
- Performance benchmarking (p50, p95, p99 measurements)
- Actual traffic simulation with k6
- Final validation of consistency strategies
- Race condition resolution strategies
→ These are covered in Phase 3-2 main work.

This PoC is ONLY for:
- Confirming tools and environment work as expected
- Verifying basic operations before complex integration

**CRITICAL:** If ANY PoC fails, do NOT proceed to Phase 3-2. Fix environment/tooling first.

## Prerequisites
- Phase 1, 2, 3-1 completed.
- `docker compose up -d` (MySQL, Redis running).
- Product 100K rows, OrderItem 150K rows exist.

## Output Structure
All PoC files go under `poc/` directory, organized by feature. Do NOT mix with main work code (`src/`, `scripts/`, `test/`).

```
poc/
├── redis-connection/
│   ├── RedisConnectionCheckController.java
│   └── README.md
├── redis-sortedset/
│   ├── test-sortedset.sh
│   └── README.md
├── redis-serialization/
│   ├── SerializationTestController.java
│   ├── TestRankingItem.java
│   └── README.md
└── concurrency/
    ├── CountDownLatchTest.java
    └── README.md
```

Each README.md contains: purpose, how to run, expected results.

## PoC Items

### PoC 1: Redis Connection + RedisTemplate Basic Operations
**Purpose:** Verify Spring Boot app can connect to Redis via RedisTemplate and execute basic commands

**Work:**
- Location: `poc/redis-connection/RedisConnectionCheckController.java` (copy for reference)
- Actual code: `src/main/java/com/project/api/poc/RedisConnectionCheckController.java`
- Dependencies: Inject `RedisTemplate<String, Object>` (requires RedisConfig bean)
- 4 endpoints:
  1. `GET /api/poc/redis/ping` → Execute Redis PING via RedisTemplate → `{"status": "PONG"}`
  2. `GET /api/poc/redis/set-get` → SET key "poc:test" value "test-value", then GET → `{"key": "poc:test", "value": "test-value"}`
  3. `GET /api/poc/redis/info` → Execute INFO command → `{"version": "7.x.x", "used_memory": "xxxKB"}`
  4. `GET /api/poc/redis/key-prefix-check` → Verify all keys follow `poc:*` pattern → `{"keysChecked": ["poc:test"], "allValid": true}`

**Key Naming Convention:**
- All Redis keys MUST use a prefix: `poc:*` for PoC, `ranking:*` or `cache:*` for Phase 3-2 main work.
- This endpoint verifies the naming convention is enforced.

**Notes:**
- The actually compiled and running code MUST be created under `src/main/java/com/project/api/poc/`. The `poc/redis-connection/` directory only holds a reference copy and README. **src/ is primary; poc/ copy is optional.**
- RedisConfig bean must exist with LettuceConnectionFactory and RedisTemplate configured.
- If RedisConfig doesn't exist yet, create minimal config:
  ```java
  @Configuration
  public class RedisConfig {
      @Bean
      public RedisConnectionFactory redisConnectionFactory() {
          return new LettuceConnectionFactory();
      }
      
      @Bean
      public RedisTemplate<String, Object> redisTemplate() {
          RedisTemplate<String, Object> template = new RedisTemplate<>();
          template.setConnectionFactory(redisConnectionFactory());
          template.setKeySerializer(new StringRedisSerializer());
          template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
          return template;
      }
  }
  ```
- RedisConnectionCheckController must be within the main application's component scan scope.

**README.md:**
```
# PoC 1: Redis Connection + RedisTemplate Verification

## Run
1. docker compose up -d
2. ./gradlew bootRun
3. curl http://localhost:8080/api/poc/redis/ping
4. curl http://localhost:8080/api/poc/redis/set-get
5. curl http://localhost:8080/api/poc/redis/info
6. curl http://localhost:8080/api/poc/redis/key-prefix-check

## Expected
- /ping → {"status": "PONG"}
- /set-get → {"key": "poc:test", "value": "test-value"}
- /info → {"version": "7.x.x", "used_memory": "xxxKB"}
- /key-prefix-check → {"keysChecked": ["poc:test"], "allValid": true}

## Key Naming Convention
- All keys MUST follow pattern: poc:*, ranking:*, or cache:*
- No keys without prefix allowed

## Cleanup
docker exec -i docker-redis-1 redis-cli DEL poc:test
```

### PoC 2: Redis Sorted Set Core Operations
**Purpose:** Verify Sorted Set commands (ZADD, ZINCRBY, ZREVRANGE, ZCARD, ZREMRANGEBYRANK) work as expected

**Work:**
- Location: `poc/redis-sortedset/test-sortedset.sh`
- Set executable: `chmod +x poc/redis-sortedset/test-sortedset.sh`

**Script:**
```bash
#!/bin/bash
# Note: This script intentionally does NOT use set -e to allow error observation

run_redis() {
    docker exec -i docker-redis-1 redis-cli "$@"
}

KEY="poc:ranking:test"

echo "=== Test 1: ZADD (Initial scores) ==="
run_redis ZADD "$KEY" 100 "product:1"
run_redis ZADD "$KEY" 200 "product:2"
run_redis ZADD "$KEY" 150 "product:3"
run_redis ZADD "$KEY" 80 "product:4"
run_redis ZADD "$KEY" 120 "product:5"
echo "Added 5 items"

echo ""
echo "=== Test 2: ZINCRBY (Increment product:1 by 50) ==="
RESULT=$(run_redis ZINCRBY "$KEY" 50 "product:1")
echo "Score after increment: $RESULT (expected: 150)"

echo ""
echo "=== Test 3: ZREVRANGE (Top 3 with scores) ==="
run_redis ZREVRANGE "$KEY" 0 2 WITHSCORES

echo ""
echo "=== Test 4: ZCARD (Total size) ==="
SIZE=$(run_redis ZCARD "$KEY")
echo "Size: $SIZE (expected: 5)"

echo ""
echo "=== Test 5: ZREMRANGEBYRANK (Remove bottom 2) ==="
run_redis ZREMRANGEBYRANK "$KEY" 0 1
REMAINING=$(run_redis ZCARD "$KEY")
echo "Remaining: $REMAINING (expected: 3)"

echo ""
echo "=== Test 6: ZSCORE (Get specific score) ==="
SCORE=$(run_redis ZSCORE "$KEY" "product:2")
echo "product:2 score: $SCORE (expected: 200)"

echo ""
echo "=== Test 7: DEL (Cleanup) ==="
run_redis DEL "$KEY"
echo "Key removed"

echo ""
echo "=== Test 8: Error handling - ZINCRBY on non-existent key ==="
run_redis ZINCRBY "poc:nonexistent" 10 "item:1"
echo "Auto-created with score 10"

echo ""
echo "=== Cleanup ==="
run_redis DEL "poc:nonexistent"

echo ""
echo "=== PoC 2 complete ==="
```

**README.md:**
```
# PoC 2: Redis Sorted Set Operations Verification

## Run
chmod +x poc/redis-sortedset/test-sortedset.sh
./poc/redis-sortedset/test-sortedset.sh

## Check
- Test 1: ZADD adds 5 items successfully
- Test 2: ZINCRBY increments score (100 → 150)
- Test 3: ZREVRANGE returns top 3 in descending order
- Test 4: ZCARD returns 5
- Test 5: ZREMRANGEBYRANK removes bottom 2, leaves 3
- Test 6: ZSCORE returns correct score
- Test 7: DEL removes key
- Test 8: ZINCRBY on non-existent key auto-creates it

## Key Learning
- Sorted Set maintains order automatically
- ZINCRBY is atomic (no race condition)
- ZREMRANGEBYRANK enables memory management (keep top N)
```

### PoC 3: GenericJackson2JsonRedisSerializer Type Safety
**Purpose:** Verify DTO serialization/deserialization preserves type information (Long doesn't become Integer)

**Work:**
- Location: `poc/redis-serialization/SerializationTestController.java` (copy)
- Actual code: `src/main/java/com/project/api/poc/SerializationTestController.java`
- DTO: `src/main/java/com/project/api/poc/TestRankingItem.java`

**TestRankingItem.java:**
```java
package com.project.api.poc;

public record TestRankingItem(
    Long productId,
    String productName,
    Long salesCount
) {}
```

**SerializationTestController.java:**
```java
package com.project.api.poc;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/poc/redis/serialization")
public class SerializationTestController {
    
    private final RedisTemplate<String, Object> redisTemplate;
    private static final String TEST_KEY = "poc:serialization:test";
    
    public SerializationTestController(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    
    @PostMapping("/save-dto")
    public Map<String, Object> saveDto() {
        TestRankingItem item = new TestRankingItem(
            100000001L,  // Large Long to test type preservation
            "Test Product",
            999999L
        );
        
        redisTemplate.opsForValue().set(TEST_KEY, item);
        
        return Map.of(
            "status", "saved",
            "original", item,
            "originalClass", item.getClass().getName()
        );
    }
    
    @GetMapping("/load-dto")
    public Map<String, Object> loadDto() {
        Object loaded = redisTemplate.opsForValue().get(TEST_KEY);
        
        Map<String, Object> result = new HashMap<>();
        result.put("loaded", loaded);
        result.put("loadedClass", loaded != null ? loaded.getClass().getName() : "null");
        
        if (loaded instanceof TestRankingItem item) {
            result.put("typeMatch", true);
            result.put("productIdClass", item.productId().getClass().getName());
            result.put("salesCountClass", item.salesCount().getClass().getName());
            result.put("productIdValue", item.productId());
            result.put("salesCountValue", item.salesCount());
        } else {
            result.put("typeMatch", false);
        }
        
        return result;
    }
    
    @DeleteMapping("/cleanup")
    public Map<String, String> cleanup() {
        redisTemplate.delete(TEST_KEY);
        return Map.of("status", "cleaned");
    }
}
```

**README.md:**
```
# PoC 3: Redis Serialization Type Safety Verification

## Run
1. ./gradlew bootRun
2. curl -X POST http://localhost:8080/api/poc/redis/serialization/save-dto
3. curl http://localhost:8080/api/poc/redis/serialization/load-dto
4. curl -X DELETE http://localhost:8080/api/poc/redis/serialization/cleanup

## Check
- save-dto response shows originalClass: TestRankingItem
- load-dto response shows:
  - typeMatch: true
  - productIdClass: java.lang.Long (NOT Integer)
  - salesCountClass: java.lang.Long
  - Values match original (100000001, 999999)

## Key Learning
- GenericJackson2JsonRedisSerializer preserves type metadata
- Prevents Long → Integer conversion issues
- Safe for DTO round-trip serialization
```

### PoC 4: CountDownLatch Concurrency Test Pattern
**Purpose:** Verify CountDownLatch can synchronize multiple threads for race condition testing

**Work:**
- Location: `poc/concurrency/CountDownLatchTest.java` (copy)
- Actual code: `src/test/java/com/project/poc/CountDownLatchTest.java`

**CountDownLatchTest.java:**
```java
package com.project.poc;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CountDownLatchTest {
    
    @Test
    void testConcurrentIncrement_WithoutSynchronization_LostUpdate() throws InterruptedException {
        // Given: Shared counter (not thread-safe)
        class UnsafeCounter {
            private int count = 0;
            public void increment() {
                int temp = count;
                // Simulate some processing delay
                try { Thread.sleep(1); } catch (InterruptedException e) {}
                count = temp + 1;
            }
            public int getCount() { return count; }
        }
        
        UnsafeCounter counter = new UnsafeCounter();
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        // When: 10 threads increment simultaneously
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for signal
                    counter.increment();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown(); // Start all threads simultaneously
        doneLatch.await(5, TimeUnit.SECONDS); // Wait for completion
        executor.shutdown();
        
        // Then: Lost update occurs (count < 10)
        int finalCount = counter.getCount();
        System.out.println("Expected: 10, Actual: " + finalCount);
        assertTrue(finalCount < 10, "Lost update should occur without synchronization");
    }
    
    @Test
    void testConcurrentIncrement_WithAtomicInteger_NoLostUpdate() throws InterruptedException {
        // Given: Thread-safe counter
        AtomicInteger counter = new AtomicInteger(0);
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        // When: 10 threads increment simultaneously
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    counter.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Then: No lost update (count == 10)
        int finalCount = counter.get();
        System.out.println("Expected: 10, Actual: " + finalCount);
        assertEquals(10, finalCount, "AtomicInteger should prevent lost updates");
    }
}
```

**README.md:**
```
# PoC 4: CountDownLatch Concurrency Test Verification

## Run
./gradlew test --tests CountDownLatchTest

## Check
- testConcurrentIncrement_WithoutSynchronization_LostUpdate:
  - Expected: 10, Actual: < 10 (e.g., 3, 5, 7)
  - Demonstrates race condition (Lost Update)
- testConcurrentIncrement_WithAtomicInteger_NoLostUpdate:
  - Expected: 10, Actual: 10
  - Demonstrates atomic operation

## Key Learning
- CountDownLatch enables simultaneous thread execution
- Pattern for reproducing race conditions in tests
- Atomic operations prevent lost updates
```

## .gitignore Additions
```
# PoC test results (if any output files generated)
poc/redis-sortedset/test-results/
```

## General Notes
- Do NOT use `set -e` in any shell scripts. PoC scripts intentionally allow errors as part of verification.
- Redis credentials (default, no password) are local docker-compose defaults. Do NOT parameterize Redis connection in this phase.
- CountDownLatch test may occasionally pass even without synchronization due to timing. Run multiple times to observe lost updates.

## Work Order
Complete all PoCs sequentially, then commit once.

1. Create `poc/` directory structure
2. PoC 1: RedisConnectionCheckController + RedisConfig (if needed) + README
3. PoC 2: test-sortedset.sh + README
4. PoC 3: SerializationTestController + TestRankingItem + README
5. PoC 4: CountDownLatchTest + README
6. Update `.gitignore` (if needed)
7. Pause for user review after all files created

User manually runs each PoC to verify, then commits.

## Done Criteria

### Claude Code Completion (File Existence)
- [ ] `src/main/java/com/project/api/poc/RedisConnectionCheckController.java` exists
- [ ] `src/main/java/com/project/config/RedisConfig.java` exists (or equivalent config)
- [ ] `poc/redis-connection/RedisConnectionCheckController.java` (copy) + `README.md` exist
- [ ] `poc/redis-sortedset/test-sortedset.sh` + `README.md` exist
- [ ] `src/main/java/com/project/api/poc/SerializationTestController.java` exists
- [ ] `src/main/java/com/project/api/poc/TestRankingItem.java` exists
- [ ] `poc/redis-serialization/SerializationTestController.java` (copy) + `README.md` exist
- [ ] `src/test/java/com/project/poc/CountDownLatchTest.java` exists
- [ ] `poc/concurrency/CountDownLatchTest.java` (copy) + `README.md` exist
- [ ] All .sh files have executable permission
- [ ] RedisTemplate bean is configured in RedisConfig

### Human Verification (Run Confirmation)
- [ ] `docker exec -i docker-redis-1 redis-cli PING` → `PONG`
- [ ] `GET /api/poc/redis/ping` → `{"status": "PONG"}`
- [ ] `GET /api/poc/redis/set-get` → String SET/GET works
- [ ] `GET /api/poc/redis/info` → Redis version displayed
- [ ] `GET /api/poc/redis/key-prefix-check` → All keys follow `poc:*` pattern
- [ ] `./poc/redis-sortedset/test-sortedset.sh` → All 8 tests pass
- [ ] ZREVRANGE returns items in descending score order
- [ ] ZINCRBY increments score correctly (100 → 150)
- [ ] ZREMRANGEBYRANK removes bottom items
- [ ] `POST /api/poc/redis/serialization/save-dto` → DTO saved
- [ ] `GET /api/poc/redis/serialization/load-dto` → typeMatch: true, productIdClass: java.lang.Long
- [ ] `./gradlew test --tests CountDownLatchTest` → Both tests pass
- [ ] First test shows lost update (finalCount < 10)
- [ ] Second test shows no lost update (finalCount == 10)
