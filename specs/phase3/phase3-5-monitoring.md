# Phase 3-5 — 모니터링 프로비저닝 (Grafana as Code)

> 관측 스택을 **버전관리되는 산출물**로 만든다. 지금 Grafana **컨테이너**는 docker-compose에 있으나, 데이터소스·대시보드는 전부 **웹 UI(localhost:3000)에서 수동 설정**되어 docker 볼륨(`grafana-storage`)에만 살아 있다 → **repo에 없다 = 재현 불가**. 이 phase는 데이터소스 + 대시보드를 **프로비저닝-as-code**로 정의해 `docker compose up`만으로 자동 로드되게 하고, 시나리오 v1~v4 before/after 대시보드를 코드로 구축한다.

## Context
- Phase 3-1(인덱스, PR #3), 3-2(랭킹, PR #4·#5), 3-3(동시성, PR #8·#9), 3-4(비동기 발급, PR #13) 완료 상태
- **모니터링 인프라는 PR #6에서 이미 구축됨** (아래 "기존 인프라 현황" 참조 — 신규 컨테이너 추가 아님)
- 두 개의 메트릭 소스가 이미 흐르고 있다:
  - **(a) Spring Actuator scrape** — Prometheus가 `host.docker.internal:8080/actuator/prometheus`를 5s 간격 scrape. JVM, HikariCP 풀(`hikaricp_connections_*`), HTTP 서버(`http_server_requests_seconds_*`) 등
  - **(b) k6 remote-write** — k6가 Prometheus의 remote-write receiver(`--web.enable-remote-write-receiver`)로 부하 트렌드를 push. 부하 스크립트가 정의한 커스텀 메트릭(`issue_dur_200_success`, `accept_dur_202` 등)이 step/phase 태그와 함께 적재
- **Grafana 데이터소스·대시보드는 아직 repo에 없다** — 이게 이 phase가 닫는 갭
- `ddl-auto: validate` 활성 상태. 변경 금지 (이 phase는 애플리케이션 코드를 건드리지 않는다 — 인프라/설정 전용)
- Docker 컨테이너 (MySQL, Redis, Prometheus, Grafana) 실행 중 (`docker compose -f docker/docker-compose.yml up -d`)

### 기존 인프라 현황 (이미 존재 — 절대 신규 생성/삭제하지 말 것)
```
docker/docker-compose.yml
  services:
    mysql       (8.0, 3306, volume mysql-data + init-data.sql)
    redis       (7-alpine, 6379)
    prometheus  (prom/prometheus, 9090)
                command: --config.file / --web.enable-remote-write-receiver / --storage.tsdb.retention.time=15d
                volume: ./prometheus.yml → /etc/prometheus/prometheus.yml
    grafana     (grafana/grafana, 3000)
                volume: grafana-storage → /var/lib/grafana     ← 수동 설정이 사는 곳
  volumes: mysql-data, grafana-storage

docker/prometheus.yml
  global.scrape_interval: 5s
  scrape_configs: job 'spring-boot' → host.docker.internal:8080/actuator/prometheus

test/load/*.js (메트릭 소스 (b)의 정의 — 대시보드 쿼리가 참조할 실제 메트릭명)
  coupon-test.js        : issue_dur_200_success / _409_soldout / _503_lockreject (Trend),
                          issue_cnt_* (Counter), steady_* (Counter/Trend), phase 태그(accuracy|warmup|steady)
  coupon-async-test.js  : accept_dur_202 (Trend), issue_cnt_202_accepted / _429_queue_full / _other,
                          steady_* , phase 태그
  index-test.js         : http_req_duration (k6 기본), 커스텀 메트릭 없음
  ranking-test.js       : http_req_duration (k6 기본), VERSION env, 커스텀 메트릭 없음
```

## Goal
관측성을 버전관리 산출물로 못 박는다 — `docker compose up`만으로 Prometheus 데이터소스와 시나리오 대시보드(인덱스/랭킹/동시성/비동기)가 **수동 클릭 없이 자동 로드**되어 v1~v4 before/after가 시각화된다.

## Important Rules
- 먼저 `CLAUDE.md`를 읽고 프로젝트 컨벤션을 확인할 것
- **기존 compose 서비스를 깨지 말 것** — mysql/redis/prometheus/grafana 정의·포트·기존 볼륨 마운트를 변경/제거하지 않는다. grafana 서비스에 **볼륨 마운트와 env만 추가**한다
- **`grafana-storage` 볼륨을 삭제하지 말 것** — `down -v` 금지. 수동 설정이 사라지고, 더 중요하게는 다른 데이터(mysql 시드)까지 날아간다. 이 phase는 볼륨을 *대체*하지 않고 프로비저닝을 *추가*한다 (프로비저닝과 볼륨은 공존: 프로비저닝은 부팅 시 강제 적용, 볼륨은 사용자가 UI에서 만든 것 보존)
- **Grafana 프로비저닝 경로는 관례를 따른다** — 컨테이너 내부 `/etc/grafana/provisioning/datasources/`, `/etc/grafana/provisioning/dashboards/`. 대시보드 JSON은 provider가 가리키는 경로(예: `/var/lib/grafana/dashboards`)에 둔다. 경로를 임의로 바꾸지 말 것
- **애플리케이션 코드·엔티티·SQL을 건드리지 말 것** — 이 phase는 `docker/` 하위 인프라 파일과 대시보드 JSON만 다룬다
- **데이터소스명을 대시보드 JSON과 일치**시킬 것 — 데이터소스 yml의 `uid`(또는 name)와 대시보드 panel의 `datasource` 참조가 어긋나면 "No data"가 뜬다. 고정 `uid`(예: `prometheus`)를 데이터소스에 박고 대시보드는 그 uid를 참조
- 대시보드 쿼리는 **실제 존재하는 메트릭명만** 쓸 것 (위 "기존 인프라 현황"의 actuator 메트릭 + k6 커스텀 메트릭). 없는 메트릭을 발명하지 말 것 — 불확실하면 Step 0 PoC로 Prometheus에서 먼저 쿼리해 존재 확인
- 모든 추가 파일은 `docker/grafana/` 하위에 모은다 (compose가 `docker/`에서 실행되므로 상대경로 `./grafana/...`가 맞다 — 루트 아님)

---

## Step 0 (선행, 강력 권장): 경량 PoC — 메트릭 존재 확인 + 최소 프로비저닝 로드

> **PoC를 권장한다.** 대시보드를 다 짜고 "No data"를 만나면 (1) 메트릭이 실제로 없는 건지 (2) 데이터소스 연결이 안 된 건지 (3) PromQL이 틀린 건지 구분이 안 돼 디버깅이 폭증한다. 부팅 전에 **두 가지 가정**을 깬다: ① Prometheus가 k6 remote-write 시계열과 actuator 시계열을 실제로 쿼리 가능하다 ② 최소 프로비저닝 데이터소스 1개 + 1패널 대시보드가 부팅 시 자동 로드된다. 이 확인 결과는 `specs/phase3/poc-phase3-5-verification.md`로 분리해 기록(다른 phase3 PoC 문서와 동급 산출물).

### PoC 절차 (직접 수행)
1. 컨테이너 가동 상태에서 앱(`bootRun`)을 띄우고 부하를 한 번 흘린다:
   - actuator 소스: 아무 부하(`k6 run test/load/ranking-test.js`)로 `http_server_requests`·`hikaricp_*`가 채워지게
   - k6 소스: remote-write로 `k6 run -o experimental-prometheus-rw test/load/coupon-test.js` (또는 기존에 쓰던 remote-write 출력 플래그)
2. Prometheus UI(`localhost:9090`)에서 다음을 쿼리해 **시계열 존재를 눈으로 확인**(없으면 대시보드가 무의미):
   - actuator: `hikaricp_connections_active`, `http_server_requests_seconds_count`, `jvm_memory_used_bytes`
   - k6: `issue_dur_200_success` (또는 k6 prometheus-rw가 붙이는 접두사 — **실제 적재된 메트릭명을 여기서 확정**. k6 remote-write는 메트릭명을 변형할 수 있으므로 가정 금지, 쿼리로 실명 확인)
3. **최소 프로비저닝 1세트**만 만들어 부팅 자동 로드를 검증:
   - 데이터소스 yml 1개(Prometheus) + dashboards provider yml 1개 + 패널 1개짜리 대시보드 JSON 1개(`up` 또는 `hikaricp_connections_active` 단일 패널)
   - `docker compose down`(볼륨 유지) → `up -d` → 재로그인 없이 데이터소스 connected + 1패널 대시보드가 보이면 PoC 통과
4. `poc-phase3-5-verification.md`에 기록: 확인된 actuator/k6 실제 메트릭명, k6 remote-write 접두사 유무, 프로비저닝 자동 로드 성공 여부, 본 Step에서 쓸 datasource uid

### Step 0 검증 (직접 수행)
```
- Prometheus UI에서 actuator·k6 메트릭이 둘 다 시계열로 조회됨
- 최소 프로비저닝이 docker compose up만으로 로드됨 (수동 클릭 0)
- poc-phase3-5-verification.md에 확정 메트릭명·datasource uid 기록
```
검증 통과 후 `/commit`

---

## Step A: 데이터소스 프로비저닝

### Restrictions
- compose의 prometheus 서비스명·포트를 바꾸지 말 것 (URL이 거기 묶임)
- 데이터소스 `uid`는 한 번 정하면 이후 모든 대시보드가 참조하므로 **고정**(예: `prometheus`)

### 1. Prometheus 데이터소스 정의
- 위치: `docker/grafana/provisioning/datasources/datasource.yml`
- 내용:
  ```yaml
  apiVersion: 1
  datasources:
    - name: Prometheus
      uid: prometheus            # 대시보드 JSON이 참조할 고정 uid
      type: prometheus
      access: proxy              # Grafana 서버가 대신 호출 (브라우저 직접 호출 아님)
      url: http://prometheus:9090   # compose 네트워크 내부 DNS — localhost 아님
      isDefault: true
      editable: true
  ```
- 주의: `url`은 **컨테이너 네트워크 이름** `prometheus`(서비스명) 사용. `localhost:9090`은 Grafana 컨테이너 내부에선 자기 자신을 가리켜 실패. compose 기본 네트워크에서 서비스명이 DNS로 해석됨
- `access: proxy` 고정 (direct는 CORS·인증 문제)

### Step A Build Check
- 애플리케이션 빌드 없음 (인프라 전용). compose 파일 변경 전이므로 이 Step은 파일 생성만
- YAML 문법 확인: `docker run --rm -v "$PWD/docker/grafana:/g" alpine sh -c 'cat /g/provisioning/datasources/datasource.yml'` 정도로 가독 확인(또는 에디터 린트)

### Step A Verification (human will verify)
```
- datasource.yml 파일 생성됨, uid=prometheus, url=http://prometheus:9090, access=proxy, isDefault=true
- (Step C 마운트 후 전체 검증에서 실제 자동 연결 확인)
```
검증 통과 후 `/commit`

---

## Step B: 대시보드 provider + 시나리오 대시보드 JSON

### Restrictions
- 대시보드 JSON의 모든 패널 `datasource`는 Step A의 `uid: prometheus`를 참조 (불일치 시 No data)
- PromQL은 **실존 메트릭만** (Step 0에서 확인된 실명). k6 메트릭명 접두사가 PoC에서 다르게 확인되면 그 실명으로 교체
- 대시보드 JSON은 **Grafana export 포맷**(top-level `dashboard` 래핑 없이 raw dashboard object, 또는 provisioning이 받는 포맷)으로. provisioning은 raw dashboard JSON을 읽음

### 1. 대시보드 provider
- 위치: `docker/grafana/provisioning/dashboards/dashboards.yml`
- 내용:
  ```yaml
  apiVersion: 1
  providers:
    - name: 'flashdeal-dashboards'
      orgId: 1
      folder: 'FlashDeal'          # Grafana UI에서 묶일 폴더명
      type: file
      disableDeletion: false
      updateIntervalSeconds: 30
      allowUiUpdates: true
      options:
        path: /var/lib/grafana/dashboards   # 컨테이너 내부 — Step C에서 ./grafana/dashboards를 여기에 마운트
        foldersFromFilesStructure: false
  ```

### 2. 시나리오 대시보드 JSON (각 1파일, `docker/grafana/dashboards/` 하위)

각 대시보드는 위 두 메트릭 소스 중 해당하는 것을 읽는다. **모든 패널 datasource uid=prometheus**.

- **`index.json`** — 시나리오 ① 인덱스 최적화 (소스 (a) actuator HTTP + (b) k6 기본 `http_req_duration`)
  - 패널: v1~v4 product 조회 지연 p95 비교 — `http_server_requests_seconds_count`/`_sum`로 라우트별 p95 추정, 또는 k6 `http_req_duration` p95
  - 패널: throughput(req/s) by version. **버전 분리는 actuator의 `uri` 라벨**(`/api/v1/products` vs v4) 또는 k6 run 태그(`VERSION`)로
  - 주의: index-test.js는 커스텀 메트릭이 없으므로 actuator의 `http_server_requests_seconds_*`(uri 라벨로 v1~v4 분리)가 1차 소스

- **`ranking.json`** — 시나리오 ③ 랭킹 (DB ORDER BY vs Redis)
  - 패널: v1(DB ORDER BY) vs v4(Redis Sorted Set) 응답 지연 p95 — actuator `http_server_requests_seconds` uri 라벨(`/api/v1/rankings`~`/api/v4/rankings`) 또는 k6 `http_req_duration`
  - 패널: throughput by version

- **`concurrency.json`** — 시나리오 ④ 동시성 (소스 (a) HikariCP + (b) k6 coupon-test 커스텀)
  - 패널: **HikariCP 풀** — `hikaricp_connections_active`, `hikaricp_connections_pending`(v3 FOR UPDATE 풀 포화의 간판 지표 — phase3-3 보강 #3과 직결), `hikaricp_connections_max`
  - 패널: **503 분해** — k6 `issue_cnt_503_pool_timeout` / `issue_cnt_503_lock_wait` / `issue_cnt_503_not_acquired`로 거절 원인 분리(phase3-3 "503 의미 구분")
  - 패널: **outcome별 지연** — `issue_dur_200_success` / `issue_dur_409_soldout` / `issue_dur_503_lockreject` p95를 셋으로 분리(집계 p95 금지 — phase3-3 Blocker 계승)
  - 패널: 성공 카운트 `issue_cnt_200_success` (정합성 ≤ total_qty 시각 확인)

- **`async.json`** — 시나리오 비동기 발급 (Phase 3-4, 소스 (b) k6 coupon-async-test)
  - 패널: **접수 지연** `accept_dur_202` p95 (라벨: "접수 지연"임을 패널 설명에 명시 — coupon-async-test.js T4 규율 계승. E2E 발급 지연은 k6에 없으므로 별도 표기/제외)
  - 패널: **큐 깊이/백프레셔** — `issue_cnt_429_queue_full`(QUEUE_FULL = 백프레셔 발동, 오류 아님)와 `issue_cnt_202_accepted` 대비. 큐 깊이 게이지가 actuator 커스텀 메트릭으로 노출돼 있으면 추가(없으면 Step 0에서 확인 후 패널 제외 — 메트릭 발명 금지)

- 각 대시보드 공통: **`templating`에 version/phase 변수**(가능하면)와 시간범위 기본값을 둬 v1~v4 before/after 토글을 쉽게. step/phase 태그(accuracy|warmup|steady)를 변수로 노출하면 before/after 분리가 명확

### Step B Build Check
- JSON 문법 검증: `for f in docker/grafana/dashboards/*.json; do python3 -m json.tool "$f" >/dev/null && echo "OK $f"; done`
- 애플리케이션 빌드 없음

### Step B Verification (human will verify)
```
- 4개 대시보드 JSON 생성, 모든 패널 datasource uid=prometheus 참조
- python3 -m json.tool 로 4파일 전부 valid JSON
- 각 PromQL이 Step 0에서 확인한 실존 메트릭명을 사용 (발명 0)
```
검증 통과 후 `/commit`

---

## Step C: compose 와이어링

### Restrictions
- grafana 서비스에서 **기존 `grafana-storage` 마운트를 유지**하고 새 마운트를 *추가*만 한다 (교체 금지)
- 다른 서비스(mysql/redis/prometheus) 정의는 한 줄도 바꾸지 않는다
- 호스트 경로는 `./grafana/...` (compose가 `docker/`에서 실행되므로 `docker/grafana/...`가 됨)

### 1. grafana 서비스에 볼륨·env 추가
- 위치: `docker/docker-compose.yml` → `services.grafana`
- 변경 후 형태:
  ```yaml
  grafana:
    image: grafana/grafana
    ports:
      - "3000:3000"
    volumes:
      - grafana-storage:/var/lib/grafana                                   # 기존 — 유지
      - ./grafana/provisioning:/etc/grafana/provisioning                   # 추가 — 데이터소스+provider
      - ./grafana/dashboards:/var/lib/grafana/dashboards                   # 추가 — provider가 읽는 대시보드 JSON
    # GF_PATHS_PROVISIONING은 기본값(/etc/grafana/provisioning)과 동일하므로 위 관례 경로면 env 불필요.
    # 비관례 경로를 쓸 때만 아래를 추가:
    # environment:
    #   - GF_PATHS_PROVISIONING=/etc/grafana/provisioning
  ```
- 주의:
  - `./grafana/dashboards`를 `/var/lib/grafana/dashboards`에 마운트 → Step B provider의 `options.path`와 **반드시 일치**
  - `grafana-storage`가 `/var/lib/grafana`를 덮으므로, 그 하위 `dashboards`에 bind mount를 겹치면 Grafana 부팅 시 provider가 bind mount 경로를 읽는다(volume 위에 bind가 우선). PoC(Step 0)에서 이 겹침이 실제로 동작하는지 확인 완료 가정. 의심되면 provider path를 `/etc/grafana/dashboards`로 바꾸고 `./grafana/dashboards:/etc/grafana/dashboards`로 마운트(볼륨과 안 겹쳐 더 안전) — **Step 0에서 어느 쪽이 깨끗한지 확정**

### Step C Build Check
- `docker compose -f docker/docker-compose.yml config` 로 compose 문법·머지 결과 검증 (실제 up 전에 YAML/마운트 경로 확인)

### Step C Verification (human will verify)
```
- docker compose config 에서 grafana에 3개 볼륨 마운트가 보이고 grafana-storage 유지됨
- 다른 서비스 정의 변경 없음 (git diff로 grafana 블록만 변경 확인)
```
검증 통과 후 `/commit`

---

## Step D: 통합 검증 (자동 로드 + 양 소스 렌더)

### 절차 (직접 수행)
1. `docker compose -f docker/docker-compose.yml down` (**`-v` 절대 금지** — 볼륨 유지)
2. `docker compose -f docker/docker-compose.yml up -d`
3. `localhost:3000` 접속 → **수동 클릭 없이** 확인:
   - Configuration → Data sources: **Prometheus가 자동 등록 + connected**(Test 녹색)
   - Dashboards → FlashDeal 폴더: **index/ranking/concurrency/async 4개 자동 등록**
4. 양 메트릭 소스가 실제로 렌더되는지 부하로 확인:
   - 앱(`bootRun`) 가동 → `k6 run test/load/ranking-test.js`(actuator 소스) → ranking/index 대시보드 패널에 데이터
   - `k6 run` remote-write로 `coupon-test.js`(k6 소스) → concurrency 대시보드 outcome 패널·503 분해·Hikari 패널에 데이터
   - `coupon-async-test.js` → async 대시보드 접수 지연·429 패널에 데이터
5. before/after 토글 확인: version/phase 템플릿 변수로 v1↔v4, accuracy↔steady 전환 시 패널이 갱신됨

### Step D Verification (human will verify)
```
- down→up 후 데이터소스·대시보드가 수동 설정 없이 전부 로드됨 (재현성 달성)
- actuator 소스 패널(Hikari/HTTP)과 k6 소스 패널(issue_*/accept_*)이 둘 다 데이터 렌더
- v1~v4 / before-after 토글 동작
```
검증 통과 후 `/commit` → 전체 `/pr`

---

## Final Directory Structure After Phase 3-5
```
docker/
├── docker-compose.yml          (grafana 서비스에 볼륨2+env 추가 — 나머지 불변)
├── prometheus.yml              (기존 — 불변)
├── init-data.sql               (기존 — 불변)
└── grafana/                    ← 신규 (프로비저닝 as code)
    ├── provisioning/
    │   ├── datasources/
    │   │   └── datasource.yml   (Prometheus, uid=prometheus, url=http://prometheus:9090)
    │   └── dashboards/
    │       └── dashboards.yml   (file provider → /var/lib/grafana/dashboards)
    └── dashboards/
        ├── index.json          (시나리오 ① v1~v4 조회 지연/throughput)
        ├── ranking.json        (시나리오 ③ DB ORDER BY vs Redis)
        ├── concurrency.json    (시나리오 ④ Hikari 풀 active/pending, 503 분해, outcome 지연)
        └── async.json          (Phase 3-4 접수 지연, 429 백프레셔)

specs/phase3/
└── poc-phase3-5-verification.md   (Step 0 PoC 기록 — 확정 메트릭명·datasource uid)
```

## Work Order
순차 진행. **각 Step 완료 후 정지**하여 직접 검증·커밋.

1. **Step 0** PoC — Prometheus에서 actuator·k6 메트릭 존재 확인 + 최소 프로비저닝 자동 로드 검증
   → Pause → 직접 메트릭 실명·자동 로드 확인 → `/commit`
2. **Step A** 데이터소스 프로비저닝 yml
   → Pause → 파일·uid·url 확인 → `/commit`
3. **Step B** 대시보드 provider + 4개 시나리오 대시보드 JSON
   → Pause → valid JSON + 실존 메트릭만 사용 확인 → `/commit`
4. **Step C** compose 와이어링 (grafana 볼륨/env 추가, grafana-storage 유지)
   → Pause → `docker compose config`로 마운트·타 서비스 불변 확인 → `/commit`
5. **Step D** 통합 검증 — down(볼륨유지)→up→자동 로드 + 양 소스 렌더 + before/after 토글
   → Pause → 직접 브라우저 검증 → `/commit` → `/pr`

After all steps committed, human will create PR manually or via `/pr`.

## Outside Claude Code Scope (human tasks)
- **브라우저로 `localhost:3000` 열어** 데이터소스 자동 연결·대시보드 자동 등록을 눈으로 확인 (Claude는 헤드리스로 UI 클릭 검증 불가)
- 앱 `bootRun` + k6 부하 실행으로 양 메트릭 소스를 채우고 패널 렌더 확인
- before/after 스크린샷 캡처 (포트폴리오 산출물)
- Step 0 PoC에서 k6 remote-write 메트릭 실명·접두사를 Prometheus UI로 확정 (k6 버전/출력 플래그에 따라 다를 수 있음)
- PR을 main에 머지
