# Phase [X-Y] — [기능명] 인간 체크리스트

> Branch: `feature/phase[X-Y]-[feature-name]`

## ── 0. 사전 준비 ──

- [ ] 브랜치 생성
```bash
      git checkout -b feature/phase[X-Y]-[feature-name]
```

- [ ] Spec 파일 준비
```bash
      # specs/ 디렉토리에 phase[X-Y]-[feature-name]_en.md 배치
```

- [ ] 환경 확인
```bash
      # 필수: Docker 컨테이너 기동
      docker compose up -d
      
      # 선택: [필요한 경우만 추가]
      # 예: MySQL 데이터 확인 (Phase 3-1)
      docker exec -it docker-mysql-1 mysql -u root -proot flashdeal -e "SELECT COUNT(*) FROM product;"
      
      # 예: Redis 연결 확인 (Phase 3-2)
      docker exec -it docker-redis-1 redis-cli PING
```

---

## ── 1. Step A: [작업명] ──

### Claude Code에 전달:
```
@phase[X-Y]-[feature-name]_en.md
Implement **Phase [X-Y]: [기능명]** based on the spec above.

**[Global Rules]**
1. Package names: Use actual project structure
2. Sequential execution: Stop after each Step

---

**Start with Step A: [작업 요약].**

[구체적 지시사항]

**After completion, verify compilation with `./gradlew compileJava` only.**
```

- [ ] Claude Code 작업 완료 대기
  💡 예상 소요시간: [X-Y]분

---

### 검증 블록 (상황에 맞게 선택 사용)

#### 🔹 파일 존재 확인 (거의 모든 Phase 공통)
```bash
# [컴포넌트 A] 생성 확인
find src -name "[FileName].java" -type f
# 기대값: src/main/java/.../[FileName].java

# 예: Service 파일 확인
find src -name "RankingService.java" -type f
```

- [ ] [FileName].java 존재

#### 🔹 엔티티 미수정 확인 (엔티티 변경 금지 시)
```bash
git diff src/main/java/**/domain/[entity]/[Entity].java
# 기대값: 변경 없음
```

- [ ] [Entity] 엔티티 변경 없음

#### 🔹 특정 코드 패턴 확인 (DTO 필드 검증 등)
```bash
# 예: DTO에 stock/salesCount 없는지 확인
grep -E "stock|salesCount" $(find src -name "ProductListResponse.java")
# 기대값: 결과 없음

# 예: Redis 관련 어노테이션 확인
grep -E "@Cacheable|@RedisHash" $(find src -name "RankingService.java")
# 기대값: @Cacheable 존재
```

- [ ] [조건] 확인됨

#### 🔹 SQL 스크립트 검증 (Phase 3-1 전용)
```bash
# 인덱스 스크립트 파일 존재 확인
ls scripts/index/
# 기대값: step0-drop-all.sql ~ step5-reverse-composite.sql

# step4 적용 후 인덱스 확인
docker exec -i docker-mysql-1 mysql -uroot -proot flashdeal < scripts/index/step4-composite.sql
docker exec -i docker-mysql-1 mysql -uroot -proot flashdeal -e "SHOW INDEX FROM product WHERE Key_name = 'idx_product_category_created';"
# 기대값: idx_product_category_created 존재
```

- [ ] SQL 스크립트 6개 존재
- [ ] 인덱스 생성/삭제 정상 동작

#### 🔹 Redis 동작 확인 (Phase 3-2 전용)
```bash
# Redis에 랭킹 데이터 삽입 테스트
docker exec -i docker-redis-1 redis-cli ZADD test:ranking 100 "product:1" 200 "product:2"
docker exec -i docker-redis-1 redis-cli ZREVRANGE test:ranking 0 -1 WITHSCORES
# 기대값: product:2 200, product:1 100 (내림차순)

# 클린업
docker exec -i docker-redis-1 redis-cli DEL test:ranking
```

- [ ] Sorted Set 정렬 정상 동작

#### 🔹 쿼리 로그 확인 (Phase 3-3 N+1 전용)
```bash
# application.yml에 쿼리 로깅 설정 확인
grep "show-sql: true" src/main/resources/application.yml
grep "format_sql: true" src/main/resources/application.yml

# 서버 기동 후 콘솔에서 쿼리 개수 확인
# (이건 런타임 검증에서 진행)
```

- [ ] 쿼리 로깅 설정 확인

---

### 런타임 검증 (상황에 맞게 선택 사용)

#### 🔹 API 응답 검증 (거의 모든 Phase 공통)
```bash
# 서버 기동
./gradlew bootRun

# 터미널 새 탭에서:

# 1. [검증 시나리오 1] - 정상 케이스
curl -s [URL] | python3 -m json.tool | head -30
# 기대값: [응답 구조]

# 예: 상품 목록 조회 (Phase 3-1)
curl -s -G "http://localhost:8080/api/v1/products" \
  --data-urlencode "category=전자기기" -d "page=0" -d "size=20" \
  | python3 -m json.tool | head -30
# 기대값: content 배열 20개, totalElements 존재

# 예: 랭킹 조회 (Phase 3-2)
curl -s "http://localhost:8080/api/v1/ranking/products?limit=10" \
  | python3 -m json.tool
# 기대값: 10개 상품, salesCount 내림차순

# 서버 종료
lsof -t -i:8080 | xargs kill -9 || true
```

- [ ] [검증 조건 1] 확인
- [ ] [검증 조건 2] 확인

#### 🔹 쿼리 개수 확인 (Phase 3-3 N+1 전용)
```bash
# 서버 콘솔에서 직접 확인
# Before (N+1): "Hibernate: select ..." 23회
# After (Fetch Join): "Hibernate: select ..." 1회

# 또는 로그 파싱
tail -f logs/application.log | grep "Hibernate: select" | wc -l
```

- [ ] 쿼리 23회 → 1회 확인

#### 🔹 동시성 테스트 (Phase 3-4 동시성 전용)
```bash
# JMeter 또는 k6 스크립트 실행
k6 run test/load/concurrency-test.js

# 발급된 쿠폰 개수 확인
docker exec -it docker-mysql-1 mysql -u root -proot flashdeal \
  -e "SELECT COUNT(*) FROM coupon WHERE issued = true;"
# 기대값: 정확히 100

# Redis에서 확인
docker exec -it docker-redis-1 redis-cli GET coupon:remaining
# 기대값: 0
```

- [ ] 쿠폰 정확히 100개 발급
- [ ] 초과 발급 없음

---

### 커밋:
- [ ] `/commit`

---

## ── 2. Step B: [작업명] ──

[Step A와 동일한 구조]

---

## ── 마무리 ──

- [ ] 전체 git log 확인
```bash
      git log --oneline
```
      - Step A 커밋
      - Step B 커밋
      - Step C 커밋
      - Step D 커밋

- [ ] PR 생성
```bash
      /pr
```

- [ ] GitHub에서 PR 확인 및 머지

---

## ── 이후 직접 수행 (Claude Code 범위 밖) ──

### Phase 3-1 (인덱스 최적화) 예시:
- [ ] EXPLAIN 결과 해석 (type, key, rows, Extra)
- [ ] k6 결과 before/after 비교
- [ ] `docs/01-index-optimization.md` 작성

### Phase 3-2 (랭킹) 예시:
- [ ] Redis String vs Sorted Set 성능 비교
- [ ] `docs/02-ranking.md` 작성

### Phase 3-3 (N+1) 예시:
- [ ] Fetch Join vs BatchSize 쿼리 개수 비교
- [ ] `docs/03-nplusone.md` 작성

### Phase 3-4 (동시성) 예시:
- [ ] 락 전략별 트레이드오프 분석
- [ ] `docs/04-concurrency.md` 작성
