# Phase 3-4 인수인계 — 비동기 발급 측정 환경

> 비동기 발급(v6a/b/c) 측정을 재현하는 데 필요한 엔지니어링 정보 — 핀 목록, 측정 환경(전용 격리 스택),
> 전이 트랩, teardown. 측정 수치 자체는 [`results/phase3-4-async/analysis.md`](../../../results/phase3-4-async/analysis.md).

## 1. 핀 목록 (통제변수 — ops 엔드포인트 런타임 assert로 강제, 전 버전 일치)

executor 4/4/10,000 / AbortPolicy(429) · 소비 동시성 4 · 큐 상한 10,000 · BRPOP 1s ·
XREADGROUP BLOCK 1s/COUNT 10 · XAUTOCLAIM 회수 주기 5s/min-idle 10s · graceful shutdown 10s ·
상태 TTL 3,600s · 수렴 게이트(깊이 0 && 행 수 3연속 동일, 1s 폴링, 타임아웃 120s) ·
부하(정확성 500VU×2iter / 관찰 warmup 5s + steady 30s) · 계승 핀(Hikari 10/30s, Tomcat 200,
innodb_lock_wait 50s, REPEATABLE-READ, total_qty 100, 유니크 userId `__VU*100000+__ITER`) ·
재전달 런 한정 total_qty 1,100(AFTER_COMMIT 발화 창 확보용 — 본문 §4.3).

## 2. 측정 환경 — 전용 격리 스택 (본체 인프라와 분리)

측정용 MySQL·Redis를 본체 인프라와 **별도 compose 프로젝트·볼륨·포트**로 띄워 격리한다 — 측정·reset이
본체 데이터에 닿지 않게.

```sh
docker compose -p phase3-4-async \
  -f docker/docker-compose.yml -f docker/docker-compose.phase3-4.yml up -d mysql redis
```

- 컨테이너 `phase3-4-async-mysql-1`/`-redis-1`, 볼륨 `phase3-4-async_mysql-data`, 포트 mysql **3307**·
  redis **6380**(본체 3306/6379와 비충돌). 오버라이드 파일이 포트를 `!override`로 **교체**한다(멀티 `-f`의
  ports는 기본 병합이라 override 없으면 본체 포트가 잔존).
- 측정 하네스(`scripts/measure/run-coupon-async.sh`)엔 `COMPOSE_PROJECT_NAME=phase3-4-async`를 env로 주입 —
  `docker exec`가 전용 컨테이너만 직격. 컨테이너 compose 라벨 assert로 본체(`docker`)를 잡으면 abort(본체 보호).
- 앱은 전용 스택을 보도록 오버라이드: `SPRING_DATASOURCE_URL`(mysql:3307) · `SPRING_DATA_REDIS_PORT`(6380).
  **`up`은 반드시 서비스명 `mysql redis` 명시**(무인자면 prometheus/grafana까지 띄워 본체 포트와 충돌).

## 3. 전이 트랩 (재현 시 점검 — 엔지니어링 함정)

| # | 함정 | 대응 |
|---|---|---|
| 1 | gradle-wrapper.jar gitignore — 새 체크아웃에 없음 | 본 레포엔 존재. 새 워크트리면 복사 |
| 2 | fresh-volume 스키마 0개 | 전용 볼륨은 비어 있으므로 앱 1회 `--spring.jpa.hibernate.ddl-auto=create`로 테이블 생성 후 측정 |
| 3 | **redis 연결 복수성** | 앱이 redis 클라이언트 2개(Redisson + StringRedisTemplate). 전용 스택 격리는 *둘 다* 전용 포트로 가야 완성 — `RedisConfig`가 `spring.data.redis`를 따르는지 확인(과거 무인자 LettuceConnectionFactory가 6379 하드코딩으로 누수). 측정 전 단건 검증: 전용 redis에 키 적재 && 본체 redis 앱 키 0건 |
| 4 | XLEN vs XINFO lag | v6c 깊이는 `XLEN`(XACK 후 XDEL 트림 전제). `XINFO GROUPS`의 lag는 XDEL tombstone 후 nil이 되어 과소집계 |
| 5 | XACK→XDEL 비원자 | 사이 크래시 시 acked-but-undeleted 잔류로 깊이 0 미도달(loud-fail) — 잔류 수동 XDEL 후 재실행 |
| 6 | AFTER_COMMIT 발화 창 | 발급 성공 경로 전용이라 qty 소진 후 도달 불가 — 재전달 런 한정 qty=1,100 |
| 7 | stale app 포트 점유 | 버전 전환 시 직전 앱이 8080 점유하면 새 앱 바인드 실패 → 재기동 전 기존 앱 kill |
| 8 | 측정→기록 전사 오류 | 기입 후 파일→표 기계 대조(python 재추출) |

## 4. Teardown

- 앱 JVM 정지(8080 free).
- `docker compose -p phase3-4-async … down` — **`-v` 미사용**(전용 볼륨 보존). 컨테이너·네트워크 제거,
  포트 3307/6380/8080 free.
- 본체 스택(`docker` 프로젝트, 3306/6379)은 무접촉.
