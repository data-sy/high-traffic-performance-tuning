package com.project.service.coupon.async;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.project.infrastructure.asyncqueue.AsyncPins;
import com.project.infrastructure.asyncqueue.IssueStreamQueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.stereotype.Component;

/**
 * v6c 워커 — 컨슈머 그룹 c1~c4 (핀 CONSUMERS) + 별도 회수 루프.
 *
 * <p><b>XACK 시점 계약</b>: 처리 결과가 무엇이든(ISSUED·SOLD_OUT·FAILED·중복 흡수)
 * 터미널 상태 기록 후 XACK한다 — ISSUED만 ack하면 탈락 ~900건이 PEL에 잔류해 회수 루프가 무한
 * 재전달하고 깊이가 영원히 0이 되지 않는다. 미ack로 남는 것은 "터미널 기록 전에 죽은" 건뿐이며
 * 그것이 PEL 회수 실험(T5/T6)의 대상이다.
 *
 * <p><b>PEL 회수는 자동이 아니다 (T5)</b>: claimer 스레드가 주기(핀 5s)마다 XPENDING으로 PEL을
 * 보고 min-idle(핀 10s) 경과분만 XCLAIM해 재처리한다 — 재전달이 여기서 태어나고, 그 중복은
 * 멱등 흡수(CouponIssueProcessor)가 무해화한다 (T6).
 *
 * <p><b>오류 내성</b>: 런 간 reset(스트림 삭제+그룹 재생성)이 만드는 NOGROUP 포함
 * 일시 오류는 로그 후 그룹 재보장·재시도 — 루프를 종료하지 않는다.
 */
@Component
public class CouponStreamWorkerV6c implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CouponStreamWorkerV6c.class);

    private final IssueStreamQueueRepository queue;
    private final CouponIssueProcessor processor;

    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running = false;

    public CouponStreamWorkerV6c(IssueStreamQueueRepository queue, CouponIssueProcessor processor) {
        this.queue = queue;
        this.processor = processor;
    }

    @Override
    public void start() {
        running = true;
        queue.ensureGroup();
        for (int i = 1; i <= AsyncPins.CONSUMERS; i++) {
            String name = "c" + i;
            Thread t = new Thread(() -> consumeLoop(name), "couponStreamWorker-" + name);
            threads.add(t);
            t.start();
        }
        Thread claimer = new Thread(this::claimLoop, "couponStreamClaimer");
        threads.add(claimer);
        claimer.start();
        log.info("[v6c-worker] started {} consumers + claimer (block {}ms, count {}, claim every {}ms, min-idle {}ms)",
                AsyncPins.CONSUMERS, AsyncPins.XREAD_BLOCK_MS, AsyncPins.XREAD_COUNT,
                AsyncPins.XAUTOCLAIM_INTERVAL_MS, AsyncPins.XAUTOCLAIM_MIN_IDLE_MS);
    }

    private void consumeLoop(String consumerName) {
        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records = queue.readGroup(consumerName);
                if (records == null) {
                    continue;
                }
                for (MapRecord<String, Object, Object> r : records) {
                    handle(r, consumerName, false);
                }
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                log.warn("[v6c-worker:{}] transient error — ensureGroup 후 재시도 (loop survives): {}",
                        consumerName, e.getMessage());
                queue.ensureGroup();                      // NOGROUP(런 간 reset) 자가 복구
                sleepQuietly(200);
            }
        }
    }

    private void claimLoop() {
        while (running) {
            try {
                sleepQuietly(AsyncPins.XAUTOCLAIM_INTERVAL_MS);
                if (!running) {
                    break;
                }
                List<RecordId> idle = new ArrayList<>();
                for (PendingMessage pm : queue.pending()) {
                    if (pm.getElapsedTimeSinceLastDelivery().toMillis() >= AsyncPins.XAUTOCLAIM_MIN_IDLE_MS) {
                        idle.add(pm.getId());
                    }
                }
                if (idle.isEmpty()) {
                    continue;
                }
                // XCLAIM(min-idle) — 죽은 컨슈머의 미완료분이 여기서 재전달된다 (T5→T6)
                List<MapRecord<String, Object, Object>> claimed =
                        queue.claim("claimer", idle.toArray(RecordId[]::new));
                log.info("[v6c-claimer] PEL 회수: 후보 {}건 → 회수 {}건 (재전달 — 중복은 멱등 흡수가 받는다)",
                        idle.size(), claimed == null ? 0 : claimed.size());
                if (claimed != null) {
                    for (MapRecord<String, Object, Object> r : claimed) {
                        handle(r, "claimer", true);
                    }
                }
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                log.warn("[v6c-claimer] transient error — 재시도 (loop survives): {}", e.getMessage());
                queue.ensureGroup();
            }
        }
    }

    private void handle(MapRecord<String, Object, Object> record, String consumerName, boolean redelivered) {
        Map<Object, Object> v = record.getValue();
        String requestId = String.valueOf(v.get("requestId"));
        Long couponId = Long.valueOf(String.valueOf(v.get("couponId")));
        Long userId = Long.valueOf(String.valueOf(v.get("userId")));
        if (redelivered) {
            log.info("[v6c-redeliver] id={} requestId={} consumer={}", record.getId(), requestId, consumerName);
        }
        processor.process(requestId, couponId, userId);   // 터미널 기록까지 (AFTER_COMMIT 카오스 훅 포함)
        queue.ack(record.getId());                        // 모든 터미널 후 XACK (계약)
    }

    @Override
    public void stop() {
        running = false;
        long deadline = System.currentTimeMillis() + AsyncPins.GRACEFUL_SHUTDOWN_SEC * 1000L;
        for (Thread t : threads) {
            long remain = deadline - System.currentTimeMillis();
            if (remain > 0) {
                try {
                    t.join(remain);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("[v6c-worker] stopped (graceful — 미ack 잔여는 PEL에 남아 재기동 후 회수된다)");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
