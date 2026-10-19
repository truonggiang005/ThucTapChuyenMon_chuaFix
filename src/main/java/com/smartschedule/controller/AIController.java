package com.smartschedule.controller;

import com.smartschedule.dto.AIHealthDTO;
import com.smartschedule.service.AIService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trạng thái AI service cho giao diện (FUN-02): trình duyệt hỏi Spring (cùng origin),
 * Spring mới hỏi Python. Trình duyệt không gọi thẳng cổng 8001.
 *
 * healthy chỉ true khi Spring gọi được endpoint CẦN KHÓA của Python, nên khóa nội bộ lệch cũng hiện ra (B5).
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AIController {

    private final AIService aiService;

    @GetMapping("/health")
    public ResponseEntity<AIHealthDTO> health() {
        return ResponseEntity.ok(aiService.health());
    }
}
