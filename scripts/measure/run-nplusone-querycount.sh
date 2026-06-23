#!/usr/bin/env bash
# Phase 3-7 N+1 — 격리 런(VU=1, 단발 요청) 쿼리 수 측정.
# 정본: specs/phase3/phase3-7-n-plus-one-draft.md (R2: 쿼리 수는 격리 런, p95는 부하 런으로 분리).
#
# 방법(스펙 R2): org.hibernate.SQL: DEBUG 로그의 statement 이벤트 수를 센다.
#   - format_sql=true라도 멀티라인 SQL의 로거 필드는 첫 줄에만 찍히므로 `org.hibernate.SQL` 매칭 = 이벤트 수.
#   - VU=1 단발이라 다른 요청 인터리브 없음(Statistics 전역 누적 함정 회피).
#
# 전제: 앱이 이미 "측정할 버전의 올바른 설정"으로 떠 있고 SQL DEBUG가 $APP_LOG 파일로 들어간다.
#   - 부팅/토글(v2 EAGER 소스 토글·v3 batch 프로퍼티)은 상위 오케스트레이터(run-nplusone-measure.sh)가 한다.
#   - 이 스크립트는 "현재 떠 있는 앱"의 한 버전 경로(목록·상세)만 격리 카운트한다.
#
# 사용: APP_LOG=results/phase3-7-n-plus-one/app.log \
#        scripts/measure/run-nplusone-querycount.sh <VERSION> <USER_ID> <ORDER_ID>
set -euo pipefail

VERSION="${1:?VERSION 필요 (예: v1)}"
USER_ID="${2:?USER_ID 필요}"
ORDER_ID="${3:?ORDER_ID 필요}"
APP_LOG="${APP_LOG:?APP_LOG 경로 필요}"
BASE="${BASE_URL:-http://localhost:8080}"

[[ -f "$APP_LOG" ]] || { echo "ABORT: APP_LOG 없음: $APP_LOG" >&2; exit 1; }

# 한 요청의 SQL statement 이벤트 수 = 요청 직후 로그 델타에서 `org.hibernate.SQL` 카운트.
count_one() {
    local url="$1"
    local before after status
    before=$(wc -l < "$APP_LOG")
    status=$(curl -s -o /dev/null -w '%{http_code}' "$url")
    sleep 0.4                                   # 비동기 로그 flush 안정화
    after=$(wc -l < "$APP_LOG")
    local q
    q=$(tail -n +"$((before + 1))" "$APP_LOG" | grep -cE 'org\.hibernate\.SQL' || true)
    echo "$status $q"
}

echo "=== Phase 3-7 query-count [$VERSION] (격리 VU=1) ==="
echo "APP_LOG=$APP_LOG  user=$USER_ID  order=$ORDER_ID"

read -r LIST_STATUS LIST_Q < <(count_one "$BASE/api/$VERSION/users/$USER_ID/orders")
read -r DET_STATUS  DET_Q  < <(count_one "$BASE/api/$VERSION/orders/$ORDER_ID")

echo "목록 GET /api/$VERSION/users/$USER_ID/orders  -> HTTP $LIST_STATUS, 쿼리 $LIST_Q"
echo "상세 GET /api/$VERSION/orders/$ORDER_ID       -> HTTP $DET_STATUS, 쿼리 $DET_Q"

# 게이트: 두 경로 모두 200이어야 측정 유효.
if [[ "$LIST_STATUS" != "200" || "$DET_STATUS" != "200" ]]; then
    echo "ABORT: 비정상 HTTP (목록 $LIST_STATUS / 상세 $DET_STATUS)" >&2
    exit 2
fi

# 결과 행(추후 표 집계용 TSV). version<TAB>list_q<TAB>detail_q
printf '%s\t%s\t%s\n' "$VERSION" "$LIST_Q" "$DET_Q" >> "${OUT_TSV:-/dev/null}"
echo "OK"
