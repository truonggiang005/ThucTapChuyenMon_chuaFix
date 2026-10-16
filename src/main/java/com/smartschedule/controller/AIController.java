package com.smartschedule.controller;

import com.smartschedule.service.AIService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Trạng thái AI service cho giao diện (FUN-02): trình duyệt hỏi Spring (cùng origin),
 * Spring mới hỏi Python. Trình duyệt không gọi thẳng cổng 8001.
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AIController {

    private final AIService aiService;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Boolean>> health() {
        return ResponseEntity.ok(Map.of("healthy", aiService.isServiceHealthy()));
    }
}
