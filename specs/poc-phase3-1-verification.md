# PoC — Phase 3-1 도구 검증

## 목표
Phase 3-1 본 작업에서 사용할 도구와 기능이 현재 환경에서 정상 동작하는지 최소 단위로 검증한다. 모든 PoC가 통과한 상태를 커밋하여, 본 작업에서 문제 발생 시 도구 문제인지 코드 문제인지 판별할 수 있는 기준점을 만든다.

## 전제 조건
- Phase 1, 2가 완료된 상태여야 한다.
- `docker compose up -d`로 MySQL, Redis가 기동 중이어야 한다.
- Phase 2의 `scripts/generate-test-data.sh`로 Product 10만 건이 생성되어 있어야 한다.

## 결과물 위치
모든 PoC 파일은 `poc/` 디렉토리에 기능별로 배치한다. 본 작업 코드(`src/`, `scripts/`, `test/`)와 섞이지 않도록 한다.

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

각 디렉토리의 README.md에 해당 PoC의 목적, 실행 방법, 예상 결과를 기록한다.

## PoC 항목

### PoC 1: Spring Boot API + DB 연결 + 페이징
**목적:** API 생성 → DB 조회 → 페이징 응답까지 전체 체인이 동작하는지 확인

**작업:**
- 위치: `poc/api-health/HealthCheckController.java`
- 실제 배치: `src/main/java/com/project/api/poc/HealthCheckController.java`
  - poc 디렉토리에는 코드 사본과 README를 두고, 실제 동작하는 코드는 src 안에 배치한다.
- 엔드포인트 3개:
  1. `GET /api/poc/health` → `{"status": "ok"}` 반환 (서버 기동 확인)
  2. `GET /api/poc/db-check` → Product 테이블에서 `SELECT COUNT(*)` 실행 → `{"count": 100000}` 반환 (DB 연결 확인)
  3. `GET /api/poc/paging-check` → `ProductRepository.findByCategoryOrderByCreatedAtDesc("전자기기", PageRequest.of(0, 5))` 실행 → Page JSON 반환 (페이징 동작 확인)
- paging-check 엔드포인트의 응답에서 확인할 것:
  - `content` 배열에 5개 항목
  - `totalElements` > 0
  - `totalPages` > 0
  - content 내 모든 항목의 category가 "전자기기"

**README.md 내용:**
```
# PoC 1: API + DB + 페이징 검증

## 실행 방법
1. docker compose up -d
2. ./gradlew bootRun
3. curl http://localhost:8080/api/poc/health
4. curl http://localhost:8080/api/poc/db-check
5. curl http://localhost:8080/api/poc/paging-check

## 예상 결과
- /health → {"status": "ok"}
- /db-check → {"count": 100000}
- /paging-check → Page JSON with 5 items, all category = "전자기기"
```

**주의:**
- 실제로 컴파일되고 실행되는 코드는 반드시 `src/main/java/com/project/api/poc/` 에 생성한다. `poc/api-health/`에는 참고용 사본과 README만 둔다. **src 쪽이 먼저이고, poc 사본은 없어도 무방하다.**
- HealthCheckController에서 ProductRepository를 주입받아 사용한다.
- `findByCategoryOrderByCreatedAtDesc` 메서드가 ProductRepository에 없으면 추가한다. 이 메서드는 본 작업(Phase 3-1)에서도 그대로 사용될 것이므로, PoC 이후에도 삭제하지 않는다.
- HealthCheckController는 메인 애플리케이션(`com.project`)의 컴포넌트 스캔 범위 안에 있어야 한다. 패키지 경로 `com.project.api.poc`는 스캔 범위 안이므로 그대로 사용한다.

### PoC 2: MySQL 인덱스 생성/삭제
**목적:** 셸 스크립트로 인덱스를 생성하고 삭제할 수 있는지, 에러 핸들링이 가능한지 확인

**작업:**
- 위치: `poc/mysql-index/test-index.sh`
- 실행 권한 부여: `chmod +x poc/mysql-index/test-index.sh`

**스크립트 내용:**
```bash
#!/bin/bash
# PoC 2: MySQL 인덱스 생성/삭제 검증
# 참고: MySQL CLI에서 -p 옵션으로 비밀번호를 전달하면
# "[Warning] Using a password on the command line interface can be insecure." 경고가 stderr에 출력된다.
# 이 경고는 정상이며, 2>/dev/null로 억제한다. 에러가 아니다.

MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_USER=root
MYSQL_PASSWORD=root
MYSQL_DATABASE=flashdeal

MYSQL_CMD="mysql -h${MYSQL_HOST} -P${MYSQL_PORT} -u${MYSQL_USER} -p${MYSQL_PASSWORD} ${MYSQL_DATABASE}"

echo "=== Test 1: 인덱스 생성 ==="
$MYSQL_CMD -e "CREATE INDEX idx_poc_test ON product(category);" 2>/dev/null
$MYSQL_CMD -e "SHOW INDEX FROM product WHERE Key_name = 'idx_poc_test';" 2>/dev/null
echo "(idx_poc_test가 보이면 성공)"

echo ""
echo "=== Test 2: 인덱스 삭제 ==="
$MYSQL_CMD -e "DROP INDEX idx_poc_test ON product;" 2>/dev/null
RESULT=$($MYSQL_CMD -e "SHOW INDEX FROM product WHERE Key_name = 'idx_poc_test';" 2>/dev/null)
if [ -z "$RESULT" ]; then
    echo "삭제 확인: idx_poc_test 없음 (성공)"
else
    echo "삭제 실패: idx_poc_test 아직 존재"
fi

echo ""
echo "=== Test 3: 존재하지 않는 인덱스 DROP (에러 확인) ==="
# 참고: "[Warning] Using a password on the command line interface can be insecure." 경고는 무시한다.
$MYSQL_CMD -e "DROP INDEX idx_poc_test ON product;" 2>&1
echo "(위에 에러 메시지가 출력되면 정상)"

echo ""
echo "=== Test 4: || true 로 에러 방지 ==="
$MYSQL_CMD -e "DROP INDEX idx_poc_test ON product;" 2>/dev/null || true
echo "에러 방지 성공 (스크립트 계속 실행됨)"

echo ""
echo "=== Test 5: 개별 명령 + || true 연속 실행 ==="
$MYSQL_CMD -e "DROP INDEX idx_not_exist_1 ON product;" 2>/dev/null || true
$MYSQL_CMD -e "DROP INDEX idx_not_exist_2 ON product;" 2>/dev/null || true
$MYSQL_CMD -e "CREATE INDEX idx_poc_test ON product(category);" 2>/dev/null
$MYSQL_CMD -e "SHOW INDEX FROM product WHERE Key_name = 'idx_poc_test';" 2>/dev/null
echo "(존재하지 않는 인덱스 DROP 2개 → CREATE 1개 → 정상 동작 확인)"

# 정리
$MYSQL_CMD -e "DROP INDEX idx_poc_test ON product;" 2>/dev/null || true

echo ""
echo "=== PoC 2 완료 ==="
```

**README.md 내용:**
```
# PoC 2: MySQL 인덱스 생성/삭제 검증

## 실행 방법
chmod +x poc/mysql-index/test-index.sh
./poc/mysql-index/test-index.sh

## 확인 포인트
- Test 1: CREATE INDEX 후 SHOW INDEX에 idx_poc_test 존재
- Test 2: DROP INDEX 후 idx_poc_test 제거 확인
- Test 3: 존재하지 않는 인덱스 DROP 시 에러 메시지 확인
- Test 4: || true 로 에러 발생해도 스크립트 중단 없음
- Test 5: 여러 DROP + CREATE 연속 실행 정상 동작

## 결론
run-explain.sh의 step0-drop-all.sql에서 에러 방지 전략:
→ 각 DROP 문을 개별 mysql 명령으로 실행하며 2>/dev/null || true 붙이기
```

### PoC 3: EXPLAIN 실행 + 파일 저장 + 셸 스크립트 연동
**목적:** 셸 스크립트에서 MySQL CLI로 EXPLAIN을 실행하고 결과를 파일로 저장할 수 있는지 확인

**작업:**
- 위치: `poc/mysql-explain/test-explain.sh`
- 실행 권한 부여: `chmod +x poc/mysql-explain/test-explain.sh`

**스크립트 내용:**
```bash
#!/bin/bash
# PoC 3: EXPLAIN 실행 + 파일 저장 검증
# 참고: MySQL CLI에서 -p 옵션으로 비밀번호를 전달하면
# "[Warning] Using a password on the command line interface can be insecure." 경고가 stderr에 출력된다.
# 이 경고는 정상이며, 2>/dev/null로 억제한다. 에러가 아니다.

MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_USER=root
MYSQL_PASSWORD=root
MYSQL_DATABASE=flashdeal

MYSQL_CMD="mysql -h${MYSQL_HOST} -P${MYSQL_PORT} -u${MYSQL_USER} -p${MYSQL_PASSWORD} ${MYSQL_DATABASE}"

RESULT_DIR="poc/mysql-explain/results"
mkdir -p "$RESULT_DIR"

QUERY="SELECT * FROM product WHERE category = '전자기기' ORDER BY created_at DESC LIMIT 20"

echo "=== Test 1: EXPLAIN 테이블 형태 저장 ==="
$MYSQL_CMD -e "EXPLAIN ${QUERY};" > "$RESULT_DIR/test-explain.txt" 2>/dev/null
echo "결과: $RESULT_DIR/test-explain.txt"
cat "$RESULT_DIR/test-explain.txt"

echo ""
echo "=== Test 2: EXPLAIN FORMAT=JSON 저장 ==="
$MYSQL_CMD -e "EXPLAIN FORMAT=JSON ${QUERY};" > "$RESULT_DIR/test-explain.json" 2>/dev/null
echo "결과: $RESULT_DIR/test-explain.json"
head -5 "$RESULT_DIR/test-explain.json"

echo ""
echo "=== Test 3: SQL 파일을 셸에서 실행 ==="
# 임시 SQL 파일 생성 후 실행
echo "CREATE INDEX idx_poc_explain ON product(category);" > "$RESULT_DIR/temp-index.sql"
$MYSQL_CMD < "$RESULT_DIR/temp-index.sql" 2>/dev/null
$MYSQL_CMD -e "EXPLAIN ${QUERY};" > "$RESULT_DIR/test-with-index.txt" 2>/dev/null
echo "인덱스 적용 후 결과:"
cat "$RESULT_DIR/test-with-index.txt"

# 정리
$MYSQL_CMD -e "DROP INDEX idx_poc_explain ON product;" 2>/dev/null
rm -f "$RESULT_DIR/temp-index.sql"

echo ""
echo "=== Test 4: MySQL 접속 실패 시 에러 ==="
mysql -hlocalhost -P9999 -uroot -pwrong testdb -e "SELECT 1;" 2>&1 | head -1
echo "(위 에러 메시지가 출력되면 정상)"

echo ""
echo "=== PoC 3 완료 ==="
echo "생성된 파일:"
ls -la "$RESULT_DIR/"
```

**README.md 내용:**
```
# PoC 3: EXPLAIN + 파일 저장 + 셸 스크립트 검증

## 실행 방법
chmod +x poc/mysql-explain/test-explain.sh
./poc/mysql-explain/test-explain.sh

## 확인 포인트
- Test 1: txt 파일에 EXPLAIN 테이블 형태 결과 저장됨
- Test 2: json 파일에 EXPLAIN FORMAT=JSON 결과 저장됨
- Test 3: SQL 파일을 셸에서 실행 → 인덱스 적용 후 EXPLAIN 결과 변화 확인
- Test 4: 접속 실패 시 에러 메시지 정상 출력

## 결과 파일
poc/mysql-explain/results/ 에 생성됨
```

**주의:**
- `poc/mysql-explain/results/` 디렉토리는 스크립트가 자동 생성한다.
- 이 디렉토리를 `.gitignore`에 추가한다: `poc/mysql-explain/results/`

### PoC 4: k6 설치 확인 + 실행 + summary-export
**목적:** k6가 설치되어 있고, 서버에 요청을 보내고, 결과를 JSON으로 저장할 수 있는지 확인

**작업:**
- 위치: `poc/k6-basic/test-k6.js`

**k6 스크립트:**
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

**README.md 내용:**
```
# PoC 4: k6 기본 동작 검증

## 사전 확인
k6 version
(미설치 시: https://grafana.com/docs/k6/latest/set-up/install-k6/)

## 실행 방법
1. 서버 기동: ./gradlew bootRun
2. 기본 실행: k6 run poc/k6-basic/test-k6.js
3. JSON 저장: k6 run poc/k6-basic/test-k6.js --summary-export=poc/k6-basic/test-result.json

## 확인 포인트
- k6 실행 완료, checks 3/3 passed
- http_req_duration 출력
- test-result.json 파일 생성
- JSON 안에 metrics.http_req_duration 필드 존재
```

**주의:**
- `poc/k6-basic/test-result.json`을 `.gitignore`에 추가한다.
- k6가 미설치인 경우 README에 설치 방법 링크를 제공한다.

## .gitignore 추가 규칙
이번 PoC에서 `.gitignore`에 아래 규칙을 추가한다:
```
# PoC 결과 파일
poc/mysql-explain/results/
poc/k6-basic/test-result.json
```

## 전체 주의사항
- 모든 셸 스크립트에 `set -e`를 사용하지 않는다. PoC 2, 3은 의도적으로 에러를 허용하는 검증이므로, `set -e`가 있으면 테스트가 중단된다.
- MySQL 접속 정보(root/root/flashdeal)는 로컬 docker-compose 기본값이다. 이 단계에서 환경변수화하거나 추상화하지 않는다. 하드코딩이 의도이다.

## 작업 순서
모든 PoC를 순서대로 작업한 뒤, 한 번에 커밋한다.

1. `poc/` 디렉토리 구조 생성
2. PoC 1: HealthCheckController + README 작성
3. PoC 2: test-index.sh + README 작성
4. PoC 3: test-explain.sh + README 작성
5. PoC 4: test-k6.js + README 작성
6. `.gitignore` 업데이트
7. 모든 파일 작성 완료 후 사용자에게 점검 요청

사용자가 각 PoC를 직접 실행하여 통과를 확인한 뒤 커밋한다.

## 완료 조건

### Claude Code 완료 기준 (파일 존재)
- [ ] `src/main/java/com/project/api/poc/HealthCheckController.java` 존재
- [ ] `poc/api-health/HealthCheckController.java` (사본) + `README.md` 존재
- [ ] `poc/mysql-index/test-index.sh` + `README.md` 존재
- [ ] `poc/mysql-explain/test-explain.sh` + `README.md` 존재
- [ ] `poc/k6-basic/test-k6.js` + `README.md` 존재
- [ ] ProductRepository에 `findByCategoryOrderByCreatedAtDesc` 메서드 존재
- [ ] `.gitignore`에 PoC 결과 파일 제외 규칙 추가됨
- [ ] 모든 .sh 파일에 실행 권한 부여됨

### 사용자 검증 기준 (실행 확인)
- [ ] `GET /api/poc/health` → `{"status": "ok"}`
- [ ] `GET /api/poc/db-check` → `{"count": 100000}` (또는 실제 데이터 수)
- [ ] `GET /api/poc/paging-check` → Page JSON 정상 응답, category 필터 동작
- [ ] `./poc/mysql-index/test-index.sh` → 인덱스 CREATE/DROP/에러방지 정상 동작
- [ ] `./poc/mysql-explain/test-explain.sh` → results/ 에 파일 3개 생성, EXPLAIN 결과 확인
- [ ] `k6 version` 출력
- [ ] `k6 run poc/k6-basic/test-k6.js` 정상 완료
- [ ] `--summary-export` 옵션으로 JSON 파일 생성 확인
