package com.project.service.coupon.async;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.infrastructure.asyncqueue.AsyncPins;
import com.project.infrastructure.asyncqueue.IssueListQueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * v6b 워커 — 앱 내 백그라운드 스레드 4개(핀 CONSUMERS)가 BRPOP(1s) 루프.
 *
 * <p><b>오류 내성</b>: 일시 Redis 오류는 로그 후 짧은 대기를 두고 재시도하며
 * 루프를 종료하지 않는다 — 루프가 죽으면 다음 런이 컨슈머 0으로 NON_CONVERGED가 되는데 그것은
 * 하네스가 만든 정지이지 실험 결과가 아니다.
 *
 * <p><b>T8 graceful</b>: SIGTERM 시 {@link #stop()}이 새 pop을 멈추고 in-flight 처리 종료를
 * 핀 시간(10s)까지 대기 — kill -9(halt)와 달리 유실이 없어야 한다 (대조 실험).
 */
@Component
public class CouponQueueWorkerV6b implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CouponQueueWorkerV6b.class);

    private final IssueListQueueRepository queue;
    private final CouponIssueProcessor processor;
    private final ObjectMapper objectMapper;

    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running = false;

    public CouponQueueWorkerV6b(IssueListQueueRepository queue,
                                CouponIssueProcessor processor,
                                ObjectMapper objectMapper) {
        this.queue = queue;
        this.processor = processor;
        this.objectMapper = objectMapper;
    }

    @Override
    public void start() {
        running = true;
        for (int i = 1; i <= AsyncPins.CONSUMERS; i++) {
            Thread t = new Thread(this::loop, "couponListWorker-" + i);
            t.setDaemon(false);
            workers.add(t);
            t.start();
        }
        log.info("[v6b-worker] started {} BRPOP workers (timeout {}ms)",
                AsyncPins.CONSUMERS, AsyncPins.BRPOP_TIMEOUT_MS);
    }

    private void loop() {
        while (running) {
            try {
                String json = queue.poll();                       // BRPOP 1s — 꺼내는 순간 큐에서 삭제 (ack 없음)
                if (json == null) {
                    continue;
                }
                IssueMessage m = objectMapper.readValue(json, IssueMessage.class);
                processor.process(m.requestId(), m.couponId(), m.userId());
            } catch (Exception e) {
                if (!running) {
                    break;                                        // 종료 중 커넥션 끊김은 정상 경로
                }
                log.warn("[v6b-worker] transient error — retrying (loop survives)", e);
                sleepQuietly(200);
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        long deadline = System.currentTimeMillis() + AsyncPins.GRACEFUL_SHUTDOWN_SEC * 1000L;
        for (Thread t : workers) {
            long remain = deadline - System.currentTimeMillis();
            if (remain > 0) {
                try {
                    t.join(remain);                               // in-flight 종료 대기 (T8 핀 10s)
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("[v6b-worker] stopped (graceful, in-flight drained within pin)");
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
