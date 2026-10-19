package com.smartschedule;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.ZoneId;
import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling // Bật tính năng Cron Job cho Module 3 (Auto-assign)
public class SmartScheduleApplication {

    public static void main(String[] args) {
        // A13: thời gian trong DB/JDBC/cron dùng LocalDateTime không múi giờ, nên ép JVM về đúng múi giờ nghiệp vụ
        // TRƯỚC khi Spring khởi động (khớp với serverTimezone trong URL JDBC và bean Clock ở TimeConfig).
        String zone = System.getenv().getOrDefault("APP_TIMEZONE", "Asia/Ho_Chi_Minh");
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(zone)));
        SpringApplication.run(SmartScheduleApplication.class, args);
    }
}
