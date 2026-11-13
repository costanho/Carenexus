package com.carenexus.api.communications.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/** POST /api/communications/threads/{threadId}/read: move the caller's read position forward. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarkThreadReadRequest {

    @Schema(description = "Newest message the caller has read. Values above the newest message are capped; the "
            + "position never moves back.", example = "902", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = MessagingValidation.REQUIRED)
    @Min(value = 0, message = MessagingValidation.NOT_NEGATIVE)
    private Integer lastReadMessageId;
}
