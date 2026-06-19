#!/bin/bash
# =============================================================
# Phase 3-6 CP-B — 인덱스 재측정 (1M product)
#   헤드라인: 동일 1M 런 내부 step1(type=ALL+filesort) ≫ step4(type=ref, no-filesort)
#   설계 = docs/plan/phase3-6-measurement-plan.md §3 CP-B.
#
# 각 step: reset 인덱스 → step SQL 적용 → ANALYZE TABLE product → EXPLAIN(text+json) 캡처
#          → 비포화 pending 샘플러 가동 → k6(vus=8, files-only) → 게이트 판정.
# 진실 소스 = 파일(EXPLAIN + step별 k6 json). 대시보드 아님(인덱스는 §1.2상 파일 증거).
# files-only: k6 --out 제거(정본 prometheus 무접촉, T13).
# 본체 실행: MYSQL_CONTAINER 디폴트=docker-mysql-1(본체 스택). k6 = 정본 무수정 -scale 변형(vus=8).
# =============================================================
set -euo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-docker-mysql-1}"
APP="${APP:-http://localhost:8080}"
run_mysql() { docker exec -i "${MYSQL_CONTAINER}" mysql -uroot -proot flashdeal "$@" 2>/dev/null; }

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
INDEX_DIR="$ROOT/scripts/index"
OUT="$ROOT/results/phase3-6-scale-up"
EXPLAIN_DIR="$OUT/explain"; K6_DIR="$OUT/k6"; SAT_DIR="$OUT/saturation"
mkdir -p "$EXPLAIN_DIR" "$K6_DIR" "$SAT_DIR"
K6_SCRIPT="$ROOT/test/load/index-test-scale.js"  # 정본 index-test.js 무수정 — vus=8 -scale 변형
APP_LOG="${APP_LOG:-/tmp/phase3-6-app-boot.log}"
GATES="$SAT_DIR/cp-b-gates.md"

QUERY="SELECT * FROM product WHERE category = '전자기기' ORDER BY created_at DESC LIMIT 20"

log() { echo "[$(date '+%H:%M:%S')] $*"; }

# health
if ! curl -sf "$APP/actuator/health" >/dev/null; then echo "ABORT: 앱(8080) 미응답 — bootRun 필요" >&2; exit 1; fi
POOL_MAX=$(curl -s "$APP/actuator/prometheus" | awk -F'[ ]' '/^hikaricp_connections\{/{print $2}')
log "pool max = ${POOL_MAX} (VU=8 < pool 엄격 전제)"

reset_indexes() {
  run_mysql -e "DROP INDEX idx_product_category ON product" 2>/dev/null || true
  run_mysql -e "DROP INDEX idx_product_created_at ON product" 2>/dev/null || true
  run_mysql -e "DROP INDEX idx_product_category_created ON product" 2>/dev/null || true
  run_mysql -e "DROP INDEX idx_product_created_category ON product" 2>/dev/null || true
}

# pending 샘플러: 백그라운드로 /actuator/prometheus의 hikaricp_connections_pending·active를 ~0.5s 간격 샘플
sample_pending() {  # $1 = outfile ; 종료는 파일 $1.stop 생성으로
  local out="$1"; : > "$out"
  while [ ! -f "${out}.stop" ]; do
    local line; line=$(curl -s "$APP/actuator/prometheus" 2>/dev/null)
    local pend act; pend=$(awk '/^hikaricp_connections_pending\{/{print $2}' <<<"$line")
    act=$(awk '/^hikaricp_connections_active\{/{print $2}' <<<"$line")
    echo "$(date '+%H:%M:%S') pending=${pend:-NA} active=${act:-NA}" >> "$out"
    sleep 0.5
  done
}

# step 정의: num:name:sqlfile
STEPS=(
  "1:no-index:"            # baseline (빈 SQL — reset 후 무인덱스)
  "2:category-only:step2-category-only.sql"
  "3:created-at-only:step3-created-at-only.sql"
  "4:composite:step4-composite.sql"
  "5:reverse-composite:step5-reverse-composite.sql"
)

echo "# CP-B 인덱스 재측정 게이트 (1M product) — $(date '+%Y-%m-%d %H:%M')" > "$GATES"
echo "" >> "$GATES"
echo "| step | EXPLAIN type | key | rows | Extra(filesort?) | k6 p95(ms) | http_req_failed | reqs(N) | pending(max/>0 samples/연속>0) | 게이트 |" >> "$GATES"
echo "|---|---|---|---|---|---|---|---|---|---|" >> "$GATES"

for entry in "${STEPS[@]}"; do
  IFS=':' read -r num name sqlfile <<< "$entry"
  log "================= step ${num} (${name}) ================="

  reset_indexes
  if [ -n "$sqlfile" ]; then log "apply $sqlfile"; run_mysql < "$INDEX_DIR/$sqlfile"; fi
  log "ANALYZE TABLE product (stale 통계 방지 — 교차검산1 거짓모순 차단)"
  run_mysql -e "ANALYZE TABLE product;" >/dev/null

  # EXPLAIN (캐시-불변 주 증거)
  run_mysql -e "EXPLAIN $QUERY"             > "$EXPLAIN_DIR/step${num}-${name}.txt"
  run_mysql -N --raw -e "EXPLAIN FORMAT=JSON $QUERY" > "$EXPLAIN_DIR/step${num}-${name}.json"  # -N --raw: 유효 JSON(이스케이프 방지)
  # type/key/rows/Extra 추출 (EXPLAIN 컬럼: id,select_type,table,partitions,type,possible_keys,key,key_len,ref,rows,filtered,Extra)
  read -r etype ekey erows eextra <<< "$(run_mysql -N -e "EXPLAIN $QUERY" | awk -F'\t' 'NR==1{print $5, ($7==""?"NULL":$7), $10, ($12==""?"-":$12)}')"
  log "EXPLAIN: type=${etype} key=${ekey} rows=${erows} Extra=${eextra}"

  # 비포화 샘플러 시작 + app-log 마커(statement-timeout 검출용)
  PEND="$SAT_DIR/step${num}-${name}-pending.log"
  rm -f "${PEND}.stop"
  sample_pending "$PEND" &
  SAMP_PID=$!
  LOG_MARK=$(wc -l < "$APP_LOG" 2>/dev/null || echo 0)

  # k6 (vus=8, files-only — --out 제거: T13 본체 9090 무접촉)
  log "k6 run (vus=8 files-only)"
  k6 run --summary-export="$K6_DIR/step${num}-${name}.json" "$K6_SCRIPT" >"$SAT_DIR/step${num}-${name}-k6.out" 2>&1 || true

  # 샘플러 종료
  touch "${PEND}.stop"; wait "$SAMP_PID" 2>/dev/null || true; rm -f "${PEND}.stop"

  # 게이트 신호 산출
  P95=$(python3 -c "import json;d=json.load(open('$K6_DIR/step${num}-${name}.json'));print(round(d['metrics']['http_req_duration']['p(95)'],1))" 2>/dev/null || echo NA)
  FAILED=$(python3 -c "import json;d=json.load(open('$K6_DIR/step${num}-${name}.json'));print(d['metrics'].get('http_req_failed',{}).get('value','NA'))" 2>/dev/null || echo NA)
  REQS=$(python3 -c "import json;d=json.load(open('$K6_DIR/step${num}-${name}.json'));print(int(d['metrics']['http_reqs']['count']))" 2>/dev/null || echo NA)
  # pending 분석
  read -r PMAX PGT0 PCONS <<< "$(awk '
    {split($2,a,"="); v=a[2]+0; if(v>0){gt0++; cons++; if(cons>maxcons)maxcons=cons} else cons=0; if(v>mx)mx=v}
    END{printf "%g %d %d", mx+0, gt0+0, maxcons+0}' "$PEND")"
  # statement-timeout 검출 (app log 마커 이후)
  STMT_TO=$(tail -n +"$((LOG_MARK+1))" "$APP_LOG" 2>/dev/null | grep -icE "statement.*timeout|query.*timeout|JdbcSQLTimeout|cancelled|lock wait timeout" || true)

  # 비포화 판정: pending 전 샘플 0(연속>0 없음) AND http_req_failed≈0 AND statement-timeout 0
  GATE="PASS"
  if [ "$PCONS" -ge 2 ]; then GATE="FAIL(pending 연속>0)"; fi
  if [ "$FAILED" != "0" ] && [ "$FAILED" != "0.0" ] && [ "$FAILED" != "NA" ]; then GATE="FAIL(http_req_failed>0)"; fi
  if [ "${STMT_TO:-0}" -gt 0 ]; then GATE="FAIL(statement-timeout=${STMT_TO})"; fi

  log "게이트: p95=${P95}ms failed=${FAILED} N=${REQS} pending(max=${PMAX}/>0=${PGT0}/연속=${PCONS}) stmtTO=${STMT_TO} → ${GATE}"
  echo "| ${num} ${name} | ${etype} | ${ekey} | ${erows} | ${eextra} | ${P95} | ${FAILED} | ${REQS} | ${PMAX}/${PGT0}/${PCONS} | ${GATE} |" >> "$GATES"
done

# 최종 reset
reset_indexes
log "최종 인덱스 reset 완료"

echo "" >> "$GATES"
echo "## 교차검산 (헤드라인 step1 ≫ step4)" >> "$GATES"
echo "- 교차검산1(EXPLAIN 구조 ↔ k6 p95): step1 type=ALL+filesort 인데 p95 높고, step4 type=ref+no-filesort 인데 p95 낮으면 정합. EXPLAIN(캐시-불변)이 주 증거." >> "$GATES"
echo "- 교차검산2(비포화): 위 표 게이트 열 — pending 전 샘플 0(연속>0 없음)·failed≈0·stmtTO 0 이면 p95가 순수 쿼리 비용." >> "$GATES"
log "DONE — CP-B. 게이트 요약: $GATES"
cat "$GATES"
