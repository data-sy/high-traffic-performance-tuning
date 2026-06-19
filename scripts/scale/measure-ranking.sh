#!/bin/bash
# =============================================================
# Phase 3-6 CP-C — 랭킹 재측정 (1.5M order_item)
#   헤드라인: 동일 1.5M 런 내부 v1(1.5M GROUP BY 풀스캔) ≫ v4(Redis ZSet) 절대 격차. k6 p95(주 증거).
#   설계 = docs/plan/phase3-6-measurement-plan.md §3 CP-C.
#
# ⚠ 전제: 앱이 본체 스택(3306/6379)에 떠 있고, **CP-A 적재 후 1회 재시작**으로 RankingInitializer가
#   ZSet(ranking:products)에 top-100을 적재한 상태(ZCARD=100). (재시작은 본 스크립트 밖에서 수행·검증.)
#
# 라운드 진입 절차(순서 핀): ① DROP INDEX IF EXISTS idx_orderitem_product_quantity(멱등)+type=ALL 재확인
#   ② v3 String 캐시 cache:ranking:top100만 flush ③ ZSet은 flush 안 함(restart 소유) ④(밖에서 재시작 완료)
#   ⑤ v1→v4 측정(warmup+steady, VU<pool 엄격).
# 게이트: C1 pending==0(연속) / v1 N>=N_min(100) / v4 비공집 3종(ZCARD>0·200 비공집·counter delta>0).
# 교차검산: k6 throughput ↔ actuator counter delta.
# 본체 실행: MYSQL/REDIS_CONTAINER 디폴트=docker-{mysql,redis}-1(본체 스택). k6 = 정본 무수정 -scale 변형.
# 출력은 results/phase3-6-scale-up/ 격리(정본 phase3-2/k6 무접촉).
# =============================================================
set -euo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-docker-mysql-1}"
REDIS_CONTAINER="${REDIS_CONTAINER:-docker-redis-1}"
APP="${APP:-http://localhost:8080}"
N_MIN="${N_MIN:-100}"
VUS="${VUS:-8}"
WARMUP_DUR="${WARMUP_DUR:-15s}"
MEAS_DUR="${MEAS_DUR:-60s}"
# v1은 요청당 수십초(8-way 1.5M GROUP BY 경합)라 동일 duration이면 표본 기아(N<N_min) →
# v1만 측정-시간 축을 길게(직교: VU<pool과 무관). 나머지(v2~v4)는 빠르므로 MEAS_DUR 충분.
V1_MEAS_DUR="${V1_MEAS_DUR:-$MEAS_DUR}"

run_mysql() { docker exec -i "${MYSQL_CONTAINER}" mysql -uroot -proot flashdeal "$@" 2>/dev/null; }
run_redis() { docker exec -i "${REDIS_CONTAINER}" redis-cli "$@" 2>/dev/null; }

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT="$ROOT/results/phase3-6-scale-up"
EXPLAIN_DIR="$OUT/explain"; K6_DIR="$OUT/k6"; SAT_DIR="$OUT/saturation"
mkdir -p "$EXPLAIN_DIR" "$K6_DIR" "$SAT_DIR"
K6_SCRIPT="$ROOT/test/load/ranking-test-scale.js"  # 정본 ranking-test.js 무수정 — env-parametrized -scale 변형
APP_LOG="${APP_LOG:-/tmp/phase3-6-app-boot.log}"
GATES="$SAT_DIR/cp-c-gates.md"
V1_QUERY="SELECT oi.product_id, SUM(oi.quantity) AS sales_count FROM order_item oi GROUP BY oi.product_id ORDER BY sales_count DESC LIMIT 100"

log() { echo "[$(date '+%H:%M:%S')] $*"; }
counter() {  # $1=uri ; actuator http_server_requests count 합(상태코드별 합)
  curl -s "$APP/actuator/prometheus" 2>/dev/null \
    | awk -v u="uri=\"$1\"" '/^http_server_requests_seconds_count/ && index($0,u){s+=$2} END{printf "%d", s+0}'
}
sample_pending() { local out="$1"; : > "$out"
  while [ ! -f "${out}.stop" ]; do
    local line; line=$(curl -s "$APP/actuator/prometheus" 2>/dev/null)
    echo "$(date '+%H:%M:%S') pending=$(awk '/^hikaricp_connections_pending\{/{print $2}' <<<"$line") active=$(awk '/^hikaricp_connections_active\{/{print $2}' <<<"$line")" >> "$out"
    sleep 0.5
  done
}

# health
if ! curl -sf "$APP/actuator/health" >/dev/null; then echo "ABORT: 앱(8080) 미응답" >&2; exit 1; fi

# ---------- 라운드 진입 절차 ----------
log "① DROP INDEX IF EXISTS idx_orderitem_product_quantity (멱등) + v1 EXPLAIN type=ALL 재확인"
run_mysql -e "DROP INDEX idx_orderitem_product_quantity ON order_item" 2>/dev/null || true
run_mysql -e "ANALYZE TABLE order_item;" >/dev/null
run_mysql -e "EXPLAIN $V1_QUERY"             > "$EXPLAIN_DIR/ranking-v1-no-index.txt"
run_mysql -N --raw -e "EXPLAIN FORMAT=JSON $V1_QUERY" > "$EXPLAIN_DIR/ranking-v1-no-index.json"  # -N --raw: 유효 JSON
V1_TYPE=$(run_mysql -N -e "EXPLAIN $V1_QUERY" | awk -F'\t' 'NR==1{print $5}')
log "    v1 EXPLAIN type=${V1_TYPE} (ALL 기대 — 무인덱스 1.5M 풀스캔)"
if [ "$V1_TYPE" != "ALL" ]; then
  echo "ABORT: v1 baseline 오염 — EXPLAIN type=${V1_TYPE} != ALL (인덱스 잔존). 측정 무효." >&2; exit 1
fi

log "② v3 String 캐시 cache:ranking:top100 flush (ZSet은 flush 안 함)"
run_redis DEL cache:ranking:top100 >/dev/null

log "③④ ZSet 적재 확인 (restart 소유 — 본 스크립트 밖 재시작으로 적재됨)"
ZCARD0=$(run_redis ZCARD ranking:products | tr -d '[:space:]')
log "    ZCARD ranking:products = ${ZCARD0}"
if [ "${ZCARD0:-0}" -lt 1 ]; then
  echo "ABORT: ZSet 비어있음(ZCARD=${ZCARD0}). CP-A 적재 후 앱 재시작이 필요(RankingInitializer)." >&2; exit 1
fi

# ---------- ⑤ 측정 ----------
echo "# CP-C 랭킹 재측정 게이트 (1.5M order_item) — $(date '+%Y-%m-%d %H:%M')" > "$GATES"
echo "" >> "$GATES"
echo "- 부하모델: VU=${VUS} < pool=10 엄격, warmup ${WARMUP_DUR} + steady ${MEAS_DUR}(v1=${V1_MEAS_DUR}, 표본확보), N_min=${N_MIN}" >> "$GATES"
echo "- v1 EXPLAIN type=${V1_TYPE}(ALL, 무인덱스 1.5M 풀스캔 백스톱), ZCARD(ZSet)=${ZCARD0}" >> "$GATES"
echo "" >> "$GATES"
echo "| ver | p95(ms) | N(reqs) | failed | counter Δ | k6 throughput↔counter | pending(max/>0/연속) | stmtTO | 게이트 |" >> "$GATES"
echo "|---|---|---|---|---|---|---|---|---|" >> "$GATES"

VERSIONS=("v1" "v2" "v3" "v4")
for ver in "${VERSIONS[@]}"; do
  log "================= ${ver} ================="
  uri="/api/${ver}/rankings"
  CUR_DUR="$MEAS_DUR"; [ "$ver" = "v1" ] && CUR_DUR="$V1_MEAS_DUR"

  if [ "$ver" = "v2" ]; then
    log "v2: CREATE INDEX idx_orderitem_product_quantity + ANALYZE TABLE order_item"
    run_mysql -e "CREATE INDEX idx_orderitem_product_quantity ON order_item(product_id, quantity)" 2>/dev/null || true
    run_mysql -e "ANALYZE TABLE order_item;" >/dev/null
  fi

  # warmup (버려짐 — buffer pool/캐시 데움, v1 cold-first 교란 제거)
  log "warmup ${WARMUP_DUR} (discard)"
  k6 run -e VERSION="$ver" -e VUS="$VUS" -e DURATION="$WARMUP_DUR" "$K6_SCRIPT" >/dev/null 2>&1 || true

  # 측정 런
  CB=$(counter "$uri")
  PEND="$SAT_DIR/${ver}-pending.log"; rm -f "${PEND}.stop"; sample_pending "$PEND" & SAMP=$!
  MARK=$(wc -l < "$APP_LOG" 2>/dev/null || echo 0)
  log "steady ${CUR_DUR} 측정"
  k6 run -e VERSION="$ver" -e VUS="$VUS" -e DURATION="$CUR_DUR" \
     --summary-export="$K6_DIR/ranking-${ver}.json" "$K6_SCRIPT" >"$SAT_DIR/${ver}-k6.out" 2>&1 || true
  touch "${PEND}.stop"; wait "$SAMP" 2>/dev/null || true; rm -f "${PEND}.stop"
  CA=$(counter "$uri")

  P95=$(python3 -c "import json;d=json.load(open('$K6_DIR/ranking-${ver}.json'));print(round(d['metrics']['http_req_duration']['p(95)'],1))" 2>/dev/null||echo NA)
  N=$(python3 -c "import json;d=json.load(open('$K6_DIR/ranking-${ver}.json'));print(int(d['metrics']['http_reqs']['count']))" 2>/dev/null||echo NA)
  FAILED=$(python3 -c "import json;d=json.load(open('$K6_DIR/ranking-${ver}.json'));print(d['metrics'].get('http_req_failed',{}).get('value','NA'))" 2>/dev/null||echo NA)
  CDELTA=$(( CA - CB ))
  # k6 throughput ↔ counter delta 정합(±10% 또는 근사)
  XCHK="ok"; if [ "$N" != "NA" ] && [ "$CDELTA" -gt 0 ]; then
    diff=$(( N>CDELTA ? N-CDELTA : CDELTA-N )); tol=$(( N/10 + 5 ))
    [ "$diff" -le "$tol" ] && XCHK="ok(N=${N}~Δ=${CDELTA})" || XCHK="MISMATCH(N=${N} vs Δ=${CDELTA})"
  fi
  read -r PMAX PGT0 PCONS <<< "$(awk '{split($2,a,"=");v=a[2]+0; if(v>0){g++;c++;if(c>mc)mc=c}else c=0; if(v>mx)mx=v} END{printf "%g %d %d",mx+0,g+0,mc+0}' "$PEND")"
  STMT_TO=$(tail -n +"$((MARK+1))" "$APP_LOG" 2>/dev/null | grep -icE "statement.*timeout|query.*timeout|JdbcSQLTimeout|lock wait timeout" || true)

  GATE="PASS"
  [ "$PCONS" -ge 2 ] && GATE="FAIL(pending 연속>0)"
  { [ "$FAILED" != "0" ] && [ "$FAILED" != "0.0" ] && [ "$FAILED" != "NA" ]; } && GATE="FAIL(http_req_failed>0)"
  [ "${STMT_TO:-0}" -gt 0 ] && GATE="FAIL(stmtTO=${STMT_TO})"
  [ "$ver" = "v1" ] && [ "$N" != "NA" ] && [ "$N" -lt "$N_MIN" ] && GATE="FAIL(N<N_min: ${N}<${N_MIN} 표본기아)"

  # v4 비공집 3종
  V4NOTE=""
  if [ "$ver" = "v4" ]; then
    ZC=$(run_redis ZCARD ranking:products | tr -d '[:space:]')
    HTTP=$(curl -s -o /dev/null -w "%{http_code}" "$APP/api/v4/rankings")
    LEN=$(curl -s "$APP/api/v4/rankings" | python3 -c "import json,sys;print(len(json.load(sys.stdin).get('rankings',[])))" 2>/dev/null||echo 0)
    V4NOTE="ZCARD=${ZC} http=${HTTP} rankings_len=${LEN}"
    log "v4 비공집: ${V4NOTE}"
    { [ "${ZC:-0}" -lt 1 ] || [ "$HTTP" != "200" ] || [ "${LEN:-0}" -lt 1 ] || [ "$CDELTA" -lt 1 ]; } && GATE="FAIL(v4 비공집 위반: ${V4NOTE} Δ=${CDELTA})"
  fi

  log "${ver}: p95=${P95}ms N=${N} failed=${FAILED} counterΔ=${CDELTA} xchk=${XCHK} pending(${PMAX}/${PGT0}/${PCONS}) stmtTO=${STMT_TO} → ${GATE} ${V4NOTE}"
  echo "| ${ver} | ${P95} | ${N} | ${FAILED} | ${CDELTA} | ${XCHK} | ${PMAX}/${PGT0}/${PCONS} | ${STMT_TO} | ${GATE} ${V4NOTE} |" >> "$GATES"
done

# cleanup: v2 인덱스 제거(다음 라운드/측정 무오염)
run_mysql -e "DROP INDEX idx_orderitem_product_quantity ON order_item" 2>/dev/null || true
log "v2 인덱스 cleanup(DROP) 완료"

echo "" >> "$GATES"
echo "## 헤드라인 / 교차검산" >> "$GATES"
echo "- 헤드라인: v1(1.5M GROUP BY 풀스캔, EXPLAIN type=ALL) p95 ≫ v4(ZSet) p95 절대 격차. k6 p95 주 증거 + v1 EXPLAIN type=ALL 캐시-불변 백스톱." >> "$GATES"
echo "- C1 비포화: pending 전 샘플 0(연속>0 없음)·failed≈0·stmtTO 0. v1 N>=N_min(${N_MIN}) 표본 충분." >> "$GATES"
echo "- 교차검산1: k6 throughput(N) ↔ actuator counter delta 정합(위 xchk)." >> "$GATES"
echo "- 교차검산3: v4 비공집 3종(ZCARD>0·200 비공집·counterΔ>0) — 깨진 v4가 '빠름'으로 가짜 헤드라인 통과 차단." >> "$GATES"
log "DONE — CP-C. 게이트: $GATES"
cat "$GATES"
