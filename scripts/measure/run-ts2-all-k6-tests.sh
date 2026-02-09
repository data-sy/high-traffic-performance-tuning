#!/bin/bash

# Phase 3-2 Step E: k6 Load Test Automation
# Runs ranking-test.js for v1~v4 and saves results

# Redis 접속 함수
run_redis() {
    docker exec -i docker-redis-1 redis-cli "$@"
}

# MySQL 접속 함수
run_mysql() {
    docker exec -i docker-mysql-1 mysql -uroot -proot flashdeal "$@"
}

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
K6_SCRIPT="$PROJECT_ROOT/test/load/ranking-test.js"
RESULT_DIR="$PROJECT_ROOT/results/k6"
mkdir -p "$RESULT_DIR"

echo "=========================================="
echo "Phase 3-2: k6 Load Test (v1 ~ v4)"
echo "=========================================="
echo ""

# Prerequisite: k6 installed
if ! command -v k6 &> /dev/null; then
    echo "❌ k6 is not installed."
    echo "  Install: brew install k6"
    exit 1
fi

# Prerequisite: k6 script exists
if [ ! -f "$K6_SCRIPT" ]; then
    echo "❌ k6 script not found: $K6_SCRIPT"
    exit 1
fi

# Prerequisite: Server health
echo "Checking server health..."
if ! curl -sf http://localhost:8080/api/poc/health > /dev/null 2>&1; then
    echo "❌ Server is not running! Start with: ./gradlew bootRun"
    exit 1
fi
echo "✓ Server is healthy"
echo ""

# Run k6 for each version
versions=("v1" "v2" "v3" "v4")
pass_count=0

for version in "${versions[@]}"; do
    echo "=========================================="
    echo "Testing $version..."
    echo "=========================================="

    result_file="$RESULT_DIR/phase3-2-${version}.json"

    # Clear Redis cache before v3/v4 to ensure clean state
    if [ "$version" = "v3" ] || [ "$version" = "v4" ]; then
        echo "Clearing Redis cache..."
        run_redis DEL "cache:ranking:top100" > /dev/null 2>&1
    fi

    # Warm up the endpoint
    echo "Warming up $version endpoint..."
    curl -s "http://localhost:8080/api/${version}/rankings" > /dev/null 2>&1
    sleep 1

    # Run k6
    k6 run -e "VERSION=${version}" \
        --summary-export="$result_file" \
        "$K6_SCRIPT"

    k6_exit=$?

    if [ $k6_exit -eq 0 ] && [ -f "$result_file" ]; then
        echo "✓ $version completed. Results: $result_file"
        pass_count=$((pass_count + 1))
    else
        echo "⚠️  $version test had issues (exit code: $k6_exit)"
    fi

    echo ""

    # Pause between tests to let system settle
    if [ "$version" != "v4" ]; then
        echo "Pausing 5s before next test..."
        sleep 5
    fi
done

# ==========================================
# Summary
# ==========================================
echo "=========================================="
echo "Summary: $pass_count/${#versions[@]} tests completed"
echo "=========================================="
echo ""

# Extract and compare p95 from each result
echo "Performance Comparison (p95 response time):"
echo "---------------------------------------------"

for version in "${versions[@]}"; do
    result_file="$RESULT_DIR/phase3-2-${version}.json"
    if [ -f "$result_file" ]; then
        p95=$(python3 -c "
import json
try:
    with open('$result_file') as f:
        data = json.load(f)
    duration = data.get('metrics', {}).get('http_req_duration', {}).get('values', {})
    p95 = duration.get('p(95)', 0)
    print(f'{p95:.2f}')
except:
    print('N/A')
" 2>/dev/null)
        echo "  $version: p95 = ${p95}ms"
    else
        echo "  $version: (no result file)"
    fi
done

echo ""
echo "Result files:"
for version in "${versions[@]}"; do
    echo "  results/k6/phase3-2-${version}.json"
done
echo ""

if [ $pass_count -eq ${#versions[@]} ]; then
    echo "✅ All k6 tests completed successfully"
else
    echo "⚠️  Some tests failed. Check output above."
fi
