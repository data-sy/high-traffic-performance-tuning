package com.project.service.coupon.occupancy;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * phase 3-9 부팅 assert(M2) — <b>측정 전용 {@code @Profile("occupancy")} 게이트</b>라 본체(base 무프로파일)는
 * 이 빈을 인스턴스화하지 않는다(본체 무오염). {@code ApplicationReadyEvent}에서 <b>실제</b> Hikari 풀 크기와
 * Tomcat 최대 스레드를 핀과 대조하고 불일치면 컨텍스트를 닫아 <b>기동을 실패</b>시킨다.
 *
 * <p><b>R1 풀 스윕 대조:</b> 기대 풀값은 {@code occupancy.expect-pool}(env {@code OCCUPANCY_POOL} 주입)로,
 * 실제 {@code HikariDataSource.getMaximumPoolSize()}와 대조 → env 주입이 실제로 먹었는지 확증.
 * 자가검증(M5a): {@code OCCUPANCY_EXPECT_POOL}을 실제와 다르게 주면 고의 불일치 → abort.
 */
@Component
@Profile("occupancy")
public class CriticalSectionPinsBootAssert {

    private static final Logger log = LoggerFactory.getLogger(CriticalSectionPinsBootAssert.class);

    private final DataSource dataSource;
    private final Environment env;

    public CriticalSectionPinsBootAssert(DataSource dataSource, Environment env) {
        this.dataSource = dataSource;
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void assertPins(ApplicationReadyEvent event) {
        int expectPool = env.getProperty("occupancy.expect-pool", Integer.class, CriticalSectionPins.HIKARI_MAX_POOL_SIZE);
        int actualPool = actualHikariPool();
        int actualThreads = actualTomcatMaxThreads(event.getApplicationContext());

        StringBuilder fail = new StringBuilder();
        if (actualPool != expectPool) {
            fail.append(String.format("%n  ✗ Hikari maximumPoolSize=%d (기대 %d — occupancy.expect-pool)", actualPool, expectPool));
        }
        // Tomcat 스레드는 R 칸 불변 핀(200). 읽기 실패(-1)면 경고만(핀 대조 불가), 읽혔는데 불일치면 abort.
        if (actualThreads > 0 && actualThreads != CriticalSectionPins.TOMCAT_THREADS_MAX) {
            fail.append(String.format("%n  ✗ Tomcat threads.max=%d (기대 %d)", actualThreads, CriticalSectionPins.TOMCAT_THREADS_MAX));
        }

        if (fail.length() > 0) {
            log.error("[CriticalSectionPinsBootAssert] 통제변수 핀 불일치 — 기동 중단:{}", fail);
            throw new IllegalStateException("phase3-9 핀 불일치 — 측정 substrate 무효:" + fail);
        }
        log.info("[CriticalSectionPinsBootAssert] ✓ 핀 일치: Hikari pool={} (기대 {}), Tomcat threads={} (기대 {})",
                actualPool, expectPool, actualThreads, CriticalSectionPins.TOMCAT_THREADS_MAX);
    }

    private int actualHikariPool() {
        try {
            if (dataSource instanceof HikariDataSource hikari) {
                return hikari.getMaximumPoolSize();
            }
            HikariDataSource unwrapped = dataSource.unwrap(HikariDataSource.class);
            return unwrapped != null ? unwrapped.getMaximumPoolSize() : -1;
        } catch (Exception e) {
            log.warn("[CriticalSectionPinsBootAssert] Hikari 풀 실측 실패", e);
            return -1;
        }
    }

    private int actualTomcatMaxThreads(ApplicationContext ctx) {
        try {
            if (ctx instanceof ServletWebServerApplicationContext swac) {
                WebServer ws = swac.getWebServer();
                if (ws instanceof TomcatWebServer tomcat) {
                    Executor ex = tomcat.getTomcat().getConnector().getProtocolHandler().getExecutor();
                    if (ex instanceof ThreadPoolExecutor tpe) {
                        return tpe.getMaximumPoolSize();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[CriticalSectionPinsBootAssert] Tomcat 스레드 실측 실패 — 핀 대조 생략", e);
        }
        // 실측 불가 → 설정 프로퍼티로 후퇴(핀 대조는 못 하나 최소 기록)
        Integer cfg = env.getProperty("server.tomcat.threads.max", Integer.class);
        return cfg != null ? cfg : -1;
    }
}
