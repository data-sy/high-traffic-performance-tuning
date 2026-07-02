#!/bin/sh
# phase 3-9 임계 구간 점유 측정 러너 — 정확성 축 1회(정합 게이트) + 성능 축 N회(throughput 반복).
#   usage: scripts/measure/run-occupancy.sh <cell> <perf_repeats>   (예: r0 3)
# 두 축 분리(3-3 계승):
#   정확성 축: constant-vus 500/10s, total_qty=100 → 행수==100(초과 0) 게이트
#   성능 축:   warmup 5s + steady 30s, total_qty 대용량 → steady 성공 throughput(반복 N회로 노이즈밴드용)
# env: COMPOSE_PROJECT_NAME(기본 docker), RESULTS_DIR, VUS(500)
set -eu

CELL="${1:?usage: run-occupancy.sh <cell> <perf_repeats>}"
REPEATS="${2:-1}"
VUS="${VUS:-500}"
WARMUP="${WARMUP:-5s}"; STEADY="${STEADY:-30s}"; ACC_DURATION="${ACC_DURATION:-10s}"
PERF_TOTAL_QTY="${PERF_TOTAL_QTY:-100000000}"
EXPECT_LOCK_WAIT="${EXPECT_LOCK_WAIT:-50}"; EXPECT_ISOLATION="${EXPECT_ISOLATION:-REPEATABLE-READ}"

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
RESULTS="${RESULTS_DIR:-$ROOT/results/phase3-9-critical-section-occupancy}"
K6JS="$ROOT/test/load/coupon-test.js"
mkdir -p "$RESULTS/k6" "$RESULTS/integrity"
run_mysql() { docker exec -i "${COMPOSE_PROJECT_NAME:-docker}-mysql-1" mysql -uroot -proot flashdeal "$@"; }
run_redis() { docker exec -i "${COMPOSE_PROJECT_NAME:-docker}-redis-1" redis-cli "$@"; }
STEADY_S=$(echo "$STEADY" | sed 's/s$//')

echo "========================= $CELL (repeats=$REPEATS) ========================="
curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1 || { echo "  ✗ 서버 미기동" >&2; exit 1; }
CV="$(run_mysql -N -e 'SELECT @@innodb_lock_wait_timeout, @@transaction_isolation;')"
ACT_LW="$(echo "$CV" | awk '{print $1}')"; ACT_ISO="$(echo "$CV" | awk '{print $2}')"
[ "$ACT_LW" = "$EXPECT_LOCK_WAIT" ] && [ "$ACT_ISO" = "$EXPECT_ISOLATION" ] || {
    echo "  ✗ 통제변수 불일치: lock_wait=$ACT_LW(기대$EXPECT_LOCK_WAIT) iso=$ACT_ISO(기대$EXPECT_ISOLATION)" >&2; exit 2; }
[ "$(run_redis PING 2>/dev/null)" = "PONG" ] || { echo "  ✗ Redis 미도달" >&2; exit 2; }
echo "  ✓ server up · lock_wait=$ACT_LW · iso=$ACT_ISO · redis PONG"

# ───────── 정확성 축 (1회) ─────────
echo "[정확성 축] reset(total_qty=100) → k6 vus $VUS/$ACC_DURATION → 행수==100"
run_mysql < "$ROOT/scripts/coupon/reset-coupon.sql"
k6 run -e VERSION="$CELL" -e PROFILE=accuracy -e VUS="$VUS" -e DURATION="$ACC_DURATION" \
    "$K6JS" --summary-export="$RESULTS/k6/$CELL-accuracy.json" >/dev/null 2>&1
sleep 2
run_mysql -t -e "SELECT (SELECT total_qty FROM coupon WHERE id=1) total_qty,
  (SELECT issued FROM coupon WHERE id=1) issued_counter,
  (SELECT COUNT(*) FROM coupon_issue WHERE coupon_id=1) actual_issued;" | tee "$RESULTS/integrity/$CELL.txt"

# ───────── 성능 축 (N회) ─────────
TPUT_CSV=""
for i in $(seq 1 "$REPEATS"); do
    echo "[성능 축 run $i/$REPEATS] reset(total_qty=$PERF_TOTAL_QTY) → warmup $WARMUP + steady $STEADY"
    run_mysql < "$ROOT/scripts/coupon/reset-coupon.sql"
    run_mysql -e "UPDATE coupon SET total_qty=$PERF_TOTAL_QTY WHERE id=1;"
    PJSON="$RESULTS/k6/$CELL-performance-$i.json"
    k6 run -e VERSION="$CELL" -e PROFILE=performance -e VUS="$VUS" -e WARMUP="$WARMUP" -e STEADY="$STEADY" \
        "$K6JS" --summary-export="$PJSON" >/dev/null 2>&1
    LINE=$(python3 - "$PJSON" "$STEADY_S" <<'PY'
import json,sys
m=json.load(open(sys.argv[1]))["metrics"]; ss=float(sys.argv[2])
def c(k): return int(m.get(k,{}).get("count",0))
s200=c('steady_cnt_200_success'); s409=c('steady_cnt_409_soldout')
s503=c('steady_cnt_503_lockreject'); soth=c('steady_cnt_other')
d=m.get('steady_dur_200_success',{}); p95=d.get('p(95)'); p99=d.get('p(99)')
tp=s200/ss
print(f"{tp:.1f}|{s200}|{s409}|{s503}|{soth}|{p95}|{p99}")
PY
)
    TP=$(echo "$LINE" | cut -d'|' -f1); S503=$(echo "$LINE" | cut -d'|' -f4)
    SOTH=$(echo "$LINE" | cut -d'|' -f5); P95=$(echo "$LINE" | cut -d'|' -f6)
    echo "  run $i: throughput=${TP}/s  503=$S503  other=$SOTH  p95=${P95}ms"
    TPUT_CSV="${TPUT_CSV:+$TPUT_CSV,}$TP"
    # 코드 동일 칸(r0/r2/r3/r4)에서 503>0 또는 other>0면 배선/정합 이상 → 멈춤 신호
    case "$CELL" in
      r0|r2|r3|r4)
        if [ "$S503" != "0" ] || [ "$SOTH" != "0" ]; then
            echo "  ⚠ STOP-AND-REPORT: 코드 동일 칸 $CELL 에서 503=$S503 other=$SOTH (>0) — 배선/정합 이상" >&2
        fi ;;
    esac
done

echo "[$CELL 성능 throughput 배열] $TPUT_CSV"
echo "$CELL,$TPUT_CSV" >> "$RESULTS/throughput-runs.csv"
