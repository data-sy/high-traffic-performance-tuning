package com.project.infrastructure.asyncqueue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * v6c 큐 = Redis Stream + 컨슈머 그룹. XADD 적재 / XREADGROUP 분배 / 터미널 기록 후 XACK.
 * 꺼내도 사라지지 않는다 — XACK 전 사망분은 PEL에 남고, 회수 루프가 재전달한다 (at-least-once).
 *
 * <p>큐 상한 가드는 XLEN이 아니라 <b>미처리 깊이(lag + pending)</b> 기준 — 스트림은 XACK 후에도
 * 엔트리를 보존하므로 XLEN은 "쌓인 일"이 아니라 "지나간 일 전체"다.
 */
@Repository
public class IssueStreamQueueRepository {

    public static final String STREAM_KEY = "coupon:issue:stream";
    public static final String GROUP = "coupon-issuers";

    private final StringRedisTemplate redis;

    public IssueStreamQueueRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @return 적재 성공 여부 — 미처리 깊이가 상한(근사) 이상이면 false (파사드가 429 매핑) */
    public boolean offer(Map<String, String> fields) {
        if (unprocessedDepth() >= AsyncPins.QUEUE_CAP) {
            return false;
        }
        redis.opsForStream().add(MapRecord.create(STREAM_KEY, fields));
        return true;
    }

    /**
     * 미처리 깊이. ack 완료분을 XDEL로 트림({@link #ack})하므로 스트림에는 미분배(lag) +
     * 미ack(pending) 엔트리만 남는다 — 따라서 XLEN == lag + pending == 깊이 (하네스의
     * XINFO GROUPS lag+pending 정의와 수치 일치). 트림 없이는 XLEN이 "지나간 일 전체"가 되어
     * 관찰 축에서 가드가 헛발동한다.
     */
    public long unprocessedDepth() {
        Long len = redis.opsForStream().size(STREAM_KEY);
        return len == null ? 0 : len;
    }

    /** 그룹 보장 — 런 간 reset(스트림 삭제)이 만든 NOGROUP에서 워커가 자가 복구하는 경로. */
    public void ensureGroup() {
        try {
            redis.opsForStream().createGroup(STREAM_KEY, ReadOffset.latest(), GROUP);
        } catch (Exception e) {
            // BUSYGROUP(이미 존재) 포함 — 보장 목적이므로 무시
        }
    }

    public List<MapRecord<String, Object, Object>> readGroup(String consumerName) {
        return redis.opsForStream().read(
                Consumer.from(GROUP, consumerName),
                StreamReadOptions.empty()
                        .block(Duration.ofMillis(AsyncPins.XREAD_BLOCK_MS))
                        .count(AsyncPins.XREAD_COUNT),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));
    }

    /**
     * XACK + XDEL — 모든 터미널 상태 기록 후에만 호출한다 (ISSUED·SOLD_OUT·FAILED·중복 흡수).
     * XDEL은 ack 완료분 트림(깊이 정의 유지용) — PEL의 미ack 엔트리는 삭제되지 않으므로
     * 재전달 안전성에 영향 없다.
     */
    public void ack(RecordId id) {
        redis.opsForStream().acknowledge(STREAM_KEY, GROUP, id);
        redis.opsForStream().delete(STREAM_KEY, id);
    }

    /** PEL 조회 — 회수 루프 입력 (XPENDING). */
    public PendingMessages pending() {
        return redis.opsForStream().pending(STREAM_KEY, GROUP, Range.unbounded(), 100L);
    }

    /** min-idle 경과분 회수 (XCLAIM) — 회수는 자동이 아니다, 이 호출이 재전달을 만든다 (T5). */
    public List<MapRecord<String, Object, Object>> claim(String newConsumer, RecordId... ids) {
        return redis.opsForStream().claim(STREAM_KEY, GROUP, newConsumer,
                Duration.ofMillis(AsyncPins.XAUTOCLAIM_MIN_IDLE_MS), ids);
    }
}
