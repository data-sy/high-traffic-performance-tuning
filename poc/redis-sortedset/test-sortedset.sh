#!/bin/bash
# Note: This script intentionally does NOT use set -e to allow error observation

run_redis() {
    docker exec -i docker-redis-1 redis-cli "$@"
}

KEY="poc:ranking:test"

echo "=== Test 1: ZADD (Initial scores) ==="
run_redis ZADD "$KEY" 100 "product:1"
run_redis ZADD "$KEY" 200 "product:2"
run_redis ZADD "$KEY" 150 "product:3"
run_redis ZADD "$KEY" 80 "product:4"
run_redis ZADD "$KEY" 120 "product:5"
echo "Added 5 items"

echo ""
echo "=== Test 2: ZINCRBY (Increment product:1 by 50) ==="
RESULT=$(run_redis ZINCRBY "$KEY" 50 "product:1")
echo "Score after increment: $RESULT (expected: 150)"

echo ""
echo "=== Test 3: ZREVRANGE (Top 3 with scores) ==="
run_redis ZREVRANGE "$KEY" 0 2 WITHSCORES

echo ""
echo "=== Test 4: ZCARD (Total size) ==="
SIZE=$(run_redis ZCARD "$KEY")
echo "Size: $SIZE (expected: 5)"

echo ""
echo "=== Test 5: ZREMRANGEBYRANK (Remove bottom 2) ==="
run_redis ZREMRANGEBYRANK "$KEY" 0 1
REMAINING=$(run_redis ZCARD "$KEY")
echo "Remaining: $REMAINING (expected: 3)"

echo ""
echo "=== Test 6: ZSCORE (Get specific score) ==="
SCORE=$(run_redis ZSCORE "$KEY" "product:2")
echo "product:2 score: $SCORE (expected: 200)"

echo ""
echo "=== Test 7: DEL (Cleanup) ==="
run_redis DEL "$KEY"
echo "Key removed"

echo ""
echo "=== Test 8: Error handling - ZINCRBY on non-existent key ==="
run_redis ZINCRBY "poc:nonexistent" 10 "item:1"
echo "Auto-created with score 10"

echo ""
echo "=== Cleanup ==="
run_redis DEL "poc:nonexistent"

echo ""
echo "=== PoC 2 complete ==="
