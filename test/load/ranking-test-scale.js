import http from 'k6/http';
import { check, sleep } from 'k6';

const VERSION = __ENV.VERSION || 'v1';
// Phase 3-6 scale-up 전용 변형(정본 ranking-test.js 무수정 — phase3-2 baseline 보존), plan §5 부하모델 핀:
//  - VU < maximumPoolSize(=10) 엄격 → flat VUS=8 (기존 ramp target=100=10×pool 폐기:
//    pending==0 비포화 게이트 자기파괴 방지). flat 프로파일이라 --summary-export p95 = steady-state.
//  - p(95)<1000 threshold 제거: 1.5M v1 p95는 수초라 threshold 상시 fail → k6 exit≠0로
//    정상 측정이 "실패한 측정"으로 오독됨(요청은 성공, http_req_failed엔 무영향). N_min·warmup·
//    비포화는 오케스트레이터(measure-ranking.sh)가 가드.
const VUS = __ENV.VUS ? parseInt(__ENV.VUS) : 8;
const DURATION = __ENV.DURATION || '60s';

export const options = {
    vus: VUS,
    duration: DURATION,
};

export default function () {
    const res = http.get(`http://localhost:8080/api/${VERSION}/rankings`);

    check(res, {
        'status is 200': (r) => r.status === 200,
        // 'response time < 1000ms' check 제거: 1.5M v1 p95≈30s라 상시 fail로 로그 오염(요청은 200 성공이라
        // http_req_failed·exit엔 무영향이나 가독성 저해). 측정 가드는 오케스트레이터의 비포화·N_min.
        'rankings array exists': (r) => {
            try {
                const body = JSON.parse(r.body);
                return Array.isArray(body.rankings);
            } catch (e) {
                return false;
            }
        },
    });

    sleep(0.1);
}
