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

    /** App route to open, e.g. the patient's caregiver-access screen; null for MESSAGE notifications. */
    private String actionUrl;

    /** What the notification opens: CAREGIVER_ACCESS, or null (MESSAGE notifications use threadId). */
    private String target;
}
