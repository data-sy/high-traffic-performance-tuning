#!/bin/bash
# =============================================================
# Phase 3-6 Scale-Up Loader — 영구 대용량(100만) 측정 데이터 적재
#   users 1,000 / product 1,000,000 / orders 500,000 / order_item 1,500,000
#
# 설계 근거 = docs/plan/phase3-6-measurement-plan.md §3 CP-A:
#   - 서버사이드 적재: bash 청크 루프 → INSERT ... SELECT (tally 숫자 테이블)
#     (100K baseline generate-test-data.sh의 1행씩 bash printf는 1M엔 비현실적)
#   - 결정론: created_at = NOW() - INTERVAL (n%365) DAY (RAND 금지),
#             order_item 행수 = JOIN seq5 ON k <= 1+(o.id%5) (seq5 1-base k∈{1..5})
#     RAND는 product_id·quantity·price·user_id 같은 행별 독립 스칼라에만 (정렬축·JOIN 카디널리티엔 금지)
#   - PRODUCT_COUNT 리터럴 보간(${PRODUCT_COUNT}) — @user변수 금지(청크마다 새 docker exec 커넥션 → NULL)
#   - 적재-전 assert: order_item 적재 전 product·orders MIN(id)=1 AND MAX(id)=COUNT(*)=목표
#   - 적재-후 assert: COUNT(order_item)=ORDER_COUNT*3, product_id NOT NULL·1..PRODUCT_COUNT
#   - 음성 테스트: 오염 fixture로 assert가 *실제 abort*하는지 이빨 실증(자기복원)
#   - 청크 5만~10만 행/커밋(1.5M 단일 트랜잭션 금지 — undo/binlog 폭증 + lock_wait_timeout=50s)
#   - 보조 인덱스 미생성(stepN/v2가 측정 시점 생성) + 적재 직후 ANALYZE TABLE 1회
#   - sales_cnt·orders.total = 장식 컬럼(어떤 측정도 안 읽음; 랭킹 v1은 SUM(quantity) 라이브) → non-fatal
#
# 본체 실행(디폴트): ./scripts/scale/load-scale-up.sh  → docker-mysql-1 / db flashdeal.
#   ⚠ 시작 시 4개 테이블 TRUNCATE 후 재적재(인플레이스). down -v 아님 — 볼륨·프로비저닝 보존.
# 컨테이너는 MYSQL_CONTAINER 환경변수로 오버라이드 가능(다른 스택에 적재 시).
# 스모크: PRODUCT_COUNT=1000 ORDER_COUNT=500 ./scripts/scale/load-scale-up.sh (적은 규모로 어댑테이션 검증)
# =============================================================
set -euo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-docker-mysql-1}"
MYSQL_DATABASE="${MYSQL_DATABASE:-flashdeal}"

USER_COUNT="${USER_COUNT:-1000}"
PRODUCT_COUNT="${PRODUCT_COUNT:-1000000}"
ORDER_COUNT="${ORDER_COUNT:-500000}"          # 5의 배수 전제 (1+(id%5) 합 = 정확히 3N ⟺ N%5==0)
PRODUCT_CHUNK="${PRODUCT_CHUNK:-100000}"        # 행/커밋
ORDER_CHUNK="${ORDER_CHUNK:-100000}"
OI_ORDER_CHUNK="${OI_ORDER_CHUNK:-25000}"       # order_item은 orders.id 범위로 청크(×~3 = ~75K행/커밋)

run_mysql() { docker exec -i "${MYSQL_CONTAINER}" mysql -uroot -proot "${MYSQL_DATABASE}" "$@" 2>/dev/null; }
# 스칼라 1개 조회(헤더 없이)
q() { run_mysql -N -e "$1"; }

log() { echo "[$(date '+%H:%M:%S')] $*"; }

echo "============================================================"
echo " Phase 3-6 Scale-Up Loader"
echo " container=${MYSQL_CONTAINER} db=${MYSQL_DATABASE}"
echo " targets: users=${USER_COUNT} product=${PRODUCT_COUNT} orders=${ORDER_COUNT} order_item=$((ORDER_COUNT*3))"
echo "============================================================"

# ORDER_COUNT % 5 가드 (불변식 전제 — 위반 시 적재-후 COUNT assert가 실패하므로 선제 차단)
if [ $((ORDER_COUNT % 5)) -ne 0 ]; then
  echo "ABORT: ORDER_COUNT(${ORDER_COUNT}) % 5 != 0 — order_item 행수 = SUM(1+(id%5)) = 3N 불변식이 깨짐" >&2
  exit 1
fi

# ----------------------------------------------------------
# Step 0: 기존 데이터 클리어 + 보조 테이블(tally, seq5) 구축
# ----------------------------------------------------------
log "[0/7] Clearing tables + building tally/seq5 ..."
# NOTE: MySQL 8.0 innodb_autoinc_lock_mode=2(디폴트)에서 INSERT..SELECT는 auto_increment에
# 갭을 낼 수 있다 → tally의 n 연속성을 autoinc에 의존하지 않는다. _src(값 무관, 행수만)를
# doubling으로 키운 뒤 ROW_NUMBER()로 n=1..count 연속을 *명시 생성*한다.
run_mysql <<'SQL'
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE order_item;
TRUNCATE TABLE orders;
TRUNCATE TABLE product;
TRUNCATE TABLE users;
SET FOREIGN_KEY_CHECKS = 1;

DROP TABLE IF EXISTS _src;
DROP TABLE IF EXISTS _tally;
DROP TABLE IF EXISTS seq5;
CREATE TABLE _src (x TINYINT);
CREATE TABLE seq5 (k INT NOT NULL PRIMARY KEY);
INSERT INTO seq5 (k) VALUES (1),(2),(3),(4),(5);
INSERT INTO _src (x) VALUES (1);
SQL

# _src를 doubling으로 >= PRODUCT_COUNT 행까지 (값 무관, 행수만 — 갭 무관)
cur=$(q "SELECT COUNT(*) FROM _src")
while [ "$cur" -lt "$PRODUCT_COUNT" ]; do
  run_mysql -e "INSERT INTO _src (x) SELECT x FROM _src;"
  cur=$(q "SELECT COUNT(*) FROM _src")
done
# n = 1..PRODUCT_COUNT 연속 (ROW_NUMBER → PK; autoinc 미사용이라 갭 없음)
run_mysql -e "
CREATE TABLE _tally (n BIGINT NOT NULL PRIMARY KEY);
INSERT INTO _tally (n)
SELECT n FROM (SELECT ROW_NUMBER() OVER () AS n FROM _src) z WHERE n <= ${PRODUCT_COUNT};"
read -r tcnt tmin tmax <<< "$(q "SELECT COUNT(*), MIN(n), MAX(n) FROM _tally")"
log "    tally rows=${tcnt} MIN=${tmin} MAX=${tmax} (연속 1..${PRODUCT_COUNT} 기대), seq5=5"
if [ "$tcnt" != "$PRODUCT_COUNT" ] || [ "$tmin" != "1" ] || [ "$tmax" != "$PRODUCT_COUNT" ]; then
  echo "ABORT: tally 연속성 생성 실패 (cnt=${tcnt} min=${tmin} max=${tmax})" >&2; exit 1
fi

# ----------------------------------------------------------
# Step 1: users (deterministic; RAND 불요)
# ----------------------------------------------------------
log "[1/7] users x ${USER_COUNT} ..."
# id=n 명시 삽입 — autoinc INSERT..SELECT 갭 방지(MIN=1·MAX=COUNT 연속 보장)
run_mysql -e "
INSERT INTO users (id, email, name, created_at)
SELECT n, CONCAT('user', n, '@test.com'), CONCAT('User ', n), NOW(6) - INTERVAL (n % 365) DAY
FROM _tally WHERE n <= ${USER_COUNT} ORDER BY n;"

# ----------------------------------------------------------
# Step 2: product x PRODUCT_COUNT (chunked)
#   category = ELT(1+((n-1)%5), ...) 결정론, price/stock = 행별 RAND
# ----------------------------------------------------------
log "[2/7] product x ${PRODUCT_COUNT} (chunk=${PRODUCT_CHUNK}) ..."
lo=1
while [ "$lo" -le "$PRODUCT_COUNT" ]; do
  hi=$(( lo + PRODUCT_CHUNK - 1 ))
  if [ "$hi" -gt "$PRODUCT_COUNT" ]; then hi=$PRODUCT_COUNT; fi
  run_mysql -e "
  INSERT INTO product (id, name, price, category, stock, sales_cnt, created_at)
  SELECT n,
         CONCAT(ELT(1+((n-1)%5),'전자기기','의류','식품','도서','스포츠'), ' 상품 ', n),
         10000 + FLOOR(RAND()*90000),
         ELT(1+((n-1)%5),'전자기기','의류','식품','도서','스포츠'),
         10 + FLOOR(RAND()*500),
         0,
         NOW(6) - INTERVAL (n % 365) DAY
  FROM _tally WHERE n BETWEEN ${lo} AND ${hi} ORDER BY n;"
  log "    product ${hi}/${PRODUCT_COUNT}"
  lo=$(( hi + 1 ))
done

# ----------------------------------------------------------
# Step 3: orders x ORDER_COUNT (chunked)
#   user_id/status = 행별 RAND, created_at 결정론
# ----------------------------------------------------------
log "[3/7] orders x ${ORDER_COUNT} (chunk=${ORDER_CHUNK}) ..."
lo=1
while [ "$lo" -le "$ORDER_COUNT" ]; do
  hi=$(( lo + ORDER_CHUNK - 1 ))
  if [ "$hi" -gt "$ORDER_COUNT" ]; then hi=$ORDER_COUNT; fi
  run_mysql -e "
  INSERT INTO orders (id, user_id, status, total, created_at)
  SELECT n,
         1 + FLOOR(RAND()*${USER_COUNT}),
         ELT(1+FLOOR(RAND()*2),'PAID','DELIVERED'),
         0,
         NOW(6) - INTERVAL (n % 365) DAY
  FROM _tally WHERE n BETWEEN ${lo} AND ${hi} ORDER BY n;"
  log "    orders ${hi}/${ORDER_COUNT}"
  lo=$(( hi + 1 ))
done

# ----------------------------------------------------------
# 적재-전 assert (차단) — order_item 적재 전 product·orders id 연속성/카운트
# ----------------------------------------------------------
log "[pre-assert] product & orders id 연속성 + 카운트 ..."
assert_table_contiguous() {  # $1=table $2=expected
  local t="$1" exp="$2"
  read -r mn mx cnt <<< "$(q "SELECT MIN(id), MAX(id), COUNT(*) FROM ${t}")"
  echo "    ${t}: MIN(id)=${mn} MAX(id)=${mx} COUNT=${cnt} (expected ${exp})"
  if [ "$mn" != "1" ] || [ "$mx" != "$exp" ] || [ "$cnt" != "$exp" ]; then
    echo "ABORT(pre-assert): ${t} MIN(id)=1 AND MAX(id)=COUNT(*)=${exp} 위반" >&2
    return 1
  fi
}
assert_table_contiguous product "$PRODUCT_COUNT"
assert_table_contiguous orders  "$ORDER_COUNT"

# ----------------------------------------------------------
# Step 4: order_item x 3N (chunked over orders.id; JOIN seq5)
#   행수 = 1+(o.id%5), product_id/quantity/price = 행별 RAND
# ----------------------------------------------------------
log "[4/7] order_item x $((ORDER_COUNT*3)) (chunk=${OI_ORDER_CHUNK} orders) ..."
lo=1
while [ "$lo" -le "$ORDER_COUNT" ]; do
  hi=$(( lo + OI_ORDER_CHUNK - 1 ))
  if [ "$hi" -gt "$ORDER_COUNT" ]; then hi=$ORDER_COUNT; fi
  run_mysql -e "
  INSERT INTO order_item (order_id, product_id, quantity, price)
  SELECT o.id,
         1 + FLOOR(RAND()*${PRODUCT_COUNT}),
         1 + FLOOR(RAND()*5),
         10000 + FLOOR(RAND()*90000)
  FROM (SELECT id FROM orders WHERE id BETWEEN ${lo} AND ${hi}) o
  JOIN seq5 s ON s.k <= 1 + (o.id % 5);"
  log "    order_item via orders ${hi}/${ORDER_COUNT} (running COUNT=$(q "SELECT COUNT(*) FROM order_item"))"
  lo=$(( hi + 1 ))
done

# ----------------------------------------------------------
# 적재-후 assert (차단)
# ----------------------------------------------------------
EXPECT_OI=$(( ORDER_COUNT * 3 ))
assert_post_oi_count() {
  local cnt; cnt=$(q "SELECT COUNT(*) FROM order_item")
  echo "    order_item COUNT=${cnt} (expected ${EXPECT_OI})"
  if [ "$cnt" != "$EXPECT_OI" ]; then
    echo "ABORT(post-assert): COUNT(order_item)=${cnt} != ${EXPECT_OI}. 첫 점검 = ORDER_COUNT % 5 == 0 ? (${ORDER_COUNT}%5=$((ORDER_COUNT%5)))" >&2
    return 1
  fi
}
assert_post_oi_fk() {
  read -r nulls mn mx <<< "$(q "SELECT SUM(product_id IS NULL), MIN(product_id), MAX(product_id) FROM order_item")"
  echo "    order_item product_id: NULLs=${nulls} MIN=${mn} MAX=${mx} (range 1..${PRODUCT_COUNT})"
  if [ "$nulls" != "0" ] || [ "$mn" -lt 1 ] || [ "$mx" -gt "$PRODUCT_COUNT" ]; then
    echo "ABORT(post-assert): product_id NULL 또는 범위이탈 (NULLs=${nulls} MIN=${mn} MAX=${mx})" >&2
    return 1
  fi
}
log "[post-assert] order_item 카운트 + product_id 무결성 ..."
assert_post_oi_count
assert_post_oi_fk

# ----------------------------------------------------------
# 음성 테스트 (assert 이빨 실증) — 자기복원
#   오염: order_item 1행 삭제 → COUNT 불변식 위반 → assert가 *실제 abort*해야 함
#   (set -e 하 subshell + `|| rc=$?` 로 테스트 컨텍스트화 → 부모 미abort)
# ----------------------------------------------------------
log "[neg-test] assert 이빨 실증 (order_item 1행 삭제 → abort 기대) ..."
del_oid=$(q "SELECT order_id FROM order_item ORDER BY id DESC LIMIT 1")   # 동일 order로 복원하려 보존
run_mysql -e "DELETE FROM order_item ORDER BY id DESC LIMIT 1;"
log "    오염 후 COUNT=$(q "SELECT COUNT(*) FROM order_item") (order_id=${del_oid} 1행 삭제)"
neg_rc=0
( assert_post_oi_count ) || neg_rc=$?
if [ "$neg_rc" -eq 0 ]; then
  echo "NEGATIVE TEST FAILED: 오염 데이터인데 assert가 통과함 (이빨 없음 = 측정 무효 위험)" >&2
  exit 1
fi
log "    ✅ NEGATIVE TEST PASSED: assert가 오염(COUNT 불일치)에 abort (rc=${neg_rc})"
# 복원: 동일 order_id로 유효 1행 재삽입 → 분포·per-order 카운트 정확 복원(product_id/qty/price는 RAND이라 측정무관)
run_mysql -e "INSERT INTO order_item (order_id, product_id, quantity, price) VALUES (${del_oid}, 1+FLOOR(RAND()*${PRODUCT_COUNT}), 1+FLOOR(RAND()*5), 10000+FLOOR(RAND()*90000));"
log "    복원 후 재검증:"
assert_post_oi_count
assert_post_oi_fk

# ----------------------------------------------------------
# Step 5: 장식 컬럼 집계 (non-fatal — 측정 무영향; 랭킹 v1은 SUM(quantity) 라이브)
# ----------------------------------------------------------
log "[5/7] (non-fatal) product.sales_cnt / orders.total 집계 — 측정 미사용 장식 컬럼, 실패해도 계속 ..."
set +e
run_mysql -e "
UPDATE product p
JOIN (SELECT product_id, SUM(quantity) sc FROM order_item GROUP BY product_id) a
  ON a.product_id = p.id
SET p.sales_cnt = a.sc;" && log "    sales_cnt 갱신 OK" || log "    (warn) sales_cnt 갱신 실패 — non-fatal (측정 미사용)"
set -e

# ----------------------------------------------------------
# Step 6: ANALYZE TABLE (측정 CP EXPLAIN 신뢰성 전제 — bulk load 후 1회)
# ----------------------------------------------------------
log "[6/7] ANALYZE TABLE product, order_item, orders, users ..."
run_mysql -e "ANALYZE TABLE product, order_item, orders, users;" >/dev/null

# ----------------------------------------------------------
# Step 7: 검증 쿼리 로그 (행수/분포/created_at 분산)
# ----------------------------------------------------------
log "[7/7] 검증 쿼리 ..."
echo "--- 행수 ---"
run_mysql -e "
SELECT 'users' tbl, COUNT(*) cnt FROM users
UNION ALL SELECT 'product', COUNT(*) FROM product
UNION ALL SELECT 'orders', COUNT(*) FROM orders
UNION ALL SELECT 'order_item', COUNT(*) FROM order_item;"
echo "--- product 카테고리 분포 (5종 균등) ---"
run_mysql -e "SELECT category, COUNT(*) cnt FROM product GROUP BY category ORDER BY category;"
echo "--- created_at 분산 (product) ---"
run_mysql -e "SELECT MIN(created_at) min_ts, MAX(created_at) max_ts, COUNT(DISTINCT DATE(created_at)) distinct_days FROM product;"
echo "--- order_item 분포 sanity (행수=3N, product_id 범위) ---"
run_mysql -e "SELECT COUNT(*) oi_cnt, MIN(product_id) min_pid, MAX(product_id) max_pid, COUNT(DISTINCT order_id) distinct_orders FROM order_item;"

# scratch 테이블 정리 (측정 쿼리와 무교차이나 위생 — CP-A 리뷰어 note1)
run_mysql -e "DROP TABLE IF EXISTS _src; DROP TABLE IF EXISTS _tally; DROP TABLE IF EXISTS seq5;"
log "    scratch 테이블(_src/_tally/seq5) DROP 완료"

log "DONE — scale-up 적재 완료."
