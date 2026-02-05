import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    vus: 10,
    duration: '30s',
};

export default function () {
    const res = http.get('http://localhost:8080/api/v1/products?category=%EC%A0%84%EC%9E%90%EA%B8%B0%EA%B8%B0&page=0&size=20');
    check(res, {
        'status is 200': (r) => r.status === 200,
    });
    sleep(0.1);
}
