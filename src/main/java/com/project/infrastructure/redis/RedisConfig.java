package com.project.infrastructure.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    /**
     * spring.data.redis.host/port를 따르는 Lettuce 연결 팩토리.
     *
     * <p>이전 구현은 무인자 {@code new LettuceConnectionFactory()}라 host/port를 항상 localhost:6379로
     * 하드코딩하고 {@code spring.data.redis.*}를 무시했다 — Redisson은 프로퍼티를 따라가는데
     * StringRedisTemplate만 6379로 고정되어, 전용 스택(6380) 토폴로지에서 상태 해시가 본체 redis로
     * 새는 잠복 버그였다(phase3-4 본 실행 cp2 발견). 기본값은 종전대로 localhost:6379라 본체 무영향.
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port) {
        return new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }
}
