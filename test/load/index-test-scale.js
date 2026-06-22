import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    // Phase 3-6 scale-up 전용 변형(정본 index-test.js 무수정 — phase3-1 baseline 보존):
    // VU < maximumPoolSize(=10) 엄격 → vus 10→8. pending==0 비포화 게이트(measure-index.sh)가
    // acquire 큐 오염으로 자기파괴하는 것을 방지. plan §5 부하모델 핀. p95 = 순수 쿼리 비용.
    vus: 8,
    duration: '30s',
};

export default function () {
    const res = http.get('http://localhost:8080/api/v1/products?category=%EC%A0%84%EC%9E%90%EA%B8%B0%EA%B8%B0&page=0&size=20');
    check(res, {
        'status is 200': (r) => r.status === 200,
    });
    sleep(0.1);
}
