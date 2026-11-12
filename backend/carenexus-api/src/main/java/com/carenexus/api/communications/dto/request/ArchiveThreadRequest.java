package com.carenexus.api.communications.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/** PUT /api/communications/threads/{threadId}/archive: the caller's own archive flag. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArchiveThreadRequest {

    @Schema(description = "true archives the conversation for the caller, false restores it.",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = MessagingValidation.TRUE_OR_FALSE)
    private Boolean archived;
}
