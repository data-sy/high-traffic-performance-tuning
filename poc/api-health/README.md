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
