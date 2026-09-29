package com.smartschedule.service;

import com.smartschedule.dto.*;
import com.smartschedule.entity.Employee;
import com.smartschedule.entity.EmployeeSkill;
import com.smartschedule.entity.Shift;
import com.smartschedule.repository.EmployeeRepository;
import com.smartschedule.repository.EmployeeSkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service giao tiếp với Python AI Microservice qua RestTemplate.
 *
 * Chịu trách nhiệm:
 *   1. Build request body (chuyển Entity → DTO)
 *   2. Gọi HTTP POST sang Python (http://localhost:8000/api/v1/match-score)
 *   3. Parse response và trả về danh sách đã xếp hạng
 *
 * Nếu AI service không khả dụng (down/timeout), trả về danh sách mặc định
 * (không chấm điểm) để hệ thống vẫn hoạt động.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AIService {

    private final RestTemplate restTemplate;
    private final EmployeeRepository employeeRepository;
    private final EmployeeSkillRepository employeeSkillRepository;

    @Value("${ai.service.url}")
    private String aiServiceUrl;

    /**
     * ★ GỌI PYTHON AI: Chấm điểm danh sách ứng viên cho một ca.
     *
     * Flow:
     *   1. Chuyển Shift entity → AIShiftData DTO
     *   2. Chuyển List<Employee> → List<AIEmployeeData> DTO (kèm hours_worked_this_week)
     *   3. POST sang Python /api/v1/match-score
     *   4. Nhận AIMatchResponse (danh sách đã xếp hạng)
     *
     * @param shift      Ca cần tìm người
     * @param candidates Danh sách nhân viên đã qua vòng lọc Repository
     * @return AIMatchResponse với ranked_candidates (giảm dần theo score)
     */
    public AIMatchResponse getMatchScores(Shift shift, List<Employee> candidates) {
        log.info("[AI SERVICE] Gọi Python AI cho Shift #{} | {} ứng viên", shift.getId(), candidates.size());

        try {
            // ── Bước 1: Build AIShiftData ──
            double durationHours = Duration.between(shift.getStartTime(), shift.getEndTime()).toMinutes() / 60.0;
            AIShiftData shiftData = AIShiftData.builder()
                    .shift_id(shift.getId())
                    .branch_id(shift.getBranch().getId())
                    .required_skill_id(shift.getRequiredSkill() != null ? shift.getRequiredSkill().getId() : 0L)
                    .required_level(shift.getRequiredLevel())
                    .start_time(shift.getStartTime().toString())
                    .end_time(shift.getEndTime().toString())
                    .shift_duration_hours(durationHours)
                    .build();

            // ── Bước 2: Build List<AIEmployeeData> ──
            // Tính weekStart/weekEnd cho hours_worked_this_week
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime weekStart = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toLocalDate().atStartOfDay();
            LocalDateTime weekEnd = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).toLocalDate().atTime(23, 59, 59);

            List<AIEmployeeData> employeeDataList = candidates.stream().map(emp -> {
                // Tính giờ đã làm trong tuần
                Double hoursWorked = employeeRepository.calculateHoursWorkedInWeek(
                        emp.getId(), weekStart, weekEnd
                );

                // Lấy skill_level cho required_skill
                int skillLevel = 1;
                if (shift.getRequiredSkill() != null) {
                    Optional<EmployeeSkill> es = employeeSkillRepository.findByEmployeeIdAndSkillId(
                            emp.getId(), shift.getRequiredSkill().getId()
                    );
                    if (es.isPresent()) {
                        skillLevel = es.get().getSkillLevel();
                    }
                }

                return AIEmployeeData.builder()
                        .employee_id(emp.getId())
                        .full_name(emp.getFullName())
                        .skill_level(skillLevel)
                        .total_completed(emp.getTotalCompleted())
                        .total_cancelled(emp.getTotalCancelled())
                        .late_arrival_count(emp.getLateArrivalCount())
                        .preferred_time(emp.getPreferredTime())
                        .hours_worked_this_week(hoursWorked != null ? hoursWorked : 0.0)
                        .max_hours_per_week(emp.getMaxHoursPerWeek())
                        .build();
            }).collect(Collectors.toList());

            // ── Bước 3: Gửi POST sang Python ──
            AIMatchRequest request = AIMatchRequest.builder()
                    .shift(shiftData)
                    .candidates(employeeDataList)
                    .build();

            String url = aiServiceUrl + "/api/v1/match-score";
            log.info("[AI SERVICE] POST → {}", url);

            AIMatchResponse response = restTemplate.postForObject(url, request, AIMatchResponse.class);

            if (response != null && response.getRanked_candidates() != null) {
                log.info("[AI SERVICE] ✅ Nhận {} kết quả | Top: {} ({}%)",
                        response.getTotal_candidates(),
                        response.getRanked_candidates().get(0).getFull_name(),
                        response.getRanked_candidates().get(0).getMatch_score());
            }

            return response;

        } catch (RestClientException e) {
            log.error("[AI SERVICE] ❌ Python AI không khả dụng: {}", e.getMessage());
            // Fallback: trả về danh sách không chấm điểm (score = 50%)
            return buildFallbackResponse(shift, candidates);
        }
    }

    /**
     * Fallback khi Python AI service không khả dụng.
     * Gán score mặc định = 50% cho tất cả, giữ thứ tự gốc.
     */
    private AIMatchResponse buildFallbackResponse(Shift shift, List<Employee> candidates) {
        log.warn("[AI SERVICE] Sử dụng fallback scoring (50% cho tất cả)");

        List<AIEmployeeScore> fallbackScores = candidates.stream().map(emp ->
                AIEmployeeScore.builder()
                        .employee_id(emp.getId())
                        .full_name(emp.getFullName())
                        .match_score(50.0)
                        .build()
        ).collect(Collectors.toList());

        return AIMatchResponse.builder()
                .shift_id(shift.getId())
                .ranked_candidates(fallbackScores)
                .total_candidates(fallbackScores.size())
                .build();
    }

    /**
     * Health check: kiểm tra Python AI service còn sống không.
     * @return true nếu service phản hồi OK
     */
    public boolean isServiceHealthy() {
        try {
            String url = aiServiceUrl + "/api/v1/health";
            restTemplate.getForObject(url, String.class);
            return true;
        } catch (RestClientException e) {
            log.warn("[AI SERVICE] Health check failed: {}", e.getMessage());
            return false;
        }
    }
}
