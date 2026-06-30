#!/bin/bash
# =============================================================
# Phase 3-8 AD-1 — 복합 인덱스 idx_product_category_created 멱등 복원 (정본 DDL)
#
# 왜 이 스크립트가 정본인가:
#   - Product 엔티티에 @Index/@Table(indexes) 선언이 없고 ddl-auto=validate라 앱이 인덱스를
#     만들지 않는다. 시드/마이그레이션에도 영구 생성 DDL이 없어, 측정용 인덱스(measure-index.sh·
#     run-ts1-k6.sh)가 DROP/CREATE로 토글한 뒤 라이브 DB에 인덱스가 사라진 채 드리프트한다.
#   - 상품목록 오리진 쿼리(findByCategoryOrderByCreatedAtDesc)의 지연은 이 인덱스 존재 여부에
#     좌우된다(있으면 type=ref·no filesort ≈수십ms, 없으면 full scan+filesort 수백ms).
#   - 따라서 이 DDL을 정본 스크립트로 둬서 리셋 후에도 동일하게 복원한다.
#
# 멱등: 이미 있으면 skip, 없으면 CREATE. (MySQL은 CREATE INDEX IF NOT EXISTS 미지원 → SHOW INDEX로 분기.)
# MySQL 접근은 docker exec 본체 컨테이너(CLAUDE.md 규율) — 본체 docker-mysql-1/3306 공유 재사용(읽기전용 phase는 아니나
#   이 인덱스 복원은 측정 baseline 전제이므로 본체 DB에 적용).
#
# 사용: ./scripts/ensure-product-index.sh    (MYSQL_CONTAINER 로 본체 컨테이너명 override 가능)
# 측정 전 게이트(M4·AD-1)는 이 스크립트로 인덱스를 보장한 뒤 EXPLAIN 으로 type=ref·no filesort 를 확인·기록한다.
# =============================================================
set -euo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-docker-mysql-1}"
run_mysql() { docker exec -i "${MYSQL_CONTAINER}" mysql -uroot -proot flashdeal "$@" 2>/dev/null; }

INDEX_NAME="idx_product_category_created"
QUERY="SELECT * FROM product WHERE category = '전자기기' ORDER BY created_at DESC LIMIT 20"

echo "[ensure-product-index] target container=${MYSQL_CONTAINER}, index=${INDEX_NAME}"

# 존재 여부: SHOW INDEX 결과 행 수 (Key_name 일치 시 1행 이상)
present=$(run_mysql -N -e "SHOW INDEX FROM product WHERE Key_name='${INDEX_NAME}'" | grep -c . || true)

if [ "${present}" -gt 0 ]; then
  echo "[ensure-product-index] 이미 존재 (rows=${present}) — skip CREATE"
else
  echo "[ensure-product-index] 부재 → CREATE INDEX ${INDEX_NAME} ON product(category, created_at DESC)"
  run_mysql -e "CREATE INDEX ${INDEX_NAME} ON product(category, created_at DESC);"
  echo "[ensure-product-index] ANALYZE TABLE product (stale 통계 방지)"
  run_mysql -e "ANALYZE TABLE product;" >/dev/null
fi

echo "[ensure-product-index] SHOW INDEX (확인):"
run_mysql -e "SHOW INDEX FROM product WHERE Key_name='${INDEX_NAME}';"

echo "[ensure-product-index] EXPLAIN (측정 전 게이트 증거 — type=ref·no filesort 기대):"
run_mysql -e "EXPLAIN ${QUERY};"

echo "[ensure-product-index] DONE"
