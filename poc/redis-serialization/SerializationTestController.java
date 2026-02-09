package com.project.api.poc;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/poc/redis/serialization")
public class SerializationTestController {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final String TEST_KEY = "poc:serialization:test";

    public SerializationTestController(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostMapping("/save-dto")
    public Map<String, Object> saveDto() {
        TestRankingItem item = new TestRankingItem(
            100000001L,
            "Test Product",
            999999L
        );

        redisTemplate.opsForValue().set(TEST_KEY, item);

        return Map.of(
            "status", "saved",
            "original", item,
            "originalClass", item.getClass().getName()
        );
    }

    @GetMapping("/load-dto")
    public Map<String, Object> loadDto() {
        Object loaded = redisTemplate.opsForValue().get(TEST_KEY);

        Map<String, Object> result = new HashMap<>();
        result.put("loaded", loaded);
        result.put("loadedClass", loaded != null ? loaded.getClass().getName() : "null");

        if (loaded instanceof TestRankingItem item) {
            result.put("typeMatch", true);
            result.put("productIdClass", item.productId().getClass().getName());
            result.put("salesCountClass", item.salesCount().getClass().getName());
            result.put("productIdValue", item.productId());
            result.put("salesCountValue", item.salesCount());
        } else {
            result.put("typeMatch", false);
        }

        return result;
    }

    @DeleteMapping("/cleanup")
    public Map<String, String> cleanup() {
        redisTemplate.delete(TEST_KEY);
        return Map.of("status", "cleaned");
    }
}
