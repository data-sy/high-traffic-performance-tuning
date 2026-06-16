# PoC — Phase 3-1 Tool Verification

## Goal
Verify that all tools and features needed for Phase 3-1 work correctly in the current environment. Commit the passing state as a reference point to distinguish tool issues from code issues during main work.

## Prerequisites
- Phase 1, 2 completed.
- `docker compose up -d` (MySQL, Redis running).
- Product 100K rows exist (from Phase 2 `scripts/generate-test-data.sh`).

## Output Structure
All PoC files go under `poc/` directory, organized by feature. Do NOT mix with main work code (`src/`, `scripts/`, `test/`).

```
poc/
├── api-health/
│   ├── HealthCheckController.java
│   └── README.md
├── mysql-index/
│   ├── test-index.sh
│   └── README.md
├── mysql-explain/
│   ├── test-explain.sh
│   └── README.md
└── k6-basic/
    ├── test-k6.js
    └── README.md
```

Each README.md contains: purpose, how to run, expected results.

## PoC Items

### PoC 1: Spring Boot API + DB Connection + Paging
**Purpose:** Verify full chain: API creation → DB query → paging response

**Work:**
- Location: `poc/api-health/HealthCheckController.java` (copy for reference)
- Actual code: `src/main/java/com/project/api/poc/HealthCheckController.java`
- 3 endpoints:
  1. `GET /api/poc/health` → `{"status": "ok"}`
  2. `GET /api/poc/db-check` → `SELECT COUNT(*)` from Product → `{"count": 100000}`
  3. `GET /api/poc/paging-check` → `ProductRepository.findByCategoryOrderByCreatedAtDesc("전자기기", PageRequest.of(0, 5))` → Page JSON

**Notes:**
- The actually compiled and running code MUST be created under `src/main/java/com/project/api/poc/`. The `poc/api-health/` directory only holds a reference copy and README. **src/ is primary; poc/ copy is optional.**
- Inject ProductRepository into HealthCheckController.
- If `findByCategoryOrderByCreatedAtDesc` doesn't exist in ProductRepository, add it. This method will be reused in Phase 3-1 main work — do NOT delete it after PoC.
- HealthCheckController must be within the main application's component scan scope. Package `com.project.api.poc` is under `com.project`, so it will be scanned.

**README.md:**
```
# PoC 1: API + DB + Paging Verification

## Run
1. docker compose up -d
2. ./gradlew bootRun
3. curl http://localhost:8080/api/poc/health
4. curl http://localhost:8080/api/poc/db-check
5. curl http://localhost:8080/api/poc/paging-check

## Expected
- /health → {"status": "ok"}
- /db-check → {"count": 100000}
- /paging-check → Page JSON with 5 items, all category = "전자기기"
```

### PoC 2: MySQL Index Create/Drop
**Purpose:** Verify index creation, deletion, and error handling via shell script

**Work:**
- Location: `poc/mysql-index/test-index.sh`
- Set executable: `chmod +x poc/mysql-index/test-index.sh`

**Script:**
```bash
#!/bin/bash
# Note: MySQL CLI with -p flag outputs "[Warning] Using a password on the command line
# interface can be insecure." to stderr. This is normal, not an error. Suppressed by 2>/dev/null.

MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_USER=root
MYSQL_PASSWORD=root
MYSQL_DATABASE=flashdeal

MYSQL_CMD="mysql -h${MYSQL_HOST} -P${MYSQL_PORT} -u${MYSQL_USER} -p${MYSQL_PASSWORD} ${MYSQL_DATABASE}"
$MYSQL_CMD -e "CREATE INDEX idx_poc_test ON product(category);" 2>/dev/null
$MYSQL_CMD -e "SHOW INDEX FROM product WHERE Key_name = 'idx_poc_test';" 2>/dev/null

echo "=== Test 2: Drop index ==="
$MYSQL_CMD -e "DROP INDEX idx_poc_test ON product;" 2>/dev/null
RESULT=$($MYSQL_CMD -e "SHOW INDEX FROM product WHERE Key_name = 'idx_poc_test';" 2>/dev/null)
if [ -z "$RESULT" ]; then
    echo "Drop confirmed: idx_poc_test removed"
else
    echo "Drop failed: idx_poc_test still exists"
fi

echo "=== Test 3: Drop non-existent (expect error) ==="
$MYSQL_CMD -e "DROP INDEX idx_poc_test ON product;" 2>&1

echo "=== Test 4: || true error suppression ==="
$MYSQL_CMD -e "DROP INDEX idx_poc_test ON product;" 2>/dev/null || true
echo "Error suppressed (script continues)"

echo "=== Test 5: Sequential DROP + CREATE ==="
$MYSQL_CMD -e "DROP INDEX idx_not_exist_1 ON product;" 2>/dev/null || true
$MYSQL_CMD -e "DROP INDEX idx_not_exist_2 ON product;" 2>/dev/null || true
$MYSQL_CMD -e "CREATE INDEX idx_poc_test ON product(category);" 2>/dev/null
$MYSQL_CMD -e "SHOW INDEX FROM product WHERE Key_name = 'idx_poc_test';" 2>/dev/null

# Cleanup
$MYSQL_CMD -e "DROP INDEX idx_poc_test ON product;" 2>/dev/null || true

echo "=== PoC 2 complete ==="
```

**README.md:**
```
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
```

### PoC 3: EXPLAIN Execution + File Save + Shell Script
**Purpose:** Verify shell script can run EXPLAIN via MySQL CLI and save results to files

**Work:**
- Location: `poc/mysql-explain/test-explain.sh`
- Set executable: `chmod +x poc/mysql-explain/test-explain.sh`

**Script content:**
```bash
#!/bin/bash
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
cat "$RESULT_DIR/test-explain.txt"

echo "=== Test 2: EXPLAIN FORMAT=JSON ==="
$MYSQL_CMD -e "EXPLAIN FORMAT=JSON ${QUERY};" > "$RESULT_DIR/test-explain.json" 2>/dev/null
head -5 "$RESULT_DIR/test-explain.json"

echo "=== Test 3: Execute SQL file from shell ==="
echo "CREATE INDEX idx_poc_explain ON product(category);" > "$RESULT_DIR/temp-index.sql"
$MYSQL_CMD < "$RESULT_DIR/temp-index.sql" 2>/dev/null
$MYSQL_CMD -e "EXPLAIN ${QUERY};" > "$RESULT_DIR/test-with-index.txt" 2>/dev/null
echo "With index:"
cat "$RESULT_DIR/test-with-index.txt"
$MYSQL_CMD -e "DROP INDEX idx_poc_explain ON product;" 2>/dev/null
rm -f "$RESULT_DIR/temp-index.sql"

echo "=== Test 4: Connection failure error ==="
mysql -hlocalhost -P9999 -uroot -pwrong testdb -e "SELECT 1;" 2>&1 | head -1

echo "=== PoC 3 complete ==="
ls -la "$RESULT_DIR/"
```

**README.md:**
```
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
```

**Note:** Add `poc/mysql-explain/results/` to `.gitignore`.

### PoC 4: k6 Install Check + Run + Summary Export
**Purpose:** Verify k6 is installed, can hit server, and export results to JSON

**Work:**
- Location: `poc/k6-basic/test-k6.js`

**Script:**
```javascript
import http from 'k6/http';
import { check } from 'k6';

export const options = {
    vus: 1,
    iterations: 3,
};

export default function () {
    const res = http.get('http://localhost:8080/api/poc/health');
    check(res, {
        'status is 200': (r) => r.status === 200,
    });
}
```

**README.md:**
```
# PoC 4: k6 Basic Verification

## Pre-check
k6 version
(If not installed: https://grafana.com/docs/k6/latest/set-up/install-k6/)

## Run
1. Start server: ./gradlew bootRun
2. Basic: k6 run poc/k6-basic/test-k6.js
3. Export: k6 run poc/k6-basic/test-k6.js --summary-export=poc/k6-basic/test-result.json

## Check
- k6 completes, checks 3/3 passed
- http_req_duration in output
- test-result.json created
- JSON contains metrics.http_req_duration field
```

**Note:** Add `poc/k6-basic/test-result.json` to `.gitignore`.

## .gitignore Additions
```
# PoC result files
poc/mysql-explain/results/
poc/k6-basic/test-result.json
```

## General Notes
- Do NOT use `set -e` in any shell scripts. PoC 2 and 3 intentionally allow errors as part of verification. `set -e` would abort the tests prematurely.
- MySQL credentials (root/root/flashdeal) are local docker-compose defaults. Do NOT parameterize or abstract DB credentials in this phase. Hardcoding is intentional.

## Work Order
Complete all PoCs sequentially, then commit once.

1. Create `poc/` directory structure
2. PoC 1: HealthCheckController + README
3. PoC 2: test-index.sh + README
4. PoC 3: test-explain.sh + README
5. PoC 4: test-k6.js + README
6. Update `.gitignore`
7. Pause for user review after all files created

User manually runs each PoC to verify, then commits.

## Done Criteria

### Claude Code Completion (File Existence)
- [ ] `src/main/java/com/project/api/poc/HealthCheckController.java` exists
- [ ] `poc/api-health/HealthCheckController.java` (copy) + `README.md` exist
- [ ] `poc/mysql-index/test-index.sh` + `README.md` exist
- [ ] `poc/mysql-explain/test-explain.sh` + `README.md` exist
- [ ] `poc/k6-basic/test-k6.js` + `README.md` exist
- [ ] ProductRepository contains `findByCategoryOrderByCreatedAtDesc` method
- [ ] `.gitignore` includes PoC result exclusion rules
- [ ] All .sh files have executable permission

### Human Verification (Run Confirmation)
- [ ] `GET /api/poc/health` → `{"status": "ok"}`
- [ ] `GET /api/poc/db-check` → `{"count": 100000}` (or actual count)
- [ ] `GET /api/poc/paging-check` → Page JSON, category filter works
- [ ] `./poc/mysql-index/test-index.sh` → index CREATE/DROP/error handling works
- [ ] `./poc/mysql-explain/test-explain.sh` → 3 files in results/, EXPLAIN output verified
- [ ] `k6 version` outputs version
- [ ] `k6 run poc/k6-basic/test-k6.js` completes
- [ ] `--summary-export` creates JSON file
