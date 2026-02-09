package com.project.service.ranking;

import com.project.domain.order.OrderItemRepository;
import com.project.infrastructure.redis.RankingRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Hourly drift recovery between DB (source of truth) and Redis (performance layer).
 *
 * Consistency strategy:
 * 1. DB is always Source of Truth
 * 2. Redis is a read-performance cache layer
 * 3. DB save success + Redis update failure:
 *    → Temporary inconsistency allowed (max 1-hour drift)
 *    → Periodic rebuild via this scheduled task
 * 4. Full Redis failure:
 *    → Fallback to v1/v2 (direct DB query)
 *    → After recovery, call rebuildRankingFromDB()
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankingRecoveryService {

    private final OrderItemRepository orderItemRepository;
    private final RankingRedisRepository rankingRedisRepository;

    @Scheduled(fixedRate = 3600000) // 1 hour
    public void checkAndRecoverDrift() {
        log.info("Starting hourly ranking drift check...");

        List<Object[]> dbResults = orderItemRepository.findTopProductsBySales(10);

        int driftCount = 0;
        for (Object[] row : dbResults) {
            Long productId = ((Number) row[0]).longValue();
            Long dbSalesCount = ((Number) row[1]).longValue();

            Double redisScore = rankingRedisRepository.getScore(productId);
            long redisCount = redisScore != null ? redisScore.longValue() : 0L;

            if (Math.abs(dbSalesCount - redisCount) > dbSalesCount * 0.1) {
                driftCount++;
            }
        }

        if (driftCount > 1) {
            log.warn("Drift detected in {}/10 top products. Rebuilding ranking...", driftCount);
            rebuildRankingFromDB();
        } else {
            log.info("Ranking drift check passed. No rebuild needed.");
        }
    }

    public void rebuildRankingFromDB() {
        rankingRedisRepository.deleteRankingKey();

        List<Object[]> results = orderItemRepository.findTopProductsBySales(100);

        for (Object[] row : results) {
            Long productId = ((Number) row[0]).longValue();
            Long salesCount = ((Number) row[1]).longValue();
            rankingRedisRepository.incrementScore(productId, salesCount);
        }

        rankingRedisRepository.removeOutOfTop(100);

        log.info("Ranking rebuilt from DB: {} products", results.size());
    }
}
