package com.smartschedule.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Đồng hồ DUY NHẤT của ứng dụng (A13). Mọi nơi cần "bây giờ" dùng LocalDateTime.now(clock) thay vì giờ máy chủ,
 * nên deploy trên cloud (thường chạy UTC) vẫn tính đúng giờ nghiệp vụ. Múi giờ cấu hình ở app.timezone.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock(@Value("${app.timezone:Asia/Ho_Chi_Minh}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
