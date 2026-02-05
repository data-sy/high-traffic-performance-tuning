# PoC Verification Results — Phase 3-1

**Test Date:** 2026-02-04

## PoC 1: Spring Boot API + DB Connection + Paging

| Test | Expected | Actual | Result |
|------|----------|--------|--------|
| `GET /api/poc/health` | `{"status":"ok"}` | `{"status":"ok"}` | PASS |
| `GET /api/poc/db-check` | `{"count":100000}` | `{"count":100000}` | PASS |
| `GET /api/poc/paging-check` | Page JSON, 5 items, category=전자기기 | 5 items, totalElements=20000, totalPages=4000, all category=전자기기 | PASS |

## PoC 2: MySQL Index Create/Drop

| Test | Expected | Actual | Result |
|------|----------|--------|--------|
| Test 1: CREATE INDEX | idx_poc_test visible in SHOW INDEX | idx_poc_test visible, BTREE, Cardinality=4 | PASS |
| Test 2: DROP INDEX | idx_poc_test removed | Drop confirmed: empty result | PASS |
| Test 3: DROP non-existent | Error message output | `ERROR 1091 (42000): Can't DROP 'idx_poc_test'; check that column/key exists` | PASS |
| Test 4: `\|\| true` suppression | Script continues after error | "Error suppressed (script continues)" printed | PASS |
| Test 5: Sequential DROP + CREATE | Non-existent DROP x2 then CREATE succeeds | idx_poc_test created after two failed drops | PASS |

## PoC 3: EXPLAIN Execution + File Save

| Test | Expected | Actual | Result |
|------|----------|--------|--------|
| Test 1: EXPLAIN table format | txt file with type, key, Extra columns | `type=ALL`, `key=NULL`, `Extra=Using where; Using filesort` | PASS |
| Test 2: EXPLAIN FORMAT=JSON | json file with query plan | JSON with query_cost=10110.45, access_type=ALL, rows=99742 | PASS |
| Test 3: SQL file execution | EXPLAIN changes after index applied | `type=ref`, `key=idx_poc_explain`, rows=37760 | PASS |
| Test 4: Connection failure | Error message output | mysql connection error displayed | PASS |
| File generation | 3 files in results/ | test-explain.txt, test-explain.json, test-with-index.txt | PASS |

## PoC 4: k6 Install + Run + Summary Export

| Test | Expected | Actual | Result |
|------|----------|--------|--------|
| k6 basic run | 3/3 checks passed | checks_succeeded=100.00%, 3 out of 3 | PASS |
| http_req_duration | Metric present in output | avg=1.69ms, p(95)=3.27ms | PASS |
| --summary-export | test-result.json created | File created, 3082 bytes | PASS |

## Fix Applied

| File | Before | After | Reason |
|------|--------|-------|--------|
| `poc/mysql-index/test-index.sh` | `MYSQL_HOST=localhost` | `MYSQL_HOST=127.0.0.1` | `mysql -hlocalhost` uses Unix socket, fails for Docker containers |
| `poc/mysql-explain/test-explain.sh` | `MYSQL_HOST=localhost` | `MYSQL_HOST=127.0.0.1` | Same as above |

## Summary

- **Total tests:** 16
- **Passed:** 16
- **Failed:** 0
- **All tools verified:** Spring Boot API, MySQL CLI, EXPLAIN, k6
