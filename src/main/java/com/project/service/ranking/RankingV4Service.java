package com.project.service.ranking;

import com.project.api.ranking.dto.RankingItemResponse;
import com.project.api.ranking.dto.RankingResponse;
import com.project.infrastructure.redis.RankingRedisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RankingV4Service {

    private final RankingRedisRepository rankingRedisRepository;

    /**
     * Zero DB queries — reads entirely from Redis Sorted Set.
     * Product names use placeholder format "Product {id}" to avoid DB access.
     */
    public RankingResponse getRankings() {
        Set<ZSetOperations.TypedTuple<Object>> topN = rankingRedisRepository.getTopN(100);
        List<RankingItemResponse> rankings = new ArrayList<>();

        int rank = 1;
        if (topN != null) {
            for (ZSetOperations.TypedTuple<Object> tuple : topN) {
                Long productId = Long.parseLong(tuple.getValue().toString());
                Long salesCount = tuple.getScore() != null ? tuple.getScore().longValue() : 0L;
                String productName = "Product " + productId;

                rankings.add(new RankingItemResponse(rank++, productId, productName, salesCount));
            }
        }

        return new RankingResponse(rankings, LocalDateTime.now());
    }

    /**
     * Atomic increment via ZINCRBY — no race condition possible.
     */
    public void incrementScore(Long productId, int quantity) {
        rankingRedisRepository.incrementScore(productId, quantity);
        rankingRedisRepository.removeOutOfTop(100);
    }
}
