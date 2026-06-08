# 동시성 트러블슈팅 02 — 전역 락 → couponId 단위 락으로 경합 줄이기 (락 잘게 가져가기)

> [01](concurrency-troubleshooting-01.md)에서 락↔트랜잭션 경계를 바로잡았다면, 다음 문제는 **락 입자(granularity)**다.

## 한 줄 요약
`synchronized(this)`나 단일 전역 락으로 쿠폰 발급을 보호하면, **서로 다른 쿠폰의 발급까지 전부 직렬화**된다. 쿠폰 A의 경쟁이 쿠폰 B 발급을 막을 이유는 없다. 락을 **couponId 단위**로 잘게 쪼개면 무관한 쿠폰끼리는 병렬로 처리된다.

## 문제 (전역 락의 과도한 직렬화)
```java
public void issue(Long couponId, Long userId) {
    synchronized (this) {           // ← 모든 쿠폰이 같은 모니터를 두고 경쟁
        ...
    }
}
```
- 쿠폰 1,000종을 동시에 발급해도 한 줄로 줄을 선다 → 처리량이 쿠폰 수와 무관하게 평평해짐.
- 정합성은 맞지만, "동시성 제어"의 목적(필요한 곳만 직렬화)에 어긋남.

## 해법 — couponId별 락 객체를 인터닝
```java
private final ConcurrentHashMap<Long, Object> locks = new ConcurrentHashMap<>();

private Object lockFor(Long couponId) {
    return locks.computeIfAbsent(couponId, k -> new Object());
}

public void issue(Long couponId, Long userId) {
    synchronized (lockFor(couponId)) {     // 같은 couponId만 직렬화, 다른 쿠폰은 병렬
        service.issueInTx(couponId, userId);   // 01의 규칙: 락이 트랜잭션을 감쌈
    }
}
```

## 주의점
- **`Long`/`String` 식별자를 락 객체로 직접 쓰지 말 것.** `synchronized(couponId)`는 `Long` 오토박싱 캐시(-128~127)에 따라 서로 다른 인스턴스가 되거나 의도치 않게 공유된다. `couponId.toString().intern()`도 문자열 풀에 영구 적재되는 부작용이 있다. → **전용 모니터 객체를 맵으로 인터닝**하는 위 방식이 안전.
- **맵 무한 증가**: `computeIfAbsent`로 쌓인 엔트리는 자동 제거되지 않는다. couponId 종류가 유한·소수면 무시 가능하지만, 키가 많이 늘면 메모리 누수. → 경계가 필요하면 Guava `Striped<Lock>`(고정 버킷 수, 약간의 해시 충돌을 감수하고 무한 증가 차단)을 쓰는 게 깔끔.
- **여전히 단일 JVM 한정**: [01](concurrency-troubleshooting-01.md)과 같은 한계. 다중 인스턴스에선 이 맵이 공유되지 않으므로 무효.

## 분산 환경에선 자연스럽게 해결됨
Redis 분산 락은 키 자체가 couponId를 포함(`lock:coupon:{couponId}`)하므로, **별도 인터닝 없이 키 단위로 잘게 잠긴다.** 즉 v4(Redis 락)로 가면 "couponId 단위 락"은 공짜로 따라온다. 이 트러블슈팅은 주로 v2(`synchronized`) 단계의 처리량 개선 맥락에서 의미가 있다.
