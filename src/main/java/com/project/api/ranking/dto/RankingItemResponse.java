package com.project.api.ranking.dto;

public record RankingItemResponse(
        int rank,
        Long productId,
        String productName,
        Long salesCount
) {}
