package com.project.infrastructure.asyncqueue;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 부팅 시 executor 빈 존재·설정값 assert — 불일치 시 기동 실패 (v6a, T1/T2).
 * "executor 빈이 있다"가 아니라 "핀 값 그대로 만들어졌다"를 기동 시점에 확정한다.
 * (런타임 재대조는 하네스 {@code assert_ops_pins}가 측정 시작마다 수행 — 이중 게이트.)
 */
@Component
public class AsyncPinsBootAssert {

    private final ThreadPoolTaskExecutor couponExecutor;

    public AsyncPinsBootAssert(@Qualifier("couponExecutor") ThreadPoolTaskExecutor couponExecutor) {
        this.couponExecutor = couponExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void assertPins() {
        var tpe = couponExecutor.getThreadPoolExecutor();
        int capacity = tpe.getQueue().size() + tpe.getQueue().remainingCapacity();
        String rejection = tpe.getRejectedExecutionHandler().getClass().getSimpleName();
        if (tpe.getCorePoolSize() != AsyncPins.EXECUTOR_CORE
                || tpe.getMaximumPoolSize() != AsyncPins.EXECUTOR_MAX
                || capacity != AsyncPins.EXECUTOR_QUEUE_CAPACITY
                || !AsyncPins.REJECTION_POLICY.equals(rejection)) {
            throw new IllegalStateException(
                    "couponExecutor 핀 불일치 — 기동 중단: core=%d max=%d queueCapacity=%d rejection=%s (기대 %d/%d/%d/%s)"
                            .formatted(tpe.getCorePoolSize(), tpe.getMaximumPoolSize(), capacity, rejection,
                                    AsyncPins.EXECUTOR_CORE, AsyncPins.EXECUTOR_MAX,
                                    AsyncPins.EXECUTOR_QUEUE_CAPACITY, AsyncPins.REJECTION_POLICY));
        }
    }
}
