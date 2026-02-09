import http from 'k6/http';
import { check, sleep } from 'k6';

const VERSION = __ENV.VERSION || 'v1';

export const options = {
    stages: [
        { duration: '30s', target: 50 },   // warmup: 0 → 50 VUs
        { duration: '1m', target: 100 },    // load: 50 → 100 VUs
        { duration: '30s', target: 0 },     // cooldown: 100 → 0 VUs
    ],
    thresholds: {
        http_req_duration: ['p(95)<1000'],
    },
};

export default function () {
    const res = http.get(`http://localhost:8080/api/${VERSION}/rankings`);

    check(res, {
        'status is 200': (r) => r.status === 200,
        'response time < 1000ms': (r) => r.timings.duration < 1000,
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
