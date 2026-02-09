#!/bin/bash

# k6 load test automation script for Phase 3-2 Ranking
# Tests all 4 ranking versions (v1~v4) and saves k6 results

# MySQL access via Docker
run_mysql() {
    docker exec -i docker-mysql-1 mysql -uroot -proot flashdeal "$@" 2>/dev/null
}

# Directories
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
RESULTS_DIR="$PROJECT_ROOT/results/phase3-2-ranking-optimization/k6"
K6_SCRIPT="$PROJECT_ROOT/test/load/ranking-test.js"

# Create results directory
mkdir -p "$RESULTS_DIR"

# Health check
echo "Checking server health..."
if ! curl -sf http://localhost:8080/api/poc/health > /dev/null; then
    echo "Server not running. Start with ./gradlew bootRun"
    exit 1
fi
echo "Server is running."
echo ""

# Version definitions: version:output_name:description:pre_command
VERSIONS=(
    "v1:v1-no-index:DB direct query (no index):"
    "v2:v2-with-index:DB direct query (with index):CREATE INDEX idx_orderitem_product_quantity ON order_item(product_id, quantity)"
    "v3:v3-string-cache:Redis String caching:"
    "v4:v4-sorted-set:Redis Sorted Set:"
)

echo "========================================"
echo "k6 Load Test: Phase 3-2 Ranking"
echo "========================================"
echo ""

for entry in "${VERSIONS[@]}"; do
    IFS=':' read -r version output_name description pre_cmd <<< "$entry"

    echo "----------------------------------------"
    echo "$version: $description"
    echo "----------------------------------------"

    # Apply pre-command if exists (e.g., create index for v2)
    if [ -n "$pre_cmd" ]; then
        echo "Applying: $pre_cmd"
        run_mysql -e "$pre_cmd" 2>/dev/null || true
    fi

    # Run k6
    echo "Running k6 load test (VUs=10, Duration=30s)..."
    k6 run "$K6_SCRIPT" \
        -e VERSION="$version" \
        --summary-export="$RESULTS_DIR/${output_name}.json" \
        2>&1 | tail -20

    echo "Saved: ${output_name}.json"
    echo ""

    # Brief pause between versions
    sleep 2
done

# Cleanup: drop index created for v2
echo "----------------------------------------"
echo "Cleanup: dropping v2 index..."
echo "----------------------------------------"
run_mysql -e "DROP INDEX idx_orderitem_product_quantity ON order_item" 2>/dev/null || true
echo ""

# Summary
echo "========================================"
echo "Summary: Generated files"
echo "========================================"
ls -la "$RESULTS_DIR"/*.json 2>/dev/null | awk '{print $NF}'
echo ""
echo "Done!"
