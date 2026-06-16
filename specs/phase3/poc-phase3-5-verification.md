# PoC — Phase 3-5 도구 검증 (Grafana 프로비저닝)

## 목표
대시보드를 본격 구축하기 **전에**, phase 3-5 프로비저닝-as-code가 의존하는 3가지 전제를 실측으로 확정한다.
나중에 "No data"·"datasource 안 붙음"으로 삽질하지 않도록 가장 싼 비용으로 미리 깬다.

1. Prometheus에 **두 메트릭 소스의 시계열이 실제로 존재하고 쿼리되는가** — (a) Spring Actuator 스크레이프, (b) k6 remote-write.
2. Grafana **datasource 프로비저닝**(`provisioning/datasources/*.yml`)이 `docker compose up`만으로 자동 연결되는가.
3. Grafana **dashboard 프로비저닝**(provider + JSON)이 자동 로드되고 패널이 데이터를 그리는가 — 그리고 bind-mount 경로가 기존 `grafana-storage` 볼륨과 충돌하지 않는가.

## 전제 조건
- `docker compose -f docker/docker-compose.yml up -d` 로 mysql·redis·prometheus·grafana 기동.
- 앱은 호스트에서 1개 구동(`./gradlew bootRun`) — actuator 메트릭이 `host.docker.internal:8080/actuator/prometheus`로 노출.
- Prometheus는 `--web.enable-remote-write-receiver` 활성(기존 compose 설정). k6 remote-write 대상 = `http://localhost:9090/api/v1/write`.
- 실제 메트릭 이름은 코드 기준: k6 = `accept_dur_202`·`issue_dur_*`·`issue_cnt_503_*`(`test/load/coupon-async-test.js`·`coupon-test.js`), actuator = `http_server_requests_seconds_*`·`hikaricp_connections_active`·`hikaricp_connections_pending`·`jvm_*`.

## 결과물 위치
- PoC 검증 로그/캡처: `results/phase3-5-monitoring/poc/` (스크린샷·쿼리 응답 저장)
- 본 구현 산출물(이 PoC 통과 후): `docker/grafana/provisioning/`, `docker/grafana/dashboards/`

---

## PoC 항목

### PoC 1: Prometheus에 두 소스 시계열이 쿼리되는가
대시보드가 참조할 메트릭이 실제로 TSDB에 들어와 있는지, HTTP API로 직접 확인한다.

```bash
#!/bin/bash
# PoC 1: Prometheus 시계열 존재 확인 (actuator scrape + k6 remote-write)
# Prometheus HTTP API로 쿼리. 결과 result 배열이 비어있지 않으면 시계열 존재.

PROM=http://localhost:9090

echo "=== (a) Actuator 스크레이프 시계열 ==="
for m in http_server_requests_seconds_count hikaricp_connections_active hikaricp_connections_pending jvm_memory_used_bytes; do
  n=$(curl -s "$PROM/api/v1/query?query=$m" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]["result"]))')
  echo "  $m → 시계열 $n개"
done

echo "=== (b) k6 remote-write 시계열 (부하 1회 돌린 뒤 확인) ==="
# 먼저: k6 run --out experimental-prometheus-rw test/load/coupon-async-test.js  (짧게 1회)
for m in accept_dur_202 issue_dur_v6a issue_cnt_503_pool; do
  n=$(curl -s "$PROM/api/v1/query?query=$m" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]["result"]))')
  echo "  $m → 시계열 $n개"
done
```

**실행 방법**
1. 앱 기동 후 상품목록/쿠폰 API에 가벼운 부하 1회 → (a) actuator 메트릭 생성.
2. `k6 run --out experimental-prometheus-rw=http://localhost:9090/api/v1/write test/load/coupon-async-test.js` 짧게 1회 → (b) k6 메트릭 push.
3. 위 스크립트 실행.

**예상 결과**
- (a) 4개 메트릭 모두 시계열 ≥ 1.
- (b) k6 메트릭 시계열 ≥ 1 (이름은 실제 스크립트의 `Trend`/`Counter` 명과 일치해야 함 — 0이면 메트릭 이름 또는 remote-write 대상 불일치).

**확인 포인트**
- k6 메트릭 이름의 실제 prefix/형태를 여기서 **확정**(대시보드 쿼리에 그대로 박을 문자열). 0이 나오면 본 구현 전에 이름부터 교정.

**결론** (2026-06-16 실측, k6 v1.5.0 / `-o experimental-prometheus-rw`):
- **PASS.** 두 소스 모두 시계열 적재 확인.
- **(a) actuator** — 전부 존재: `up`(1), `http_server_requests_seconds_count`/`_sum`(각 4, uri 라벨로 분리), `hikaricp_connections_active`/`_pending`/`_max`(각 1), `jvm_memory_used_bytes`(6).
- **(b) k6 remote-write — 이름이 변형된다 (가장 중요한 발견):**
  - **모든 메트릭에 `k6_` prefix.** 스펙 예시(`issue_dur_200_success`)를 그대로 쓰면 전부 No data였음.
  - **Counter → `k6_<name>_total`** (예: `k6_issue_cnt_200_success_total`, `k6_issue_cnt_202_accepted_total`, `k6_issue_cnt_409_soldout_total`).
  - **Trend → `k6_<name>_<stat>`. 기본 stat = `p99` 단일.** (예: `k6_issue_dur_200_success_p99`, `k6_accept_dur_202_p99`). 괄호는 제거됨: `p(95)`→`p95`.
  - **p95를 적재하려면** k6 실행 시 `K6_PROMETHEUS_RW_TREND_STATS="p(95),p(99)"`(또는 `avg,p(95),p(99)`) 지정 필요 → `k6_accept_dur_202_p95` 적재 확인함. **본 구현의 부하 실행은 이 env를 표준으로 사용한다.**
  - **버전/프로필 의존(코드엔 있으나 해당 부하 안 돌면 미적재 — 발명 아님):**
    - `k6_issue_cnt_503_pool_timeout_total`/`_lock_wait_total`/`_not_acquired_total`, `k6_issue_dur_503_lockreject_p*` → **v3+ 부하**에서만 (v1은 503 없음).
    - `k6_issue_cnt_429_queue_full_total` → 백프레셔 발동(observe 고부하)에서만.
    - `k6_steady_*` → coupon-test `PROFILE=performance` / async `PROFILE=observe` 에서만.
  - 전체 스냅샷: `results/phase3-5-monitoring/poc/poc-results.log`.

---

### PoC 2: datasource 프로비저닝이 자동 연결되는가
최소 datasource YAML 하나만 마운트해 `docker compose up`만으로 Prometheus가 붙는지 확인(수동 클릭 0회).

```yaml
# docker/grafana/provisioning/datasources/datasource.yml (PoC용 최소본)
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
```
compose의 grafana 서비스에 임시 마운트:
```yaml
    volumes:
      - grafana-storage:/var/lib/grafana
      - ./grafana/provisioning:/etc/grafana/provisioning   # ← 추가
```

**실행 방법**
1. 위 파일 생성 + compose 마운트 추가 후 `docker compose -f docker/docker-compose.yml up -d grafana`.
2. `curl -s -u admin:admin http://localhost:3000/api/datasources | python3 -m json.tool` 로 datasource 목록 확인.

**예상 결과**: `name: Prometheus`, `url: http://prometheus:9090` 데이터소스가 **수동 추가 없이** 목록에 존재.

**확인 포인트**: 컨테이너 내부 네트워크에서 `prometheus`(서비스명)로 해석되는지 — `localhost`가 아니라 `http://prometheus:9090` 여야 함.

**결론** (2026-06-16 실측):
- **PASS.** `docker compose up -d` 만으로 `/api/datasources`에 `name: Prometheus`, `uid: prometheus`, `url: http://prometheus:9090`, `access: proxy`, `isDefault: true` 자동 등록. 수동 클릭 0.
- 본 구현 datasource는 PoC와 동일 파일 사용(고정 `uid: prometheus`) — 대시보드 panel이 이 uid를 참조.

---

### PoC 3: dashboard 프로비저닝 자동 로드 + 볼륨 충돌 확인
대시보드 provider + 최소 JSON 1개를 마운트해 자동 로드되는지, 그리고 bind-mount가 기존 `grafana-storage`(수동 설정이 남아있는 볼륨)와 충돌하지 않는지 확인.

```yaml
# docker/grafana/provisioning/dashboards/dashboards.yml
apiVersion: 1
providers:
  - name: 'phase3'
    type: file
    options:
      path: /etc/grafana/dashboards
```
- `docker/grafana/dashboards/_poc.json` — 패널 1개(예: `http_server_requests_seconds_count` 그래프)짜리 최소 대시보드.
- compose 마운트 추가: `- ./grafana/dashboards:/etc/grafana/dashboards`

**실행 방법**
1. 위 2파일 + 마운트 추가 → `docker compose up -d grafana`.
2. `curl -s -u admin:admin http://localhost:3000/api/search?type=dash-db | python3 -m json.tool` 로 대시보드 등록 확인.
3. 브라우저에서 패널이 데이터를 그리는지 육안 확인.

**예상 결과**: `_poc` 대시보드가 자동 등록되고 패널에 데이터가 그려진다.

**확인 포인트**
- 프로비저닝된 대시보드는 UI에서 편집 불가(read-only) — 의도된 동작. 본 구현 시 대시보드는 **코드로만** 수정한다.
- `grafana-storage` 볼륨과 bind-mount 경로(`/etc/grafana/...`)는 서로 다른 경로라 충돌 없음을 확인 — 충돌하면 본 구현에서 마운트 전략 재검토.

**결론** (2026-06-16 실측):
- **PASS.** provider(`/etc/grafana/dashboards`) + `_poc.json`(패널 `up`)가 `docker compose up -d`만으로 FlashDeal 폴더에 자동 등록(`/api/search`에 `uid: poc-phase3-5`).
- **마운트 경로 확정: `/etc/grafana/dashboards`** (스펙 Step C가 허용한 안전 변형). `grafana-storage`(`/var/lib/grafana`)와 경로가 안 겹쳐 **오버레이 충돌 0** — `down -v` 없이 grafana만 재생성해도 볼륨 보존(컨테이너 `docker inspect`로 3개 마운트 + grafana-storage 유지 확인). 본 구현은 이 경로 그대로 사용.
- 프로비저닝 대시보드는 UI read-only(`allowUiUpdates: false`) — 코드로만 수정.

---

## PoC 종합 결론 — 본 구현 착수 가능
세 항목 모두 PASS. 본 구현(Step A~D)에 고정할 사항:
- datasource `uid`: **`prometheus`**, url `http://prometheus:9090`, access proxy.
- 대시보드 마운트: `./grafana/dashboards → /etc/grafana/dashboards` (볼륨 비충돌).
- **PromQL 메트릭명 규칙**: actuator는 실명 그대로 / k6는 **`k6_` prefix + Counter `_total` + Trend `_p95`·`_p99`**.
- **부하 실행 표준 env**: `K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write`, `K6_PROMETHEUS_RW_TREND_STATS="p(95),p(99)"`.
- 이미 PoC 단계에서 datasource.yml·dashboards.yml(provider)·compose 마운트가 본 구현용으로 생성됨 → Step A·C는 사실상 완료, Step B(대시보드 4종)에서 `_poc.json`을 교체.

---

## .gitignore 추가 규칙
- `results/phase3-5-monitoring/poc/` 의 스크린샷(`*.png`)은 용량 고려해 선택적 커밋. 쿼리 응답 로그(`*.log`·`*.json`)는 작게 유지.

## PoC 통과 기준
세 항목 모두 "예상 결과" 충족 시 phase 3-5 본 구현(Step A~D) 착수. PoC 1에서 확정한 메트릭 이름을 대시보드 쿼리에 사용한다.

## Outside Claude Code Scope (human tasks)
- `docker compose up` 실행, 브라우저(`localhost:3000`)에서 datasource·대시보드 육안 확인 및 스크린샷.
- k6 remote-write 1회 실행해 (b) 시계열 생성.
- PoC 결론 칸 기입 후 본 구현 착수 판단.
