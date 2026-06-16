#!/bin/bash
# PoC 1 — Prometheus 시계열/메트릭명 실측 (actuator scrape + k6 remote-write)
#
# 메트릭 "이름"을 가정하지 않는다: Prometheus __name__ 라벨값을 직접 덤프해
# k6 remote-write가 붙이는 prefix/suffix(예: k6_ , _total , _p99)를 실명으로 확정한다.
# 결과의 (b) 블록에 찍힌 문자열이 대시보드 PromQL에 그대로 들어갈 메트릭명이다.
set -euo pipefail
PROM=${PROM:-http://localhost:9090}

names() { curl -s "$PROM/api/v1/label/__name__/values"; }
count() { curl -s "$PROM/api/v1/query?query=$1" \
  | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]["result"]))'; }
grep_names() { names | python3 -c "import sys,json
needle='$1'.lower()
for x in sorted(json.load(sys.stdin)['data']):
    if needle in x.lower(): print('    ', x)"; }

echo "=== (a) Actuator scrape — 시계열 존재 확인 (개수 >= 1 이어야 함) ==="
for m in up http_server_requests_seconds_count http_server_requests_seconds_sum \
         hikaricp_connections_active hikaricp_connections_pending hikaricp_connections_max \
         jvm_memory_used_bytes; do
  printf "  %-42s → %s 시계열\n" "$m" "$(count "$m")"
done

echo
echo "=== (b) k6 remote-write — 실제 적재된 메트릭명 (prefix/suffix 확정) ==="
echo "  k6 부하를 remote-write로 1회 돌린 뒤 실행할 것. 아래 목록이 비면 메트릭명/대상 불일치."
echo "  -- 'issue' 포함 --"; grep_names issue
echo "  -- 'accept' 포함 --"; grep_names accept
echo "  -- 'steady' 포함 --"; grep_names steady
echo "  -- 'k6' 로 시작 --"; names | python3 -c "import sys,json
for x in sorted(json.load(sys.stdin)['data']):
    if x.lower().startswith('k6'): print('    ', x)"
