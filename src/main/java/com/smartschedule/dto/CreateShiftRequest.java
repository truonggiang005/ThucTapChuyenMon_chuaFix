package com.smartschedule.dto;

import jakarta.validation.constraints.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * DTO cho request tạo/sửa ca. Có ràng buộc đầu vào (SEC-14); thời gian là LocalDateTime
 * nên JSON sai định dạng bị từ chối với HTTP 400 thay vì lỗi 500.
 */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateShiftRequest {
    @NotBlank(message = "Tiêu đề ca không được để trống")
    @Size(max = 200, message = "Tiêu đề tối đa 200 ký tự")
    private String title;

    @NotNull(message = "Thiếu thời gian bắt đầu")
    private LocalDateTime startTime;

    @NotNull(message = "Thiếu thời gian kết thúc")
    private LocalDateTime endTime;

    @Min(value = 1, message = "Level tối thiểu là 1")
    @Max(value = 5, message = "Level tối đa là 5")
    private Integer requiredLevel;

    @NotNull(message = "Thiếu chi nhánh")
    private Long branchId;

    private Long requiredSkillId;
    private Long assignedToId;   // Nullable: nếu null → status = OPEN

    /**
     * Version của ca mà client đang xem (chỉ dùng khi SỬA - A8). Nếu khác version trong DB nghĩa là người khác
     * đã sửa/nhận ca trong lúc đó → 409, tránh hai admin ghi đè lẫn nhau.
     */
    private Long version;
}
