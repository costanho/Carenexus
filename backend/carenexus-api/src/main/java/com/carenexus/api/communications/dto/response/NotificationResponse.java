package com.carenexus.api.communications.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "One in-app notification of the signed-in user")
public class NotificationResponse {

    @Schema(description = "Row id; use it to mark the notification read", example = "214")
    private Integer notificationId;

    @Schema(description = "Notification type; clients key icon and colour on it",
            allowableValues = {"APPOINTMENT", "MEDICATION", "LAB_RESULT", "MESSAGE", "AI_ALERT", "SYSTEM", "PAYMENT", "REFERRAL"},
            example = "LAB_RESULT")
    private String type;

    @Schema(description = "Short generic title", example = "New lab result")
    private String title;

    @Schema(description = "One generic sentence; never a result value, diagnosis, dose or message body",
            example = "A lab result for Thomas Dube is available")
    private String message;

    @Schema(allowableValues = {"LOW", "NORMAL", "HIGH", "CRITICAL"}, example = "NORMAL")
    private String priority;

    private Boolean read;

    @Schema(description = "When it was marked read; null while unread", nullable = true)
    private Instant readAt;

    private Instant createdAt;

    @Schema(description = "Conversation announced by a thread MESSAGE notification; null for every other row", nullable = true)
    private Integer threadId;

    @Schema(description = "Message announced by a thread MESSAGE notification; null otherwise", nullable = true)
    private Integer messageId;

    @Schema(description = "The patient the notification is about; null for account-level notifications",
            example = "PAT-00001", nullable = true)
    private String patientSystemId;

    /** App route to open, e.g. the patient's caregiver-access screen; null for MESSAGE notifications. */
    @Schema(description = "In-app link to open: /dashboard/<role>?section=<key>[&patient=][&id=][&tab=]. "
            + "Null means the notification is informational; always null for thread MESSAGE notifications",
            example = "/dashboard/proxy?section=documents-results&patient=PAT-00001&id=88&tab=labs", nullable = true)
    private String actionUrl;

    /** What the notification opens (NotificationTarget name), or null (MESSAGE notifications use threadId). */
    @Schema(description = "What the notification opens (for example LAB_RESULTS, CAREGIVER_ACCESS); null for thread "
            + "MESSAGE notifications and informational rows. Clients navigate by actionUrl, not by target",
            example = "LAB_RESULTS", nullable = true)
    private String target;

    /** Preference category (NotificationCategory name). */
    @Schema(description = "Preference category: the notification setting that can switch this kind of notification off",
            example = "LAB_RESULT")
    private String category;

    @Schema(description = "Upper snake case kind of the referenced row; for display or diagnostics only",
            example = "LAB_RESULT", nullable = true)
    private String resourceType;

    @Schema(description = "Id of the referenced row", example = "88", nullable = true)
    private Integer resourceId;
}
