#!/bin/sh
# Phase 3-3 동시성 측정 러너 (락 버전 파라미터화 — v1..v5 재사용 하네스).
#   usage: scripts/measure/run-coupon-concurrency.sh <LOCK_VERSION>   (예: v1, v2)
#   두 축을 분리 측정한다:
#     - 정확성 축: constant-vus 500 / 10s (v1과 동일 → 직접 비교). 초과발급이 막히나(actual_issued).
#     - 성능 축:   warmup 5s + steady 30s. 락 직렬화 오버헤드(throughput/p95/p99/409율, steady-only).
#   env: VUS, DURATION(정확성), WARMUP, STEADY(성능), COMPOSE_PROJECT_NAME
set -eu

VERSION="${1:?usage: run-coupon-concurrency.sh <LOCK_VERSION> (예: v1, v2)}"
VUS="${VUS:-500}"
DURATION="${DURATION:-10s}"
WARMUP="${WARMUP:-5s}"
STEADY="${STEADY:-30s}"
# 성능 축 전용 대용량 한도: total_qty=100이면 warmup 안에 즉시 매진 → steady 전부 409라
# 성공 throughput(=락 직렬화 비용)이 0으로 측정 불가. 성공 경로를 steady 내내 지속시키려면 한도를 키운다.
# (정확성 축은 여전히 total_qty=100으로 초과발급을 테스트 — 두 축은 쿠폰 설정이 다르다.)
PERF_TOTAL_QTY="${PERF_TOTAL_QTY:-100000000}"

# ── 통제변수 핀 (문서 핀 + 런타임 assert) ──
# 값을 인프라로 강제(compose recreate)하지 않고, 측정 시작 시점에 실측해 핀과 다르면 중단한다.
# "문서 가정"을 "검증된 가정"으로 닫음 (스키마/wrapper jar 누락과 같은 클래스의 암묵 환경의존 방지).
EXPECT_LOCK_WAIT="${EXPECT_LOCK_WAIT:-50}"
EXPECT_ISOLATION="${EXPECT_ISOLATION:-REPEATABLE-READ}"

# ── run_mysql: 샌드박스 컨테이너 직격 (본체 컨테이너 절대 금지) ──
# .env가 docker/ 하위라 셸엔 자동 주입 안 되므로 기본값을 docker_sandbox로.
run_mysql() { docker exec -i "${COMPOSE_PROJECT_NAME:-docker_sandbox}-mysql-1" mysql -uroot -proot flashdeal "$@"; }
run_redis() { docker exec -i "${COMPOSE_PROJECT_NAME:-docker_sandbox}-redis-1" redis-cli "$@"; }
# 측정 전 잔존 분산 락 키 제거(직전 크래시가 leaseTime 긴 락을 남기면 v4/v5 측정 오염). coupon 락만 타깃.
flush_locks() {
    docker exec -i "${COMPOSE_PROJECT_NAME:-docker_sandbox}-redis-1" sh -c \
        "redis-cli --scan --pattern 'lock:coupon:*' | xargs -r redis-cli DEL" >/dev/null 2>&1 || true
}

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# 결과 디렉토리 override (phase3-3-2 재실험은 RESULTS_DIR로 분리해 phase3-3 산출물 덮어쓰기 방지).
# 미지정 시 기존 phase3-3 경로(하위 호환).
RESULTS="${RESULTS_DIR:-$ROOT/results/phase3-3-concurrency}"
K6JS="$ROOT/test/load/coupon-test.js"
mkdir -p "$RESULTS/k6" "$RESULTS/integrity"

steady_seconds() { echo "$STEADY" | sed 's/s$//'; }

health() {
    if ! curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; then
        echo "  ✗ 서버 미기동 (http://localhost:8080). bootRun 후 재시도." >&2
        exit 1
    fi
}

echo "========================= $VERSION ========================="
health
echo "  ✓ server up"

# 통제변수 런타임 assert — 핀과 불일치하면 측정 중단 (교란된 substrate에서 비교 금지)
echo "[통제변수 assert] 런타임 실측 vs 핀..."
CV="$(run_mysql -N -e 'SELECT @@innodb_lock_wait_timeout, @@transaction_isolation;')"
ACT_LW="$(echo "$CV" | awk '{print $1}')"
ACT_ISO="$(echo "$CV" | awk '{print $2}')"
if [ "$ACT_LW" != "$EXPECT_LOCK_WAIT" ] || [ "$ACT_ISO" != "$EXPECT_ISOLATION" ]; then
    echo "  ✗ 통제변수 불일치 — 측정 중단:" >&2
    echo "     innodb_lock_wait_timeout=$ACT_LW (기대 $EXPECT_LOCK_WAIT)" >&2
    echo "     transaction_isolation=$ACT_ISO (기대 $EXPECT_ISOLATION)" >&2
    exit 2
fi
echo "  ✓ innodb_lock_wait_timeout=$ACT_LW, isolation=$ACT_ISO (핀 일치)"

# Redis 도달 확인 (v4/v5 분산 락 substrate)
if [ "$(run_redis PING 2>/dev/null)" != "PONG" ]; then
    echo "  ✗ Redis 미도달 (${COMPOSE_PROJECT_NAME:-docker_sandbox}-redis-1) — 측정 중단" >&2
    exit 2
fi
echo "  ✓ Redis PONG"

# ───────────────────────── 정확성 축 ─────────────────────────
echo ""
echo "[정확성 축] reset(+락키 flush) → k6 constant-vus ${VUS}/${DURATION} → 정합성"
run_mysql < "$ROOT/scripts/coupon/reset-coupon.sql"
flush_locks
k6 run -e VERSION="$VERSION" -e PROFILE=accuracy -e VUS="$VUS" -e DURATION="$DURATION" \
    "$K6JS" --summary-export="$RESULTS/k6/$VERSION-accuracy.json" >/dev/null 2>&1
sleep 2   # settle: in-flight 커밋이 COUNT보다 늦게 끝나는 좁은 창 방지 (F3)
run_mysql -t -e "
SELECT
  (SELECT total_qty FROM coupon WHERE id = 1)            AS total_qty,
  (SELECT issued    FROM coupon WHERE id = 1)            AS issued_counter,
  (SELECT COUNT(*) FROM coupon_issue WHERE coupon_id=1)  AS actual_issued;
" | tee "$RESULTS/integrity/$VERSION.txt"

# ───────────────────────── 성능 축 ─────────────────────────
echo ""
echo "[성능 축] reset(total_qty=${PERF_TOTAL_QTY}) → k6 warmup ${WARMUP} + steady ${STEADY} (steady-only 집계)"
run_mysql < "$ROOT/scripts/coupon/reset-coupon.sql"
run_mysql -e "UPDATE coupon SET total_qty=${PERF_TOTAL_QTY} WHERE id=1;"
flush_locks
k6 run -e VERSION="$VERSION" -e PROFILE=performance -e VUS="$VUS" -e WARMUP="$WARMUP" -e STEADY="$STEADY" \
    "$K6JS" --summary-export="$RESULTS/k6/$VERSION-performance.json" >/dev/null 2>&1

# ───────────────────────── 통일 출력 ─────────────────────────
echo ""
ACC_JSON="$RESULTS/k6/$VERSION-accuracy.json"
PERF_JSON="$RESULTS/k6/$VERSION-performance.json"
STEADY_S="$(steady_seconds)"
INTEG="$RESULTS/integrity/$VERSION.txt"

python3 - "$VERSION" "$ACC_JSON" "$PERF_JSON" "$STEADY_S" "$INTEG" <<'PY'
import json, re, sys
version, acc_path, perf_path, steady_s, integ_path = sys.argv[1:6]
steady_s = float(steady_s)

def load(p):
    with open(p) as f: return json.load(f).get("metrics", {})
def cnt(m, k): return int(m.get(k, {}).get("count", 0))
def p(m, k, stat):
    v = m.get(k, {})
    return v.get(stat, v.get(stat.replace("p(","p").replace(")",""), None))

acc = load(acc_path)
perf = load(perf_path)

# 정합성 (integrity 파일에서 actual/counter 파싱)
nums = re.findall(r"\|\s*(\d+)\s*\|\s*(\d+)\s*\|\s*(\d+)\s*\|", open(integ_path).read())
total, counter, actual = (int(x) for x in nums[-1]) if nums else (0,0,0)

print(f"────────── {version} 결과 (통일 포맷) ──────────")
print("[정확성 축]  constant-vus 500 / 10s")
print(f"  total_qty / issued_counter / actual_issued : {total} / {counter} / {actual}")
print(f"  성공200 / 한도409 / other(비-HTTP·5xx)       : {cnt(acc,'issue_cnt_200_success')} / {cnt(acc,'issue_cnt_409_soldout')} / {cnt(acc,'issue_cnt_other_nonhttp_or_5xx')}")
sig = "PASS" if (actual == counter == total and total > 0) else "FAIL"
print(f"  성공 시그니처 (actual==counter==total_qty)   : {sig}  (락 정합 = PASS, v1같은 초과 = FAIL)")

s200 = cnt(perf,'steady_cnt_200_success')
s409 = cnt(perf,'steady_cnt_409_soldout')
s503 = cnt(perf,'steady_cnt_503_lockreject')
soth = cnt(perf,'steady_cnt_other')
stot = s200+s409+s503+soth
def pct(n): return f"{100.0*n/stot:.1f}%" if stot else "n/a"
def trend(m,k):
    d=m.get(k,{})
    if not d: return "n/a"
    return f"p95={d.get('p(95)','?'):.1f}ms p99={d.get('p(99)','?'):.1f}ms" if isinstance(d.get('p(95)'),(int,float)) else "n/a"
print("[성능 축]   warmup 5s + steady 30s  (steady-only)")
s503pool = cnt(perf,'steady_cnt_503_pool_timeout')
s503lock = cnt(perf,'steady_cnt_503_lock_wait')
s503noacq = cnt(perf,'steady_cnt_503_not_acquired')
print(f"  성공 throughput (200/s)   : {s200/steady_s:.1f}/s   (steady 성공 {s200}건 / {steady_s:.0f}s)")
print(f"  성공(200) 지연            : {trend(perf,'steady_dur_200_success')}")
print(f"  락거절(503) 지연          : {trend(perf,'steady_dur_503_lockreject')}")
print(f"  steady 구성 200/409/503/other : {s200}/{s409}/{s503}/{soth}  (409율={pct(s409)}, 503율={pct(s503)})")
if s503 or soth:
    print(f"  503 분해 ⓐ풀/ⓑ행락/ⓒ미획득  : {s503pool}/{s503lock}/{s503noacq}  (미분류 503={s503-s503pool-s503lock-s503noacq}, other(5xx/비-HTTP)={soth})")
PY

echo ""
echo "완료: $ACC_JSON , $PERF_JSON , $INTEG"
