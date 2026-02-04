# Phase 3-1 — 인덱스 최적화 (상품 목록 조회)

## 목표
카테고리별 상품 목록 조회 API를 구현하고, 인덱스 유무에 따른 성능 차이를 EXPLAIN과 k6로 측정할 수 있는 환경을 구성한다. 최종 상태: API 1개 + 인덱스 SQL 스크립트 6개 + EXPLAIN 측정 자동화 스크립트 + k6 부하 테스트 스크립트 + k6 결과 저장 스크립트 준비 완료.

## 배경

### 트러블슈팅 스토리 (참고용, 구현에는 영향 없음)
이 Phase의 목적은 아래 트러블슈팅을 재현하고 검증하는 것이다:
- **문제**: `SELECT * FROM product WHERE category = ? ORDER BY created_at DESC LIMIT 20` 쿼리가 인덱스 없이 Full Table Scan + filesort 발생
- **해결**: `(category, created_at DESC)` 복합 인덱스 적용으로 93% 개선
- **추가 실험**: 단일 인덱스, 역순 복합 인덱스도 EXPLAIN으로 비교

### API 설계 원칙
인덱스 시나리오는 **API가 1개**이다. 쿼리 자체는 동일하고 DB 인덱스만 바뀌므로, 컨트롤러를 분리하지 않는다. 인덱스 교체는 SQL 스크립트로 제어한다.

## 대상 쿼리
```sql
SELECT * FROM product
WHERE category = ?
ORDER BY created_at DESC
LIMIT ?, ?;
```

## 작업 항목

### 1. ProductRepository 조회 메서드
- 위치: `domain/product/ProductRepository.java`
- Spring Data JPA 메서드:
```java
Page<Product> findByCategoryOrderByCreatedAtDesc(String category, Pageable pageable);
```
- 추가 메서드 없이 이것 하나만 추가한다.

### 2. ProductListResponse DTO
- 위치: `api/product/dto/ProductListResponse.java`
- 필드: `id`(Long), `name`(String), `price`(Integer), `category`(String), `createdAt`(LocalDateTime)
- `stock`, `salesCount`는 목록 조회에서 불필요하므로 제외한다.
- 정적 팩토리 메서드: `public static ProductListResponse from(Product product)`

### 3. ProductService
- 위치: `service/product/ProductService.java`
- 메서드: `Page<ProductListResponse> getProductsByCategory(String category, Pageable pageable)`
- Repository 호출 → DTO 변환 → 반환. 별도 비즈니스 로직 없음.

### 4. ProductController
- 위치: `api/product/ProductController.java`
- 엔드포인트: `GET /api/v1/products`
- 파라미터:
  - `category` — String, 필수, `@RequestParam`
  - `page` — int, 기본값 0
  - `size` — int, 기본값 20
- 응답: `Page<ProductListResponse>` (Spring Data의 기본 페이징 응답 형태)

### 5. 테스트 데이터 확인
- Phase 2의 `scripts/generate-test-data.sh`로 이미 Product 10만 건이 생성되어 있어야 한다.
- category 5종 (전자기기, 의류, 식품, 도서, 스포츠) 균등 분배 확인.
- **별도 데이터 생성 스크립트를 추가하지 않는다.** 기존 데이터가 없으면 Phase 2 스크립트를 먼저 실행한다.

### 6. 인덱스 SQL 스크립트
위치: `scripts/index/`

**step0-drop-all.sql** — 모든 커스텀 인덱스 제거
```sql
-- product 테이블의 커스텀 인덱스만 제거 (PK 인덱스는 건드리지 않음)
DROP INDEX idx_product_category ON product;
DROP INDEX idx_product_created_at ON product;
DROP INDEX idx_product_category_created ON product;
DROP INDEX idx_product_created_category ON product;
```
- 존재하지 않는 인덱스 DROP 시 에러 방지: 각 DROP 문 앞에 존재 여부 체크 또는 개별 실행 가능하도록 구성한다.
- 실행 순서상 항상 step0을 먼저 실행한 뒤 다른 step을 적용한다.

**step1-no-index.sql** — 인덱스 없음 (명시용)
```sql
-- 인덱스 없음 상태. step0-drop-all.sql 실행과 동일.
-- 이 파일은 실험 흐름의 명시성을 위해 존재한다.
SELECT 'No custom index applied' AS status;
```

**step2-category-only.sql** — category 단일 인덱스
```sql
CREATE INDEX idx_product_category ON product(category);
```

**step3-created-at-only.sql** — created_at 단일 인덱스
```sql
CREATE INDEX idx_product_created_at ON product(created_at DESC);
```

**step4-composite.sql** — (category, created_at DESC) 복합 인덱스
```sql
CREATE INDEX idx_product_category_created ON product(category, created_at DESC);
```

**step5-reverse-composite.sql** — (created_at, category) 역순 복합 인덱스 (실험용)
```sql
CREATE INDEX idx_product_created_category ON product(created_at DESC, category);
```

### 7. 측정 자동화 스크립트
위치: `scripts/measure/run-explain.sh`

이 스크립트는 각 인덱스 단계별로 step0 초기화 → 인덱스 적용 → EXPLAIN 실행 → 결과 저장을 자동화한다.

**동작:**
1. `results/explain/` 디렉토리 생성
2. 각 step(1~5)에 대해:
   - step0-drop-all.sql 실행 (인덱스 초기화)
   - stepN 인덱스 SQL 실행
   - `EXPLAIN SELECT * FROM product WHERE category = '전자기기' ORDER BY created_at DESC LIMIT 20;` 실행 → `results/explain/stepN-{name}.txt` 저장
   - `EXPLAIN FORMAT=JSON SELECT * FROM product WHERE category = '전자기기' ORDER BY created_at DESC LIMIT 20;` 실행 → `results/explain/stepN-{name}.json` 저장
3. 완료 메시지 출력

**MySQL 접속 정보:**
```bash
MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_USER=root
MYSQL_PASSWORD=root
MYSQL_DATABASE=flashdeal
```

**결과 파일 구조:**
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

**주의:**
- step0의 DROP INDEX에서 인덱스가 존재하지 않으면 에러가 발생한다. `2>/dev/null` 또는 조건부 DROP으로 처리한다.
- 스크립트에 실행 권한을 부여한다: `chmod +x scripts/measure/run-explain.sh`

### 8. results 디렉토리
- `results/explain/.gitkeep` 파일을 생성하여 디렉토리를 Git에 포함시킨다.
- `.gitignore`에 `results/explain/*.txt`와 `results/explain/*.json`을 추가하여 실제 측정 결과는 커밋하지 않는다.

### 9. k6 부하 테스트 스크립트
위치: `test/load/index-test.js`

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

### 10. k6 결과 저장 스크립트
위치: `scripts/measure/run-k6.sh`

이 스크립트는 각 인덱스 단계별로 step0 초기화 → 인덱스 적용 → k6 실행 → 결과 JSON 저장을 자동화한다.

**동작:**
1. `results/k6/` 디렉토리 생성
2. 서버 헬스체크 (localhost:8080 응답 대기)
3. 각 step(1~5)에 대해:
   - step0-drop-all.sql 실행 (인덱스 초기화)
   - stepN 인덱스 SQL 실행
   - `k6 run test/load/index-test.js --summary-export=results/k6/stepN-{name}.json` 실행
4. 완료 메시지 출력

**전제 조건:**
- Spring Boot 서버가 기동 중이어야 한다 (스크립트가 확인함).
- k6가 설치되어 있어야 한다.

**결과 파일 구조:**
```
results/k6/
├── step1-no-index.json
├── step2-category-only.json
├── step3-created-at-only.json
├── step4-composite.json
└── step5-reverse-composite.json
```

**주의:**
- run-explain.sh와 달리 서버가 떠있어야 하므로 별도 스크립트로 분리한다.
- 스크립트에 실행 권한을 부여한다: `chmod +x scripts/measure/run-k6.sh`

### 11. results 디렉토리
- `results/explain/.gitkeep` 파일을 생성하여 디렉토리를 Git에 포함시킨다.
- `results/k6/.gitkeep` 파일을 생성하여 디렉토리를 Git에 포함시킨다.
- `.gitignore`에 아래 규칙을 추가하여 실제 측정 결과는 커밋하지 않는다:
  - `results/explain/*.txt`
  - `results/explain/*.json`
  - `results/k6/*.json`

## 작업 순서 및 커밋 계획

각 단계 완료 후 커밋하고, 사용자에게 점검을 요청한다.

### Step A: 기능 구현
작업:
- ProductListResponse DTO 생성
- ProductRepository에 조회 메서드 추가
- ProductService 생성
- ProductController 생성

커밋 후 점검:
- `./gradlew bootRun` 후 `GET /api/v1/products?category=전자기기&page=0&size=20` 정상 응답 확인

### Step B: 인덱스 SQL 스크립트
작업:
- `scripts/index/` 에 step0~step5 총 6개 파일 생성

커밋 후 점검:
- 파일 6개 존재 확인
- step0 → step4 순서로 수동 실행 테스트 (선택)

### Step C: 측정 자동화 + results 디렉토리
작업:
- `results/explain/.gitkeep`, `results/k6/.gitkeep` 생성
- `.gitignore`에 측정 결과 제외 규칙 추가
- `scripts/measure/run-explain.sh` 생성

커밋 후 점검:
- `run-explain.sh` 실행 시 `results/explain/` 에 10개 파일 생성 확인

### Step D: k6 스크립트
작업:
- `test/load/index-test.js` 생성
- `scripts/measure/run-k6.sh` 생성

커밋 후 점검:
- 서버 기동 상태에서 `run-k6.sh` 실행 시 `results/k6/` 에 5개 파일 생성 확인
- Phase 2에서 `ddl-auto: validate`로 변경되어 있어야 한다. `create`이면 서버 재시작 시 인덱스가 사라진다.
- 인덱스 실험 시 반드시 step0으로 초기화한 뒤 원하는 step만 적용한다. 인덱스가 중복 적용되면 결과가 오염된다.
- k6 실행 전 서버가 기동 중이어야 한다.
- Product 10만 건 데이터가 없으면 EXPLAIN 결과가 의미 없다. Phase 2의 generate-test-data.sh를 먼저 실행한다.

## 완료 조건
- [ ] `GET /api/v1/products?category=전자기기&page=0&size=20` 정상 응답
- [ ] 응답 형태: Page JSON (content, totalElements, totalPages 등 포함)
- [ ] `scripts/index/` 에 step0~step5 SQL 파일 6개 존재
- [ ] `scripts/measure/run-explain.sh` 실행 시 `results/explain/` 에 10개 파일 생성
- [ ] `scripts/measure/run-k6.sh` 실행 시 `results/k6/` 에 5개 파일 생성
- [ ] `test/load/index-test.js` k6 스크립트 존재
- [ ] `.gitignore`에 results 측정 파일 제외 규칙 추가
