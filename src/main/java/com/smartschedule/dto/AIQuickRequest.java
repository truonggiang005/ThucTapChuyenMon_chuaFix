package com.smartschedule.dto;

import lombok.*;
import java.util.List;

/** Request chấm điểm nhanh theo lô (B4): nhiều cặp (ca, nhân viên) trong MỘT lời gọi, không tốn quota Gemini. */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AIQuickRequest {
    private List<AIQuickItem> items;
}
