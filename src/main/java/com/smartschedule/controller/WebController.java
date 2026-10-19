package com.smartschedule.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller trả về các trang giao diện Thymeleaf (src/main/resources/templates).
 * - "/"          → index.html     (Đăng nhập)
 * - "/dashboard" → dashboard.html (Trang quản lý ca làm việc)
 */
@Controller
public class WebController {

    @GetMapping("/")
    public String loginPage() {
        return "index";
    }

    @GetMapping("/dashboard")
    public String dashboardPage() {
        return "dashboard";
    }
}
