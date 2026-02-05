package com.project.api.poc;

import com.project.domain.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/poc")
@RequiredArgsConstructor
public class HealthCheckController {

    private final ProductRepository productRepository;

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/db-check")
    public ResponseEntity<Map<String, Long>> dbCheck() {
        long count = productRepository.count();
        return ResponseEntity.ok(Map.of("count", count));
    }

    @GetMapping("/paging-check")
    public ResponseEntity<?> pagingCheck() {
        return ResponseEntity.ok(
                productRepository.findByCategoryOrderByCreatedAtDesc(
                        "전자기기", PageRequest.of(0, 5))
        );
    }
}
