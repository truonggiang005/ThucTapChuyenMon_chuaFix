package com.smartschedule;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // Bật tính năng Cron Job cho Module 3 (Auto-assign)
public class SmartScheduleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartScheduleApplication.class, args);
    }
}
