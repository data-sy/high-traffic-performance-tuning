package com.project.race;

import com.project.infrastructure.redis.RankingRedisRepository;
import com.project.service.ranking.RankingV3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Race condition test for Redis String caching (v3).
 *
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
@SpringBootTest
class RankingRaceConditionTest {

    @Autowired
    private RankingV3Service v3Service;

    @Autowired
    private RankingRedisRepository rankingRedisRepository;

    @BeforeEach
    void setUp() {
        rankingRedisRepository.clearCache();
    }

    @Test
    void testStringCacheRaceCondition() throws InterruptedException {
        int threadCount = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger dbQueryCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // When: 100 threads call v3Service.getRankings() simultaneously
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();

                    // Check cache before calling service
                    String cachedBefore = rankingRedisRepository.getCachedRanking();

                    // Call service (may hit DB or cache)
                    v3Service.getRankings();

                    // If cache was null before our call, we likely triggered a DB query
                    if (cachedBefore == null) {
                        int count = dbQueryCount.incrementAndGet();
                        System.out.println("[" + Thread.currentThread().getName() + "] "
                                + "Cache was empty, DB query triggered (count: " + count + ") at "
                                + System.currentTimeMillis());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Start all threads simultaneously
        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Then: Multiple threads hit the DB due to race condition
        int actualDbQueries = dbQueryCount.get();
        System.out.println("\n======================================");
        System.out.println("Race Condition Test Results (v3 String Cache)");
        System.out.println("======================================");
        System.out.println("Total threads: " + threadCount);
        System.out.println("Expected DB queries: 1 (if cache was atomic)");
        System.out.println("Actual DB queries: " + actualDbQueries);
        System.out.println("Lost Update occurred: " + (actualDbQueries > 1 ? "YES" : "NO"));
        System.out.println("======================================\n");

        // Race condition: multiple threads see null cache simultaneously
        // and all proceed to query the DB before any of them writes the cache
        assertTrue(actualDbQueries > 1,
                "Expected multiple DB queries due to race condition, but got: " + actualDbQueries);
    }
}
