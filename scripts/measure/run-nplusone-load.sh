#!/usr/bin/env bash
# Phase 3-7 N+1 — 부하 런(p95) + 비포화 게이트.
# 정본: specs/phase3/phase3-7-n-plus-one-draft.md (R3). 게이트: http_req_failed≈0 · Hikari pending==0.
#
# k6를 백그라운드로 돌리며 actuator/prometheus의 hikaricp_connections_pending을 샘플링,
# max pending>0이면 포화로 판정(부하점 과다 → VUS 낮춰 재실행). p95는 보조 지표(목록의 1차 근거는 쿼리 수).
#
# 사용: scripts/measure/run-nplusone-load.sh <list|detail> <VERSION> [VUS] [DURATION]
set -euo pipefail

PATHKIND="${1:?list|detail}"
VERSION="${2:?VERSION 필요}"
VUS="${3:-8}"
DURATION="${4:-30s}"
BASE="${BASE_URL:-http://localhost:8080}"
OUTDIR="${OUTDIR:-results/phase3-7-n-plus-one/k6}"
mkdir -p "$OUTDIR"

case "$PATHKIND" in
    list)   SCRIPT=test/load/nplusone-list-test.js ;;
    detail) SCRIPT=test/load/nplusone-detail-test.js ;;
    *) echo "ABORT: PATHKIND은 list|detail" >&2; exit 1 ;;
esac

SUMMARY="$OUTDIR/${PATHKIND}-${VERSION}-summary.json"
POOL_SAMPLES="$OUTDIR/${PATHKIND}-${VERSION}-pending.txt"
: > "$POOL_SAMPLES"

pending_now() {
    curl -s "$BASE/actuator/prometheus" \
        | awk '/^hikaricp_connections_pending/ {print $2; found=1} END{if(!found) print "NA"}' \
        | head -1
}

echo "=== Phase 3-7 load [$PATHKIND $VERSION] VUS=$VUS DUR=$DURATION ==="
k6 run -e VERSION="$VERSION" -e VUS="$VUS" -e DURATION="$DURATION" \
       --summary-export="$SUMMARY" "$SCRIPT" >"$OUTDIR/${PATHKIND}-${VERSION}-k6.log" 2>&1 &
K6_PID=$!

# k6 도는 동안 pending 샘플링(max 추적).
MAXP=0
while kill -0 "$K6_PID" 2>/dev/null; do
    p=$(pending_now)
    [[ "$p" =~ ^[0-9.]+$ ]] && { echo "$p" >> "$POOL_SAMPLES"; awk -v a="$p" -v b="$MAXP" 'BEGIN{exit !(a>b)}' && MAXP="$p"; }
    sleep 1
done
wait "$K6_PID" || { echo "ABORT: k6 비정상 종료(로그 확인: $OUTDIR/${PATHKIND}-${VERSION}-k6.log)" >&2; exit 2; }

# 결과 파싱(p95 ms, 실패율).
P95=$(awk -F'[:,]' '/"p\(95\)"/{gsub(/[^0-9.]/,"",$2); print $2; exit}' "$SUMMARY" 2>/dev/null || echo NA)
FAILRATE=$(grep -o '"http_req_failed"[^}]*"value":[0-9.]*' "$SUMMARY" | grep -o '"value":[0-9.]*' | head -1 | cut -d: -f2 || echo NA)

echo "p95(ms)=$P95  http_req_failed=$FAILRATE  max_pending=$MAXP"

# 비포화 게이트.
GATE_OK=1
awk -v m="$MAXP" 'BEGIN{exit !(m==0 || m=="0")}' || { echo "GATE FAIL: pending max=$MAXP (>0 포화) → VUS 낮춰 재실행"; GATE_OK=0; }
if [[ "$FAILRATE" != "NA" ]]; then
    awk -v f="$FAILRATE" 'BEGIN{exit !(f<0.01)}' || { echo "GATE FAIL: http_req_failed=$FAILRATE (≥1%)"; GATE_OK=0; }
fi

printf '%s\t%s\t%s\t%s\t%s\n' "$PATHKIND" "$VERSION" "$P95" "$FAILRATE" "$MAXP" >> "${OUT_TSV:-/dev/null}"
[[ "$GATE_OK" == 1 ]] && echo "GATE OK" || { echo "GATE 위반 — 결과 무효"; exit 3; }
