package com.project.api.coupon;

import java.util.LinkedHashMap;
import java.util.Map;

import com.project.infrastructure.asyncqueue.AsyncPins;
import com.project.infrastructure.asyncqueue.ChaosKillSwitch;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 측정 하네스 전용 ops 표면 — 사용자 트래픽 아님.
 *
 * <ul>
 *   <li>{@code GET /api/v6/ops/pins} — 신규 핀 런타임 실측값. 하네스 {@code assert_ops_pins}가
 *       대조 후 불일치 시 측정 중단. executor 값은 설정 echo가 아니라 <b>실제 풀에서 읽는다</b>.</li>
 *   <li>{@code GET /api/v6/ops/depth} — v6a 깊이 소스 (queueSize + activeCount).</li>
 *   <li>{@code POST /api/v6/ops/chaos/arm?point=} — 카오스 무장 (드레인 구간 발동 핀).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v6/ops")
public class CouponAsyncOpsController {

    private final ThreadPoolTaskExecutor couponExecutor;
    private final ChaosKillSwitch chaos;

    public CouponAsyncOpsController(@Qualifier("couponExecutor") ThreadPoolTaskExecutor couponExecutor,
                                    ChaosKillSwitch chaos) {
        this.couponExecutor = couponExecutor;
        this.chaos = chaos;
    }

    @GetMapping("/pins")
    public Map<String, Object> pins() {
        var tpe = couponExecutor.getThreadPoolExecutor();
        Map<String, Object> pins = new LinkedHashMap<>();
        pins.put("executorCore", tpe.getCorePoolSize());
        pins.put("executorMax", tpe.getMaximumPoolSize());
        pins.put("executorQueueCapacity", tpe.getQueue().size() + tpe.getQueue().remainingCapacity());
        pins.put("rejectionPolicy", tpe.getRejectedExecutionHandler().getClass().getSimpleName());
        pins.put("queueCap", AsyncPins.QUEUE_CAP);
        pins.put("consumers", AsyncPins.CONSUMERS);
        pins.put("brpopTimeoutMs", AsyncPins.BRPOP_TIMEOUT_MS);
        pins.put("xreadBlockMs", AsyncPins.XREAD_BLOCK_MS);
        pins.put("xreadCount", AsyncPins.XREAD_COUNT);
        pins.put("xautoclaimIntervalMs", AsyncPins.XAUTOCLAIM_INTERVAL_MS);
        pins.put("xautoclaimMinIdleMs", AsyncPins.XAUTOCLAIM_MIN_IDLE_MS);
        pins.put("gracefulShutdownSec", AsyncPins.GRACEFUL_SHUTDOWN_SEC);
        pins.put("statusTtlSec", AsyncPins.STATUS_TTL_SEC);
        pins.put("chaosArmed", chaos.armedPoint());   // 평시 null — 측정 전 잔존 무장 검출용
        return pins;
    }

    @GetMapping("/depth")
    public Map<String, Object> depth() {
        var tpe = couponExecutor.getThreadPoolExecutor();
        return Map.of("queueSize", tpe.getQueue().size(), "activeCount", tpe.getActiveCount());
    }

    @PostMapping("/chaos/arm")
    public Map<String, Object> arm(@RequestParam String point) {
        chaos.arm(point);
        return Map.of("armed", point);
    }
}
