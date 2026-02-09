package com.project.initializer;

import com.project.domain.order.OrderItemRepository;
import com.project.infrastructure.redis.RankingRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RankingInitializer implements ApplicationRunner {

    private final OrderItemRepository orderItemRepository;
    private final RankingRedisRepository rankingRedisRepository;

    @Override
    public void run(ApplicationArguments args) {
        rankingRedisRepository.deleteRankingKey();

        List<Object[]> results = orderItemRepository.findTopProductsBySales(100);

        for (Object[] row : results) {
            Long productId = ((Number) row[0]).longValue();
            Long salesCount = ((Number) row[1]).longValue();
            rankingRedisRepository.incrementScore(productId, salesCount);
        }

        log.info("Ranking initialized: {} products", results.size());
    }
}
