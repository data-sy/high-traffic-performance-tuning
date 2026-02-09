package com.project.api.ranking;

import com.project.api.ranking.dto.RankingResponse;
import com.project.service.ranking.RankingV1Service;
import com.project.service.ranking.RankingV2Service;
import com.project.service.ranking.RankingV3Service;
import com.project.service.ranking.RankingV4Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RankingController {

    private final RankingV1Service v1Service;
    private final RankingV2Service v2Service;
    private final RankingV3Service v3Service;
    private final RankingV4Service v4Service;

    public RankingController(RankingV1Service v1Service,
                             RankingV2Service v2Service,
                             RankingV3Service v3Service,
                             RankingV4Service v4Service) {
        this.v1Service = v1Service;
        this.v2Service = v2Service;
        this.v3Service = v3Service;
        this.v4Service = v4Service;
    }

    @GetMapping("/v1/rankings")
    public RankingResponse getV1Rankings() {
        return v1Service.getRankings();
    }

    @GetMapping("/v2/rankings")
    public RankingResponse getV2Rankings() {
        return v2Service.getRankings();
    }

    @GetMapping("/v3/rankings")
    public RankingResponse getV3Rankings() {
        return v3Service.getRankings();
    }

    @GetMapping("/v4/rankings")
    public RankingResponse getV4Rankings() {
        return v4Service.getRankings();
    }
}
