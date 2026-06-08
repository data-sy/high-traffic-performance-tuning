package com.project.service.coupon;

import com.project.api.coupon.dto.CouponIssueResponse;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * v4 — Redis 분산 락(Redisson {@link RLock}). 파사드: 락 ⊃ 트랜잭션(커밋 이후 finally unlock).
 *
 * <p><b>워치독 자동 갱신</b>(leaseTime 미지정, 결정 #4/발견4): 고정 lease는 GC pause/스톨이 lease를
 * 넘기는 순간 만료-중간탈취 → 초과발급을 구조적으로 못 막는다. v4의 존재 이유가 분산 경계 정합이라
 * 정확성을 측정 청결성과 안 바꾼다. → v5(고정TTL)와 <b>비동형</b>(Step G 갱신 축).
 *
 * <p><b>fail-fast</b>: {@code tryLock(0, ...)} waitTime=0 → 즉시 실패 시 503 LOCK_NOT_ACQUIRED(ⓒ).
 * 대기 큐가 없어 공유부하 10s에선 한도 미충전(actual&lt;total) 가능 — 이는 정합 실패가 아니라 fail-fast의
 * 정상 귀결(발견5). 합격식 = actual ≤ total(초과 0).
 *
 * <p><b>가드</b>: ① 미획득 시 unlock 호출 안 함(Redisson은 미보유 unlock 시 IllegalMonitorStateException →
 * 거절이 500으로 새 분모 오염). ② tryLock RedisException(인프라 장애)도 단일 ⓒ로 접되 WARN 로그로 구분.
 *
 * <p><b>한계</b>: 단일 Redis = SPOF, true Redlock 아님(단일 노드), <b>fencing 부재</b>(만료-중간탈취 시
 * 두 홀더 동시 점유는 워치독도 못 막음 — fencing token 영역, Step G). → §6 프로덕션 v3 선택 근거.
 */
@Service
public class CouponIssueServiceV4 {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueServiceV4.class);

    private final RedissonClient redissonClient;
    private final CouponIssueServiceV4Inner inner;

    public CouponIssueServiceV4(RedissonClient redissonClient, CouponIssueServiceV4Inner inner) {
        this.redissonClient = redissonClient;
        this.inner = inner;
    }

    public CouponIssueResponse issue(Long couponId, Long userId) {
        RLock lock = redissonClient.getLock("lock:coupon:" + couponId);

        boolean locked;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);   // waitTime=0 fail-fast, leaseTime 미지정 → 워치독
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockNotAcquiredException(couponId);
        } catch (RedisException e) {                       // 인프라 장애 — 락 경합 패배와 구분(WARN), 단일 ⓒ
            log.warn("v4 tryLock RedisException (couponId={})", couponId, e);
            throw new LockNotAcquiredException(couponId);
        }

        if (!locked) {
            throw new LockNotAcquiredException(couponId);  // 미획득 → unlock 호출 안 함
        }

        try {
            return inner.issueTx(couponId, userId);        // 별도 빈 @Transactional — 커밋이 락 안에서 완료
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
