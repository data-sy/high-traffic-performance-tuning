#!/bin/sh
# phase 3-9 구조 카운트 프로브 (M1) — 동시성 0 단일요청 + 코드 정적 카운트로 in-lock DB 왕복 수 *확정*.
#   usage: scripts/coupon/probe-occupancy.sh <cell>   (cell: r0|r2|r3|r4)
# 포화 인터리브 추론 금지: 프로브는 정의상 성공 1건이라 소진/롤백 오염이 없다(M1 성공 표본).
#
# 게이트 3종(미충족 시 비영):
#   (i)  칸별 프로브 카운트 == 기대(R0=4·R2=4·R3=3·R4=2)
#   (ii) 전 칸 flush 순서 게이트 == 기대 시퀀스(특히 R2 existsBy 앞 UPDATE coupon 금지 = 조기 auto-flush 적발)
#
# env: APP_LOG(앱 콘솔 로그 경로), COMPOSE_PROJECT_NAME(기본 docker), BASE(기본 localhost:8080)
set -eu

CELL="${1:?usage: probe-occupancy.sh <cell> (r0|r2|r3|r4)}"
BASE="${BASE:-http://localhost:8080}"
APP_LOG="${APP_LOG:-/Users/owner/high-traffic-performance-tuning/build/occupancy-app.log}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
run_mysql() { docker exec -i "${COMPOSE_PROJECT_NAME:-docker}-mysql-1" mysql -uroot -proot flashdeal "$@"; }

# 칸별 기대: 카운트 + flush 순서 시퀀스(콤마 구분 타입)
#   FORUPDATE=FOR UPDATE SELECT · ISSUESEL=existsBy SELECT(from coupon_issue) · INSERT · UPDCOUPON=update coupon
case "$CELL" in
  r0|r1) EXPECT_COUNT=4; EXPECT_SEQ="FORUPDATE,ISSUESEL,INSERT,UPDCOUPON" ;;
  r2)    EXPECT_COUNT=4; EXPECT_SEQ="FORUPDATE,ISSUESEL,INSERT,UPDCOUPON" ;;
  r3)    EXPECT_COUNT=3; EXPECT_SEQ="UPDCOUPON,ISSUESEL,INSERT" ;;
  r4)    EXPECT_COUNT=2; EXPECT_SEQ="UPDCOUPON,INSERT" ;;
  *) echo "  ✗ 알 수 없는 cell: $CELL" >&2; exit 2 ;;
esac

if [ ! -f "$APP_LOG" ]; then
    echo "  ✗ APP_LOG 없음: $APP_LOG (측정 앱을 이 경로로 리다이렉트해 기동)" >&2; exit 2
fi

echo "========================= 프로브 $CELL ========================="
# canonical seed 리셋(total_qty=100·issued=0·coupon_issue 비움) → 프로브는 성공 1건
run_mysql < "$ROOT/scripts/coupon/reset-coupon.sql"

START=$(wc -l < "$APP_LOG")
USERID=999999
HTTP=$(curl -s -o /tmp/probe-body.$$ -w '%{http_code}' -X POST "$BASE/api/$CELL/coupons/issue" \
    -H 'Content-Type: application/json' -d "{\"couponId\":1,\"userId\":$USERID}")
sleep 0.6   # 로그 flush 대기
BODY=$(cat /tmp/probe-body.$$ 2>/dev/null); rm -f /tmp/probe-body.$$
echo "  HTTP $HTTP  body=$BODY"
if [ "$HTTP" != "200" ]; then
    echo "  ✗ 프로브 요청이 200이 아님 — 성공 표본 아님, 카운트 무효" >&2; exit 3
fi

# 이번 요청이 낸 신규 로그 라인만 (동시성 0이라 전부 이 요청 소산)
NEW=$(tail -n "+$((START+1))" "$APP_LOG")

# 순서 시퀀스 추출: coupon 관련 SQL 문장만, 등장 순서대로 타입 태깅
SEQ=$(printf '%s\n' "$NEW" | awk '
  /org.hibernate.SQL/ || /^Hibernate:/ || /select |insert |update / {
    line=tolower($0)
    if (line ~ /for update/)                       print "FORUPDATE"
    else if (line ~ /insert into coupon_issue/)    print "INSERT"
    else if (line ~ /update coupon/)               print "UPDCOUPON"
    else if (line ~ /from coupon_issue/)           print "ISSUESEL"
  }' | paste -sd, -)

COUNT=$(printf '%s' "$SEQ" | awk -F, '{print ($1==""?0:NF)}')

echo "  [프로브 카운트]  관측=$COUNT  기대=$EXPECT_COUNT"
echo "  [flush 순서]     관측=$SEQ"
echo "                   기대=$EXPECT_SEQ"

FAIL=0
if [ "$COUNT" != "$EXPECT_COUNT" ]; then
    echo "  ✗ (i) 프로브 카운트 불일치 — 표와 어긋남" >&2; FAIL=1
fi
if [ "$SEQ" != "$EXPECT_SEQ" ]; then
    echo "  ✗ (ii) flush 순서 불일치 — 조기 auto-flush 오염 의심(R2: existsBy 앞 UPDATE coupon 금지)" >&2; FAIL=1
fi
if [ "$FAIL" = 0 ]; then
    echo "  ✓ 게이트 통과: 카운트·순서 모두 기대와 일치"
fi
exit "$FAIL"
