package com.smartschedule.dto;

import lombok.*;

/** Một cặp (ca, ứng viên) gửi sang Python để chấm điểm NHANH bằng quy tắc (không gọi Gemini). */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AIQuickItem {
    private AIShiftData shift;
    private AIEmployeeData candidate;
}
