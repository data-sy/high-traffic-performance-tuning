# 동시성 트러블슈팅 01 — @Transactional + 락(synchronized/분산락) 경계 불일치

> 락 해제가 커밋보다 먼저 일어나는 함정. 시나리오 ④(선착순 쿠폰 발급) v2/v4 구현 중 마주침.

## 한 줄 요약
`@Transactional` 메서드 안에 `synchronized`(또는 분산 락)를 걸면, AOP 프록시의 커밋이 락 해제 "밖"에서 실행되어 "락 해제 → (아직 미커밋) → 커밋" 사이에 경쟁 창이 생긴다. 그 틈에 다음 스레드가 stale 데이터를 읽어 lost update가 발생 → **단일 인스턴스에서도 정합성이 깨진다**.

## 왜 생기나 (실행 순서)
`@Transactional`은 프록시가 처리하므로 실제 순서는 이렇다:

```
프록시: 트랜잭션 시작
  → 메서드 본문 { 락 획득 → 작업(조회·검사·증가·저장) → 락 해제 }
프록시: 커밋   ← 락 해제보다 나중. 즉 락 밖에서 커밋
```

- 같은 메서드에 `@Transactional`과 `synchronized`를 같이 걸면 모니터는 커밋 "전"에 풀린다.
- 스레드 A가 락을 놓는 순간 스레드 B가 락을 잡지만, A의 커밋이 아직 반영되지 않은 상태라 B는 옛 값을 읽는다 → read-modify-write 경쟁 → 초과 발급/lost update.
- 메서드 레벨이든 블록 레벨이든, 락이 "메서드 본문 안"에 있는 한 동일하게 발생한다.

## 잘못된 코드 (전형적 실수)
```java
@Service
class CouponIssueService {
    @Transactional
    public synchronized void issue(...) {   // ← 커밋은 이 메서드 밖에서 일어남
        ...
    }
}
```
`synchronized`가 풀린 뒤 프록시가 커밋하므로, 락 해제와 커밋 사이에 경쟁 창이 열린다.

## 올바른 패턴 (둘 중 하나)

**1) 락을 트랜잭션 경계 "바깥"에 둔다** — 락 메서드는 비트랜잭션, 내부에서 `@Transactional` 빈 호출
```java
@Service
class CouponIssueFacade {                      // 트랜잭션 없음
    private final CouponIssueService service;   // @Transactional 빈 (별도 빈이어야 프록시 적용)
    public void issue(...) {
        synchronized (lock) {
            service.issueInTx(...);             // 이 호출이 끝날 때 커밋까지 완료됨
        }                                       // 커밋 이후에 락 해제
    }
}
```
> 주의: 같은 클래스 내부 호출은 프록시를 안 타므로 반드시 "다른 빈"으로 분리할 것.

**2) 락 블록 안에서 트랜잭션을 수동 관리** — `TransactionTemplate` 사용
```java
synchronized (lock) {
    transactionTemplate.execute(status -> { ...; return null; });  // 블록 안에서 커밋 완료
}
```

## 핵심 규칙
- "락 해제는 반드시 커밋 이후"가 성립하도록, **락 경계가 트랜잭션 경계를 "감싸야" 한다.** (트랜잭션 ⊂ 락, 그 반대 아님)
- `@Transactional`과 락을 같은 메서드에 거는 순간 이 조건은 **원리상 충족 불가능**하다.

## 적용 범위 (synchronized만의 문제가 아님)
- **`@Lock(PESSIMISTIC_WRITE)` 비관락**: 락 자체가 트랜잭션과 함께 풀리므로 보통 안전하지만, 락 획득 전 별도 조회를 트랜잭션 밖에서 하면 동일 함정 가능.
- **Redis 분산 락(SET NX 등)**: `finally`에서 unlock하되, 트랜잭션 커밋 이후에 unlock해야 함. 커밋 전에 unlock하면 다음 임계영역이 미커밋 상태를 본다 (동일 원리).
- 결론: **"트랜잭션 경계 ↔ 락 경계 순서"는 락 종류와 무관한 공통 함정.**
