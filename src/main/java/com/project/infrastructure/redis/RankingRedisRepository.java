package com.project.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import java.util.Set;
import java.util.concurrent.TimeUnit;

@Repository
@RequiredArgsConstructor
public class RankingRedisRepository {

    private static final String RANKING_KEY = "ranking:products";
    private static final String CACHE_KEY = "cache:ranking:top100";

    private final RedisTemplate<String, Object> redisTemplate;

    // Sorted Set operations

    public void incrementScore(Long productId, double delta) {
        redisTemplate.opsForZSet().incrementScore(RANKING_KEY, productId.toString(), delta);
    }

    public Set<ZSetOperations.TypedTuple<Object>> getTopN(int n) {
        return redisTemplate.opsForZSet().reverseRangeWithScores(RANKING_KEY, 0, n - 1);
    }

    public Double getScore(Long productId) {
        return redisTemplate.opsForZSet().score(RANKING_KEY, productId.toString());
    }

    public void removeOutOfTop(int topN) {
        Long size = redisTemplate.opsForZSet().zCard(RANKING_KEY);
        if (size != null && size > topN) {
            redisTemplate.opsForZSet().removeRange(RANKING_KEY, 0, size - topN - 1);
        }
    }

    // String caching operations (for v3)

    public void cacheRanking(String data) {
        redisTemplate.opsForValue().set(CACHE_KEY, data, 5, TimeUnit.MINUTES);
    }

    public String getCachedRanking() {
        Object cached = redisTemplate.opsForValue().get(CACHE_KEY);
        return cached != null ? cached.toString() : null;
    }

    public void clearCache() {
        redisTemplate.delete(CACHE_KEY);
    }

    public void deleteRankingKey() {
        redisTemplate.delete(RANKING_KEY);
    }
}
