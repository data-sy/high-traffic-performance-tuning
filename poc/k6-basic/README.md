# PoC 4: k6 Basic Verification

## Pre-check
k6 version
(If not installed: https://grafana.com/docs/k6/latest/set-up/install-k6/)

## Run
1. Start server: ./gradlew bootRun
2. Basic: k6 run poc/k6-basic/test-k6.js
3. Export: k6 run poc/k6-basic/test-k6.js --summary-export=poc/k6-basic/test-result.json

## Check
- k6 completes, checks 3/3 passed
- http_req_duration in output
- test-result.json created
- JSON contains metrics.http_req_duration field
