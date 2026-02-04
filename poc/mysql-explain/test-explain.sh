#!/bin/bash
# PoC 3: EXPLAIN Execution + File Save Verification
# Note: MySQL CLI with -p flag outputs "[Warning] Using a password on the command line
# interface can be insecure." to stderr. This is normal, not an error. Suppressed by 2>/dev/null.

MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_USER=root
MYSQL_PASSWORD=root
MYSQL_DATABASE=flashdeal

MYSQL_CMD="mysql -h${MYSQL_HOST} -P${MYSQL_PORT} -u${MYSQL_USER} -p${MYSQL_PASSWORD} ${MYSQL_DATABASE}"

RESULT_DIR="poc/mysql-explain/results"
mkdir -p "$RESULT_DIR"

QUERY="SELECT * FROM product WHERE category = '전자기기' ORDER BY created_at DESC LIMIT 20"

echo "=== Test 1: EXPLAIN table format ==="
$MYSQL_CMD -e "EXPLAIN ${QUERY};" > "$RESULT_DIR/test-explain.txt" 2>/dev/null
echo "Result: $RESULT_DIR/test-explain.txt"
cat "$RESULT_DIR/test-explain.txt"

echo ""
echo "=== Test 2: EXPLAIN FORMAT=JSON ==="
$MYSQL_CMD -e "EXPLAIN FORMAT=JSON ${QUERY};" > "$RESULT_DIR/test-explain.json" 2>/dev/null
echo "Result: $RESULT_DIR/test-explain.json"
head -5 "$RESULT_DIR/test-explain.json"

echo ""
echo "=== Test 3: Execute SQL file from shell ==="
echo "CREATE INDEX idx_poc_explain ON product(category);" > "$RESULT_DIR/temp-index.sql"
$MYSQL_CMD < "$RESULT_DIR/temp-index.sql" 2>/dev/null
$MYSQL_CMD -e "EXPLAIN ${QUERY};" > "$RESULT_DIR/test-with-index.txt" 2>/dev/null
echo "With index:"
cat "$RESULT_DIR/test-with-index.txt"

# Cleanup
$MYSQL_CMD -e "DROP INDEX idx_poc_explain ON product;" 2>/dev/null
rm -f "$RESULT_DIR/temp-index.sql"

echo ""
echo "=== Test 4: Connection failure error ==="
mysql -hlocalhost -P9999 -uroot -pwrong testdb -e "SELECT 1;" 2>&1 | head -1
echo "(error message above = expected)"

echo ""
echo "=== PoC 3 complete ==="
echo "Generated files:"
ls -la "$RESULT_DIR/"
