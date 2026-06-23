import http from 'k6/http';
import { check, sleep } from 'k6';

// Phase 3-7 N+1 — 주문 상세 경로 부하 런(p95). 정본: specs/phase3/phase3-7-n-plus-one.md (R3).
//
// 상세는 M≈3(주문당 평균 아이템)이라 statement 수가 적고(≈5 vs 1) 워밍 시 노이즈가 지배 →
// 상세의 우승 근거는 쿼리 수(격리 런)로 가른다. 이 부하 런은 보조 지표(p95)일 뿐(R3 #7).
// orderId도 전체 ~500,000건에 랜덤 분산(워킹셋 > 버퍼풀).
//
// 사용: k6 run -e VERSION=v1 -e VUS=8 -e DURATION=30s -e ORDER_MAX=500000 test/load/nplusone-detail-test.js

const VERSION = __ENV.VERSION || 'v1';
const ORDER_MAX = parseInt(__ENV.ORDER_MAX || '500000');  // orders.id ≈ 1..500000 (IDENTITY)

export const options = {
    vus: parseInt(__ENV.VUS || '8'),
    duration: __ENV.DURATION || '30s',
    thresholds: {
        http_req_failed: ['rate<0.01'],
    },
    tags: { scenario: 'nplusone-detail', version: VERSION },
};

export default function () {
    const orderId = 1 + Math.floor(Math.random() * ORDER_MAX);  // 매 요청 랜덤 분산(R3)
    const res = http.get(`http://localhost:8080/api/${VERSION}/orders/${orderId}`, {
        tags: { version: VERSION },
    });
    check(res, { 'status is 200': (r) => r.status === 200 });
    sleep(0.1);
}
