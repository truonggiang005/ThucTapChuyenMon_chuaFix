package com.smartschedule.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * RestTemplate gọi Python AI Microservice.
 *
 * Timeout đọc phải LỚN HƠN tổng thời hạn Python dành cho Gemini (AI_DEADLINE_SECONDS, mặc định 12s),
 * nếu không Java bỏ cuộc trong khi Python vẫn đang chạy (B8). Mặc định 15s.
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder,
                                     @Value("${ai.service.connect-timeout-seconds:3}") long connectSeconds,
                                     @Value("${ai.service.read-timeout-seconds:15}") long readSeconds) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(connectSeconds))
                .setReadTimeout(Duration.ofSeconds(readSeconds))
                .build();
    }
}
