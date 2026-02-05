#!/bin/bash
# PoC 2: MySQL Index Create/Drop Verification
# Note: MySQL CLI with -p flag outputs "[Warning] Using a password on the command line
# interface can be insecure." to stderr. This is normal, not an error. Suppressed by 2>/dev/null.

MYSQL_HOST=127.0.0.1
MYSQL_PORT=3306
MYSQL_USER=root
MYSQL_PASSWORD=root
MYSQL_DATABASE=flashdeal

MYSQL_CMD="mysql -h${MYSQL_HOST} -P${MYSQL_PORT} -u${MYSQL_USER} -p${MYSQL_PASSWORD} ${MYSQL_DATABASE}"

echo "=== Test 1: Create index ==="
$MYSQL_CMD -e "CREATE INDEX idx_poc_test ON product(category);" 2>/dev/null
$MYSQL_CMD -e "SHOW INDEX FROM product WHERE Key_name = 'idx_poc_test';" 2>/dev/null
echo "(idx_poc_test visible = success)"

echo ""
echo "=== Test 2: Drop index ==="
$MYSQL_CMD -e "DROP INDEX idx_poc_test ON product;" 2>/dev/null
RESULT=$($MYSQL_CMD -e "SHOW INDEX FROM product WHERE Key_name = 'idx_poc_test';" 2>/dev/null)
if [ -z "$RESULT" ]; then
    echo "Drop confirmed: idx_poc_test removed"
else
    echo "Drop failed: idx_poc_test still exists"
fi

echo ""
echo "=== Test 3: Drop non-existent (expect error) ==="
$MYSQL_CMD -e "DROP INDEX idx_poc_test ON product;" 2>&1
echo "(error message above = expected)"

echo ""
echo "=== Test 4: || true error suppression ==="
$MYSQL_CMD -e "DROP INDEX idx_poc_test ON product;" 2>/dev/null || true
echo "Error suppressed (script continues)"

echo ""
echo "=== Test 5: Sequential DROP + CREATE ==="
$MYSQL_CMD -e "DROP INDEX idx_not_exist_1 ON product;" 2>/dev/null || true
$MYSQL_CMD -e "DROP INDEX idx_not_exist_2 ON product;" 2>/dev/null || true
$MYSQL_CMD -e "CREATE INDEX idx_poc_test ON product(category);" 2>/dev/null
$MYSQL_CMD -e "SHOW INDEX FROM product WHERE Key_name = 'idx_poc_test';" 2>/dev/null
echo "(non-existent DROP x2 → CREATE x1 → works)"

# Cleanup
$MYSQL_CMD -e "DROP INDEX idx_poc_test ON product;" 2>/dev/null || true

echo ""
echo "=== PoC 2 complete ==="
