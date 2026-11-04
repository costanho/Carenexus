package com.carenexus.api.communications.dto.response;

import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationResponse {

    private Integer notificationId;

    private String type;

    private String title;

    private String message;

    private String priority;

    private Boolean read;

    private Instant readAt;

    private Instant createdAt;

    private Integer threadId;

    private Integer messageId;

    private String patientSystemId;
}
