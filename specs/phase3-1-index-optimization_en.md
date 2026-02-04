# Phase 3-1 — Index Optimization Implementation

## Context
- Phase 1 (project setup) and Phase 2 (domain entities + test data) are complete.
- Product table has 100K rows (20K per category: 전자기기, 의류, 식품, 도서, 스포츠).
- `ddl-auto: validate` is active. Do NOT change this.
- Docker containers (MySQL, Redis) are running via `docker compose up -d`.
- PoC verification is complete. Tools (MySQL CLI, EXPLAIN, k6) are confirmed working.

## Goal
Implement product list API with category filtering + pagination, then create index scripts and measurement automation to compare 5 index strategies (step1~step5).

## Important Rules
- Read `CLAUDE.md` first for project conventions.
- Do NOT create v1~v4 separate controllers. This scenario uses a **single controller + single service**. The before/after difference comes from which **index SQL script** is applied, not from code changes.
- Do NOT use `set -e` in any shell scripts. Some commands intentionally fail (e.g., DROP INDEX on non-existent index).
- MySQL credentials are hardcoded: `root/root`, database `flashdeal`, host `127.0.0.1`, port `3306`. Do NOT parameterize.
- All `.sh` files must have executable permission (`chmod +x`).

---

## Step A: API Implementation

### Restrictions
- Do NOT modify the Product entity in any way.
- Do NOT return Product entity directly from Controller or Service. Always use DTO.
- Controller must call Service, Service must call Repository. No shortcuts.

### 1. ProductRepository — add query method
- Location: existing `ProductRepository.java` in the project's `domain/product` package.
- Add method: `Page<Product> findByCategoryOrderByCreatedAtDesc(String category, Pageable pageable)`
- This method may already exist from PoC phase. If so, keep it as-is.

### 2. ProductListResponse DTO
- Location: `api/product/dto/ProductListResponse.java` (under the project's base package)
- Fields: `id`(Long), `name`(String), `price`(Integer), `category`(String), `createdAt`(LocalDateTime)
- **Exclude**: `stock`, `salesCount` — these must NOT appear in the response.
- Use `@Getter` and constructor (or `@Builder`) following project Lombok conventions.
- Add static factory method: `public static ProductListResponse from(Product product)`

### 3. ProductService
- Location: `service/product/ProductService.java` (under the project's base package)
- Method: `public Page<ProductListResponse> getProducts(String category, Pageable pageable)`
- Calls `productRepository.findByCategoryOrderByCreatedAtDesc(category, pageable)`
- Maps each Product to ProductListResponse using the factory method
- `@Service`, `@RequiredArgsConstructor`, `@Transactional(readOnly = true)`

### 4. ProductController
- Location: `api/product/ProductController.java` (under the project's base package)
- Endpoint: `GET /api/v1/products`
- Parameters: `@RequestParam String category`, `@RequestParam(defaultValue = "0") int page`, `@RequestParam(defaultValue = "20") int size`
- Controller creates `PageRequest.of(page, size)` and passes the Pageable to Service.
- Returns: `Page<ProductListResponse>`
- `@RestController`, `@RequiredArgsConstructor`

### Step A Build Check
- Run `./gradlew compileJava` to verify compilation.
- Do NOT run the application (`bootRun`) or tests. Human will verify runtime behavior.

### Step A Verification (human will verify)
```
GET /api/v1/products?category=전자기기&page=0&size=20
→ 200 response
→ JSON has content, totalElements, totalPages fields
→ content items have id, name, price, category, createdAt
→ content items do NOT have stock or salesCount
→ Non-existent category returns empty content with 200
```

### Step A Commit
After verification passes, run `/commit` with staged files.

---

## Step B: Index SQL Scripts

### Location: `scripts/index/`

Create 6 SQL files. Each script must be independently executable and idempotent.

### step0-drop-all.sql
Drops all custom indexes. Used as a reset before applying any step.
```sql
-- Drop all custom indexes (ignore errors if not exist)
-- MySQL does not support DROP INDEX IF EXISTS, so use separate statements
-- The run-explain.sh script handles errors with || true
DROP INDEX idx_product_category ON product;
DROP INDEX idx_product_created_at ON product;
DROP INDEX idx_product_category_created ON product;
DROP INDEX idx_product_created_category ON product;
```
**Note:** These will fail if the index doesn't exist. That's expected. The shell script calling these uses `|| true` to suppress errors.

### step1-no-index.sql
```sql
-- Step 1: No index (baseline)
-- This file is intentionally empty.
-- After step0-drop-all.sql removes all indexes, this state represents the baseline.
-- Exists for documentation and symmetry with other steps.
```

### step2-category-only.sql
```sql
-- Step 2: Single index on category only
CREATE INDEX idx_product_category ON product(category);
```

### step3-created-at-only.sql
```sql
-- Step 3: Single index on created_at only
CREATE INDEX idx_product_created_at ON product(created_at DESC);
```

### step4-composite.sql
```sql
-- Step 4: Composite index (category, created_at DESC) — optimal
CREATE INDEX idx_product_category_created ON product(category, created_at DESC);
```

### step5-reverse-composite.sql
```sql
-- Step 5: Reverse composite (created_at DESC, category) — experimental
-- Demonstrates why column order matters in composite indexes
CREATE INDEX idx_product_created_category ON product(created_at DESC, category);
```

### Step B Verification (human will verify)
```
- scripts/index/ has 6 files (step0~step5)
- step0 runs without errors from clean state (|| true handles missing indexes)
- step4 → SHOW INDEX FROM product shows idx_product_category_created
- step0 again → custom indexes all removed
- step0 → step2 → step0 → step3 → no index overlap
```

### Step B Commit
After verification passes, run `/commit` with staged files.

---

## Step C: EXPLAIN Measurement Automation

### scripts/measure/run-explain.sh

This script iterates through all 5 steps, applies each index, runs EXPLAIN, and saves results.

**Logic:**
```
For each step (1~5):
  1. Execute step0-drop-all.sql (reset — each DROP command with 2>/dev/null || true)
  2. Execute stepN SQL (apply index — with 2>/dev/null)
  3. Run EXPLAIN on the target query → save to results/explain/stepN-{name}.txt
  4. Run EXPLAIN FORMAT=JSON on the same query → save to results/explain/stepN-{name}.json
```

**Target query:**
```sql
SELECT * FROM product WHERE category = '전자기기' ORDER BY created_at DESC LIMIT 20
```

**Output files (10 total):**
```
results/explain/step1-no-index.txt
results/explain/step1-no-index.json
results/explain/step2-category-only.txt
results/explain/step2-category-only.json
results/explain/step3-created-at-only.txt
results/explain/step3-created-at-only.json
results/explain/step4-composite.txt
results/explain/step4-composite.json
results/explain/step5-reverse-composite.txt
results/explain/step5-reverse-composite.json
```

**Implementation notes:**
- For step0 execution: run each DROP INDEX statement individually with `2>/dev/null || true`, do NOT pipe the entire .sql file (individual error handling is needed).
- MySQL CLI command format: `mysql -h127.0.0.1 -P3306 -uroot -proot flashdeal`
- Suppress MySQL password warning with `2>/dev/null` on all commands.
- Create `results/explain/` directory with `mkdir -p`.
- After all steps complete, run step0 again to leave DB in clean state.
- Print summary showing which files were generated.

### results/explain/ directory
- Create `results/explain/.gitkeep` so the directory is tracked by git.

### .gitignore additions
```
# Measurement results (generated, not tracked)
results/explain/*.txt
results/explain/*.json
!results/explain/.gitkeep
```

### Step C Verification (human will verify)
```
- chmod +x scripts/measure/run-explain.sh
- run-explain.sh completes without errors
- results/explain/ has 10 files (5 txt + 5 json)
- step1-no-index.txt: type=ALL, Extra contains "Using filesort"
- step4-composite.txt: type=ref, no filesort
- step5-reverse-composite.txt: different result from step4
- git status shows no results files (gitignore works)
```

### Step C Commit
After verification passes, run `/commit` with staged files.

---

## Step D: k6 Load Test Script + Automation

### test/load/index-test.js

k6 script targeting the product list API.

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    vus: 10,
    duration: '30s',
};

export default function () {
    const res = http.get('http://localhost:8080/api/v1/products?category=전자기기&page=0&size=20');
    check(res, {
        'status is 200': (r) => r.status === 200,
    });
    sleep(0.1);
}
```

### scripts/measure/run-k6.sh

Automation script that runs k6 for each index step and saves results.

**Logic:**
```
For each step (1~5):
  1. Execute step0-drop-all.sql (reset — individual DROP with || true)
  2. Execute stepN SQL (apply index)
  3. Run: k6 run test/load/index-test.js --summary-export=results/k6/stepN-{name}.json
  4. Brief pause between steps
```

**Output files:**
```
results/k6/step1-no-index.json
results/k6/step2-category-only.json
results/k6/step3-created-at-only.json
results/k6/step4-composite.json
results/k6/step5-reverse-composite.json
```

**Implementation notes:**
- Create `results/k6/` directory with `mkdir -p`.
- Create `results/k6/.gitkeep`.
- Server must be running before this script executes. Add a health check at the start:
  ```bash
  curl -sf http://localhost:8080/api/poc/health > /dev/null || { echo "Server not running. Start with ./gradlew bootRun"; exit 1; }
  ```
- After all steps, run step0 to leave DB clean.
- Print summary of generated files.

### .gitignore additions (append to existing)
```
results/k6/*.json
!results/k6/.gitkeep
```

### Step D Verification (human will verify)
```
- k6 version works
- Server running + k6 run test/load/index-test.js completes
- k6 output shows http_req_duration and checks
- chmod +x scripts/measure/run-k6.sh
- run-k6.sh completes → results/k6/ has 5 json files
- step1 vs step4 json: http_req_duration shows measurable difference
```

### Step D Commit
After verification passes, run `/commit` with staged files.

---

## Final Directory Structure After Phase 3-1
```
scripts/
├── index/
│   ├── step0-drop-all.sql
│   ├── step1-no-index.sql
│   ├── step2-category-only.sql
│   ├── step3-created-at-only.sql
│   ├── step4-composite.sql
│   └── step5-reverse-composite.sql
└── measure/
    ├── run-explain.sh
    └── run-k6.sh

test/
└── load/
    └── index-test.js

results/
├── explain/
│   └── .gitkeep
└── k6/
    └── .gitkeep

src/main/java/com/project/
├── api/product/
│   ├── ProductController.java
│   └── dto/
│       └── ProductListResponse.java
└── service/product/
    └── ProductService.java
```

## Work Order
Complete steps sequentially. **Pause after each step** for human verification before committing.

1. **Step A**: API implementation (Controller, Service, DTO, Repository method)
   → Pause → Human verifies API → `/commit`
2. **Step B**: Index SQL scripts (6 files in scripts/index/)
   → Pause → Human verifies index create/drop → `/commit`
3. **Step C**: EXPLAIN automation (run-explain.sh + results directory + .gitignore)
   → Pause → Human runs script and verifies results → `/commit`
4. **Step D**: k6 scripts (index-test.js + run-k6.sh + results directory + .gitignore)
   → Pause → Human runs script and verifies results → `/commit`

After all steps committed, human will create PR manually or via `/pr`.

## Outside Claude Code Scope (human tasks)
- Run each verification manually
- Execute run-explain.sh and interpret EXPLAIN results (type, key, rows, Extra)
- Execute run-k6.sh and compare before/after metrics
- Write `docs/01-index-optimization.md` troubleshooting document
- Merge PR to main
