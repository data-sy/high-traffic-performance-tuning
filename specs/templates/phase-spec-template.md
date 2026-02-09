# Phase [X-Y] — [기능명]

## Context
- Phase [X-Y-1] ([이전 작업]) 완료 상태
- [Entity/Table] has [N]K rows ([데이터 분포 설명])
- `ddl-auto: validate` is active. Do NOT change this.
- Docker containers (MySQL, Redis) are running via `docker compose up -d`.
- PoC verification is complete. Tools ([도구 목록]) are confirmed working.

## Goal
[한 문장으로 이 phase의 목표 설명]

## Important Rules
- Read `CLAUDE.md` first for project conventions.
- Do NOT [금지 사항 1].
- Do NOT [금지 사항 2].
- MySQL runs in Docker. Access via shell function: `run_mysql() { docker exec -i docker-mysql-1 mysql -uroot -proot flashdeal "$@"; }`. Define this at the top of each shell script. Do NOT use `mysql -h127.0.0.1` directly.
- All `.sh` files must have executable permission (`chmod +x`).
- [프로젝트별 추가 규칙]

---

## Step A: [첫 번째 작업명]

### Restrictions
- Do NOT modify [Entity] entity in any way.
- Do NOT [구체적 금지 사항].
- [Controller/Service/Repository] must follow [호출 순서]. No shortcuts.

### 1. [컴포넌트 A] — [작업 내용]
- Location: `[패키지 경로]/[파일명].java`
- Add/Modify: [구체적 변경 사항]
- Fields/Methods: [상세 명세]
- Annotations: [필요한 어노테이션]

<!--
예시 (Phase 3-1):
- Location: `domain/product/ProductRepository.java`
- Add method: `Page<Product> findByCategoryOrderByCreatedAtDesc(String category, Pageable pageable)`

예시 (Phase 3-2 - 랭킹):
- Location: `service/ranking/RankingService.java`
- Add method: `List<ProductRankingResponse> getTopProducts(int limit)`
  -->

### 2. [컴포넌트 B] — [작업 내용]
[동일한 형식으로 반복]

### Step A Build Check
- Run `./gradlew compileJava` to verify compilation.
- Do NOT run the application (`bootRun`) or tests. Human will verify runtime behavior.

### Step A Verification (human will verify)
```
[검증 시나리오 1]
→ [기대 결과]

[검증 시나리오 2]
→ [기대 결과]
```

### Step A Commit
After verification passes, run `/commit` with staged files.

---

## Step B: [두 번째 작업명]

[Step A와 동일한 구조로 반복]

---

## Step C: [세 번째 작업명]

[Step A와 동일한 구조로 반복]

---

## Step D: [네 번째 작업명]

[Step A와 동일한 구조로 반복]

---

## Final Directory Structure After Phase [X-Y]
```
[프로젝트 루트]/
├── scripts/
│   ├── [카테고리]/
│   │   ├── [파일1].sql
│   │   └── [파일2].sql
│   └── measure/
│       ├── [스크립트1].sh
│       └── [스크립트2].sh
├── test/
│   └── load/
│       └── [테스트파일].js
├── results/
│   ├── [결과디렉토리1]/
│   └── [결과디렉토리2]/
└── src/main/java/com/project/
    ├── api/[domain]/
    │   ├── [Controller].java
    │   └── dto/
    │       └── [DTO].java
    └── service/[domain]/
        └── [Service].java
```

## Work Order
Complete steps sequentially. **Pause after each step** for human verification before committing.

1. **Step A**: [작업 요약]
   → Pause → Human verifies [검증 대상] → `/commit`
2. **Step B**: [작업 요약]
   → Pause → Human verifies [검증 대상] → `/commit`
3. **Step C**: [작업 요약]
   → Pause → Human verifies [검증 대상] → `/commit`
4. **Step D**: [작업 요약]
   → Pause → Human verifies [검증 대상] → `/commit`

After all steps committed, human will create PR manually or via `/pr`.

## Outside Claude Code Scope (human tasks)
- Run each verification manually
- Execute [스크립트명] and interpret results
- Execute [스크립트명] and compare before/after metrics
- Write `docs/[XX]-[feature-name].md` troubleshooting document
- Merge PR to main
