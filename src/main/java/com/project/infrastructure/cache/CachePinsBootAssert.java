package com.project.infrastructure.cache;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;

/**
 * 부팅 핀·격리·baseline 정합 assert (A-3, M2) — 불일치 시 {@code IllegalStateException} 으로 기동 실패.
 * (phase4-1 {@code EventPinsBootAssert} 패턴 계승. 런타임 재대조는 하네스가 {@code GET /api/cache/ops/pins} 로 — 이중 게이트.)
 *
 * <p>측정 전용(@Profile phase3-8) — 본체 부팅엔 로드되지 않아 6379/3306 기본 토폴로지를 방해하지 않는다(v1 무오염).
 * 측정 스택은 SPRING_PROFILES_ACTIVE=phase3-8 로 기동 → application-phase3-8.yml 이 redis.port=6380 override,
 * datasource 는 base 의 본체 3306 상속.
 *
 * <p>대조 항목:
 * <ol>
 *   <li>전용 Redis 포트 = 6380 (LettuceConnectionFactory 실측, AD-2·A-1)</li>
 *   <li>datasource = 본체 3306 (jdbc url, AD-2 — 우발적 전용 MySQL/6380쪽 직격 차단)</li>
 *   <li>Hikari maximumPoolSize = 10 (DR-POOL-1)</li>
 *   <li>Hibernate 2차/쿼리 캐시 off (M4·Q6 — 켜지면 재계산이 ORM 캐시에 흡수돼 카운터 거짓 0)</li>
 *   <li>락 불변 한 쌍 ordering: LOCK_WAIT_MS ≥ LOCK_TIMEOUT_MS (DR-4 — 전구간 p99 비교는 수렴 게이트)</li>
 *   <li>herd 천장 cross-assert: STAMPEDE/PENETRATION_CONCURRENCY &lt; server.tomcat.threads.max (DR-TOMCAT-1)</li>
 *   <li>캐시 직렬화 라운드트립 self-check (DR-2)</li>
 *   <li>key prefix 정합 (A-1)</li>
 * </ol>
 */
@Component
@Profile("phase3-8")
public class CachePinsBootAssert {

    private static final Logger log = LoggerFactory.getLogger(CachePinsBootAssert.class);

    private final LettuceConnectionFactory redisFactory;
    private final DataSource dataSource;
    private final EntityManagerFactory entityManagerFactory;
    private final Environment environment;
    private final LookAsideCache lookAsideCache;

    public CachePinsBootAssert(LettuceConnectionFactory redisFactory,
                               DataSource dataSource,
                               EntityManagerFactory entityManagerFactory,
                               Environment environment,
                               LookAsideCache lookAsideCache) {
        this.redisFactory = redisFactory;
        this.dataSource = dataSource;
        this.entityManagerFactory = entityManagerFactory;
        this.environment = environment;
        this.lookAsideCache = lookAsideCache;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void assertPins() {
        // 1) 전용 Redis 포트
        int redisPort = redisFactory.getPort();
        if (redisPort != CachePins.CACHE_REDIS_PORT) {
            fail("전용 Redis 포트 불일치: 실측 %d, 기대 %d(전용 6380) — 측정 스택이 본체 6379에 묶임(격리 위반·AD-2)"
                    .formatted(redisPort, CachePins.CACHE_REDIS_PORT));
        }

        // 2) datasource = 본체 3306
        String jdbcUrl = jdbcUrl();
        if (jdbcUrl == null || !jdbcUrl.contains(":" + CachePins.DATASOURCE_MYSQL_PORT + "/")) {
            fail("datasource 불일치: jdbcUrl=%s — 기대 본체 MySQL :%d (전용 MySQL 미신설·AD-2)"
                    .formatted(jdbcUrl, CachePins.DATASOURCE_MYSQL_PORT));
        }

        // 3) Hikari pool = 10
        int poolSize = maximumPoolSize();
        if (poolSize != CachePins.HIKARI_MAX_POOL_SIZE) {
            fail("Hikari maximumPoolSize 불일치: 실측 %d, 기대 %d(DR-POOL-1 — 본체/전용 동일, over-provision 금지)"
                    .formatted(poolSize, CachePins.HIKARI_MAX_POOL_SIZE));
        }

        // 4) Hibernate 2차/쿼리 캐시 off
        assertHibernateCacheOff();

        // 5) 락 불변 ordering (전구간 p99 비교는 수렴 게이트가 실측으로)
        if (CachePins.LOCK_WAIT_MS < CachePins.LOCK_TIMEOUT_MS) {
            fail("락 불변 위반(DR-4): LOCK_WAIT_MS(%d) < LOCK_TIMEOUT_MS(%d) — 한 쌍 LOCK_WAIT ≥ LOCK_TIMEOUT ≥ 전구간 p99"
                    .formatted(CachePins.LOCK_WAIT_MS, CachePins.LOCK_TIMEOUT_MS));
        }

        // 6) herd 천장 cross-assert: C < tomcat threads.max (DR-TOMCAT-1)
        int tomcatMax = environment.getProperty("server.tomcat.threads.max", Integer.class, 200);
        if (CachePins.STAMPEDE_CONCURRENCY >= tomcatMax) {
            fail("herd 천장 위반(DR-TOMCAT-1): STAMPEDE_CONCURRENCY(%d) ≥ tomcat threads.max(%d) — latch 가 n 미충족 → 무한 대기"
                    .formatted(CachePins.STAMPEDE_CONCURRENCY, tomcatMax));
        }
        if (CachePins.PENETRATION_CONCURRENCY >= tomcatMax) {
            fail("herd 천장 위반(DR-TOMCAT-1): PENETRATION_CONCURRENCY(%d) ≥ tomcat threads.max(%d)"
                    .formatted(CachePins.PENETRATION_CONCURRENCY, tomcatMax));
        }

        // 7) 캐시 직렬화 라운드트립 self-check (DR-2)
        lookAsideCache.selfCheckRoundTrip();

        // 8) key prefix 정합
        if (!CachePins.KEY_PREFIX.equals(lookAsideCache.keyPrefix())) {
            fail("key prefix 불일치: %s vs %s".formatted(CachePins.KEY_PREFIX, lookAsideCache.keyPrefix()));
        }

        log.info("[CachePinsBootAssert] PASS — redis=:{} datasource=:{} hikariPool={} tomcatMax={} "
                        + "TTL_BASE={}ms NULL_TTL={}ms jitter=±{} LOCK_TIMEOUT={}ms LOCK_WAIT={}ms STALE_WINDOW={}ms "
                        + "C(stampede/penetration)={}/{} keyPrefix={} 핫키={} | L2cache=off · 직렬화 self-check OK",
                redisPort, CachePins.DATASOURCE_MYSQL_PORT, poolSize, tomcatMax,
                CachePins.TTL_BASE_MS, CachePins.NULL_TTL_MS, CachePins.TTL_JITTER_RATIO,
                CachePins.LOCK_TIMEOUT_MS, CachePins.LOCK_WAIT_MS, CachePins.STALE_WINDOW_MS,
                CachePins.STAMPEDE_CONCURRENCY, CachePins.PENETRATION_CONCURRENCY,
                CachePins.KEY_PREFIX, CachePins.HOT_CATEGORIES);
    }

    private String jdbcUrl() {
        if (dataSource instanceof HikariDataSource hikari) {
            return hikari.getJdbcUrl();
        }
        // 폴백 — Environment 의 설정값(실제 빈이 HikariDataSource 가 아닐 때)
        return environment.getProperty("spring.datasource.url");
    }

    private int maximumPoolSize() {
        if (dataSource instanceof HikariDataSource hikari) {
            return hikari.getMaximumPoolSize();
        }
        fail("DataSource 가 HikariDataSource 가 아님: " + dataSource.getClass().getName() + " — 풀 크기 대조 불가(DR-POOL-1)");
        return -1; // unreachable
    }

    /**
     * Hibernate 2차/쿼리 캐시 off 확인 (M4·Q6·AD-6).
     * EntityManagerFactory 속성에서 use_second_level_cache / use_query_cache 가 "true" 면 기동 실패.
     * (전용 vs 본체 yml 의 L2 설정 차이도 같은 위치에서 잡힘 — 둘 다 base application.yml 상속·미설정=off.)
     */
    private void assertHibernateCacheOff() {
        Map<String, Object> props = entityManagerFactory.getProperties();
        assertOff(props, "hibernate.cache.use_second_level_cache");
        assertOff(props, "hibernate.cache.use_query_cache");
    }

    private void assertOff(Map<String, Object> props, String key) {
        Object v = props.get(key);
        if (v != null && "true".equalsIgnoreCase(String.valueOf(v))) {
            fail("Hibernate 캐시가 켜져 있음(M4·Q6 — 재계산이 ORM 캐시에 흡수돼 카운터 거짓 0): %s=%s".formatted(key, v));
        }
    }

    private void fail(String message) {
        throw new IllegalStateException("[CachePinsBootAssert] 기동 중단 — " + message);
    }
}
