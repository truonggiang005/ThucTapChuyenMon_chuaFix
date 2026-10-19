package com.smartschedule.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/** Một kỹ năng của nhân viên kèm mức 1..5. */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SkillLevelDTO {
    @NotNull(message = "Thiếu kỹ năng")
    private Long skillId;

    private String skillName;

    @NotNull(message = "Thiếu mức kỹ năng")
    @Min(value = 1, message = "Mức kỹ năng tối thiểu là 1")
    @Max(value = 5, message = "Mức kỹ năng tối đa là 5")
    private Integer level;
}
