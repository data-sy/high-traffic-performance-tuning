import http from 'k6/http';
import exec from 'k6/execution';
import { Trend, Counter } from 'k6/metrics';

// Phase 3-4 비동기 발급 부하 (v6a/v6b/v6c 파라미터화).
//   accuracy: per-vu-iterations 500VU × 2iter = 1,000건 적재 (고정 N)
//   observe : warmup 5s + steady 30s constant-vus (백프레셔 관찰 축)
// 라벨 규율(T4): 여기서 재는 지연은 전부 "접수 지연"이다. 발급 지연(E2E)은 상태 해시에서 산출.
const VERSION = __ENV.VERSION || 'v6a';        // v6a | v6b | v6c
const PROFILE = __ENV.PROFILE || 'accuracy';   // 'accuracy' | 'observe'
const VUS = parseInt(__ENV.VUS || '500', 10);
const ITERS = parseInt(__ENV.ITERS || '2', 10);
const WARMUP = __ENV.WARMUP || '5s';
const STEADY = __ENV.STEADY || '30s';
const BASE = __ENV.BASE || 'http://localhost:8080';

// 버킷: 202(접수) / 429(QUEUE_FULL — 백프레셔 발동, 오류 아님) / other(비-HTTP·미분류 — 분모 클린)
const durAccept = new Trend('accept_dur_202', true);
const cnt202 = new Counter('issue_cnt_202_accepted');
const cnt429 = new Counter('issue_cnt_429_queue_full');
const cntOther = new Counter('issue_cnt_other_nonhttp_or_5xx');
// steady-only (observe 프로필 — warmup 집계 제외)
const sDurAccept = new Trend('steady_accept_dur_202', true);
const sCnt202 = new Counter('steady_cnt_202_accepted');
const sCnt429 = new Counter('steady_cnt_429_queue_full');
const sCntOther = new Counter('steady_cnt_other');

// 유니크 userId 통제 (phase 3-3 결정 #5 계승) — 시나리오 간 충돌 방지 base
const SCEN_BASE = { accuracy: 0, warmup: 0, steady: 500000000 };

function buildScenarios() {
    if (PROFILE === 'observe') {
        return {
            warmup: { executor: 'constant-vus', vus: VUS, duration: WARMUP, startTime: '0s', exec: 'issue', tags: { phase: 'warmup' } },
            steady: { executor: 'constant-vus', vus: VUS, duration: STEADY, startTime: WARMUP, exec: 'issue', tags: { phase: 'steady' } },
        };
    }
    return {
        accuracy: { executor: 'per-vu-iterations', vus: VUS, iterations: ITERS, exec: 'issue', tags: { phase: 'accuracy' } },
    };
}

export const options = {
    scenarios: buildScenarios(),
    summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

export function issue() {
    const scen = exec.scenario.name;
    const userId = (SCEN_BASE[scen] || 0) + __VU * 100000 + __ITER;
    const res = http.post(
        `${BASE}/api/${VERSION}/coupons/issue`,
        JSON.stringify({ couponId: 1, userId: userId }),
        { headers: { 'Content-Type': 'application/json' } },
    );
    const steady = (scen === 'steady');
    if (res.status === 202) {
        cnt202.add(1); durAccept.add(res.timings.duration);
        if (steady) { sCnt202.add(1); sDurAccept.add(res.timings.duration); }
    } else if (res.status === 429) {
        cnt429.add(1);
        if (steady) { sCnt429.add(1); }
    } else {
        cntOther.add(1);
        if (steady) { sCntOther.add(1); }
    }
}
