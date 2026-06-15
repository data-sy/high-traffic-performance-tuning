package com.project.infrastructure.asyncqueue;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * v6a 전용 executor (T2 — 기본 executor 금지, 전 항목 명시 핀).
 *
 * <p>P2 메모: Java 풀은 core가 차면 큐에 먼저 줄을 세우고 큐가 가득 차야 max로 증원한다 —
 * 이 실험은 max=core로 그 증원 단계를 봉인했다(소비 동시성 통제).
 * 큐 만석 + 풀 만석이면 {@link ThreadPoolExecutor.AbortPolicy} → 파사드가 429 QUEUE_FULL로 매핑.
 */
@Configuration
@EnableAsync
public class AsyncExecutorConfig {

    @Bean("couponExecutor")
    public ThreadPoolTaskExecutor couponExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(AsyncPins.EXECUTOR_CORE);
        executor.setMaxPoolSize(AsyncPins.EXECUTOR_MAX);
        executor.setQueueCapacity(AsyncPins.EXECUTOR_QUEUE_CAPACITY);
        executor.setThreadNamePrefix("couponExecutor-");   // T1 검증: 처리 스레드명이 이 접두여야 비동기
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // T8 graceful: SIGTERM 시 새 작업은 안 받고 in-flight 종료를 핀 시간만큼 대기
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(AsyncPins.GRACEFUL_SHUTDOWN_SEC);
        return executor;
    }
}
