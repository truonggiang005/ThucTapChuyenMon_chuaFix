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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service giao tiếp với Python AI Microservice qua RestTemplate.
 *
 * Chia làm 2 bước để KHÔNG giữ transaction DB trong lúc chờ HTTP (LOG-08):
 *   1. buildRequest(shift, candidates): đọc entity → DTO (gọi bên trong transaction của caller).
 *   2. score(request): gọi HTTP sang Python (http://localhost:8001/api/v1/match-score), không đụng DB.
 *
 * Nếu AI service không khả dụng (down/timeout), trả về điểm mặc định đánh dấu fallback=true
 * để giao diện vẫn hiển thị, nhưng cron KHÔNG dùng điểm này để tự gán ca.
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

    /** Khóa bí mật dùng chung với Python (header X-Internal-Key) - SEC-08 */
    @Value("${ai.service.internal-key:}")
    private String internalKey;

    /**
     * Bước 1: Chuyển Shift + danh sách Employee → AIMatchRequest.
     * Gọi trong transaction (có truy cập quan hệ lazy).
     */
    public AIMatchRequest buildRequest(Shift shift, List<Employee> candidates) {
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

        // LOG-06: tuần được tính theo TUẦN CỦA CA (giống bộ lọc ở repository), không phải tuần hiện tại
        LocalDateTime[] week = ShiftService.weekRange(shift.getStartTime());

        List<AIEmployeeData> employeeDataList = candidates.stream().map(emp -> {
            Double hoursWorked = employeeRepository.calculateHoursWorkedInWeek(
                    emp.getId(), week[0], week[1], 0L);

            int skillLevel = 1;
            if (shift.getRequiredSkill() != null) {
                Optional<EmployeeSkill> es = employeeSkillRepository.findByEmployeeIdAndSkillId(
                        emp.getId(), shift.getRequiredSkill().getId());
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
                    .branch_id(emp.getPrimaryBranch() != null ? emp.getPrimaryBranch().getId() : 0L)
                    .build();
        }).collect(Collectors.toList());

        return AIMatchRequest.builder().shift(shiftData).candidates(employeeDataList).build();
    }

    /**
     * Bước 2: Gửi request sang Python và nhận danh sách đã xếp hạng. Không truy cập DB.
     */
    public AIMatchResponse score(AIMatchRequest request) {
        Long shiftId = request.getShift().getShift_id();
        log.info("[AI SERVICE] Gọi Python AI cho Shift #{} | {} ứng viên", shiftId, request.getCandidates().size());

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (internalKey != null && !internalKey.isBlank()) {
                headers.set("X-Internal-Key", internalKey);
            }

            String url = aiServiceUrl + "/api/v1/match-score";
            AIMatchResponse response = restTemplate.postForObject(
                    url, new HttpEntity<>(request, headers), AIMatchResponse.class);

            if (response == null || response.getRanked_candidates() == null) {
                log.warn("[AI SERVICE] Python trả về phản hồi rỗng, dùng điểm dự phòng");
                return buildFallbackResponse(request);
            }
            if (!response.getRanked_candidates().isEmpty()) {
                AIEmployeeScore top = response.getRanked_candidates().get(0);
                log.info("[AI SERVICE] Nhận {} kết quả | Top: #{} ({}%)",
                        response.getTotal_candidates(), top.getEmployee_id(), top.getMatch_score());
            }
            return response;

        } catch (RestClientException e) {
            log.error("[AI SERVICE] Python AI không khả dụng: {}", e.getMessage());
            return buildFallbackResponse(request);
        }
    }

    /** Tiện ích cho nơi đã đang ở trong transaction: build + score. */
    public AIMatchResponse getMatchScores(Shift shift, List<Employee> candidates) {
        return score(buildRequest(shift, candidates));
    }

    /**
     * Fallback khi Python AI service không khả dụng.
     * Điểm mặc định 75% cho tất cả và fallback=true: KHÔNG phản ánh độ phù hợp thật.
     */
    private AIMatchResponse buildFallbackResponse(AIMatchRequest request) {
        log.warn("[AI SERVICE] Sử dụng điểm dự phòng (75% cho tất cả)");

        List<AIEmployeeScore> fallbackScores = request.getCandidates().stream().map(c -> {
            Map<String, Object> breakdown = new HashMap<>();
            breakdown.put("reliability", 80.0);
            breakdown.put("skill_fit", 80.0);
            breakdown.put("time_fit", 70.0);
            breakdown.put("workload_fit", 70.0);
            breakdown.put("explanation", "Ứng viên đủ điều kiện cơ bản theo tiêu chuẩn ca trực.");

            return AIEmployeeScore.builder()
                    .employee_id(c.getEmployee_id())
                    .full_name(c.getFull_name())
                    .match_score(75.0)
                    .breakdown(breakdown)
                    .build();
        }).collect(Collectors.toList());

        return AIMatchResponse.builder()
                .shift_id(request.getShift().getShift_id())
                .ranked_candidates(fallbackScores)
                .total_candidates(fallbackScores.size())
                .fallback(true)
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
