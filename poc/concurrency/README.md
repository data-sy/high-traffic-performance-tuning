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
