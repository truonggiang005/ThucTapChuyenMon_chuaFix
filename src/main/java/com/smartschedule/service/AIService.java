package com.smartschedule.service;

import com.smartschedule.dto.*;
import com.smartschedule.entity.Employee;
import com.smartschedule.entity.Shift;
import com.smartschedule.repository.EmployeeSkillRepository;
import com.smartschedule.util.WorkWeek;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Service giao tiếp với Python AI Microservice qua RestTemplate.
 *
 * Chia làm 2 bước để KHÔNG giữ transaction DB trong lúc chờ HTTP (LOG-08):
 *   1. buildRequest(): đọc entity → DTO (trong transaction của caller, dùng truy vấn GỘP - không N+1).
 *   2. score(): gọi HTTP sang Python, không đụng DB.
 *
 * Khi AI không dùng được, Java KHÔNG tự bịa điểm (trước đây trả 75% cho tất cả - B2). Thay vào đó trả về
 * response có available=false + error_code + message, để giao diện báo đúng sự thật và cron không có điểm nào để gán.
 * Còn điểm dự phòng do Python tính bằng quy tắc thì được Python đánh dấu fallback=true (B1).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AIService {

    /** Python từ chối quá 200 ứng viên/lời gọi (schemas.MAX_CANDIDATES). */
    public static final int MAX_CANDIDATES = 200;
    /** Số cặp tối đa cho một lời gọi chấm điểm nhanh (schemas.MAX_QUICK_ITEMS). */
    public static final int MAX_QUICK_ITEMS = 100;

    private final RestTemplate restTemplate;
    private final EmployeeSkillRepository employeeSkillRepository;
    private final ShiftRules rules;

    @Value("${ai.service.url}")
    private String aiServiceUrl;

    /** Khóa bí mật dùng chung với Python (header X-Internal-Key) - SEC-08 */
    @Value("${ai.service.internal-key:}")
    private String internalKey;

    // Bước 1: dựng request (trong transaction)

    /** Dữ liệu ca gửi AI. */
    public AIShiftData buildShiftData(Shift shift) {
        return AIShiftData.builder()
                .shift_id(shift.getId())
                .branch_id(shift.getBranch().getId())
                .required_skill_id(shift.getRequiredSkill() != null ? shift.getRequiredSkill().getId() : 0L)
                .required_level(shift.getRequiredLevel() != null ? shift.getRequiredLevel() : 1)
                .start_time(shift.getStartTime().toString())
                .end_time(shift.getEndTime().toString())
                .shift_duration_hours(WorkWeek.hoursBetween(shift.getStartTime(), shift.getEndTime()))
                .build();
    }

    /**
     * Dữ liệu các ứng viên cho một ca. Giờ đã làm trong tuần và mức kỹ năng được lấy bằng HAI truy vấn gộp cho cả
     * danh sách (A11), mọi giá trị null được thay bằng mặc định hợp lệ (B10).
     */
    public List<AIEmployeeData> buildCandidateData(Shift shift, List<Employee> candidates) {
        List<Long> ids = candidates.stream().map(Employee::getId).toList();

        Map<Long, Double> hours = rules.workedHours(ids,
                WorkWeek.weekStart(shift.getStartTime()), WorkWeek.weekEnd(shift.getStartTime()), shift.getId());

        Map<Long, Integer> levels = new HashMap<>();
        if (shift.getRequiredSkill() != null && !ids.isEmpty()) {
            for (Object[] row : employeeSkillRepository.findLevelsForEmployees(shift.getRequiredSkill().getId(), ids)) {
                levels.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
            }
        }

        return candidates.stream().map(emp -> AIEmployeeData.builder()
                .employee_id(emp.getId())
                .full_name(emp.getFullName() != null ? emp.getFullName() : "")
                .skill_level(clamp(levels.getOrDefault(emp.getId(), 1), 1, 5))
                .total_completed(nonNegative(emp.getTotalCompleted()))
                .total_cancelled(nonNegative(emp.getTotalCancelled()))
                .late_arrival_count(nonNegative(emp.getLateArrivalCount()))
                .preferred_time(emp.getPreferredTime() != null ? emp.getPreferredTime() : "ANY")
                .hours_worked_this_week(hours.getOrDefault(emp.getId(), 0.0))
                .max_hours_per_week(emp.getMaxHoursPerWeek() != null ? emp.getMaxHoursPerWeek() : 40)
                .branch_id(emp.getPrimaryBranch() != null ? emp.getPrimaryBranch().getId() : 0L)
                .build()).toList();
    }

    /** Chuyển Shift + danh sách Employee → AIMatchRequest (tối đa 200 ứng viên). */
    public AIMatchRequest buildRequest(Shift shift, List<Employee> candidates) {
        List<Employee> limited = candidates;
        if (candidates.size() > MAX_CANDIDATES) {
            log.warn("[AI SERVICE] Ca #{} có {} ứng viên, chỉ gửi {} đầu tiên", shift.getId(), candidates.size(), MAX_CANDIDATES);
            limited = candidates.subList(0, MAX_CANDIDATES);
        }
        return AIMatchRequest.builder()
                .shift(buildShiftData(shift))
                .candidates(buildCandidateData(shift, limited))
                .build();
    }

    // Bước 2: gọi Python (không đụng DB)

    /** Chấm điểm bằng Gemini (có dự phòng quy tắc ở phía Python). Không bao giờ ném exception ra ngoài. */
    public AIMatchResponse score(AIMatchRequest request) {
        Long shiftId = request.getShift().getShift_id();
        log.info("[AI SERVICE] Gọi Python AI cho Shift #{} | {} ứng viên", shiftId, request.getCandidates().size());

        try {
            AIMatchResponse response = restTemplate.postForObject(
                    aiServiceUrl + "/api/v1/match-score", new HttpEntity<>(request, jsonHeaders()), AIMatchResponse.class);

            if (response == null || response.getRanked_candidates() == null) {
                log.warn("[AI SERVICE] Python trả về phản hồi rỗng");
                return unavailable(shiftId, "AI_ERROR", "AI service trả về phản hồi không hợp lệ.");
            }
            if (response.getSource() == null) {
                response.setSource("gemini");
            }
            if (response.isFallback() && response.getMessage() == null) {
                response.setMessage("AI (Gemini) tạm thời không dùng được — điểm hiển thị là điểm dự phòng theo quy tắc, chỉ để tham khảo.");
            }
            if (!response.getRanked_candidates().isEmpty()) {
                AIEmployeeScore top = response.getRanked_candidates().get(0);
                log.info("[AI SERVICE] Nhận {} kết quả (source={}, fallback={}) | Top: #{} ({}%)",
                        response.getTotal_candidates(), response.getSource(), response.isFallback(),
                        top.getEmployee_id(), top.getMatch_score());
            }
            return response;

        } catch (RestClientException e) {
            Failure f = classify(e);
            log.error("[AI SERVICE] Gọi Python thất bại ({}): {}", f.code, e.getMessage());
            return unavailable(shiftId, f.code, f.message);
        }
    }

    /**
     * Chấm điểm NHANH theo lô bằng quy tắc (không gọi Gemini, không tốn quota) cho Chợ ca (B4).
     * @return map shiftId → kết quả cho người nhận, hoặc null nếu dịch vụ không dùng được (kèm lý do qua errorOut)
     */
    public QuickOutcome quickScores(List<AIQuickItem> items) {
        if (items.isEmpty()) {
            return new QuickOutcome(true, null, Map.of());
        }
        List<AIQuickItem> limited = items.size() > MAX_QUICK_ITEMS ? items.subList(0, MAX_QUICK_ITEMS) : items;
        try {
            AIQuickResponse response = restTemplate.postForObject(
                    aiServiceUrl + "/api/v1/quick-scores",
                    new HttpEntity<>(AIQuickRequest.builder().items(limited).build(), jsonHeaders()),
                    AIQuickResponse.class);
            Map<Long, AIQuickResponse.Result> byShift = new HashMap<>();
            if (response != null && response.getResults() != null) {
                for (AIQuickResponse.Result r : response.getResults()) {
                    byShift.put(r.getShift_id(), r);
                }
            }
            return new QuickOutcome(true, null, byShift);
        } catch (RestClientException e) {
            Failure f = classify(e);
            log.error("[AI SERVICE] quick-scores thất bại ({}): {}", f.code, e.getMessage());
            return new QuickOutcome(false, f.message, Map.of());
        }
    }

    /** Kết quả chấm điểm nhanh. */
    public record QuickOutcome(boolean available, String message, Map<Long, AIQuickResponse.Result> byShift) {}

    // Health check (B5)

    /**
     * Kiểm tra AI service THẬT SỰ dùng được: gọi /api/v1/ready (endpoint cần X-Internal-Key), nên khóa lệch
     * hay thiếu cấu hình cũng bị phát hiện, không chỉ kiểm tra tiến trình Python còn sống.
     */
    public AIHealthDTO health() {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restTemplate.exchange(
                    aiServiceUrl + "/api/v1/ready", org.springframework.http.HttpMethod.GET,
                    new HttpEntity<>(jsonHeaders()), Map.class).getBody();
            boolean gemini = body != null && Boolean.TRUE.equals(body.get("gemini_configured"));
            return AIHealthDTO.builder()
                    .healthy(true)
                    .geminiConfigured(gemini)
                    .message(gemini ? "AI sẵn sàng." : "AI chạy ở chế độ quy tắc (chưa cấu hình GEMINI_API_KEY).")
                    .build();
        } catch (RestClientException e) {
            Failure f = classify(e);
            log.warn("[AI SERVICE] Health check thất bại ({}): {}", f.code, e.getMessage());
            return AIHealthDTO.builder().healthy(false).reason(f.code).message(f.message).build();
        }
    }

    // Tiện ích

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (internalKey != null && !internalKey.isBlank()) {
            headers.set("X-Internal-Key", internalKey);
        }
        return headers;
    }

    private record Failure(String code, String message) {}

    /** Phân loại lỗi để người dùng/ quản trị biết NGUYÊN NHÂN thật (khóa lệch ≠ AI sập ≠ hết quota). */
    private Failure classify(RestClientException e) {
        if (e instanceof HttpStatusCodeException http) {
            HttpStatusCode code = http.getStatusCode();
            int s = code.value();
            if (s == 401 || s == 403) {
                return new Failure("AI_KEY_MISMATCH",
                        "Khóa nội bộ giữa Spring và AI service không khớp (AI_INTERNAL_KEY ≠ INTERNAL_KEY).");
            }
            if (s == 503) {
                return new Failure("AI_NOT_CONFIGURED", "AI service chưa được cấu hình xong (thiếu INTERNAL_KEY).");
            }
            if (s == 422) {
                return new Failure("AI_BAD_REQUEST", "AI service từ chối dữ liệu gửi sang (sai hợp đồng dữ liệu).");
            }
            if (s == 429) {
                return new Failure("AI_RATE_LIMITED", "AI service đang bị giới hạn tần suất. Thử lại sau ít phút.");
            }
            return new Failure("AI_ERROR", "AI service gặp lỗi (HTTP " + s + ").");
        }
        if (e instanceof ResourceAccessException) {
            return new Failure("AI_UNREACHABLE", "Không kết nối được AI service (chưa chạy hoặc quá thời gian chờ).");
        }
        return new Failure("AI_ERROR", "Không gọi được AI service.");
    }

    private AIMatchResponse unavailable(Long shiftId, String code, String message) {
        return AIMatchResponse.builder()
                .shift_id(shiftId)
                .ranked_candidates(List.of())
                .total_candidates(0)
                .source("none")
                .fallback(true)
                .available(false)
                .error_code(code)
                .message(message)
                .build();
    }

    private static int nonNegative(Integer v) {
        return v == null || v < 0 ? 0 : v;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
