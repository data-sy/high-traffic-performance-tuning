package com.project.infrastructure.cache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * v4 비동기 갱신 전용 executor (Step D).
 *
 * <p>갱신은 CAS/플래그 단일 refresh 가드로 키당 하나만 발화하므로 동시 refresh 최대치 = 핫 키 수(5). 풀은
 * 그보다 약간 크게 둔다. 본체 {@code couponExecutor}(core=4 통제 핀)와 분리 — 캐시 갱신이 그 풀을 잠식하지 않게.
 */
@Configuration
@Profile("phase3-8")
public class CacheRefreshConfig {

    @Bean(name = "cacheRefreshExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor cacheRefreshExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("cache-refresh-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }
}
