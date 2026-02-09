package com.project.service.ranking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.api.ranking.dto.RankingItemResponse;
import com.project.api.ranking.dto.RankingResponse;
import com.project.domain.order.OrderItemRepository;
import com.project.domain.product.ProductRepository;
import com.project.infrastructure.redis.RankingRedisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RankingV3Service {

    private final RankingRedisRepository rankingRedisRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final ObjectMapper objectMapper;

    public RankingResponse getRankings() {
        String cached = rankingRedisRepository.getCachedRanking();

        if (cached != null) {
            try {
                return objectMapper.readValue(cached, RankingResponse.class);
            } catch (JsonProcessingException e) {
                rankingRedisRepository.clearCache();
            }
        }

        List<Object[]> results = orderItemRepository.findTopProductsBySales(100);
        List<RankingItemResponse> rankings = new ArrayList<>();

        int rank = 1;
        for (Object[] row : results) {
            Long productId = ((Number) row[0]).longValue();
            Long salesCount = ((Number) row[1]).longValue();

            String productName = productRepository.findById(productId)
                    .map(product -> product.getName())
                    .orElse("Unknown");

            rankings.add(new RankingItemResponse(rank++, productId, productName, salesCount));
        }

        RankingResponse response = new RankingResponse(rankings, LocalDateTime.now());

        try {
            String json = objectMapper.writeValueAsString(response);
            rankingRedisRepository.cacheRanking(json);
        } catch (JsonProcessingException e) {
            // Cache write failure is acceptable — next request will retry
        }

        return response;
    }
}
