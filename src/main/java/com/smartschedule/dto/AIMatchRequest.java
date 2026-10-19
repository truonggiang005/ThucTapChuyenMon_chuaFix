package com.smartschedule.dto;

import lombok.*;
import java.util.List;

/**
 * DTO Request gửi sang Python AI Service.
 * Mapping 1:1 với MatchRequest trong schemas.py (Python).
 */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AIMatchRequest {
    private AIShiftData shift;
    private List<AIEmployeeData> candidates;
}
