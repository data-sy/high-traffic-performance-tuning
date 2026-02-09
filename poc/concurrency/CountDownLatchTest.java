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
