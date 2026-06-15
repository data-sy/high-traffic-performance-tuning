# 무효 런 기록 (config 버그로 폐기 — 삭제 금지)

> 측정 중 발생한 무효·폐기 런을 보존 기록한다. 성공 런이 동일 경로를 덮어쓴 경우 여기에 콘솔 증거를 남긴다.
>
> **운영 절차**: 동일 경로 런을 재실행하기 전, 직전 런이 FAIL/무효였다면 그 raw json(e2e/k6/depth)을
> `*-INVALID-NNN.json` 등으로 백업한 뒤 재실행한다. (카오스 런은 파일명이 normal과 달라 상호
> 비덮어쓰기지만, 같은 카오스 런 재시도 시 적용.)

## INVALID-001 — v6a accuracy (redis-config 격리 누수)

- **증상**: 수렴·행 수는 정상(rows 100/100/100)인데 터미널 회계 FAIL —
  ```
  202/429/other : 1000/0/0
  상태 분포      : {}            ← 전용 redis(6380) 스캔 결과 0건
  terminal == 202 ? : 0 vs 1000  (유실분 1000)
  판정           : FAIL  사유: 터미널 회계 불일치 terminal=0 != 202=1000
  ```
- **근본 원인**: `RedisConfig.redisConnectionFactory()`의 무인자 `new LettuceConnectionFactory()`가
  host/port를 localhost:6379로 하드코딩·`spring.data.redis.*` 무시 → 앱 `StringRedisTemplate`(상태 해시)이
  **본체 redis(6379)** 에 1,003건 기록. 전용 redis(6380)를 스캔한 회계는 0건 → FAIL.
  Redisson은 spring.data.redis를 따라 6380 정상이라, redis 연결 2개 중 하나만 누수.
- **왜 격리 검증이 놓쳤나**: docker-exec 경로 + Redisson 연결만 확인했고 앱의 두 번째 redis 클라이언트
  (Spring Data Redis/Lettuce)를 미확인. 대체포트(6380) 토폴로지에서만 드러나는 잠복 버그.
- **조치**: `RedisConfig` 수정(spring.data.redis 준수) + 본체 redis coupon:issue:* 1,003건 네임스페이스
  한정 DEL 복원(본체 mysql 무접촉, 본체 redis 0건). 재측정 → PASS (analysis.md v6a 표).
- **raw 보존 한계**: 1차 런의 e2e/k6/depth json은 하네스 고정 경로라 성공 재측정이 덮어씀(run-ID 접미
  핀 미구현). 위 콘솔 증거가 보존본. → 교훈: 격리 검증은 앱의 모든 외부 연결을 전수 확인해야 하고,
  실패 런 raw 보존은 run-ID 접미 핀으로 정식화할 후보.
