package com.project.api.poc;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/api/poc/redis")
public class RedisConnectionCheckController {

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisConnectionCheckController(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/ping")
    public Map<String, String> ping() {
        String result = redisTemplate.getConnectionFactory()
                .getConnection()
                .ping();
        return Map.of("status", result);
    }

    @GetMapping("/set-get")
    public Map<String, Object> setGet() {
        String key = "poc:test";
        String value = "test-value";
        redisTemplate.opsForValue().set(key, value);
        Object retrieved = redisTemplate.opsForValue().get(key);
        return Map.of("key", key, "value", retrieved);
    }

    @GetMapping("/info")
    public Map<String, String> info() {
        Properties info = redisTemplate.getConnectionFactory()
                .getConnection()
                .serverCommands()
                .info();
        String version = info.getProperty("redis_version", "unknown");
        String usedMemory = info.getProperty("used_memory_human", "unknown");
        return Map.of("version", version, "used_memory", usedMemory);
    }

    @GetMapping("/key-prefix-check")
    public Map<String, Object> keyPrefixCheck() {
        Set<String> allKeys = redisTemplate.keys("*");
        List<String> checkedKeys = new ArrayList<>();
        boolean allValid = true;

        if (allKeys != null) {
            for (String key : allKeys) {
                checkedKeys.add(key);
                if (!key.startsWith("poc:") && !key.startsWith("ranking:") && !key.startsWith("cache:")) {
                    allValid = false;
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("keysChecked", checkedKeys);
        result.put("allValid", allValid);
        return result;
    }
}
