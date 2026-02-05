# PoC 2: MySQL Index Create/Drop Verification

## Run
chmod +x poc/mysql-index/test-index.sh
./poc/mysql-index/test-index.sh

## Check
- Test 1: SHOW INDEX shows idx_poc_test after CREATE
- Test 2: idx_poc_test removed after DROP
- Test 3: Error message on DROP non-existent index
- Test 4: || true prevents script abort
- Test 5: Multiple DROP + CREATE in sequence works

## Conclusion
Error handling for run-explain.sh step0:
→ Individual mysql commands with 2>/dev/null || true
