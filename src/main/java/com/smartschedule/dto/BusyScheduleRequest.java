package com.smartschedule.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDateTime;

/**
 * DTO cho request đăng ký lịch bận.
 * KHÔNG có employeeId: server lấy từ người đăng nhập (SEC-03).
 */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BusyScheduleRequest {
    @NotNull(message = "Thiếu thời gian bắt đầu")
    private LocalDateTime startTime;

    @NotNull(message = "Thiếu thời gian kết thúc")
    private LocalDateTime endTime;

    @Size(max = 300, message = "Lý do tối đa 300 ký tự")
    private String reason;
}
