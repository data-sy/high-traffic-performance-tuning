import http from 'k6/http';
import exec from 'k6/execution';
import { Trend, Counter } from 'k6/metrics';

// ── 파라미터 ──
const VERSION = __ENV.VERSION || 'v1';          // 대상 락 버전 (v1..v5)
const PROFILE = __ENV.PROFILE || 'accuracy';    // 'accuracy' | 'performance'
const VUS = parseInt(__ENV.VUS || '500', 10);
const ACC_DURATION = __ENV.DURATION || '10s';   // 정확성 축 (v1과 동일 → 직접 비교)
const WARMUP = __ENV.WARMUP || '5s';            // 성능 축 warmup (집계 제외)
const STEADY = __ENV.STEADY || '30s';           // 성능 축 steady (정상상태)
const BASE = __ENV.BASE || 'http://localhost:8080';

// ── 전체 요청 메트릭 (양 프로필 공통) ──
const dur200 = new Trend('issue_dur_200_success', true);
const dur409 = new Trend('issue_dur_409_soldout', true);
const dur503 = new Trend('issue_dur_503_lockreject', true);
const cnt200 = new Counter('issue_cnt_200_success');
const cnt409 = new Counter('issue_cnt_409_soldout');
const cnt503 = new Counter('issue_cnt_503_lockreject');
const cntOther = new Counter('issue_cnt_other_nonhttp_or_5xx');  // status0(비-HTTP)+미분류5xx → 분모 클린
// 503 서브분류: ⓐ 풀 고갈(POOL_TIMEOUT, v3) / ⓑ 행락 대기(LOCK_WAIT_TIMEOUT, v3) /
//              ⓒ 분산 락 미획득(LOCK_NOT_ACQUIRED, v4·v5 즉시실패). 응답 errorCode로 구분.
const cnt503Pool = new Counter('issue_cnt_503_pool_timeout');
const cnt503Lock = new Counter('issue_cnt_503_lock_wait');
const cnt503NotAcq = new Counter('issue_cnt_503_not_acquired');

// ── steady-only 메트릭 (성능 프로필; warmup 제외) ──
const sDur200 = new Trend('steady_dur_200_success', true);
const sDur503 = new Trend('steady_dur_503_lockreject', true);
const sCnt200 = new Counter('steady_cnt_200_success');
const sCnt409 = new Counter('steady_cnt_409_soldout');
const sCnt503 = new Counter('steady_cnt_503_lockreject');
const sCntOther = new Counter('steady_cnt_other');
const sCnt503Pool = new Counter('steady_cnt_503_pool_timeout');
const sCnt503Lock = new Counter('steady_cnt_503_lock_wait');
const sCnt503NotAcq = new Counter('steady_cnt_503_not_acquired');

// 시나리오별 userId base — warmup/steady가 같은 __VU*100000+__ITER로 겹쳐 중복발급(409)되는 걸 방지.
// 유니크 userId 통제(결정 #5)를 두 시나리오에 걸쳐 유지.
const SCEN_BASE = { warmup: 0, steady: 500000000, accuracy: 0 };

function buildScenarios() {
    if (PROFILE === 'performance') {
        return {
            // warmup(0~5s) → steady(5~35s) 연속 500 VU. warmup은 태그로 분리해 집계 제외.
            warmup: { executor: 'constant-vus', vus: VUS, duration: WARMUP, startTime: '0s', exec: 'issue', tags: { phase: 'warmup' } },
            steady: { executor: 'constant-vus', vus: VUS, duration: STEADY, startTime: WARMUP, exec: 'issue', tags: { phase: 'steady' } },
        };
    }
    return {
        accuracy: { executor: 'constant-vus', vus: VUS, duration: ACC_DURATION, exec: 'issue', tags: { phase: 'accuracy' } },
    };
}

export const options = {
    scenarios: buildScenarios(),
    summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

export function issue() {
    const scen = exec.scenario.name;
    const userId = (SCEN_BASE[scen] || 0) + __VU * 100000 + __ITER;
    const url = `${BASE}/api/${VERSION}/coupons/issue`;
    const payload = JSON.stringify({ couponId: 1, userId: userId });
    const res = http.post(url, payload, { headers: { 'Content-Type': 'application/json' } });

    const s = res.status;
    const steady = (scen === 'steady');

    if (s === 200) {
        cnt200.add(1); dur200.add(res.timings.duration);
        if (steady) { sCnt200.add(1); sDur200.add(res.timings.duration); }
    } else if (s === 409) {
        cnt409.add(1); dur409.add(res.timings.duration);
        if (steady) { sCnt409.add(1); }
    } else if (s === 503) {
        cnt503.add(1); dur503.add(res.timings.duration);
        if (steady) { sCnt503.add(1); sDur503.add(res.timings.duration); }
        let ec = null;
        try { ec = res.json('errorCode'); } catch (e) { /* 비-JSON 본문 */ }
        if (ec === 'POOL_TIMEOUT') { cnt503Pool.add(1); if (steady) sCnt503Pool.add(1); }
        else if (ec === 'LOCK_WAIT_TIMEOUT') { cnt503Lock.add(1); if (steady) sCnt503Lock.add(1); }
        else if (ec === 'LOCK_NOT_ACQUIRED') { cnt503NotAcq.add(1); if (steady) sCnt503NotAcq.add(1); }
    } else {
        cntOther.add(1);
        if (steady) { sCntOther.add(1); }
    }
}
