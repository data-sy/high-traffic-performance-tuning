#!/bin/sh
# Phase 3-4 비동기 발급 측정 하네스. 수렴 게이트·핀 assert·깊이 샘플러·카오스 스텝·터미널 회계.
# 자세한 측정 설계는 docs/reports/phase3-4-async-issuance.md, 핀·환경은 docs/handoff/phase3-4/.
#
#   usage: scripts/measure/run-coupon-async.sh <mode> [args]
#     accuracy <v6a|v6b|v6c>                      정확성·수렴 축 (1,000건 적재 → 수렴 게이트 → 터미널 회계)
#     observe  <v6a|v6b|v6c>                      백프레셔 관찰 축 (warmup+steady, 수렴 게이트 미적용)
#     chaos    <v6b|v6c> <AFTER_POP|AFTER_COMMIT> <halt|sigterm>
#                                                 카오스 런 (kill 발동 구간 = 드레인 핀.
#                                                 sigterm은 APP_PID env 필수)
#     selftest-pin                                 자가 검증 ①: 고의 핀 불일치 → abort 증명
#     selftest-nonconverge                         자가 검증 ②: 고의 미수렴 → FAIL(NON_CONVERGED) 분류 증명
#
# 종료 코드: 0 정상 / 2 핀·격리 assert 불일치(abort) / 3 배리어 타임아웃 / 4 게이트 FAIL
set -eu

MODE="${1:?usage: run-coupon-async.sh <accuracy|observe|chaos|selftest-pin|selftest-nonconverge> [args]}"

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
RESULTS="${RESULTS_DIR:-$ROOT/results/phase3-4-async}"
K6JS="$ROOT/test/load/coupon-async-test.js"
mkdir -p "$RESULTS/k6" "$RESULTS/integrity" "$RESULTS/e2e" "$RESULTS/depth" "$RESULTS/selftest"

# ── 인프라 격리 핀 ──────────────────────────────────
# 전용 격리 스택의 compose 프로젝트명을 env로 받는다(미주입 시 기본값으로 폴백 → 컨테이너 부재로
# 아래 격리 assert가 안전하게 abort). compose env는 docker/.env 만 유효(루트 .env 무시).
if [ -f "$ROOT/docker/.env" ]; then
    COMPOSE_PROJECT_NAME="$(sed -n 's/^COMPOSE_PROJECT_NAME=//p' "$ROOT/docker/.env")"
fi
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-phase3-4-async}"
MYSQL_C="${COMPOSE_PROJECT_NAME}-mysql-1"
REDIS_C="${COMPOSE_PROJECT_NAME}-redis-1"

# MYSQL_PWD로 비밀번호 경고 억제 (경고가 stderr로 새서 selftest 로그를 오염시킴)
run_mysql() { docker exec -e MYSQL_PWD=root -i "$MYSQL_C" mysql -uroot flashdeal "$@"; }
run_redis() { docker exec -i "$REDIS_C" redis-cli "$@"; }

# 컨테이너가 진짜 전용 격리 프로젝트 소속인지 라벨로 대조 — 이름이 같아도 프로젝트가 다르면
# 본체 인프라를 잡은 것이므로 즉시 abort (인프라 격리 핀).
assert_isolated_containers() {
    for c in "$MYSQL_C" "$REDIS_C"; do
        proj="$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project" }}' "$c" 2>/dev/null || true)"
        if [ "$proj" != "$COMPOSE_PROJECT_NAME" ]; then
            echo "  ✗ [격리 abort] $c 의 compose 프로젝트='$proj' (기대 '$COMPOSE_PROJECT_NAME')" >&2
            echo "    전용 격리 스택이 없거나 본체 컨테이너를 가리키고 있다 — 측정 금지" >&2
            exit 2
        fi
    done
    echo "  ✓ 격리: $MYSQL_C / $REDIS_C (project=$COMPOSE_PROJECT_NAME)"
}

# ── 통제변수 핀 + 런타임 assert (phase 3-3 방식 계승) ─────────
EXPECT_LOCK_WAIT="${EXPECT_LOCK_WAIT:-50}"
EXPECT_ISOLATION="${EXPECT_ISOLATION:-REPEATABLE-READ}"

assert_control_pins() {
    CV="$(run_mysql -N -e 'SELECT @@innodb_lock_wait_timeout, @@transaction_isolation;')"
    ACT_LW="$(echo "$CV" | awk '{print $1}')"
    ACT_ISO="$(echo "$CV" | awk '{print $2}')"
    if [ "$ACT_LW" != "$EXPECT_LOCK_WAIT" ] || [ "$ACT_ISO" != "$EXPECT_ISOLATION" ]; then
        echo "  ✗ [핀 abort] 통제변수 불일치 — 측정 중단:" >&2
        echo "     innodb_lock_wait_timeout=$ACT_LW (기대 $EXPECT_LOCK_WAIT)" >&2
        echo "     transaction_isolation=$ACT_ISO (기대 $EXPECT_ISOLATION)" >&2
        exit 2
    fi
    echo "  ✓ 핀: innodb_lock_wait_timeout=$ACT_LW, isolation=$ACT_ISO"
    [ "$(run_redis PING 2>/dev/null)" = "PONG" ] || { echo "  ✗ Redis 미도달" >&2; exit 2; }
    echo "  ✓ Redis PONG"
}

# 앱 신규 핀 assert — GET /api/v6/ops/pins 실측값 vs 핀 표 (불일치 abort)
assert_ops_pins() {
    curl -sf http://localhost:8080/api/v6/ops/pins -o /tmp/ops-pins.json || {
        echo "  ✗ ops 핀 조회 실패 (앱 미기동?)" >&2; exit 2; }
    python3 - /tmp/ops-pins.json <<'PY' || exit 2
import json, sys
EXPECT = {  # 신규 핀
    "executorCore": 4, "executorMax": 4, "executorQueueCapacity": 10000,
    "queueCap": 10000, "consumers": 4,
    "brpopTimeoutMs": 1000, "xreadBlockMs": 1000, "xreadCount": 10,
    "xautoclaimIntervalMs": 5000, "xautoclaimMinIdleMs": 10000,
    "gracefulShutdownSec": 10, "statusTtlSec": 3600,
    "rejectionPolicy": "AbortPolicy",   # 문서 핀만 있던 행을 assert로 승격
    "chaosArmed": None,                 # 직전 카오스 런의 잔존 무장 검출 — 무장 채로 측정 시작 금지
}
act = json.load(open(sys.argv[1]))
bad = [(k, v, act.get(k)) for k, v in EXPECT.items() if act.get(k) != v]
if bad:
    print("  ✗ [핀 abort] ops 핀 불일치:", file=sys.stderr)
    for k, e, a in bad: print(f"     {k}: 실측 {a} (기대 {e})", file=sys.stderr)
    sys.exit(1)
print("  ✓ ops 핀 전 항목 일치")
PY
}

# ── 쿠폰 키 네임스페이스 (coupon:issue:* 한정, 전역 삭제 금지) ──
STATUS_PAT='coupon:issue:status:*'
SEQ_KEY='coupon:issue:seq'
QUEUE_KEY='coupon:issue:queue'
STREAM_KEY='coupon:issue:stream'
GROUP='coupon-issuers'

# ── 깊이 소스 3종 (배리어는 3종 전부 확인) ──
depth_v6a() {
    # 앱 다운이면 힙 큐도 함께 소멸 — 0으로 본다 (JVM 사망 = 작업면 빈 상태)
    curl -sf http://localhost:8080/api/v6/ops/depth 2>/dev/null \
        | python3 -c 'import json,sys; d=json.load(sys.stdin); print(int(d.get("queueSize",0))+int(d.get("activeCount",0)))' \
        2>/dev/null || echo 0
}
depth_v6b() { v="$(run_redis LLEN "$QUEUE_KEY" 2>/dev/null | tr -dc '0-9')"; echo "${v:-0}"; }  # 빈 출력→0
# v6c 깊이 = XLEN. 앱이 ack 완료분을 XDEL로 트림하므로 스트림엔 미분배+미ack만 남아
# XLEN == lag + pending. (XINFO GROUPS의 lag는 XDEL tombstone 이후 nil이 되어 과소집계 —
# 측정 중 실측 발견: 429 21만 건인데 lag+pending 피크 32로 모순.)
depth_v6c() { v="$(run_redis XLEN "$STREAM_KEY" 2>/dev/null | tr -dc '0-9')"; echo "${v:-0}"; }
depth_for() {
    case "$1" in
        v6a) depth_v6a ;; v6b) depth_v6b ;; v6c) depth_v6c ;;
        *) echo "unknown version $1" >&2; exit 1 ;;
    esac
}
rows() { run_mysql -N -e 'SELECT COUNT(*) FROM coupon_issue WHERE coupon_id=1;'; }

# ── 런 시작 배리어 (핀: reset 전 작업면 0 확인, 180s 드레인 대기 → 미달 시 abort) ──
# 3종 전부 확인. v6b in-flight 맹점 → 5s settle + 행 수 안정 재확인.
barrier() {
    echo "[배리어] 작업면 잔존 확인 (깊이 3종, 최대 180s 드레인 대기)..."
    END=$(( $(date +%s) + 180 ))
    while :; do
        DA="$(depth_v6a)"; DB="$(depth_v6b)"; DC="$(depth_v6c)"
        D=$(( DA + DB + DC ))
        if [ "$D" -eq 0 ]; then
            R1="$(rows)"; sleep 5; R2="$(rows)"     # settle: pop 후 커밋 전 in-flight 창
            if [ "$R1" = "$R2" ]; then
                echo "  ✓ 배리어 통과 (깊이 v6a=$DA v6b=$DB v6c=$DC, 행 수 안정 $R2)"
                return 0
            fi
            echo "  … settle 중 행 수 변동 ($R1→$R2) — 재확인"
        else
            echo "  … 잔존 드레인 대기 (v6a=$DA v6b=$DB v6c=$DC)"
        fi
        if [ "$(date +%s)" -ge "$END" ]; then
            echo "  ✗ [배리어 타임아웃] 작업면 잔존 — 앱 재시작 후 재실행 필요 (핀)" >&2
            exit 3
        fi
        sleep 5
    done
}

# ── 런 간 키 reset (핀 — 배리어 통과 후에만, coupon:issue:* 한정) ──
reset_run() {
    barrier
    echo "[reset] coupon 시드 + coupon:issue:* 키 네임스페이스"
    run_mysql < "$ROOT/scripts/coupon/reset-coupon.sql"
    docker exec -i "$REDIS_C" sh -c \
        "redis-cli --scan --pattern '$STATUS_PAT' | xargs -r redis-cli DEL" >/dev/null
    run_redis DEL "$SEQ_KEY" "$QUEUE_KEY" "$STREAM_KEY" >/dev/null
    run_redis XGROUP CREATE "$STREAM_KEY" "$GROUP" '$' MKSTREAM >/dev/null   # v6c 그룹 재생성
    echo "  ✓ reset 완료"
}

# ── 수렴 게이트 (깊이 0 && 행 수 연속 3회 동일, 1s 폴링, 타임아웃 120s 핀) ──
CONV_TIMEOUT="${CONV_TIMEOUT:-120}"
CONV=""; T_CONV=""; CONV_DETECT=""
converge_wait() { # $1=version  $2=depth-csv-path
    V="$1"; CSV="$2"
    echo "[수렴 게이트] 깊이 0 && 행 수 연속 3회 동일 (1s 폴링, 타임아웃 ${CONV_TIMEOUT}s)"
    START=$(date +%s); STABLE=0; PREV_ROWS=-1; LAST_CHANGE=$START
    echo "elapsed_s,depth,rows" > "$CSV"
    while :; do
        NOW=$(date +%s); EL=$(( NOW - START ))
        D="$(depth_for "$V")"; R="$(rows)"
        echo "$EL,$D,$R" >> "$CSV"
        if [ "$R" = "$PREV_ROWS" ]; then STABLE=$(( STABLE + 1 )); else STABLE=1; LAST_CHANGE=$NOW; fi
        PREV_ROWS="$R"
        if [ "$D" -eq 0 ] && [ "$STABLE" -ge 3 ]; then
            CONV="CONVERGED"; CONV_DETECT=$EL; T_CONV=$(( LAST_CHANGE - START )); break
        fi
        if [ "$EL" -ge "$CONV_TIMEOUT" ]; then
            CONV="NON_CONVERGED"; CONV_DETECT=$EL; T_CONV=$(( LAST_CHANGE - START )); break
        fi
        sleep 1
    done
    if [ "$CONV" = "CONVERGED" ]; then
        echo "  ✓ 수렴 (감지 ${CONV_DETECT}s, 행 수 최종 변화 ${T_CONV}s, rows=$PREV_ROWS)"
    else
        # 미수렴은 부하 부족이 아니라 컨슈머 정지·회수 루프 부재 신호로 우선 해석
        echo "  ✗ FAIL(NON_CONVERGED) — 타임아웃 ${CONV_TIMEOUT}s 내 미수렴 (depth=$(depth_for "$V"), rows=$PREV_ROWS)"
        echo "    분류: NON_CONVERGED (별도 FAIL 사유 — 정합성 FAIL과 구분)"
    fi
}

# ── 터미널 회계 + 판정 (정확성 축 전용. ACCEPTED 잔류 시 5s 후 1회 재폴링) ──
accounting() { # $1=version $2=k6-json $3=run-label($RESULTS 하위 파일명) $4=mode(normal|loss|redelivery)
    python3 - "$1" "$2" "$3" "$4" "$RESULTS" "$MYSQL_C" "$REDIS_C" "$CONV" <<'PY'
import json, subprocess, sys, time
version, k6_path, label, mode, results, mysql_c, redis_c, conv = sys.argv[1:9]

def redis_scan_status():
    out = subprocess.run(
        ["docker","exec","-i",redis_c,"sh","-c",
         "redis-cli --scan --pattern 'coupon:issue:status:*' | while read k; do "
         "echo \"==KEY== $k\"; redis-cli HGETALL \"$k\"; done"],
        capture_output=True, text=True, check=True).stdout
    items, cur = [], None
    for line in out.splitlines():
        if line.startswith("==KEY== "):
            cur = {"_key": line[8:]}; items.append(cur); cur["_fields"] = []
        elif cur is not None:
            cur["_fields"].append(line)
    parsed = []
    for it in items:
        f = it["_fields"]
        d = {f[i]: f[i+1] for i in range(0, len(f)-1, 2)}
        d["_key"] = it["_key"]; parsed.append(d)
    return parsed

def mysql(q):
    return subprocess.run(["docker","exec","-e","MYSQL_PWD=root","-i",mysql_c,"mysql","-uroot","flashdeal","-N","-e",q],
                          capture_output=True, text=True, check=True).stdout.strip()

statuses = redis_scan_status()
def residue(sts): return [s for s in sts if s.get("status") == "ACCEPTED"]
if residue(statuses):
    time.sleep(5)                      # ACCEPTED 잔류 재확인 규칙
    statuses = redis_scan_status()
    print("  (ACCEPTED 잔류 감지 — 5s 재폴링 후 확정)")

by = {}
for s in statuses: by.setdefault(s.get("status","?"), []).append(s)
n = {k: len(v) for k, v in by.items()}
terminal = sum(n.get(k,0) for k in ("ISSUED","SOLD_OUT","FAILED"))

k6 = json.load(open(k6_path))["metrics"]
c202 = int(k6.get("issue_cnt_202_accepted", {}).get("count", 0))
c429 = int(k6.get("issue_cnt_429_queue_full", {}).get("count", 0))
coth = int(k6.get("issue_cnt_other_nonhttp_or_5xx", {}).get("count", 0))

row = mysql("SELECT (SELECT total_qty FROM coupon WHERE id=1),(SELECT issued FROM coupon WHERE id=1),(SELECT COUNT(*) FROM coupon_issue WHERE coupon_id=1);").split("\t")
total_qty, counter, rows_n = (int(x) for x in row)

def pct(vals, p):
    if not vals: return None
    vals = sorted(vals); i = max(0, min(len(vals)-1, round(p/100*len(vals))-1))
    return vals[i]
def lat(lst): return [int(s["completedAt"])-int(s["acceptedAt"]) for s in lst
                      if s.get("completedAt") and s.get("acceptedAt")]
e2e = lat(by.get("ISSUED", []))
rej = lat(by.get("SOLD_OUT", []))

# T9: 적재 seq vs 커밋 순서 역전 (상태 해시에 userId 필드가 있을 때만 — 입구 비원자 잡음 포함)
inversions = None
seq_by_user = {s["userId"]: int(s["enqueueSeq"]) for s in by.get("ISSUED", [])
               if s.get("userId") and s.get("enqueueSeq")}
if seq_by_user and rows_n:
    order = [seq_by_user.get(u) for u in mysql(
        "SELECT user_id FROM coupon_issue WHERE coupon_id=1 ORDER BY id;").split()
        if seq_by_user.get(u) is not None]
    inversions = sum(1 for i in range(len(order)) for j in range(i+1, len(order)) if order[i] > order[j])

loss = c202 - terminal                 # 유실분 := 202_count − terminal_count
fails = []
if conv != "CONVERGED": fails.append("NON_CONVERGED")
if mode == "normal":
    if not (rows_n == total_qty == counter): fails.append(f"rows/counter/total 불일치 {rows_n}/{counter}/{total_qty}")
    if not (c202 >= rows_n): fails.append(f"적재 유실 의심 202={c202} < rows={rows_n}")
    if terminal != c202: fails.append(f"터미널 회계 불일치 terminal={terminal} != 202={c202} (유실 {loss})")
elif mode == "loss":                   # v6b 유실 런: 유실 ≥1이 '기록'됨 — 고치지 않는다
    if loss < 1: fails.append(f"유실 미기록 (terminal={terminal} == 202={c202}) — kill 미발동?")
elif mode == "redelivery":             # v6c 재전달 런 (충돌 흡수 로그 grep 증거는 별도 제출)
    # 본질: 재전달이 행 수를 늘리지 못한다 — rows == ISSUED 상태 수 (중복은 흡수돼 같은 requestId가 ISSUED로 닫힘)
    if rows_n != n.get("ISSUED", 0): fails.append(f"rows {rows_n} != ISSUED {n.get('ISSUED',0)} (중복이 행 수를 늘렸거나 유실)")
    if rows_n > total_qty: fails.append(f"초과발급 rows {rows_n} > total {total_qty}")
    if terminal != c202: fails.append(f"터미널 회계 불일치 terminal={terminal} != 202={c202} (PEL 회수가 못 닫음 — v6b와의 대조 실패)")

report = {
    "version": version, "mode": mode, "convergence": conv,
    "k6": {"202": c202, "429": c429, "other": coth},
    "status_counts": n, "terminal_count": terminal, "loss": loss,
    "accepted_residue_requestIds": [s["_key"] for s in residue(statuses)][:50],
    "db": {"total_qty": total_qty, "issued_counter": counter, "rows": rows_n},
    "e2e_ms": {"p50": pct(e2e,50), "p95": pct(e2e,95), "max": max(e2e) if e2e else None, "n": len(e2e)},
    "reject_notify_ms": {"p95": pct(rej,95), "max": max(rej) if rej else None, "n": len(rej)},  # T7
    "t9_inversions": inversions,        # 입구 비원자(INCR↔적재) 잡음 포함
    "verdict": "PASS" if not fails else "FAIL", "fail_reasons": fails,
}
out = f"{results}/e2e/{label}.json"
json.dump(report, open(out, "w"), ensure_ascii=False, indent=2)
print(f"────────── {version} {mode} 터미널 회계 ──────────")
print(f"  202/429/other        : {c202}/{c429}/{coth}")
print(f"  상태 분포             : {n}")
print(f"  terminal == 202 ?    : {terminal} vs {c202}  (유실분 {loss})")
print(f"  rows/counter/total   : {rows_n}/{counter}/{total_qty}")
print(f"  E2E(ISSUED) p50/p95  : {report['e2e_ms']['p50']}/{report['e2e_ms']['p95']} ms (n={len(e2e)})")
print(f"  탈락 통보 p95/max    : {report['reject_notify_ms']['p95']}/{report['reject_notify_ms']['max']} ms (n={len(rej)})")
print(f"  T9 역전(입구잡음포함): {inversions}")
print(f"  판정                  : {report['verdict']}" + (f"  사유: {fails}" if fails else ""))
print(f"  → {out}")
sys.exit(0 if not fails else 4)
PY
}

health() {
    curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1 || {
        echo "  ✗ 앱 미기동 (http://localhost:8080)" >&2; exit 1; }
    echo "  ✓ 앱 up"
}

# ════════════════════════ 모드 분기 ════════════════════════
case "$MODE" in

accuracy)
    V="${2:?accuracy <v6a|v6b|v6c>}"
    echo "================ accuracy $V ================"
    assert_isolated_containers; assert_control_pins; health; assert_ops_pins
    reset_run
    echo "[부하] per-vu-iterations 500VU × 2iter = 1,000건"
    k6 run -e VERSION="$V" -e PROFILE=accuracy "$K6JS" \
        --summary-export="$RESULTS/k6/$V-accuracy.json" >/dev/null 2>&1
    converge_wait "$V" "$RESULTS/depth/$V-accuracy.csv"
    run_mysql -t -e "SELECT (SELECT total_qty FROM coupon WHERE id=1) AS total_qty,
        (SELECT issued FROM coupon WHERE id=1) AS issued_counter,
        (SELECT COUNT(*) FROM coupon_issue WHERE coupon_id=1) AS actual_issued;" \
        | tee "$RESULTS/integrity/$V-accuracy.txt"
    accounting "$V" "$RESULTS/k6/$V-accuracy.json" "$V-accuracy" normal
    ;;

observe)
    V="${2:?observe <v6a|v6b|v6c>}"
    echo "================ observe $V (수렴 게이트 미적용 — 깊이 포화·429 곡선이 산출물) ================"
    assert_isolated_containers; assert_control_pins; health; assert_ops_pins
    reset_run
    run_mysql -e "UPDATE coupon SET total_qty=${PERF_TOTAL_QTY:-100000000} WHERE id=1;"
    CSV="$RESULTS/depth/$V-observe.csv"; echo "elapsed_s,depth,rows" > "$CSV"
    ( START=$(date +%s); while :; do
          echo "$(( $(date +%s) - START )),$(depth_for "$V"),$(rows)" >> "$CSV"; sleep 1
      done ) & SAMPLER=$!
    echo "[부하] warmup 5s + steady 30s, 500VU 지속 (① 접수율 vs ② 커밋율 vs ④ 깊이)"
    k6 run -e VERSION="$V" -e PROFILE=observe "$K6JS" \
        --summary-export="$RESULTS/k6/$V-observe.json" >/dev/null 2>&1
    kill "$SAMPLER" 2>/dev/null || true; wait "$SAMPLER" 2>/dev/null || true
    python3 - "$RESULTS/k6/$V-observe.json" "$CSV" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))["metrics"]
rows = [l.strip().split(",") for l in open(sys.argv[2]).readlines()[1:] if l.strip()]
s202 = int(m.get("steady_cnt_202_accepted",{}).get("count",0))
s429 = int(m.get("steady_cnt_429_queue_full",{}).get("count",0))
commits = (int(rows[-1][2]) - int(rows[0][2])) if len(rows) >= 2 else 0
span = (int(rows[-1][0]) - int(rows[0][0])) or 1
peak = max(int(r[1]) for r in rows) if rows else 0
acc = m.get("steady_accept_dur_202", {})
print(f"  ① 접수 처리량(steady) : {s202/30:.1f}/s  (202 {s202}건/30s, 429 {s429}건)")
print(f"  ② 발급 완료 처리량     : {commits/span:.1f}/s  (샘플 창 {span}s 동안 {commits} 커밋)")
print(f"  ④ 깊이 피크            : {peak}  (상한 핀 10,000 — 포화 후엔 429가 자란다)")
print(f"  접수 지연 p95          : {acc.get('p(95)','n/a')} ms  (라벨: 접수 지연 — 성능 개선으로 읽지 말 것, T4)")
PY
    echo "  (관찰 축 종료 — 잔존은 정상. 다음 런이 배리어→reset으로 처리. 수렴 게이트 미적용)"
    ;;

chaos)
    V="${2:?chaos <v6b|v6c> <AFTER_POP|AFTER_COMMIT> <halt|sigterm>}"
    KP="${3:?kill-point: AFTER_POP|AFTER_COMMIT}"
    SIG="${4:?signal: halt|sigterm}"
    # 회계 모드: halt(=kill -9)는 유실(v6b)/재전달(v6c)이 기대값, sigterm(graceful)은 유실 0이
    # 기대값이라 normal 판정 — T8 대조의 핵심이 "graceful이면 회계가 그대로 닫힌다"이다.
    case "$V" in v6b|v6c) : ;; *) echo "chaos는 v6b|v6c" >&2; exit 1 ;; esac
    if [ "$SIG" = "sigterm" ]; then ACCT_MODE=normal
    elif [ "$V" = "v6b" ]; then ACCT_MODE=loss
    else ACCT_MODE=redelivery; fi
    echo "================ chaos $V kill-point=$KP signal=$SIG (드레인 구간 발동 핀) ================"
    assert_isolated_containers; assert_control_pins; health; assert_ops_pins
    reset_run
    if [ "$ACCT_MODE" = "redelivery" ]; then
        # 재전달 런 한정 total_qty=1,100: AFTER_COMMIT 훅은 발급 성공 경로에서만 지나가므로,
        # qty=100이면 부하 종료(arm) 시점에 발급이 이미 소진돼 드레인 잔여(전부 SOLD_OUT)가
        # 훅을 안 지나가 발화가 불가능하다 (실측: 60s 미발동 2회). 발급 경로를 드레인까지
        # 살리는 최소 변경 — 판정 본질은 "재전달이 행 수를 못 늘린다"(rows == ISSUED 수)로 유지.
        # 'rows==100' 문구와의 편차는 analysis에 기록.
        run_mysql -e "UPDATE coupon SET total_qty=1100 WHERE id=1;"
        echo "  (재전달 런: total_qty=1100 — 발급 경로를 드레인 구간까지 유지)"
    fi
    echo "[부하] 1,000건 적재 (202_count 확정 — kill은 적재 완료 후)"
    k6 run -e VERSION="$V" -e PROFILE=accuracy "$K6JS" \
        --summary-export="$RESULTS/k6/$V-chaos-$KP-$SIG.json" >/dev/null 2>&1
    echo "[카오스] 드레인 구간 — kill 무장"
    if [ "$SIG" = "halt" ]; then
        # 앱 자가 halt: ops로 무장하면 다음으로 kill-point에 도달하는 워커가 Runtime.halt() (kill -9 등가).
        # 주의: 드레인 중 무장은 발화가 ms 단위로 즉각이라 arm HTTP 응답이 halt에 잘릴 수 있다 —
        # curl 실패를 즉시 오류로 보지 않고, 직후 앱 사망 여부로 판정한다 (죽었으면 그게 발화 증거).
        ARM_OK=0
        curl -sf -X POST "http://localhost:8080/api/v6/ops/chaos/arm?point=$KP" >/dev/null && ARM_OK=1 \
            || echo "  … arm 응답 미수신 (즉시 발화로 잘렸을 수 있음 — 사망 감지로 판정)"
        echo "  … 앱 자가 halt 대기 (health down 감지)"
        END=$(( $(date +%s) + 60 ))
        while curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; do
            if [ "$(date +%s)" -ge "$END" ]; then
                [ "$ARM_OK" -eq 0 ] && { echo "  ✗ arm 실패 + 앱 생존 — 무장 자체가 안 됨" >&2; exit 1; }
                echo "  ✗ 60s 내 halt 미발동 (큐가 이미 비었나?)" >&2; exit 4
            fi
            sleep 1
        done
        echo "  ✓ 앱 사망 감지 ($(date +%T))"
    else
        # SIGTERM (T8 graceful 대조): in-flight 종료 대기(핀 10s) 후 내려간다 — APP_PID 필수
        kill -TERM "${APP_PID:?sigterm 모드는 APP_PID env 필수}"
        echo "  ✓ SIGTERM 전송 → graceful shutdown (대기 핀 10s)"
        # halt와 동형의 사망 감지 — graceful 종료 중 리스너가 아직 열려 있어
        # 재기동 대기 curl이 즉시 통과하면 수렴 게이트가 죽어가는 앱 위에서 시작된다 (동기화 깨짐)
        echo "  … 앱 사망 대기 (health down 감지, graceful 핀 10s + 여유)"
        END=$(( $(date +%s) + 60 ))
        while curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; do
            [ "$(date +%s)" -ge "$END" ] && { echo "  ✗ 60s 내 종료 미감지" >&2; exit 4; }
            sleep 1
        done
        echo "  ✓ 앱 사망 감지 ($(date +%T))"
    fi
    echo "[재기동 대기] 워커(사람/세션)가 앱을 재기동하면 수렴 게이트로 진행 (최대 300s)"
    END=$(( $(date +%s) + 300 ))
    until curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; do
        [ "$(date +%s)" -ge "$END" ] && { echo "  ✗ 재기동 타임아웃" >&2; exit 4; }
        sleep 2
    done
    echo "  ✓ 재기동 감지 — 수렴 게이트 진행 (v6c는 XAUTOCLAIM 회수가 여기서 실증된다)"
    converge_wait "$V" "$RESULTS/depth/$V-chaos-$KP-$SIG.csv"
    accounting "$V" "$RESULTS/k6/$V-chaos-$KP-$SIG.json" "$V-chaos-$KP-$SIG" "$ACCT_MODE"
    ;;

selftest-pin)
    LOG="$RESULTS/selftest/pin-mismatch.log"
    echo "================ 자가 검증 ① 고의 핀 불일치 → abort ================" | tee "$LOG"
    assert_isolated_containers 2>&1 | tee -a "$LOG"
    echo "[주입] EXPECT_LOCK_WAIT=999 (실제 50 — 불일치 강제)" | tee -a "$LOG"
    RC=0
    ( EXPECT_LOCK_WAIT=999 EXPECT_ISOLATION="$EXPECT_ISOLATION" assert_control_pins ) >>"$LOG" 2>&1 || RC=$?
    echo "[관측] assert 종료 코드 = $RC (기대 2 = abort)" | tee -a "$LOG"
    if [ "$RC" -eq 2 ]; then
        echo "SELFTEST-PIN: PASS — 핀 불일치가 측정을 중단시켰다" | tee -a "$LOG"
    else
        echo "SELFTEST-PIN: FAIL — abort 미발동 (rc=$RC)" | tee -a "$LOG"; exit 4
    fi
    ;;

selftest-nonconverge)
    LOG="$RESULTS/selftest/nonconverge.log"
    echo "================ 자가 검증 ② 고의 미수렴 → FAIL(NON_CONVERGED) 분류 ================" | tee "$LOG"
    assert_isolated_containers 2>&1 | tee -a "$LOG"
    assert_control_pins 2>&1 | tee -a "$LOG"
    reset_run 2>&1 | tee -a "$LOG"
    echo "[주입] 컨슈머 없는 리스트에 1건 적재 — 깊이 1이 영원히 유지 (타임아웃 핀 ${CONV_TIMEOUT}s 전체 대기)" | tee -a "$LOG"
    run_redis LPUSH "$QUEUE_KEY" '{"selftest":"nonconverge"}' >/dev/null
    set +e
    converge_wait v6b "$RESULTS/depth/selftest-nonconverge.csv" 2>&1 | tee -a "$LOG"
    set -e
    run_redis DEL "$QUEUE_KEY" >/dev/null    # 주입분 정리 (다음 배리어 오염 방지)
    if grep -q 'FAIL(NON_CONVERGED)' "$LOG"; then
        echo "SELFTEST-NONCONVERGE: PASS — 미수렴이 별도 FAIL 사유로 분류됐다" | tee -a "$LOG"
    else
        echo "SELFTEST-NONCONVERGE: FAIL — NON_CONVERGED 분류 미발생" | tee -a "$LOG"; exit 4
    fi
    ;;

*)
    echo "unknown mode: $MODE" >&2; exit 1 ;;
esac
