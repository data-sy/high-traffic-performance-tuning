package com.project.service.coupon;

import com.project.api.coupon.dto.StallDemoResult;
import com.project.domain.coupon.Coupon;
import com.project.domain.coupon.CouponIssue;
import com.project.domain.coupon.CouponIssueRepository;
import com.project.domain.coupon.CouponRepository;
import com.project.infrastructure.redis.RedisLockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * ★ 스톨 데모 — <b>fencing 부재 실증</b> (정규 측정 아님, 수동·타이밍 의존). 시리즈 결론의 유일한 구체 증거.
 *
 * <p>{@code ttlMillis < stallMillis}로 보유 중 TTL 만료(만료-중간탈취)를 인위 주입한다. 임계영역을
 * read → (stall) → write로 벌려, 스톨 중 다른 홀더가 만료된 락을 훔쳐 같이 발급 → actual&gt;total 재현.
 * REPEATABLE-READ 스냅샷이 read 시점(stall 전)에 고정돼, 늦게 커밋해도 stale issued로 발급된다.
 *
 * <p><b>핵심:</b> 이 데모에서 A의 {@code released=false}(compare-del이 자기 토큰 아님을 보고 해제 거부 = release 안전 ✓)
 * 인데도 overlap이 일어난다(✗). 즉 안전한 release(오해제 차단)와 상호배제(동시 점유 차단)는 별개이며,
 * 후자는 fencing token(자원 측 단조번호 검증) 없이는 어느 분산 락도 못 준다. → §6 프로덕션 v3 선택 근거.
 */
@Service
public class CouponStallDemoService {

    private final RedisLockRepository lockRepository;
    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final TransactionTemplate txTemplate;

    public CouponStallDemoService(RedisLockRepository lockRepository,
                                  CouponRepository couponRepository,
                                  CouponIssueRepository couponIssueRepository,
                                  PlatformTransactionManager txManager) {
        this.lockRepository = lockRepository;
        this.couponRepository = couponRepository;
        this.couponIssueRepository = couponIssueRepository;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    public StallDemoResult issueStall(Long couponId, Long userId, long ttlMillis, long stallMillis) {
        String key = "lock:coupon:" + couponId;
        String token = UUID.randomUUID().toString();

        boolean locked = lockRepository.tryLock(key, token, ttlMillis);
        if (!locked) {
            throw new LockNotAcquiredException(couponId);
        }

        boolean released;
        try {
            txTemplate.executeWithoutResult(status -> {
                Coupon coupon = couponRepository.findById(couponId)   // read (스냅샷 고정 — stall 전 issued)
                        .orElseThrow(() -> new IllegalArgumentException("coupon not found: " + couponId));
                sleepMillis(stallMillis);                             // 스톨 — ttl < stall이면 보유 중 TTL 만료
                coupon.issue();                                      // write (stale 스냅샷 기준)
                couponIssueRepository.save(new CouponIssue(couponId, userId));
            });
        } finally {
            released = lockRepository.unlock(key, token);            // 다른 홀더가 점유했으면 false(오해제 거부)
        }
        return new StallDemoResult(token, true, released);
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
