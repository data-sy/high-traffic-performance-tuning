#!/bin/bash
# =============================================================
# Bulk Test Data Generator
# Generates: User 1K, Product 100K, Order 50K, OrderItem 1~5/order
# Updates: Product.sales_cnt = SUM(OrderItem.quantity)
# =============================================================
set -e

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root}"
MYSQL_DATABASE="${MYSQL_DATABASE:-flashdeal}"
BATCH_SIZE=5000

mysql_exec() {
    mysql -h"${MYSQL_HOST}" -P"${MYSQL_PORT}" -u"${MYSQL_USER}" -p"${MYSQL_PASSWORD}" "${MYSQL_DATABASE}" "$@"
}

CATEGORIES=("전자기기" "의류" "식품" "도서" "스포츠")
STATUSES=("PAID" "DELIVERED")
TMPFILE=$(mktemp /tmp/bulk_data_XXXXXX.sql)
trap "rm -f ${TMPFILE}" EXIT

echo "=== Bulk Test Data Generator ==="
echo "Target: ${MYSQL_DATABASE}@${MYSQL_HOST}:${MYSQL_PORT}"
echo ""

# ----------------------------------------------------------
# Step 1: Clear existing data
# ----------------------------------------------------------
echo "[1/7] Clearing existing data..."
mysql_exec <<'SQL'
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE coupon_issue;
TRUNCATE TABLE coupon;
TRUNCATE TABLE order_item;
TRUNCATE TABLE orders;
TRUNCATE TABLE product;
TRUNCATE TABLE users;
SET FOREIGN_KEY_CHECKS = 1;
SQL

# ----------------------------------------------------------
# Step 2: Generate users (1,000)
# ----------------------------------------------------------
echo "[2/7] Generating 1,000 users..."
> "${TMPFILE}"
echo "INSERT INTO users (email, name, created_at) VALUES" >> "${TMPFILE}"
for ((i=1; i<=1000; i++)); do
    if [ $i -gt 1 ]; then printf "," >> "${TMPFILE}"; fi
    printf "('user%d@test.com','User %d',NOW())" "$i" "$i" >> "${TMPFILE}"
done
echo ";" >> "${TMPFILE}"
mysql_exec < "${TMPFILE}"

# ----------------------------------------------------------
# Step 3: Generate products (100,000 — 20,000 per category)
# ----------------------------------------------------------
echo "[3/7] Generating 100,000 products..."
> "${TMPFILE}"

count=0
for ((i=1; i<=100000; i++)); do
    cat_idx=$(( (i - 1) % 5 ))
    price=$(( RANDOM % 90000 + 10000 ))
    stock=$(( RANDOM % 500 + 10 ))

    if [ $(( (i - 1) % BATCH_SIZE )) -eq 0 ]; then
        if [ $i -gt 1 ]; then echo ";" >> "${TMPFILE}"; fi
        echo "INSERT INTO product (name, price, category, stock, sales_cnt, created_at) VALUES" >> "${TMPFILE}"
        printf "('%s 상품 %d',%d,'%s',%d,0,NOW())" "${CATEGORIES[$cat_idx]}" "$i" "$price" "${CATEGORIES[$cat_idx]}" "$stock" >> "${TMPFILE}"
    else
        printf ",('%s 상품 %d',%d,'%s',%d,0,NOW())" "${CATEGORIES[$cat_idx]}" "$i" "$price" "${CATEGORIES[$cat_idx]}" "$stock" >> "${TMPFILE}"
    fi

    count=$((count + 1))
    if [ $((count % 20000)) -eq 0 ]; then
        echo "  ... ${count}/100,000 products generated"
    fi
done
echo ";" >> "${TMPFILE}"
mysql_exec < "${TMPFILE}"

# ----------------------------------------------------------
# Step 4: Generate orders (50,000)
# ----------------------------------------------------------
echo "[4/7] Generating 50,000 orders..."
> "${TMPFILE}"

for ((i=1; i<=50000; i++)); do
    user_id=$(( RANDOM % 1000 + 1 ))
    status_idx=$(( RANDOM % 2 ))

    if [ $(( (i - 1) % BATCH_SIZE )) -eq 0 ]; then
        if [ $i -gt 1 ]; then echo ";" >> "${TMPFILE}"; fi
        echo "INSERT INTO orders (user_id, status, total, created_at) VALUES" >> "${TMPFILE}"
        printf "(%d,'%s',0,NOW())" "$user_id" "${STATUSES[$status_idx]}" >> "${TMPFILE}"
    else
        printf ",(%d,'%s',0,NOW())" "$user_id" "${STATUSES[$status_idx]}" >> "${TMPFILE}"
    fi
done
echo ";" >> "${TMPFILE}"
mysql_exec < "${TMPFILE}"

# ----------------------------------------------------------
# Step 5: Generate order items (1~5 per order)
# ----------------------------------------------------------
echo "[5/7] Generating order items (1~5 per order)..."
> "${TMPFILE}"

row_count=0
for ((order_id=1; order_id<=50000; order_id++)); do
    item_count=$(( RANDOM % 5 + 1 ))

    for ((j=1; j<=item_count; j++)); do
        product_id=$(( (RANDOM * 32768 + RANDOM) % 100000 + 1 ))
        quantity=$(( RANDOM % 5 + 1 ))
        price=$(( RANDOM % 90000 + 10000 ))

        if [ $((row_count % BATCH_SIZE)) -eq 0 ]; then
            if [ $row_count -gt 0 ]; then echo ";" >> "${TMPFILE}"; fi
            echo "INSERT INTO order_item (order_id, product_id, quantity, price) VALUES" >> "${TMPFILE}"
            printf "(%d,%d,%d,%d)" "$order_id" "$product_id" "$quantity" "$price" >> "${TMPFILE}"
        else
            printf ",(%d,%d,%d,%d)" "$order_id" "$product_id" "$quantity" "$price" >> "${TMPFILE}"
        fi
        row_count=$((row_count + 1))
    done

    if [ $((order_id % 10000)) -eq 0 ]; then
        echo "  ... ${order_id}/50,000 orders processed (${row_count} items so far)"
    fi
done
echo ";" >> "${TMPFILE}"
mysql_exec < "${TMPFILE}"
echo "  Total order items: ${row_count}"

# ----------------------------------------------------------
# Step 6: Update order totals & product sales_cnt
# ----------------------------------------------------------
echo "[6/7] Updating order totals..."
mysql_exec <<'SQL'
UPDATE orders o
SET total = (
    SELECT COALESCE(SUM(oi.quantity * oi.price), 0)
    FROM order_item oi
    WHERE oi.order_id = o.id
);
SQL

echo "[7/7] Updating product sales counts..."
mysql_exec <<'SQL'
UPDATE product p
SET sales_cnt = (
    SELECT COALESCE(SUM(oi.quantity), 0)
    FROM order_item oi
    WHERE oi.product_id = p.id
);
SQL

# ----------------------------------------------------------
# Step 7: Insert coupons (issued stays 0)
# ----------------------------------------------------------
echo ""
echo "Inserting coupons..."
mysql_exec <<'SQL'
INSERT INTO coupon (name, total_qty, issued, created_at) VALUES
('Welcome Coupon', 100, 0, NOW()),
('Summer Sale Coupon', 100, 0, NOW()),
('VIP Discount Coupon', 100, 0, NOW());
SQL

# ----------------------------------------------------------
# Verify
# ----------------------------------------------------------
echo ""
echo "=== Verification ==="
mysql_exec -e "
SELECT 'users' AS tbl, COUNT(*) AS cnt FROM users
UNION ALL SELECT 'product', COUNT(*) FROM product
UNION ALL SELECT 'orders', COUNT(*) FROM orders
UNION ALL SELECT 'order_item', COUNT(*) FROM order_item
UNION ALL SELECT 'coupon', COUNT(*) FROM coupon
UNION ALL SELECT 'coupon_issue', COUNT(*) FROM coupon_issue;
"

echo ""
echo "=== Category distribution ==="
mysql_exec -e "SELECT category, COUNT(*) AS cnt FROM product GROUP BY category ORDER BY category;"

echo ""
echo "Done."
