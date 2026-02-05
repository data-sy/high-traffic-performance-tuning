#!/bin/bash

# EXPLAIN measurement automation script
# Iterates through 5 index strategies and saves EXPLAIN results

# MySQL access via Docker
run_mysql() {
    docker exec -i docker-mysql-1 mysql -uroot -proot flashdeal "$@" 2>/dev/null
}

# Directories
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
INDEX_DIR="$PROJECT_ROOT/scripts/index"
RESULTS_DIR="$PROJECT_ROOT/results/explain"

# Create results directory
mkdir -p "$RESULTS_DIR"

# Target query for EXPLAIN
QUERY="SELECT * FROM product WHERE category = '전자기기' ORDER BY created_at DESC LIMIT 20"

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
echo "EXPLAIN Measurement Automation"
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

    # 3. Run EXPLAIN and save text result
    echo "Running EXPLAIN..."
    run_mysql -e "EXPLAIN $QUERY" > "$RESULTS_DIR/step${step_num}-${step_name}.txt"

    # 4. Run EXPLAIN FORMAT=JSON and save JSON result
    echo "Running EXPLAIN FORMAT=JSON..."
    run_mysql -e "EXPLAIN FORMAT=JSON $QUERY" > "$RESULTS_DIR/step${step_num}-${step_name}.json"

    echo "Saved: step${step_num}-${step_name}.txt, step${step_num}-${step_name}.json"
    echo ""
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
ls -la "$RESULTS_DIR"/*.txt "$RESULTS_DIR"/*.json 2>/dev/null | awk '{print $NF}'
echo ""
echo "Done!"
