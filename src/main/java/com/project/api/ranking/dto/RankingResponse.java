package com.project.api.ranking.dto;

import java.time.LocalDateTime;
import java.util.List;

public record RankingResponse(
        List<RankingItemResponse> rankings,
        LocalDateTime queriedAt
) {}
