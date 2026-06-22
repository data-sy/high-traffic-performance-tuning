# Phase 3-6 랭킹 스크린샷 — 보류 런북 (전시 캡처, 3-6 범위에서 제외)

> **상태: 3-6 범위에서 의도적 제외(descoped).** 랭킹 대시보드 v1(DB)↔v4(Redis) before/after 캡처는 헤드라인 수치(k6 p95)와 별개인 전시물(avg-latency 데모)이라, 측정·머지를 막지 않도록 3-6에서 빼고 이 런북만 보존한다.
> 나중에 전시를 보강할 때 이 절차대로 캡처하면 되고, 산출물은 같은 폴더의 `screenshots/`로 들어간다.

## ⚠ 먼저 알아야 할 함정 2개
1. **grafana·prometheus가 측정 후 DOWN 상태일 수 있다.** P3 측정 때 메모리 확보 위해 내려놨었다(측정은 앱 `actuator/prometheus`를 직접 스크레이프했으므로 서버 불필요였음). 캡처하려면 다시 올려야 한다.
2. **측정 런은 대시보드에 안 남아있다.** 측정 동안 prometheus가 꺼져 있어 스크레이프가 0이었다. → **스크린샷은 측정 재현이 아니라 별도 데모 런이다.** 스펙상으로도 이 스샷은 "avg-latency 데모"(전시물)이지 헤드라인(k6 p95 파일)과 별개다.

## 절차

### 1) 관측 스택 복구
```bash
docker compose -f docker/docker-compose.yml up -d
```
→ grafana(:3000) · prometheus(:9090) 복귀. mysql·redis는 이미 떠 있음.

### 2) 앱·데이터 sanity
```bash
curl -s localhost:8080/actuator/health                          # {"status":"UP"}
docker exec -i docker-redis-1 redis-cli ZCARD ranking:products  # 100
```
앱이 죽어 있으면 `./gradlew bootRun` (RankingInitializer가 멱등 재적재 → ZCARD=100).

### 3) v1·v4 데모 부하 (prometheus가 둘 다 스크레이프하게)
```bash
k6 run -e VERSION=v1 -e VUS=8 -e DURATION=60s test/load/ranking-test-scale.js
k6 run -e VERSION=v4 -e VUS=8 -e DURATION=45s test/load/ranking-test-scale.js
```
- ⚠ **각 버전 duration ≥ scrape_interval(보통 15s)의 2~3배.** 짧으면 `rate()` 윈도우에 샘플이 1개만 들어가 **v4 패널이 빈 채로 0**이 된다(스펙 §Step B line 197 핀). v1은 요청당 ~30초라 60s 권장, v4는 빠르니 45s면 충분.
- v2·v3는 캡처 대상 아님(before/after 극단 = v1 DB ↔ v4 Redis면 됨).

### 4) grafana 캡처
- http://localhost:3000 → **`ranking` 대시보드** (phase 3-5 산출물, 동작은 `docker/grafana/README.md` 참조)
- 시간 범위를 방금 데모 윈도우로 (예: Last 15m)
- v1(DB) 레이턴시 패널 ↔ v4(Redis) 레이턴시 패널 캡처 (uri로 자동 분리됨)
- 저장: `results/phase3-6-scale-up/screenshots/`

### 5) ⚠ 캡션 규율 (스펙 §Step D Note ⑰·⑪ — overclaim 금지)
- **"avg-latency 데모"로 명기.** 대시보드 패널은 actuator **avg**(`rate(_sum)/rate(_count)`)·max를 보여준다 — 헤드라인 정량치는 k6 **p95**다. **다른 통계량**이므로 "p95 결과"로 읽히지 않게 캡션에서 분리.
- **"레이턴시 데모"지 "인기 상품 랭킹" 아님.** product_id가 1M에 균등분포라 top-100 *내용*은 거의 동률 난수 집합 → "의미 있는 인기 랭킹"으로 전시하면 overclaim. v1≫v4 레이턴시 격차는 분포와 무관하므로 데모 자체는 유효.

## 끝나면
- 스샷이 `results/phase3-6-scale-up/screenshots/`에 들어가면 전시 보강 완료.
- 로드맵 Done의 Phase 3-6 항목에서 "전시 캡처 제외" 꼬리표를 갱신한다.
