# Phase 3-1 — 인덱스 최적화 구현

## 현재 상태
- Phase 1 (프로젝트 셋업), Phase 2 (도메인 엔티티 + 테스트 데이터) 완료
- Product 테이블에 10만 건 존재 (카테고리별 2만 건: 전자기기, 의류, 식품, 도서, 스포츠)
- `ddl-auto: validate` 활성 상태. 변경 금지
- Docker 컨테이너 (MySQL, Redis) 실행 중 (`docker compose up -d`)
- PoC 검증 완료. 도구 (MySQL CLI, EXPLAIN, k6) 정상 동작 확인됨

## 목표
카테고리 필터링 + 페이징 상품 목록 API를 구현하고, 인덱스 스크립트와 측정 자동화를 만들어 5가지 인덱스 전략(step1~step5)을 비교한다.

## 중요 규칙
- 먼저 `CLAUDE.md`를 읽고 프로젝트 컨벤션을 확인할 것
- v1~v4 별도 컨트롤러를 만들지 말 것. 이 시나리오는 **단일 컨트롤러 + 단일 서비스**를 사용한다. before/after 차이는 코드가 아닌 **인덱스 SQL 스크립트** 적용 여부로 발생한다
- 셸 스크립트에 `set -e`를 사용하지 말 것. 일부 명령어는 의도적으로 실패한다 (예: 존재하지 않는 인덱스에 DROP INDEX)
- MySQL 자격증명은 하드코딩: `root/root`, 데이터베이스 `flashdeal`, 호스트 `127.0.0.1`, 포트 `3306`. 파라미터화 금지
- 모든 `.sh` 파일은 실행 권한 필수 (`chmod +x`)

---

## Step A: API 구현

### 제한사항
- Product 엔티티를 어떤 식으로든 수정하지 말 것
- Controller나 Service에서 Product 엔티티를 직접 반환하지 말 것. 반드시 DTO를 사용할 것
- Controller → Service → Repository 호출 순서 필수. 단축 금지

### 1. ProductRepository — 쿼리 메서드 추가
- 위치: 프로젝트의 `domain/product` 패키지에 있는 기존 `ProductRepository.java`
- 메서드: `Page<Product> findByCategoryOrderByCreatedAtDesc(String category, Pageable pageable)`
- PoC 단계에서 이미 존재할 수 있음. 있으면 그대로 유지

### 2. ProductListResponse DTO
- 위치: 프로젝트 베이스 패키지 하위 `api/product/dto/ProductListResponse.java`
- 필드: `id`(Long), `name`(String), `price`(Integer), `category`(String), `createdAt`(LocalDateTime)
- **제외 필수**: `stock`, `salesCount` — 응답에 포함되면 안 됨
- 프로젝트 Lombok 컨벤션에 따라 `@Getter`와 생성자 (또는 `@Builder`) 사용
- 정적 팩토리 메서드 추가: `public static ProductListResponse from(Product product)`

### 3. ProductService
- 위치: 프로젝트 베이스 패키지 하위 `service/product/ProductService.java`
- 메서드: `public Page<ProductListResponse> getProducts(String category, Pageable pageable)`
- `productRepository.findByCategoryOrderByCreatedAtDesc(category, pageable)` 호출
- Product를 ProductListResponse로 팩토리 메서드를 사용해 변환
- `@Service`, `@RequiredArgsConstructor`, `@Transactional(readOnly = true)`

### 4. ProductController
- 위치: 프로젝트 베이스 패키지 하위 `api/product/ProductController.java`
- 엔드포인트: `GET /api/v1/products`
- 파라미터: `@RequestParam String category`, `@RequestParam(defaultValue = "0") int page`, `@RequestParam(defaultValue = "20") int size`
- Controller에서 `PageRequest.of(page, size)`를 생성하여 Service에 Pageable로 전달
- 반환: `Page<ProductListResponse>`
- `@RestController`, `@RequiredArgsConstructor`

### Step A 빌드 확인
- `./gradlew compileJava`로 컴파일 성공 여부 확인
- 애플리케이션 실행(`bootRun`)이나 테스트를 돌리지 말 것. 런타임 검증은 직접 수행

### Step A 검증 (직접 수행)
```
GET /api/v1/products?category=전자기기&page=0&size=20
→ 200 응답
→ JSON에 content, totalElements, totalPages 필드 존재
→ content 항목에 id, name, price, category, createdAt 존재
→ content 항목에 stock, salesCount 미포함
→ 존재하지 않는 카테고리 요청 시 빈 content로 200 응답
```

### Step A 커밋
검증 통과 후 `/commit`으로 커밋

---

## Step B: 인덱스 SQL 스크립트

### 위치: `scripts/index/`

SQL 파일 6개 생성. 각 스크립트는 독립 실행 가능하고 멱등해야 한다.

### step0-drop-all.sql
모든 커스텀 인덱스를 제거한다. 다른 step 적용 전 리셋용.
```sql
-- 모든 커스텀 인덱스 제거 (존재하지 않으면 에러 발생 — 의도된 동작)
-- MySQL은 DROP INDEX IF EXISTS를 지원하지 않으므로 개별 실행
-- run-explain.sh에서 || true로 에러를 처리함
DROP INDEX idx_product_category ON product;
DROP INDEX idx_product_created_at ON product;
DROP INDEX idx_product_category_created ON product;
DROP INDEX idx_product_created_category ON product;
```
**참고:** 인덱스가 없으면 실패한다. 이는 예상된 동작이다. 이 파일을 호출하는 셸 스크립트에서 `|| true`로 에러를 억제한다.

### step1-no-index.sql
```sql
-- Step 1: 인덱스 없음 (기준선)
-- 이 파일은 의도적으로 비어있다.
-- step0-drop-all.sql로 모든 인덱스를 제거한 상태가 기준선이다.
-- 다른 step과의 대칭성과 문서화를 위해 존재한다.
```

### step2-category-only.sql
```sql
-- Step 2: category 단일 인덱스
CREATE INDEX idx_product_category ON product(category);
```

### step3-created-at-only.sql
```sql
-- Step 3: created_at 단일 인덱스
CREATE INDEX idx_product_created_at ON product(created_at DESC);
```

### step4-composite.sql
```sql
-- Step 4: 복합 인덱스 (category, created_at DESC) — 최적 해법
CREATE INDEX idx_product_category_created ON product(category, created_at DESC);
```

### step5-reverse-composite.sql
```sql
-- Step 5: 역순 복합 인덱스 (created_at DESC, category) — 실험용
-- 복합 인덱스에서 컬럼 순서가 왜 중요한지 보여준다
CREATE INDEX idx_product_created_category ON product(created_at DESC, category);
```

### Step B 검증 (직접 수행)
```
- scripts/index/에 파일 6개 존재 (step0~step5)
- step0 실행 → 깨끗한 상태에서 에러 없이 완료 (|| true가 미존재 인덱스 에러 처리)
- step4 실행 → SHOW INDEX FROM product에서 idx_product_category_created 확인
- step0 재실행 → 커스텀 인덱스 전부 제거 확인
- step0 → step2 → step0 → step3 순서 실행 시 인덱스 겹침 없음
```

### Step B 커밋
검증 통과 후 `/commit`으로 커밋

---

## Step C: EXPLAIN 측정 자동화

### scripts/measure/run-explain.sh

5개 step을 순회하며 인덱스를 적용하고, EXPLAIN을 실행하여 결과를 저장하는 스크립트.

**로직:**
```
각 step (1~5)에 대해:
  1. step0-drop-all.sql 실행 (리셋 — 각 DROP 명령어를 2>/dev/null || true로 처리)
  2. stepN SQL 실행 (인덱스 적용 — 2>/dev/null)
  3. 대상 쿼리에 EXPLAIN 실행 → results/explain/stepN-{이름}.txt에 저장
  4. 같은 쿼리에 EXPLAIN FORMAT=JSON 실행 → results/explain/stepN-{이름}.json에 저장
```

**대상 쿼리:**
```sql
SELECT * FROM product WHERE category = '전자기기' ORDER BY created_at DESC LIMIT 20
```

**출력 파일 (총 10개):**
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

**구현 참고사항:**
- step0 실행 시: 각 DROP INDEX 문을 개별적으로 `2>/dev/null || true`와 함께 실행. 전체 .sql 파일을 파이프하지 말 것 (개별 에러 처리 필요)
- MySQL CLI 명령어 형식: `mysql -h127.0.0.1 -P3306 -uroot -proot flashdeal`
- 모든 명령어에 `2>/dev/null`로 MySQL 비밀번호 경고 억제
- `mkdir -p`로 `results/explain/` 디렉토리 생성
- 모든 step 완료 후 step0을 다시 실행하여 DB를 깨끗한 상태로 복원
- 생성된 파일 목록 요약 출력

### results/explain/ 디렉토리
- `results/explain/.gitkeep` 생성 (git에서 디렉토리를 추적하도록)

### .gitignore 추가 항목
```
# 측정 결과 (생성되는 파일, 추적하지 않음)
results/explain/*.txt
results/explain/*.json
!results/explain/.gitkeep
```

### Step C 검증 (직접 수행)
```
- chmod +x scripts/measure/run-explain.sh
- run-explain.sh 실행 → 에러 없이 완료
- results/explain/에 파일 10개 생성 (txt 5개 + json 5개)
- step1-no-index.txt: type=ALL, Extra에 "Using filesort" 포함
- step4-composite.txt: type=ref, filesort 없음
- step5-reverse-composite.txt: step4와 다른 결과
- git status에서 results 파일 미표시 (gitignore 동작 확인)
```

### Step C 커밋
검증 통과 후 `/commit`으로 커밋

---

## Step D: k6 부하 테스트 스크립트 + 자동화

### test/load/index-test.js

상품 목록 API를 대상으로 하는 k6 스크립트.

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

각 인덱스 step별로 k6를 실행하고 결과를 저장하는 자동화 스크립트.

**로직:**
```
각 step (1~5)에 대해:
  1. step0-drop-all.sql 실행 (리셋 — 개별 DROP에 || true)
  2. stepN SQL 실행 (인덱스 적용)
  3. 실행: k6 run test/load/index-test.js --summary-export=results/k6/stepN-{이름}.json
  4. step 간 짧은 대기
```

**출력 파일:**
```
results/k6/step1-no-index.json
results/k6/step2-category-only.json
results/k6/step3-created-at-only.json
results/k6/step4-composite.json
results/k6/step5-reverse-composite.json
```

**구현 참고사항:**
- `mkdir -p`로 `results/k6/` 디렉토리 생성
- `results/k6/.gitkeep` 생성
- 스크립트 실행 전 서버 구동 확인 필수. 시작 시 헬스 체크 추가:
  ```bash
  curl -sf http://localhost:8080/api/poc/health > /dev/null || { echo "서버 미실행. ./gradlew bootRun으로 시작하세요"; exit 1; }
  ```
- 모든 step 완료 후 step0 실행하여 DB를 깨끗한 상태로 복원
- 생성된 파일 목록 요약 출력

### .gitignore 추가 항목 (기존에 추가)
```
results/k6/*.json
!results/k6/.gitkeep
```

### Step D 검증 (직접 수행)
```
- k6 version 동작 확인
- 서버 실행 상태에서 k6 run test/load/index-test.js 완료
- k6 출력에 http_req_duration, checks 항목 표시
- chmod +x scripts/measure/run-k6.sh
- run-k6.sh 실행 → results/k6/에 json 5개 생성
- step1 vs step4 json: http_req_duration에서 측정 가능한 차이 존재
```

### Step D 커밋
검증 통과 후 `/commit`으로 커밋

---

## Phase 3-1 최종 디렉토리 구조
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

## 작업 순서
순차적으로 완료한다. **각 step 완료 후 반드시 정지**하여 직접 검증 후 커밋한다.

1. **Step A**: API 구현 (Controller, Service, DTO, Repository 메서드)
   → 정지 → 직접 API 검증 → `/commit`
2. **Step B**: 인덱스 SQL 스크립트 (scripts/index/에 6개 파일)
   → 정지 → 직접 인덱스 생성/삭제 검증 → `/commit`
3. **Step C**: EXPLAIN 자동화 (run-explain.sh + results 디렉토리 + .gitignore)
   → 정지 → 직접 스크립트 실행 및 결과 검증 → `/commit`
4. **Step D**: k6 스크립트 (index-test.js + run-k6.sh + results 디렉토리 + .gitignore)
   → 정지 → 직접 스크립트 실행 및 결과 검증 → `/commit`

모든 step 커밋 후 `/pr`로 PR 생성 또는 직접 생성.

## Claude Code 범위 밖 (직접 수행)
- 각 검증 직접 실행
- run-explain.sh 실행 후 EXPLAIN 결과 해석 (type, key, rows, Extra 분석)
- run-k6.sh 실행 후 before/after 지표 비교
- `docs/01-index-optimization.md` 트러블슈팅 문서 작성
- PR을 main에 머지
