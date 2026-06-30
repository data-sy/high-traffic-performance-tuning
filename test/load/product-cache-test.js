import http from 'k6/http';
import { check, sleep } from 'k6';

// Phase 3-8 캐시 사다리 — k6 부하 생성기 (정본: specs/phase3/phase3-8-cache-stampede.md).
//
// 역할 분담:
//   - 구조 1차 지표(축A 재계산 수 · 축A2 블록 수)는 ops 카운터 + 서버측 배리어 동기 fan-out 으로 잰다
//     (run-product-cache.sh) — k6 램프는 도착 타이밍을 보장 못 하므로(DR-A) 이 축의 정본이 아니다.
//   - 이 k6 스크립트는 (a) normal hit-path throughput/p95(참조 전용·버전 간 비교 금지) 와
//     (b) penetration 지속 부하(미존재 키 플러드) 의 부하 생성에 쓴다.
//
// 사용:
//   k6 run -e VERSION=v2 -e SCENARIO=normal      -e VUS=8 -e DURATION=30s test/load/product-cache-test.js
//   k6 run -e VERSION=v5 -e SCENARIO=penetration -e VUS=50 -e DURATION=20s test/load/product-cache-test.js

const VERSION = __ENV.VERSION || 'v2';           // v1~v5
const SCENARIO = __ENV.SCENARIO || 'normal';     // normal | penetration
const HOT = ['전자기기', '의류', '식품', '도서', '스포츠'];
const NONEXISTENT = __ENV.NONEXISTENT || '없는카테고리';

export const options = {
    vus: parseInt(__ENV.VUS || '8'),
    duration: __ENV.DURATION || '30s',
    thresholds: {
        // 참조 부하 비포화 가드(버스트축 아님 — 정상상태). 위반 시 부하점 재설정.
        http_req_failed: ['rate<0.01'],
    },
    tags: { scenario: `cache-${SCENARIO}`, version: VERSION },
};

export default function () {
    let category;
    if (SCENARIO === 'penetration') {
        category = NONEXISTENT;                                   // 미존재 키 플러드(축B)
    } else {
        category = HOT[Math.floor(Math.random() * HOT.length)];  // 핫 키 5종 분산(정상 hit-path)
    }
    const url = `http://localhost:8080/api/cache/${VERSION}/products?category=${encodeURIComponent(category)}`;
    const res = http.get(url, { tags: { version: VERSION } });
    // 503 = v3 락 대기 폴백(예외 경로) — 정상상태 부하에선 불변상 미발화. 발생 시 http_req_failed 로 드러남.
    check(res, { 'status is 200': (r) => r.status === 200 });
    sleep(0.1);
}
