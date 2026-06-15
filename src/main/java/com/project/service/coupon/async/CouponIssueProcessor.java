package com.project.service.coupon.async;

import com.project.domain.coupon.CouponSoldOutException;
import com.project.infrastructure.asyncqueue.ChaosKillSwitch;
import com.project.infrastructure.asyncqueue.IssueStatusRepository;
import com.project.service.coupon.CouponIssueServiceV3;
import com.project.service.coupon.DuplicateIssueException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * v6 공통 컨슈머 처리 — 발급 경로는 v3(FOR UPDATE, 단일 트랜잭션) 재사용,
 * 처리 결과는 HTTP가 아니라 상태 해시 터미널 기록으로 전달한다.
 *
 * <p>상태 전이: ACCEPTED → ISSUED(커밋 성공) | SOLD_OUT(한도 소진) | FAILED(그 외 — 재시도 없음,
 * DLQ 범위 밖). <b>중복 재전달(v6c)의 유니크 충돌은 ISSUED로 흡수</b> — 행 수는 절대 늘지 않는다 (T6).
 *
 * <p>카오스 훅 위치: AFTER_POP = 처리 시작 전(=큐에서 꺼낸 직후),
 * AFTER_COMMIT = v3 커밋 성공 직후·터미널 기록/XACK 전 — 여기서 죽으면 상태는 ACCEPTED로 남고
 * (v6b) 메시지는 증발, (v6c) PEL에 남아 재전달된다.
 */
@Service
public class CouponIssueProcessor {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueProcessor.class);

    private final CouponIssueServiceV3 v3Service;
    private final IssueStatusRepository statusRepository;
    private final ChaosKillSwitch chaos;

    public CouponIssueProcessor(CouponIssueServiceV3 v3Service,
                                IssueStatusRepository statusRepository,
                                ChaosKillSwitch chaos) {
        this.v3Service = v3Service;
        this.statusRepository = statusRepository;
        this.chaos = chaos;
    }

    public void process(String requestId, Long couponId, Long userId) {
        chaos.maybeHalt(ChaosKillSwitch.AFTER_POP);
        String status;
        try {
            v3Service.issue(couponId, userId);                 // FOR UPDATE 직렬화 + 커밋
            chaos.maybeHalt(ChaosKillSwitch.AFTER_COMMIT);     // 커밋됨 + 터미널 미기록 = 재전달 창 (T6)
            status = "ISSUED";
        } catch (CouponSoldOutException e) {
            status = "SOLD_OUT";
        } catch (DuplicateIssueException | DataIntegrityViolationException e) {
            // at-least-once의 청구서 — 재전달분의 INSERT 충돌은 "이미 발급됨"으로 정상 흡수 (T6).
            // 이 로그가 방어 작동의 증거다 (판정: 행 수==100 && 이 로그 ≥ 1).
            log.info("[IDEMPOTENT-ABSORB] duplicate issue absorbed: requestId={} couponId={} userId={}",
                    requestId, couponId, userId);
            status = "ISSUED";
        } catch (Exception e) {
            log.warn("[v6-process] FAILED requestId={} couponId={} userId={}", requestId, couponId, userId, e);
            status = "FAILED";
        }
        statusRepository.complete(requestId, status, System.currentTimeMillis());
        // T1 검증 증거: couponExecutor-* 면 비동기, http-nio-* 면 동기(프록시 미경유 — 실패)
        log.info("[v6-process] thread={} requestId={} status={}",
                Thread.currentThread().getName(), requestId, status);
    }
}
