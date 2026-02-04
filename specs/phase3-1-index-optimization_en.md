# Phase 3-1 — Index Optimization (Product List Query)

## Goal
Implement category-based product list API. Prepare index SQL scripts and measurement automation to compare performance with/without indexes via EXPLAIN and k6. End state: 1 API + 6 index scripts + EXPLAIN measurement script + k6 load test script + k6 result export script ready.

## Context

### Target Query
```sql
SELECT * FROM product
WHERE category = ?
ORDER BY created_at DESC
LIMIT ?, ?;
```

### API Design Principle
Only **1 API endpoint** for this scenario. The query is identical regardless of index — only the DB index changes. Index switching is controlled by SQL scripts, NOT by separate controllers.

## Tasks

### 1. ProductRepository Query Method
- Location: `domain/product/ProductRepository.java`
- Add single method:
```java
Page<Product> findByCategoryOrderByCreatedAtDesc(String category, Pageable pageable);
```

### 2. ProductListResponse DTO
- Location: `api/product/dto/ProductListResponse.java`
- Fields: `id`(Long), `name`(String), `price`(Integer), `category`(String), `createdAt`(LocalDateTime)
- Exclude `stock`, `salesCount` — unnecessary for list view.
- Static factory: `public static ProductListResponse from(Product product)`

### 3. ProductService
- Location: `service/product/ProductService.java`
- Method: `Page<ProductListResponse> getProductsByCategory(String category, Pageable pageable)`
- Simple delegation: Repository call → DTO conversion → return. No business logic.

### 4. ProductController
- Location: `api/product/ProductController.java`
- Endpoint: `GET /api/v1/products`
- Params:
  - `category` — String, required, `@RequestParam`
  - `page` — int, default 0
  - `size` — int, default 20
- Response: `Page<ProductListResponse>`

### 5. Test Data
- Product 100K rows must already exist from Phase 2 `scripts/generate-test-data.sh`.
- 5 categories (전자기기, 의류, 식품, 도서, 스포츠) evenly distributed.
- Do NOT create additional data scripts. If data missing, run Phase 2 script first.

### 6. Index SQL Scripts
Location: `scripts/index/`

**step0-drop-all.sql** — Drop all custom indexes
```sql
DROP INDEX idx_product_category ON product;
DROP INDEX idx_product_created_at ON product;
DROP INDEX idx_product_category_created ON product;
DROP INDEX idx_product_created_category ON product;
```
- Handle non-existent index errors: use `2>/dev/null` or conditional check per statement.
- ALWAYS run step0 before applying any other step.

**step1-no-index.sql** — No index (explicit marker)
```sql
SELECT 'No custom index applied' AS status;
```

**step2-category-only.sql** — Single index on category
```sql
CREATE INDEX idx_product_category ON product(category);
```

**step3-created-at-only.sql** — Single index on created_at
```sql
CREATE INDEX idx_product_created_at ON product(created_at DESC);
```

**step4-composite.sql** — Composite index (category, created_at DESC)
```sql
CREATE INDEX idx_product_category_created ON product(category, created_at DESC);
```

**step5-reverse-composite.sql** — Reverse composite (created_at DESC, category)
```sql
CREATE INDEX idx_product_created_category ON product(created_at DESC, category);
```

### 7. Measurement Automation Script
Location: `scripts/measure/run-explain.sh`

**Behavior:**
1. Create `results/explain/` directory
2. For each step (1-5):
   - Run step0-drop-all.sql (reset indexes)
   - Run stepN index SQL
   - Execute `EXPLAIN SELECT * FROM product WHERE category = '전자기기' ORDER BY created_at DESC LIMIT 20;` → save to `results/explain/stepN-{name}.txt`
   - Execute `EXPLAIN FORMAT=JSON SELECT ...` → save to `results/explain/stepN-{name}.json`
3. Print completion message

**MySQL connection:**
```bash
MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_USER=root
MYSQL_PASSWORD=root
MYSQL_DATABASE=flashdeal
```

**Output structure:**
```
results/explain/
├── step1-no-index.txt
├── step1-no-index.json
├── step2-category-only.txt
├── step2-category-only.json
├── step3-created-at-only.txt
├── step3-created-at-only.json
├── step4-composite.txt
├── step4-composite.json
├── step5-reverse-composite.txt
└── step5-reverse-composite.json
```

**Notes:**
- Suppress errors from DROP INDEX on non-existent indexes.
- Set executable permission: `chmod +x scripts/measure/run-explain.sh`

### 8. Results Directory
- Create `results/explain/.gitkeep` to track directory in Git.
- Add to `.gitignore`: `results/explain/*.txt` and `results/explain/*.json`

### 9. k6 Load Test Script
Location: `test/load/index-test.js`

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    scenarios: {
        constant_load: {
            executor: 'constant-vus',
            vus: 10,
            duration: '30s',
        },
    },
};

const CATEGORIES = ['전자기기', '의류', '식품', '도서', '스포츠'];

export default function () {
    const category = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
    const page = Math.floor(Math.random() * 10);

    const res = http.get(
        `http://localhost:8080/api/v1/products?category=${encodeURIComponent(category)}&page=${page}&size=20`
    );

    check(res, {
        'status is 200': (r) => r.status === 200,
    });

    sleep(0.5);
}
```

### 10. k6 Result Export Script
Location: `scripts/measure/run-k6.sh`

Automates: step0 reset → apply stepN index → run k6 → save result JSON per step.

**Behavior:**
1. Create `results/k6/` directory
2. Health check server (wait for localhost:8080)
3. For each step (1-5):
   - Run step0-drop-all.sql (reset)
   - Run stepN index SQL
   - `k6 run test/load/index-test.js --summary-export=results/k6/stepN-{name}.json`
4. Print completion message

**Prerequisites:**
- Spring Boot server must be running (script checks this).
- k6 must be installed.

**Output:**
```
results/k6/
├── step1-no-index.json
├── step2-category-only.json
├── step3-created-at-only.json
├── step4-composite.json
└── step5-reverse-composite.json
```

**Notes:**
- Separate from run-explain.sh because this requires running server.
- Set executable: `chmod +x scripts/measure/run-k6.sh`

### 11. Results Directory
- Create `results/explain/.gitkeep` and `results/k6/.gitkeep`
- Add to `.gitignore`:
  - `results/explain/*.txt`
  - `results/explain/*.json`
  - `results/k6/*.json`

## Commit Plan

Commit after each step. Pause for user review before proceeding.

### Step A: API Implementation
Work:
- Create ProductListResponse DTO
- Add query method to ProductRepository
- Create ProductService
- Create ProductController

Review after commit:
- `./gradlew bootRun` then `GET /api/v1/products?category=전자기기&page=0&size=20` returns valid response

### Step B: Index SQL Scripts
Work:
- Create 6 files in `scripts/index/` (step0-step5)

Review after commit:
- 6 files exist
- Optional: manually run step0 → step4

### Step C: Measurement Automation + Results Directory
Work:
- Create `results/explain/.gitkeep`, `results/k6/.gitkeep`
- Add exclusion rules to `.gitignore`
- Create `scripts/measure/run-explain.sh`

Review after commit:
- `run-explain.sh` generates 10 files in `results/explain/`

### Step D: k6 Scripts
Work:
- Create `test/load/index-test.js`
- Create `scripts/measure/run-k6.sh`

Review after commit:
- With server running, `run-k6.sh` generates 5 files in `results/k6/`
- `ddl-auto` must be `validate` (set in Phase 2). If `create`, indexes are dropped on restart.
- ALWAYS reset with step0 before applying any index step. Overlapping indexes corrupt results.
- Server must be running before k6 execution.
- Product 100K rows required for meaningful EXPLAIN results. Run Phase 2 generate-test-data.sh if missing.

## Done Criteria
- [ ] `GET /api/v1/products?category=전자기기&page=0&size=20` returns valid response
- [ ] Response format: Spring Page JSON (content, totalElements, totalPages, etc.)
- [ ] `scripts/index/` contains 6 SQL files (step0-step5)
- [ ] `scripts/measure/run-explain.sh` generates 10 files in `results/explain/`
- [ ] `scripts/measure/run-k6.sh` generates 5 files in `results/k6/`
- [ ] `test/load/index-test.js` exists
- [ ] `.gitignore` excludes measurement result files
