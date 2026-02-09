# PoC 2: Redis Sorted Set Operations Verification

## Run
chmod +x poc/redis-sortedset/test-sortedset.sh
./poc/redis-sortedset/test-sortedset.sh

## Check
- Test 1: ZADD adds 5 items successfully
- Test 2: ZINCRBY increments score (100 → 150)
- Test 3: ZREVRANGE returns top 3 in descending order
- Test 4: ZCARD returns 5
- Test 5: ZREMRANGEBYRANK removes bottom 2, leaves 3
- Test 6: ZSCORE returns correct score
- Test 7: DEL removes key
- Test 8: ZINCRBY on non-existent key auto-creates it

## Key Learning
- Sorted Set maintains order automatically
- ZINCRBY is atomic (no race condition)
- ZREMRANGEBYRANK enables memory management (keep top N)
