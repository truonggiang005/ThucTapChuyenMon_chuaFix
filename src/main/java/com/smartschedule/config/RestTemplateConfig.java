package com.smartschedule.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Cấu hình RestTemplate để gọi Python AI Microservice.
 *
 * RestTemplate là HTTP client đồng bộ (synchronous) của Spring.
 * Timeout được set để tránh treo khi AI service không phản hồi.
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(5))    // Timeout kết nối: 5s
                .setReadTimeout(Duration.ofSeconds(30))      // Timeout đọc response: 30s (AI có thể tính lâu)
                .build();
    }
}
