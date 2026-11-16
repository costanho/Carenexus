package com.carenexus.api.communications.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.*;

/** POST /api/communications/support-thread (body optional). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateSupportThreadRequest {

    @Schema(description = "Optional subject, at most 200 characters; trimmed, blank is stored as no subject. "
            + "Ignored when the caller already has an open support conversation.", example = "Cannot see my invoice")
    @Size(max = MessagingValidation.MAX_SUBJECT_LENGTH, message = MessagingValidation.SUBJECT_TOO_LONG)
    private String subject;
}
