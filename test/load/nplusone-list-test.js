import http from 'k6/http';
import { check, sleep } from 'k6';

// Phase 3-7 N+1 — 주문 목록 경로 부하 런(p95). 정본: specs/phase3/phase3-7-n-plus-one-draft.md (R3).
//
// R3 버퍼 워밍 함정 회피: userId를 전체 ~1,000명에 매 요청 랜덤 분산(워킹셋 > 버퍼풀 128MiB).
// 단일 핫 userId면 첫 요청이 그 유저를 버퍼풀에 적재→이후 디스크 I/O 0(완전 워밍)→v1 p95≈v4 p95로
// N+1을 못 가른다. 분산으로 실제 디스크 I/O를 일으켜 구조적 차이가 p95에 드러나게 한다.
//
// 비포화: VUS는 maximumPoolSize(=10) 미만으로 잡아 Hikari pending==0(게이트는 measure 스크립트가 확인).
// v1은 요청당 ~1+2N 쿼리·커넥션 장기 점유라 가장 먼저 포화 → 모든 버전 비포화 부하점을 VUS로 통제.
//
// 사용: k6 run -e VERSION=v1 -e VUS=8 -e DURATION=30s -e USER_MAX=1000 test/load/nplusone-list-test.js

const VERSION = __ENV.VERSION || 'v1';
const USER_MAX = parseInt(__ENV.USER_MAX || '1000');  // userId 분산 상한(orders의 user_id ≈ 1..1000)

export const options = {
    vus: parseInt(__ENV.VUS || '8'),
    duration: __ENV.DURATION || '30s',
    thresholds: {
        // 비포화 게이트(1차): 실패율 ~0. 위반 시 부하점 과다 → 재설정.
        http_req_failed: ['rate<0.01'],
    },
    tags: { scenario: 'nplusone-list', version: VERSION },
};

export default function () {
    const userId = 1 + Math.floor(Math.random() * USER_MAX);  // 매 요청 랜덤 분산(R3)
    const res = http.get(`http://localhost:8080/api/${VERSION}/users/${userId}/orders`, {
        tags: { version: VERSION },
    });
    check(res, { 'status is 200': (r) => r.status === 200 });
    sleep(0.1);
}
