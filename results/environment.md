# Measurement Environment

> **Note**: All performance measurements in this project were conducted under the following environment.  
> Refer to this document when interpreting results in `results/` directory.

---

## Machine Specifications
- **OS**: macOS (Darwin) / Apple Silicon
- **CPU**: Apple M2
- **Memory**: 16GB
- **Disk**: SSD

---

## Software Versions
- **Java**: OpenJDK 17.0.9
- **Spring Boot**: 3.5.10
- **Gradle**: 8.x (wrapper)
- **k6**: 1.5.0 (darwin/arm64)

---

## Docker Environment
- **Docker**: 29.1.5
- **MySQL**: 8.0.45
  - Container: `docker-mysql-1`
  - Configuration: Default settings (no custom my.cnf)
  - Port: `3306:3306`
- **Redis**: 7-alpine
  - Container: `docker-redis-1`
  - Configuration: Default settings
  - Port: `6379:6379`

---

## Standard Test Configuration

### k6 Load Testing
- **Default VUs**: 10
- **Default Duration**: 30s
- **Ramp-up Pattern**: Varies by test (see individual test scripts)

### Database
- **Connection Pool**: HikariCP (default settings)
- **Initial Data Volume**: 
  - `product`: 100,000 rows
  - `order_item`: 150,000 rows
  - `user`, `order`, `coupon`: Varies by phase

### Application Runtime
- **Spring Boot Mode**: `./gradlew bootRun`
- **Profile**: `default` (no specific profile)
- **JVM Options**: Default (no custom tuning)

---

## Important Notes

1. **Local Development Environment**: All measurements performed on local machine (not production-like)
2. **Docker Resource Limits**: No explicit CPU/memory limits set for containers
3. **Network Latency**: Minimal (localhost communication only)
4. **Background Processes**: No control over macOS background tasks during measurement
5. **Measurement Variance**: Results may vary ±10% due to system load

---

## Phase 3-2: Ranking Optimization (k6 Configuration)

- **Test Script**: `test/load/ranking-test.js`
- **Stages**: 30s warmup (0→50 VUs), 1min load (50→100 VUs), 30s cooldown (100→0 VUs)
- **Versions Tested**: v1 (DB no index), v2 (DB covering index), v3 (String cache), v4 (Sorted Set)
- **Threshold**: p(95) < 1000ms
- **Result Files**: `results/phase3-2-ranking-optimization/k6/phase3-2-v{1..4}.json`
- **Test Date**: 2026-02-09

---

## Version History
- **2026-02-09**: Add Phase 3-2 ranking k6 test configuration
- **2026-02-09**: Initial environment setup for Phase 3 optimization projects
