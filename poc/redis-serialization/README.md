# PoC 3: Redis Serialization Type Safety Verification

## Run
1. ./gradlew bootRun
2. curl -X POST http://localhost:8080/api/poc/redis/serialization/save-dto
3. curl http://localhost:8080/api/poc/redis/serialization/load-dto
4. curl -X DELETE http://localhost:8080/api/poc/redis/serialization/cleanup

## Check
- save-dto response shows originalClass: TestRankingItem
- load-dto response shows:
  - typeMatch: true
  - productIdClass: java.lang.Long (NOT Integer)
  - salesCountClass: java.lang.Long
  - Values match original (100000001, 999999)

## Key Learning
- GenericJackson2JsonRedisSerializer preserves type metadata
- Prevents Long → Integer conversion issues
- Safe for DTO round-trip serialization
