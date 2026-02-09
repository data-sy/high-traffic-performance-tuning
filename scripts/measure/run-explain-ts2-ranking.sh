#!/bin/bash

# EXPLAIN measurement automation script for Phase 3-2 Ranking
# Tests 3 index strategies on order_item table and saves EXPLAIN results

# MySQL access via Docker
run_mysql() {
    docker exec -i docker-mysql-1 mysql -uroot -proot flashdeal "$@" 2>/dev/null
}

# Directories
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
RESULTS_DIR="$PROJECT_ROOT/results/phase3-2-ranking-optimization/explain"

# Create results directory
mkdir -p "$RESULTS_DIR"

# Target query for EXPLAIN
QUERY="SELECT oi.product_id, SUM(oi.quantity) as sales_count FROM order_item oi GROUP BY oi.product_id ORDER BY sales_count DESC LIMIT 100"

# Function to reset ranking indexes
reset_indexes() {
    echo "Resetting ranking indexes..."
    run_mysql -e "DROP INDEX idx_orderitem_product_id ON order_item" 2>/dev/null || true
    run_mysql -e "DROP INDEX idx_orderitem_product_quantity ON order_item" 2>/dev/null || true
}

echo "========================================"
echo "EXPLAIN Measurement: Phase 3-2 Ranking"
echo "========================================"
echo ""

# ----------------------------------------
# Step 1: No index (baseline)
# ----------------------------------------
echo "----------------------------------------"
echo "Step 1: No index (baseline)"
echo "----------------------------------------"
reset_indexes

echo "Running EXPLAIN..."
run_mysql -e "EXPLAIN $QUERY" > "$RESULTS_DIR/step1-no-index.txt"

echo "Running EXPLAIN FORMAT=JSON..."
run_mysql -e "EXPLAIN FORMAT=JSON $QUERY" > "$RESULTS_DIR/step1-no-index.json"

COST1=$(run_mysql -N -e "EXPLAIN FORMAT=JSON $QUERY" | python3 -c "import sys,json; print(json.load(sys.stdin)['query_block']['cost_info']['query_cost'])" 2>/dev/null || echo "N/A")
echo "Query cost: $COST1"
echo "Saved: step1-no-index.txt, step1-no-index.json"
echo ""

# ----------------------------------------
# Step 2: Single index on product_id
# ----------------------------------------
echo "----------------------------------------"
echo "Step 2: Single index (product_id)"
echo "----------------------------------------"
reset_indexes

echo "Creating index: idx_orderitem_product_id..."
run_mysql -e "CREATE INDEX idx_orderitem_product_id ON order_item(product_id)"

echo "Running EXPLAIN..."
run_mysql -e "EXPLAIN $QUERY" > "$RESULTS_DIR/step2-product-id.txt"

echo "Running EXPLAIN FORMAT=JSON..."
run_mysql -e "EXPLAIN FORMAT=JSON $QUERY" > "$RESULTS_DIR/step2-product-id.json"

COST2=$(run_mysql -N -e "EXPLAIN FORMAT=JSON $QUERY" | python3 -c "import sys,json; print(json.load(sys.stdin)['query_block']['cost_info']['query_cost'])" 2>/dev/null || echo "N/A")
echo "Query cost: $COST2"
echo "Saved: step2-product-id.txt, step2-product-id.json"
echo ""

# ----------------------------------------
# Step 3: Covering index (product_id, quantity)
# ----------------------------------------
echo "----------------------------------------"
echo "Step 3: Covering index (product_id, quantity)"
echo "----------------------------------------"
reset_indexes

echo "Creating index: idx_orderitem_product_quantity..."
run_mysql -e "CREATE INDEX idx_orderitem_product_quantity ON order_item(product_id, quantity)"

echo "Running EXPLAIN..."
run_mysql -e "EXPLAIN $QUERY" > "$RESULTS_DIR/step3-covering.txt"

echo "Running EXPLAIN FORMAT=JSON..."
run_mysql -e "EXPLAIN FORMAT=JSON $QUERY" > "$RESULTS_DIR/step3-covering.json"

COST3=$(run_mysql -N -e "EXPLAIN FORMAT=JSON $QUERY" | python3 -c "import sys,json; print(json.load(sys.stdin)['query_block']['cost_info']['query_cost'])" 2>/dev/null || echo "N/A")
echo "Query cost: $COST3"
echo "Saved: step3-covering.txt, step3-covering.json"
echo ""

# ----------------------------------------
# Final cleanup
# ----------------------------------------
echo "----------------------------------------"
echo "Final cleanup: resetting indexes..."
echo "----------------------------------------"
reset_indexes
echo ""

# ----------------------------------------
# Summary
# ----------------------------------------
echo "========================================"
echo "Summary: Query Cost Comparison"
echo "========================================"
echo "Step 1 (no index):      $COST1"
echo "Step 2 (product_id):    $COST2"
echo "Step 3 (covering):      $COST3"
echo ""
echo "Generated files:"
ls -la "$RESULTS_DIR"/*.txt "$RESULTS_DIR"/*.json 2>/dev/null | awk '{print $NF}'
echo ""
echo "Done!"
