# Grafana 프로비저닝-as-code (Phase 3-5)

`docker compose -f docker/docker-compose.yml up -d` 만으로 datasource·대시보드가 **수동 클릭 없이** 자동 로드된다. 수동 UI 설정은 `grafana-storage` 볼륨에만 살아 재현 불가였던 것을 repo 산출물로 못박은 결과.

## 구조
```
docker/grafana/
├── provisioning/
│   ├── datasources/datasource.yml   Prometheus (uid=prometheus, url=http://prometheus:9090, proxy)
│   └── dashboards/dashboards.yml     file provider → /etc/grafana/dashboards (FlashDeal 폴더)
└── dashboards/
    ├── index.json        ① 인덱스      — 상품목록 v1~v4 throughput / avg / max (actuator uri 라벨)
    ├── ranking.json      ③ 랭킹        — DB ORDER BY vs Redis v1~v4 (actuator uri 라벨)
    ├── concurrency.json  ④ 동시성      — HikariCP 풀, 503 분해, outcome별 지연, 성공 누적 (actuator + k6)
    └── async.json        비동기 발급   — 접수 지연, 백프레셔(429), 큐 깊이 (k6 + executor 게이지)
```
- compose의 grafana 서비스가 `./grafana/provisioning`·`./grafana/dashboards`를 bind-mount. 마운트 경로는 `/etc/grafana/dashboards`(= `grafana-storage`(`/var/lib/grafana`)와 비충돌, 오버레이 없음).
- 프로비저닝 대시보드는 UI read-only(`allowUiUpdates: false`) — **수정은 이 JSON 파일로만**.

## 두 메트릭 소스
- **(a) Spring Actuator** — Prometheus가 `host.docker.internal:8080/actuator/prometheus` 5s scrape. 앱(`./gradlew bootRun`)이 떠 있어야 함. `http_server_requests_seconds_*`(uri 라벨로 버전 분리), `hikaricp_connections_*`, `executor_*`, `jvm_*`.
- **(b) k6 remote-write** — 부하 스크립트가 Prometheus remote-write로 push. **k6가 이름을 변형**: `k6_` prefix + Counter `_total` + Trend `_p95`/`_p99`. 대시보드 PromQL은 이 변형된 실명을 사용(PoC에서 확정 — `specs/phase3/poc-phase3-5-verification.md`).

## 대시보드에 데이터를 흘리는 표준 부하 실행
앱 기동 후, 대시보드를 채우려면 아래처럼 k6를 remote-write로 실행한다. **Trend p95는 아래 env가 있어야 적재된다**(없으면 p99만):
```bash
export K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write
export K6_PROMETHEUS_RW_TREND_STATS="p(95),p(99)"   # _p95 적재 (없으면 p99만)

# 동시성 (concurrency.json) — 버전·프로필 바꿔가며
VERSION=v3 k6 run -o experimental-prometheus-rw test/load/coupon-test.js
VERSION=v3 PROFILE=performance k6 run -o experimental-prometheus-rw test/load/coupon-test.js  # steady_* + 503 분해

# 비동기 (async.json)
VERSION=v6a PROFILE=observe k6 run -o experimental-prometheus-rw test/load/coupon-async-test.js  # 429 백프레셔

# 인덱스·랭킹 (index.json / ranking.json) — actuator 소스라 remote-write 불필요, VERSION만
VERSION=v1 k6 run test/load/ranking-test.js
VERSION=v4 k6 run test/load/ranking-test.js
```
- k6 메트릭은 런 종료 후 ~5분이면 **stale** 처리되어 instant 패널에서 사라진다(정상) — 비교는 런 중/직후 또는 대시보드 시간범위를 런 구간으로 맞춰서 본다.
- `phase` 템플릿 변수(accuracy/warmup/steady)로 before/after(warmup↔steady) 토글. index/ranking은 actuator 소스라 phase 라벨이 없어 변수 미적용(전 구간 표시).

## 주의
- `down -v` 금지 — `grafana-storage`·`mysql-data` 볼륨이 날아간다. 프로비저닝은 볼륨을 *대체*하지 않고 *추가*한다.
- Actuator Timer는 percentiles-histogram 미설정이라 서버측 진짜 p95 불가 → index/ranking은 avg(=rate(sum)/rate(count))·max로 비교(패널 설명에 명시). 클라이언트 p95는 k6 `http_req_duration` 참조.
