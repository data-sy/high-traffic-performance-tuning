# PoC 1: Redis Connection + RedisTemplate Verification

## Run
1. docker compose up -d
2. ./gradlew bootRun
3. curl http://localhost:8080/api/poc/redis/ping
4. curl http://localhost:8080/api/poc/redis/set-get
5. curl http://localhost:8080/api/poc/redis/info
6. curl http://localhost:8080/api/poc/redis/key-prefix-check

## Expected
- /ping → {"status": "PONG"}
- /set-get → {"key": "poc:test", "value": "test-value"}
- /info → {"version": "7.x.x", "used_memory": "xxxKB"}
- /key-prefix-check → {"keysChecked": ["poc:test"], "allValid": true}

## Key Naming Convention
- All keys MUST follow pattern: poc:*, ranking:*, or cache:*
- No keys without prefix allowed

## Cleanup
docker exec -i docker-redis-1 redis-cli DEL poc:test
