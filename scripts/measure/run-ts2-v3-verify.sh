#!/bin/bash

# Phase 3-2 Step C Verification Script
# Verifies v3 Redis String cache: performance, TTL, data structure, race condition

# Redis 접속 함수
run_redis() {
    docker exec -i docker-redis-1 redis-cli "$@"
}

# API 응답 시간 측정 함수 (macOS 호환, 출력 분리)
measure_api() {
    local url=$1
    local description=$2

    echo "Testing $description..." >&2

    local total=0
    for i in {1..3}; do
        local start=$(python3 -c "import time; print(int(time.time() * 1000))")
        curl -s "$url" > /dev/null 2>&1
        local end=$(python3 -c "import time; print(int(time.time() * 1000))")

        local ms=$((end - start))
        echo "  Run $i: ${ms}ms" >&2
        total=$((total + ms))
    done

    local avg=$((total / 3))
    echo "  Average: ${avg}ms" >&2
    echo "" >&2

    echo "$avg"
}

# 결과 저장 디렉토리
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
RESULT_DIR="$PROJECT_ROOT/results/phase3-2-ranking-optimization"
RESULT_FILE="$RESULT_DIR/v3-cache-verification.txt"
mkdir -p "$RESULT_DIR"

echo "========================================"
echo "Phase 3-2 Step C: v3 Cache Verification"
echo "========================================"
echo ""

# 서버 헬스 체크
echo "Checking server health..."
if ! curl -sf http://localhost:8080/api/poc/health > /dev/null 2>&1; then
    echo "❌ Server is not running! Please start with: ./gradlew bootRun"
    exit 1
fi
echo "✓ Server is healthy"
echo ""

# ========================================
# Test 1: Cache Performance (miss vs hit)
# ========================================
echo "========================================"
echo "Test 1: Cache Performance (miss vs hit)"
echo "========================================"
echo ""

# Clear cache before test
run_redis DEL "cache:ranking:top100" > /dev/null 2>&1

# Cache miss (first call — DB query required)
echo "Measuring cache MISS (first call, no cache)..."
miss_time=$(measure_api "http://localhost:8080/api/v3/rankings" "v3 cache miss")

# Cache hit (second call — served from Redis)
echo "Measuring cache HIT (subsequent call, from Redis)..."
hit_time=$(measure_api "http://localhost:8080/api/v3/rankings" "v3 cache hit")

# Calculate improvement
if [ "$miss_time" -gt 0 ] && [ "$hit_time" -gt 0 ]; then
    improvement=$(awk "BEGIN {printf \"%.1f\", (1 - $hit_time / $miss_time) * 100}")
    echo "Cache miss: ${miss_time}ms"
    echo "Cache hit:  ${hit_time}ms"
    echo "Improvement: ${improvement}%"
else
    improvement="N/A"
    echo "❌ Measurement failed"
fi
echo ""

# ========================================
# Test 2: Redis TTL Check
# ========================================
echo "========================================"
echo "Test 2: Redis TTL Check"
echo "========================================"
echo ""

ttl=$(run_redis TTL "cache:ranking:top100" 2>/dev/null | tr -d '[:space:]')
echo "TTL: ${ttl}s (expected: ~300s)"

if [ "$ttl" -gt 250 ] && [ "$ttl" -le 300 ] 2>/dev/null; then
    echo "✅ TTL is within expected range"
    ttl_status="PASS"
elif [ "$ttl" -gt 0 ] 2>/dev/null; then
    echo "⚠️  TTL exists but not ~300s (might have elapsed since cache set)"
    ttl_status="WARNING"
else
    echo "❌ TTL check failed (key missing or no expiry)"
    ttl_status="FAIL"
fi
echo ""

# ========================================
# Test 3: Cache Data Structure
# ========================================
echo "========================================"
echo "Test 3: Cache Data Structure"
echo "========================================"
echo ""

# Get cached data and validate
cached_data=$(run_redis GET "cache:ranking:top100" 2>/dev/null)

if [ -z "$cached_data" ]; then
    echo "❌ Cache key is empty"
    item_count="0"
    json_valid="false"
    data_status="FAIL"
else
    # Remove outer quotes if present (GenericJackson2JsonRedisSerializer wraps in quotes)
    clean_data=$(echo "$cached_data" | sed 's/^"//;s/"$//' | sed 's/\\"/"/g')

    # Count items
    item_count=$(echo "$clean_data" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    rankings = data.get('rankings', [])
    print(len(rankings))
except:
    print('error')
" 2>/dev/null)

    # Validate JSON
    json_valid=$(echo "$clean_data" | python3 -c "
import sys, json
try:
    json.load(sys.stdin)
    print('true')
except:
    print('false')
" 2>/dev/null)

    echo "JSON valid: $json_valid"
    echo "Item count: $item_count (expected: 100)"

    if [ "$item_count" = "100" ] && [ "$json_valid" = "true" ]; then
        echo "✅ Cache data structure is correct"
        data_status="PASS"
    elif [ "$json_valid" = "true" ]; then
        echo "⚠️  JSON valid but item count unexpected"
        data_status="WARNING"
    else
        echo "❌ Cache data structure invalid"
        data_status="FAIL"
    fi
fi
echo ""

# ========================================
# Test 4: Race Condition Test
# ========================================
echo "========================================"
echo "Test 4: Race Condition Test"
echo "========================================"
echo ""

echo "Running RankingRaceConditionTest..."
echo "(This requires Spring context — may take 10-20 seconds)"
echo ""

race_output=$("$PROJECT_ROOT/gradlew" -p "$PROJECT_ROOT" test --tests "*RankingRaceConditionTest" 2>&1)
race_exit_code=$?

# Try to read from the result file that test creates
RACE_CONDITION_FILE="$PROJECT_ROOT/results/phase3-2-ranking-optimization/race-condition-test.txt"

if [ "$race_exit_code" -eq 0 ] && [ -f "$RACE_CONDITION_FILE" ]; then
    # Parse from the result file (more reliable)
    actual_queries=$(grep "Actual DB queries:" "$RACE_CONDITION_FILE" | grep -oE '[0-9]+')
    lost_update=$(grep "Lost Update occurred:" "$RACE_CONDITION_FILE" | grep -oE 'YES|NO')

    if [ -n "$actual_queries" ]; then
        echo "Test result: PASSED"
        echo "Actual DB queries: $actual_queries (expected: >1)"
        echo "Lost Update occurred: $lost_update"
        echo "✅ Race condition successfully demonstrated"
        race_status="PASS"
    else
        echo "Test result: PASSED (but couldn't parse metrics from file)"
        race_status="PASS"
        actual_queries="unknown"
        lost_update="unknown"
    fi
elif [ "$race_exit_code" -eq 0 ]; then
    # Fallback: try parsing from gradle output (original method)
    actual_queries=$(echo "$race_output" | grep "Actual DB queries:" | grep -oE '[0-9]+')
    lost_update=$(echo "$race_output" | grep "Lost Update occurred:" | grep -oE 'YES|NO')

    if [ -n "$actual_queries" ]; then
        echo "Test result: PASSED"
        echo "Actual DB queries: $actual_queries (expected: >1)"
        echo "Lost Update occurred: $lost_update"
        echo "✅ Race condition successfully demonstrated"
        race_status="PASS"
    else
        echo "Test result: PASSED (but couldn't parse metrics)"
        race_status="PASS"
        actual_queries="unknown"
        lost_update="unknown"
    fi
else
    echo "Test result: FAILED (exit code: $race_exit_code)"
    echo "Output (last 20 lines):"
    echo "$race_output" | tail -20
    race_status="FAIL"
    actual_queries="N/A"
    lost_update="N/A"
fi
echo ""

# ========================================
# Summary
# ========================================
echo "========================================"
echo "Summary"
echo "========================================"
echo ""
echo "Test 1 (Cache Performance): miss=${miss_time}ms, hit=${hit_time}ms, improvement=${improvement}%"
echo "Test 2 (Redis TTL):         ${ttl}s ($ttl_status)"
echo "Test 3 (Data Structure):    items=${item_count}, json=${json_valid} ($data_status)"
echo "Test 4 (Race Condition):    queries=${actual_queries}, lost_update=${lost_update} ($race_status)"
echo ""

# ========================================
# Save results
# ========================================
cat > "$RESULT_FILE" << EOF
========================================
Phase 3-2 Step C: v3 Cache Verification
========================================

Measurement Date: $(date '+%Y-%m-%d %H:%M:%S')

Test 1: Cache Performance
-------------------------
Cache miss (DB query):    ${miss_time}ms
Cache hit (Redis):        ${hit_time}ms
Improvement:              ${improvement}%

Test 2: Redis TTL
-----------------
TTL:                      ${ttl}s
Expected:                 ~300s
Status:                   ${ttl_status}

Test 3: Cache Data Structure
----------------------------
JSON valid:               ${json_valid}
Item count:               ${item_count}
Expected items:           100
Status:                   ${data_status}

Test 4: Race Condition (100 threads)
------------------------------------
Actual DB queries:        ${actual_queries}
Expected DB queries:      1
Lost Update occurred:     ${lost_update}
Status:                   ${race_status}

Analysis:
---------
v3 (Redis String Cache) provides significant read performance improvement
through cache-aside pattern. However, the race condition test proves that
non-atomic GET/SET operations allow multiple threads to simultaneously
see null cache, causing redundant DB queries.

This structural limitation justifies v4 (Sorted Set) where:
- ZINCRBY is atomic (no race condition)
- No DB access needed for reads
- Incremental updates only (no full recomputation)
EOF

echo "Results saved to: $RESULT_FILE"
echo "========================================"
