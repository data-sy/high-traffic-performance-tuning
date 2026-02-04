import http from 'k6/http';
import { check } from 'k6';

export const options = {
    vus: 1,
    iterations: 3,
};

export default function () {
    const res = http.get('http://localhost:8080/api/poc/health');
    check(res, {
        'status is 200': (r) => r.status === 200,
    });
}
