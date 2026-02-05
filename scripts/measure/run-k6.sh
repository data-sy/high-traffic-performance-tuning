#!/bin/bash

# k6 load test automation script
# Iterates through 5 index strategies and saves k6 results

# MySQL access via Docker
run_mysql() {
    docker exec -i docker-mysql-1 mysql -uroot -proot flashdeal "$@" 2>/dev/null
}

# Directories
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
INDEX_DIR="$PROJECT_ROOT/scripts/index"
RESULTS_DIR="$PROJECT_ROOT/results/k6"
K6_SCRIPT="$PROJECT_ROOT/test/load/index-test.js"

# Create results directory
mkdir -p "$RESULTS_DIR"

# Health check - server must be running
echo "Checking server health..."
if ! curl -sf http://localhost:8080/api/poc/health > /dev/null; then
    echo "Server not running. Start with ./gradlew bootRun"
    exit 1
fi
echo "Server is running."
echo ""

# Function to reset all indexes (step0)
reset_indexes() {
    echo "Resetting indexes (step0)..."
    run_mysql -e "DROP INDEX idx_product_category ON product" 2>/dev/null || true
    run_mysql -e "DROP INDEX idx_product_created_at ON product" 2>/dev/null || true
    run_mysql -e "DROP INDEX idx_product_category_created ON product" 2>/dev/null || true
    run_mysql -e "DROP INDEX idx_product_created_category ON product" 2>/dev/null || true
}

# Step definitions: step_number:name:sql_file
STEPS=(
    "1:no-index:step1-no-index.sql"
    "2:category-only:step2-category-only.sql"
    "3:created-at-only:step3-created-at-only.sql"
    "4:composite:step4-composite.sql"
    "5:reverse-composite:step5-reverse-composite.sql"
)

echo "========================================"
echo "k6 Load Test Automation"
echo "========================================"
echo ""

# Process each step
for step in "${STEPS[@]}"; do
    IFS=':' read -r step_num step_name sql_file <<< "$step"

    echo "----------------------------------------"
    echo "Step $step_num: $step_name"
    echo "----------------------------------------"

    # 1. Reset indexes
    reset_indexes

    # 2. Apply step SQL (skip for step1 which is empty baseline)
    if [ "$step_num" != "1" ]; then
        echo "Applying $sql_file..."
        run_mysql < "$INDEX_DIR/$sql_file"
    else
        echo "Baseline (no index to apply)"
    fi

    # 3. Run k6 and save result
    echo "Running k6 load test..."
    k6 run "$K6_SCRIPT" --summary-export="$RESULTS_DIR/step${step_num}-${step_name}.json" 2>&1 | tail -20

    echo "Saved: step${step_num}-${step_name}.json"
    echo ""

    # Brief pause between steps
    sleep 2
done

# Final cleanup: reset to clean state
echo "----------------------------------------"
echo "Final cleanup: resetting indexes..."
echo "----------------------------------------"
reset_indexes
echo ""

# Summary
echo "========================================"
echo "Summary: Generated files"
echo "========================================"
ls -la "$RESULTS_DIR"/*.json 2>/dev/null | awk '{print $NF}'
echo ""
echo "Done!"
