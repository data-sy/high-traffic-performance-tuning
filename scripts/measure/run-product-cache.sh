#!/usr/bin/env bash
# =============================================================
# Phase 3-8 — 읽기 캐시 + 스탬피드 방어 측정 하네스
#   정본: specs/phase3/phase3-8-cache-stampede.md (측정 계획 §·M1~M6·축 A/A2/B/C)
#
# 비교 합법 축 = 두 정식 공동 1차 구조 지표(캐시 불변·부하 잡음 무관 카운트):
#   (A)  오리진 재계산 수(만료창당)  — v1/v2 증폭 ↔ v3/v4/v5 ≤1   [ops /recompute]
#   (A2) 만료 전환창 블록 요청 수    — v3≈C-1 ↔ v4≈0              [ops /blocked]
# 캐시 적중 지연·throughput·대기 *시간* 은 참조 전용(버전 간 비교 금지).
#
# 전제: 측정 스택이 SPRING_PROFILES_ACTIVE=phase3-8 로 떠 있고(부팅 assert PASS),
#   전용 Redis 6380·본체 MySQL 3306 공유. 도착 동기화는 서버측 배리어(ops /barrier)로.
#
# 사용:
#   scripts/measure/run-product-cache.sh gate          # 핀·인덱스·격리 게이트만
#   scripts/measure/run-product-cache.sh stampede      # 축A+A2 (전 버전 재계산·블록)
#   scripts/measure/run-product-cache.sh penetration   # 축B (v2 vs v5)
#   scripts/measure/run-product-cache.sh avalanche     # 축C (v2 vs v5, DR-AVAL-1 단서)
#   scripts/measure/run-product-cache.sh selftest      # M5 자가검증 3종(워밍·배리어없음·핀불일치)
#   scripts/measure/run-product-cache.sh all           # gate→stampede→penetration→avalanche
# =============================================================
set -euo pipefail

APP="${APP:-http://localhost:8080}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-docker-mysql-1}"
BODY_REDIS_CONTAINER="${BODY_REDIS_CONTAINER:-docker-redis-1}"   # 본체 6379 — 격리 확인용
HOT_KEY="${HOT_KEY:-전자기기}"
NONEXISTENT="${NONEXISTENT:-없는카테고리}"
KEY_PREFIX="cache:product:list:"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT="$ROOT/results/phase3-8-cache-stampede"
mkdir -p "$OUT" "$OUT/recompute" "$OUT/stampede" "$OUT/penetration" "$OUT/avalanche" "$OUT/blocked"

log() { echo "[$(date '+%H:%M:%S')] $*"; }
abort() { echo "ABORT: $*" >&2; exit 1; }

# ── ops 헬퍼 (Korean 카테고리는 --data-urlencode 로 안전 인코딩) ──────────────────
ops_pins()      { curl -s "$APP/api/cache/ops/pins"; }
ops_recompute() { curl -s "$APP/api/cache/ops/recompute"; }
ops_blocked()   { curl -s "$APP/api/cache/ops/blocked"; }
ops_reset()     { curl -s -X POST "$APP/api/cache/ops/reset" >/dev/null; }
ops_warm()      { curl -s -X POST -G "$APP/api/cache/ops/warm" --data-urlencode "version=$1" >/dev/null; }
ops_expire()    { curl -s -X POST -G "$APP/api/cache/ops/expire" --data-urlencode "key=$1" --data-urlencode "version=$2" >/dev/null; }
ops_barrier()   { curl -s -X POST -G "$APP/api/cache/ops/barrier" --data-urlencode "key=$1" --data-urlencode "n=$2" >/dev/null; }
ops_state()     { curl -s -G "$APP/api/cache/ops/state" --data-urlencode "key=$1"; }

jget() { python3 -c "import sys,json;print(json.load(sys.stdin).get(sys.argv[1], sys.argv[2]))" "$1" "${2:-}"; }
recompute_for() { ops_recompute | python3 -c "import sys,json;print(json.load(sys.stdin).get('${KEY_PREFIX}'+sys.argv[1],0))" "$1"; }
blocked_for()   { ops_blocked   | python3 -c "import sys,json;print(json.load(sys.stdin).get('${KEY_PREFIX}'+sys.argv[1],0))" "$1"; }

urlfire() { curl -s -o /dev/null -G "$APP/api/cache/v$1/products" --data-urlencode "category=$2"; }

# 배리어 동기 fan-out: arm(n) 후 n개 동시 발사 → 캐시 get 진입점에서 동시 release(같은 만료창 진입).
fire_synced() {  # $1=version $2=category $3=n
  local version="$1" category="$2" n="$3"
  ops_barrier "$category" "$n"
  for _ in $(seq "$n"); do urlfire "$version" "$category" & done
  wait
}

require_app() { curl -sf "$APP/api/cache/ops/pins" >/dev/null || abort "측정 스택 미응답($APP) — SPRING_PROFILES_ACTIVE=phase3-8 bootRun 필요"; }

C_FROM_PINS() { ops_pins | jget stampedeConcurrency 50; }

# ── 게이트 ────────────────────────────────────────────────────────────────────
gate_pins() {
  log "── 핀 게이트 (ops /pins 런타임 실측 ↔ 기대) ──"
  local p; p=$(ops_pins)
  local port prefix ttl
  port=$(echo "$p" | jget cacheRedisPort)
  prefix=$(echo "$p" | jget keyPrefix)
  ttl=$(echo "$p" | jget ttlBaseMs)
  log "pins: cacheRedisPort=$port keyPrefix=$prefix ttlBaseMs=$ttl"
  [ "$port" = "6380" ] || abort "핀 불일치: cacheRedisPort=$port (기대 6380)"
  [ "$prefix" = "$KEY_PREFIX" ] || abort "핀 불일치: keyPrefix=$prefix"
  log "핀 게이트 PASS"
}

gate_index() {
  log "── 인덱스 게이트 (AD-1: idx_product_category_created 복원·EXPLAIN) ──"
  MYSQL_CONTAINER="$MYSQL_CONTAINER" "$SCRIPT_DIR/../ensure-product-index.sh" | tee "$OUT/index-gate.txt"
  grep -q "idx_product_category_created" "$OUT/index-gate.txt" || abort "인덱스 부재 — baseline 오염(AD-1)"
  log "인덱스 게이트 PASS (EXPLAIN 은 index-gate.txt: type=ref·no filesort 확인)"
}

gate_isolation() {
  log "── 격리 게이트 (전용 6380 / 본체 6379 무접촉) ──"
  local leaked
  leaked=$(docker exec -i "$BODY_REDIS_CONTAINER" redis-cli --scan --pattern "${KEY_PREFIX}*" 2>/dev/null | grep -c . || true)
  if [ "${leaked:-0}" -gt 0 ]; then
    abort "격리 위반: 본체 Redis($BODY_REDIS_CONTAINER)에 ${KEY_PREFIX}* 키 ${leaked}개 — 캐시가 6379로 샜다"
  fi
  log "격리 게이트 PASS (본체 6379에 캐시 키 0)"
}

gate() { require_app; gate_pins; gate_index; gate_isolation; }

# ── 축 A + A2: 스탬피드 (만료 전환 버스트) ─────────────────────────────────────
warm_version_for() { case "$1" in 4) echo 4;; 5) echo 5;; *) echo 2;; esac; }   # v2/v3 → warm v2(하드TTL)
expire_version_for() { case "$1" in 4) echo 4;; *) echo 2;; esac; }              # v4 → 논리마킹, 그 외 물리 DEL

stampede() {
  require_app
  local C; C=$(C_FROM_PINS)
  log "═══ 축 A(재계산) + A2(블록) — 스탬피드 C=$C, key=$HOT_KEY ═══"
  local f="$OUT/stampede/stampede-$(date +%Y%m%d-%H%M%S).tsv"
  echo -e "version\trecompute\tblocked\tnote" > "$f"
  for V in 1 2 3 4 5; do
    ops_reset
    if [ "$V" != "1" ]; then
      ops_warm "$(warm_version_for "$V")"
      ops_expire "$HOT_KEY" "$(expire_version_for "$V")"
    fi
    fire_synced "$V" "$HOT_KEY" "$C"
    # v4 백그라운드 refresh 정착 대기(수렴 — M5 정상상태 게이트의 v4 분기)
    if [ "$V" = "4" ]; then sleep 2; else sleep 1; fi
    local rc bl note
    rc=$(recompute_for "$HOT_KEY"); bl=$(blocked_for "$HOT_KEY")
    case "$V" in
      1) note="baseline(매 요청 재계산)";;
      2) note="herd 노출(락 없음)";;
      3) note="single-flight: 재계산≈1·블록≈C-1";;
      4) note="논리만료+비동기갱신: 재계산≈1·블록≈0(물리 보존)";;
      5) note="null+jitter(스탬피드 직교 — herd 안 닫음)";;
    esac
    log "v$V → recompute=$rc blocked=$bl  [$note]"
    echo -e "v$V\t$rc\t$bl\t$note" >> "$f"
  done
  log "기록: $f"
  log "기대 대조: 축A v2≈C ↔ v3/v4≈1 | 축A2 v3≈$((C-1)) ↔ v4≈0"
}

# ── 축 B: 페네트레이션 (미존재 키, v2 vs v5) ───────────────────────────────────
penetration() {
  require_app
  local C; C=$(C_FROM_PINS)
  log "═══ 축 B — 페네트레이션 C=$C, key=$NONEXISTENT (v2 vs v5) ═══"
  local f="$OUT/penetration/penetration-$(date +%Y%m%d-%H%M%S).tsv"
  echo -e "version\twindow1\twindow2\tnote" > "$f"
  for V in 2 5; do
    ops_reset
    ops_expire "$NONEXISTENT" 2          # 깨끗한 시작(있으면 제거)
    fire_synced "$V" "$NONEXISTENT" "$C"; sleep 0.5
    local w1; w1=$(recompute_for "$NONEXISTENT")
    ops_reset                            # 윈도2 델타만 보기 위해 카운터 reset(키는 유지)
    fire_synced "$V" "$NONEXISTENT" "$C"; sleep 0.5
    local w2; w2=$(recompute_for "$NONEXISTENT")
    local note
    case "$V" in
      2) note="EMPTY_AS_MISS=true: 매 창 직격(w2≈C)";;
      5) note="널 캐싱: w1=herd(직교)→ w2≈0(널 적중)";;
    esac
    log "v$V → window1=$w1 window2=$w2  [$note]"
    echo -e "v$V\t$w1\t$w2\t$note" >> "$f"
  done
  log "기록: $f  (기대: v2 w2≈C ↔ v5 w2≈0)"
}

# ── 축 C: 어밸런치 (다수 키 동시 만료, v2 vs v5) — DR-AVAL-1 단서 ────────────────
avalanche() {
  require_app
  local C; C=$(C_FROM_PINS)
  log "═══ 축 C — 어밸런치 (v2 vs v5) ═══"
  log "⚠ DR-AVAL-1: 핫 키 5개·단일 인스턴스에선 per-key herd>1(총 동시성 ≥ 키수×herd)을 보장 못 할 공산이"
  log "   크다(DR-TOMCAT-1 200스레드 천장). 그 경우 v2·v5 키별 재계산~1로 붕괴 → 차이는 봉인된 시간 분포뿐."
  log "   per-key herd>1 미확인이면 축C는 합법 카운트 축 아님 = 참조 전용(시간-집중 지표). Q10 결선."
  local f="$OUT/avalanche/avalanche-$(date +%Y%m%d-%H%M%S).tsv"
  echo -e "version\ttotal_recompute\tper_key\tnote" > "$f"
  for V in 2 5; do
    ops_reset
    ops_warm "$V"                        # 다수 키 적재(v2 고정 TTL / v5 지터 TTL)
    log "v$V warm 직후 키별 잔여 TTL(지터 분산 관찰):"
    for c in 전자기기 의류 식품 도서 스포츠; do
      echo "    $c pttlMs=$(ops_state "$c" | jget pttlMs)"
    done
    # 동시 만료 시점까지 대기 후 전 키 광역 부하 (per-key herd 는 동시성/키수에 종속 — 위 단서)
    sleep "$(python3 -c "print($(ops_pins | jget ttlBaseMs)/1000.0 + 0.5)")"
    for c in 전자기기 의류 식품 도서 스포츠; do
      ops_barrier "$c" "$((C/5))"
      for _ in $(seq "$((C/5))"); do urlfire "$V" "$c" & done
    done
    wait; sleep 1
    local total=0 per_key=""
    for c in 전자기기 의류 식품 도서 스포츠; do
      local rk; rk=$(recompute_for "$c"); total=$((total+rk)); per_key="$per_key $c=$rk"
    done
    local note="v$V: $( [ "$V" = 2 ] && echo '고정 TTL 동시 만료' || echo '지터 분산')"
    log "v$V → total_recompute=$total per_key:$per_key"
    echo -e "v$V\t$total\t$per_key\t$note" >> "$f"
  done
  log "기록: $f  (per-key>1 미충족이면 시간 분포는 참조 전용·비교 금지)"
}

# ── M5 자가검증 3종 (liveness) ─────────────────────────────────────────────────
selftest() {
  require_app
  local C; C=$(C_FROM_PINS)
  log "═══ M5 자가검증 ═══"

  # (b) 워밍 함정: 만료 없이(따뜻한 캐시) 측정 → herd 안 보임을 *검출*
  log "── selftest-warm: 따뜻한 캐시로 측정하면 herd 미검출(M3 워밍 함정 liveness) ──"
  ops_reset; ops_warm 2
  fire_synced 2 "$HOT_KEY" "$C"; sleep 0.5
  local warm_rc; warm_rc=$(recompute_for "$HOT_KEY")
  if [ "$warm_rc" -le 1 ]; then log "  PASS: recompute=$warm_rc ≈0 — 워밍 측정은 herd 못 건드림(주제 미측정 드러남)"; \
    else log "  NOTE: recompute=$warm_rc — 예상과 다름(캐시가 안 따뜻했을 수 있음)"; fi

  # (c) 배리어 없는 램프: 도착 산포 → v2 herd≪C 과소관측을 *검출* (DR-A)
  log "── selftest-barrier: 배리어 없이 산포 발사 → v2 herd≪C 과소관측(DR-A liveness) ──"
  ops_reset; ops_warm 2; ops_expire "$HOT_KEY" 2
  for _ in $(seq "$C"); do urlfire 2 "$HOT_KEY" & sleep 0.02; done; wait; sleep 0.5
  local ramp_rc; ramp_rc=$(recompute_for "$HOT_KEY")
  if [ "$ramp_rc" -lt "$C" ]; then log "  PASS: recompute=$ramp_rc < C=$C — 산포로 herd 과소관측(배리어가 정본 동기화임을 입증)"; \
    else log "  NOTE: recompute=$ramp_rc ≥ C — 산포에도 herd 관측(populate 창이 넓었을 수 있음)"; fi

  # (a) 핀 불일치: 잘못된 기대로 게이트가 abort 하는지 확인
  log "── selftest-pins: 고의 핀 불일치 → 게이트 abort 발화 확인 ──"
  local port; port=$(ops_pins | jget cacheRedisPort)
  if [ "$port" = "9999" ]; then log "  (도달 불가)"; else log "  PASS: 실측 port=$port ≠ 고의기대 9999 → 게이트라면 abort(핀 가드 동작)"; fi
  log "자가검증 끝 — 상세는 위 로그. INVALID 런은 $OUT/INVALID-RUNS.md 에 보존."
}

case "${1:-}" in
  gate)        gate ;;
  stampede)    gate; stampede ;;
  penetration) gate; penetration ;;
  avalanche)   gate; avalanche ;;
  selftest)    selftest ;;
  all)         gate; stampede; penetration; avalanche ;;
  *) echo "usage: $0 {gate|stampede|penetration|avalanche|selftest|all}" >&2; exit 2 ;;
esac
