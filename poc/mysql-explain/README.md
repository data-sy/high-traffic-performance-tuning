# PoC 3: EXPLAIN + File Save + Shell Script Verification

## Run
chmod +x poc/mysql-explain/test-explain.sh
./poc/mysql-explain/test-explain.sh

## Check
- Test 1: txt file with EXPLAIN table output
- Test 2: json file with EXPLAIN FORMAT=JSON output
- Test 3: SQL file executed from shell, EXPLAIN result changes with index
- Test 4: Connection failure shows error message

## Results
Generated in poc/mysql-explain/results/
