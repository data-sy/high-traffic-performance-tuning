package com.project.service.ranking;

import com.project.api.ranking.dto.RankingItemResponse;
import com.project.api.ranking.dto.RankingResponse;
import com.project.domain.order.OrderItemRepository;
import com.project.domain.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RankingV1Service {

    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;

    public RankingResponse getRankings() {
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

        return new RankingResponse(rankings, LocalDateTime.now());
    }
}
