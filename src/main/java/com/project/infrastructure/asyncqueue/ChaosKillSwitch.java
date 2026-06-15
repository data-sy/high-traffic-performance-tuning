package com.project.infrastructure.asyncqueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 카오스 스텝 (T5/T6/T8) — kill 시점 변수화.
 *
 * <p>발동 구간 핀: 하네스가 <b>적재 완료 후 드레인 구간</b>에 {@code POST /api/v6/ops/chaos/arm}으로
 * 무장하면, 다음으로 해당 지점에 도달하는 워커가 {@link Runtime#halt} (= kill -9 등가, JVM 즉사).
 * SIGTERM(graceful) 대조는 하네스가 프로세스에 신호를 보내는 방식이라 이 스위치와 무관.
 *
 * <p>kill 대상은 앱 JVM뿐 — 인프라 컨테이너 kill 금지.
 */
@Component
public class ChaosKillSwitch {

    /** 꺼낸 직후(처리 시작 전) — v6b 유실 재현(T5). */
    public static final String AFTER_POP = "AFTER_POP";
    /** 커밋 직후·터미널 기록/XACK 전 — v6c 재전달 주입(T6). */
    public static final String AFTER_COMMIT = "AFTER_COMMIT";

    private static final Logger log = LoggerFactory.getLogger(ChaosKillSwitch.class);

    private volatile String armedPoint = null;

    public void arm(String point) {
        if (!AFTER_POP.equals(point) && !AFTER_COMMIT.equals(point)) {
            throw new IllegalArgumentException("unknown kill point: " + point);
        }
        this.armedPoint = point;
        log.warn("[CHAOS] armed kill-point={}", point);
    }

    public String armedPoint() {
        return armedPoint;
    }

    /** 무장된 지점이면 즉사 — halt는 셧다운 훅·finally를 모두 건너뛴다 (kill -9 등가). */
    public void maybeHalt(String point) {
        if (point.equals(armedPoint)) {
            log.warn("[CHAOS] kill-point {} hit — Runtime.halt(137)", point);
            Runtime.getRuntime().halt(137);
        }
    }
}
