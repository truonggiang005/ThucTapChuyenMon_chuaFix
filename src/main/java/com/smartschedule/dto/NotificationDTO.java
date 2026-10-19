package com.smartschedule.dto;

import lombok.*;

/** Thông báo trả về cho giao diện. */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationDTO {
    private Long id;
    private String type;
    private String message;
    private String createdAt;
    private boolean read;
}
