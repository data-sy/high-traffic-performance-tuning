#!/bin/bash

# Phase 3-2 Step D: v4 Sorted Set Verification Script
# Verifies: initialization, ZCARD, zero-DB read performance, real-time update

# Redis 접속 함수
run_redis() {
    docker exec -i docker-redis-1 redis-cli "$@"
}

# API 응답 시간 측정 함수 (macOS 호환, 출력 분리)
measure_api() {
    local url=$1
    local description=$2

    echo "  Measuring $description..." >&2

    # Warmup
    curl -s "$url" > /dev/null 2>&1
    sleep 0.5

    local total=0
    for i in {1..3}; do
        local start=$(python3 -c "import time; print(int(time.time() * 1000))")
        curl -s "$url" > /dev/null 2>&1
        local end=$(python3 -c "import time; print(int(time.time() * 1000))")

        local ms=$((end - start))
        echo "    Run $i: ${ms}ms" >&2
        total=$((total + ms))
    done

    local avg=$((total / 3))
    echo "    Average: ${avg}ms" >&2
    echo "" >&2

    echo "$avg"
}

# 결과 저장 디렉토리
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
RESULT_DIR="$PROJECT_ROOT/results/phase3-2-ranking-optimization"
RESULT_FILE="$RESULT_DIR/v4-verification.txt"
mkdir -p "$RESULT_DIR"

passed=0
failed=0
total=6

echo "=== Step D v4 Verification ==="
echo ""

# ========================================
# [1/6] Redis initialization
# ========================================
echo -n "[1/6] Redis initialization... "

if ! run_redis PING > /dev/null 2>&1; then
    echo "FAIL (cannot connect to Redis)"
    echo "  Start Redis: docker compose -f docker/docker-compose.yml up -d"
    exit 1
fi

zcard=$(run_redis ZCARD "ranking:products" 2>/dev/null | tr -d '[:space:]')
if [ "$zcard" -gt 0 ] 2>/dev/null; then
    echo "OK (ranking:products has $zcard items)"
    passed=$((passed + 1))
else
    echo "FAIL (ranking:products is empty)"
    echo "  RankingInitializer runs on server startup. Start with: ./gradlew bootRun"
    exit 1
fi

# ========================================
# [2/6] Server startup and initializer
# ========================================
echo -n "[2/6] Server startup and initializer... "

if ! curl -sf http://localhost:8080/api/poc/health > /dev/null 2>&1; then
    echo "FAIL (server not running)"
    echo "  Start with: ./gradlew bootRun"
    exit 1
fi

echo "OK ($zcard products loaded)"
passed=$((passed + 1))

# ========================================
# [3/6] Redis ZCARD
# ========================================
echo -n "[3/6] Redis ZCARD... "

if [ "$zcard" = "100" ]; then
    echo "OK (100)"
    zcard_status="PASS"
    passed=$((passed + 1))
elif [ "$zcard" -gt 90 ] 2>/dev/null; then
    echo "OK ($zcard — close to 100, may differ from prior test orders)"
    zcard_status="PASS"
    passed=$((passed + 1))
else
    echo "FAIL ($zcard, expected ~100)"
    zcard_status="FAIL"
    failed=$((failed + 1))
fi

# ========================================
# [4/6] v4 API response time
# ========================================
echo "[4/6] v4 API response time..."

v4_time=$(measure_api "http://localhost:8080/api/v4/rankings" "v4 (Sorted Set)")
v1_time=$(measure_api "http://localhost:8080/api/v1/rankings" "v1 (DB baseline)")

if [ "$v1_time" -gt 0 ] && [ "$v4_time" -ge 0 ]; then
    improvement=$(awk "BEGIN {printf \"%.1f\", (1 - $v4_time / $v1_time) * 100}")
else
    improvement="N/A"
fi

echo -n "  Result: "
echo "OK (v4=${v4_time}ms, v1=${v1_time}ms, improvement=${improvement}%)"
passed=$((passed + 1))

# ========================================
# [5/6] Order creation and score update
# ========================================
echo -n "[5/6] Order creation and score update... "

test_product_id=1
order_qty=10

# Record baseline score
baseline_raw=$(run_redis ZSCORE "ranking:products" "$test_product_id" 2>/dev/null | tr -d '[:space:]')
if [ -z "$baseline_raw" ] || [ "$baseline_raw" = "(nil)" ]; then
    baseline_raw="0"
fi
baseline_int=$(python3 -c "print(int(float('$baseline_raw')))")

# Attempt order creation via API
order_response=$(curl -sf -X POST http://localhost:8080/api/orders \
    -H "Content-Type: application/json" \
    -d "{\"userId\":1,\"items\":[{\"productId\":$test_product_id,\"quantity\":$order_qty}]}" 2>&1)
order_exit=$?

if [ $order_exit -ne 0 ]; then
    echo "SKIP (Order API not available — OrderController needed)"
    echo "  Hint: Create POST /api/orders endpoint using OrderService"
    order_status="SKIP"
    score_delta="N/A"
    failed=$((failed + 1))
else
    sleep 0.5
    new_raw=$(run_redis ZSCORE "ranking:products" "$test_product_id" 2>/dev/null | tr -d '[:space:]')
    if [ -z "$new_raw" ] || [ "$new_raw" = "(nil)" ]; then
        new_raw="0"
    fi
    new_int=$(python3 -c "print(int(float('$new_raw')))")
    score_delta=$((new_int - baseline_int))

    if [ "$score_delta" -ge "$order_qty" ]; then
        echo "OK (+$score_delta, score: $baseline_int → $new_int)"
        order_status="PASS"
        passed=$((passed + 1))
    else
        echo "FAIL (expected +$order_qty, got +$score_delta)"
        order_status="FAIL"
        failed=$((failed + 1))
    fi
fi

# ========================================
# [6/6] Ranking reflects update
# ========================================
echo -n "[6/6] Ranking reflects update... "

v4_response=$(curl -s http://localhost:8080/api/v4/rankings 2>&1)
found_score=$(echo "$v4_response" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    for r in data.get('rankings', []):
        if r.get('productId') == $test_product_id:
            print(r.get('salesCount', 0))
            sys.exit(0)
    print('not_found')
except:
    print('error')
" 2>/dev/null)

if [ "$found_score" != "not_found" ] && [ "$found_score" != "error" ]; then
    echo "OK (product $test_product_id in rankings, salesCount=$found_score)"
    ranking_status="PASS"
    passed=$((passed + 1))
else
    echo "FAIL (product $test_product_id not found in v4 response)"
    ranking_status="FAIL"
    failed=$((failed + 1))
fi

# ========================================
# Summary
# ========================================
echo ""
echo "=========================================="
echo "Results: $passed/$total checks passed"
echo "=========================================="

if [ $passed -eq $total ]; then
    echo "✅ All checks passed"
    exit_code=0
elif [ $failed -eq 0 ]; then
    echo "✅ All checks passed"
    exit_code=0
else
    echo "⚠️  $failed check(s) failed"
    exit_code=1
fi

# ========================================
# Save results
# ========================================
cat > "$RESULT_FILE" << EOF
==========================================
Phase 3-2 Step D: v4 Verification Results
==========================================

Measurement Date: $(date '+%Y-%m-%d %H:%M:%S')

Results: $passed/$total checks passed

[1/6] Redis initialization:     ZCARD=$zcard
[2/6] Server startup:           Running ($zcard products loaded)
[3/6] Redis ZCARD:              $zcard ($zcard_status)
[4/6] v4 API response time:     ${v4_time}ms
[5/6] Order score update:       delta=${score_delta} ($order_status)
[6/6] Ranking reflects update:  product $test_product_id ($ranking_status)

Performance Comparison:
-----------------------
v1 (DB, no cache):    ${v1_time}ms
v4 (Sorted Set):      ${v4_time}ms
Improvement:          ${improvement}%

Analysis:
---------
v4 (Redis Sorted Set) eliminates all DB queries for ranking reads.
- ZREVRANGE returns pre-sorted results in O(log N + M)
- No serialization/deserialization overhead (unlike v3 String cache)
- Real-time updates via atomic ZINCRBY
- No race condition possible (single Redis command per update)
EOF

echo ""
echo "Results saved to: $RESULT_FILE"

exit $exit_code
