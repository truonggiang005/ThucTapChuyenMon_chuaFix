package com.smartschedule.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;

/** Admin gán ca cho một nhân viên cụ thể. */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
public class AssignRequest {
    @NotNull(message = "Thiếu employeeId")
    private Long employeeId;
}
