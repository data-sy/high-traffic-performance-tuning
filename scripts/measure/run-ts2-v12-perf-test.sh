#!/bin/bash

# Phase 3-2 Performance Comparison Script
# Measures v1 (no index) vs v2 (with index) response times

# MySQL 접속 함수
run_mysql() {
    docker exec -i docker-mysql-1 mysql -uroot -proot flashdeal "$@"
}

# API 응답 시간 측정 함수 (macOS 호환, 출력 분리)
measure_api() {
    local version=$1
    local description=$2

    echo "Testing $version ($description)..." >&2

    # 첫 호출 (JVM warmup)
    curl -s http://localhost:8080/api/$version/rankings > /dev/null 2>&1
    sleep 1

    local total=0
    for i in {1..3}; do
        # Python으로 밀리초 측정
        local start=$(python3 -c "import time; print(int(time.time() * 1000))")
        curl -s http://localhost:8080/api/$version/rankings > /dev/null 2>&1
        local end=$(python3 -c "import time; print(int(time.time() * 1000))")

        local ms=$((end - start))
        echo "  Run $i: ${ms}ms" >&2
        total=$((total + ms))
    done

    local avg=$((total / 3))
    echo "  Average: ${avg}ms" >&2
    echo "" >&2

    # 최종 값만 stdout으로 반환
    echo "$avg"
}

# 결과 저장 디렉토리 생성
RESULT_DIR="results/phase3-2-ranking-optimization"
mkdir -p "$RESULT_DIR"

# 결과 파일 경로
RESULT_FILE="$RESULT_DIR/performance-comparison.txt"

echo "========================================"
echo "Phase 3-2 Performance Comparison"
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

# 1. 인덱스 제거 (깨끗한 상태)
echo "Step 1: Removing all indexes..."
run_mysql -e "DROP INDEX idx_orderitem_product_id ON order_item;" 2>/dev/null || true
run_mysql -e "DROP INDEX idx_orderitem_product_quantity ON order_item;" 2>/dev/null || true
echo "✓ All indexes removed"
echo ""

# 2. v1 측정 (인덱스 없음)
echo "Step 2: Measuring v1 (no index)..."
v1_time=$(measure_api "v1" "no index")

# 3. 커버링 인덱스 생성
echo "Step 3: Creating covering index..."
run_mysql << 'EOF' 2>/dev/null
CREATE INDEX idx_orderitem_product_quantity
ON order_item(product_id, quantity);
EOF
echo "✓ Covering index created: idx_orderitem_product_quantity"
echo ""

# 4. v2 측정 (인덱스 있음)
echo "Step 4: Measuring v2 (with covering index)..."
v2_time=$(measure_api "v2" "with covering index")

# 5. 결과 비교
echo "========================================"
echo "Performance Comparison Results"
echo "========================================"
echo ""
echo "v1 (no index):       ${v1_time}ms"
echo "v2 (covering index): ${v2_time}ms"
echo ""

# 개선율 계산 (0 나누기 방지)
if [ "$v1_time" -le 0 ] || [ "$v2_time" -le 0 ]; then
    improvement="N/A"
    status="ERROR"
    echo "❌ Measurement failed (invalid time detected)"
elif [ "$v1_time" -lt 10 ] || [ "$v2_time" -lt 10 ]; then
    improvement="N/A"
    status="ERROR"
    echo "❌ Measurement failed (time too short, possible error)"
else
    improvement=$(awk "BEGIN {printf \"%.1f\", (1 - $v2_time / $v1_time) * 100}")
    echo "Improvement: ${improvement}%"
    echo ""

    # 판정
    if (( $(awk "BEGIN {print ($improvement > 20 ? 1 : 0)}") )); then
        echo "✅ Significant improvement achieved!"
        status="PASS"
    else
        echo "⚠️  Improvement less than expected (target: >20%)"
        status="WARNING"
    fi
fi

echo ""

# 6. 결과 파일 저장
cat > "$RESULT_FILE" << EOF
========================================
Phase 3-2 Performance Comparison Results
========================================

Measurement Date: $(date '+%Y-%m-%d %H:%M:%S')

Performance Metrics:
--------------------
v1 (no index):       ${v1_time}ms
v2 (covering index): ${v2_time}ms

Improvement:         ${improvement}%
Status:              ${status}

Index Details:
--------------
Index Name:   idx_orderitem_product_quantity
Columns:      (product_id, quantity)
Type:         Covering Index

Query:
------
SELECT oi.product_id, SUM(oi.quantity) as sales_count
FROM order_item oi
GROUP BY oi.product_id
ORDER BY sales_count DESC
LIMIT 100

Analysis:
---------
$(if [ "$status" = "PASS" ]; then
    echo "✅ The covering index significantly improved query performance."
    echo "   - Eliminated table data access (Using index)"
    echo "   - Reduced I/O operations"
    echo "   - GROUP BY and aggregation performed on index only"
elif [ "$status" = "WARNING" ]; then
    echo "⚠️  Improvement is less than expected."
    echo "   - Expected: >20% improvement"
    echo "   - Actual: ${improvement}%"
    echo "   - Note: v1 measured ${v1_time}ms (faster than expected ~200ms)"
    echo "   - Possible causes: Query cache, small dataset in memory, JVM optimization"
elif [ "$status" = "ERROR" ]; then
    echo "❌ Measurement failed."
    echo "   - v1: ${v1_time}ms, v2: ${v2_time}ms"
    echo "   - Time too short (<10ms) suggests measurement error"
fi)

Notes:
------
- Covering index remains in database for further testing
- To remove: DROP INDEX idx_orderitem_product_quantity ON order_item
- Each measurement is average of 3 runs (excluding warmup)
- Fast baseline time may indicate: warm cache, optimized JVM, or small working set in memory
EOF

echo "========================================"
echo "Results saved to: $RESULT_FILE"
echo "========================================"
echo ""
echo "Note: Covering index remains in database for further testing."
echo "To remove: docker exec -i docker-mysql-1 mysql -uroot -proot flashdeal -e 'DROP INDEX idx_orderitem_product_quantity ON order_item;'"
echo ""
