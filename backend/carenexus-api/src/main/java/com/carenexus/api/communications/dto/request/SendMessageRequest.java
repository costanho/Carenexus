package com.carenexus.api.communications.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

/** POST /api/communications/threads/{threadId}/messages. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SendMessageRequest {

    @Schema(description = "Client retry key, unique per sender and conversation: a repeat returns the stored message.",
            example = "c5d6a0de-6a43-4d2e-9b8e-2c1f9b1f6f0a", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = MessagingValidation.REQUIRED)
    @Pattern(regexp = MessagingValidation.CLIENT_ID, message = MessagingValidation.CLIENT_ID_FORMAT)
    private String clientMessageId;

    @Schema(description = "Message text, at most 4000 characters; stored trimmed.", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = MessagingValidation.BODY_EMPTY)
    @Size(max = MessagingValidation.MAX_BODY_LENGTH, message = MessagingValidation.BODY_TOO_LONG)
    private String body;

    @Schema(description = "Urgent flag (default false). Ignored in SUPPORT conversations.")
    private Boolean urgent;
}
