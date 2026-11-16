package com.carenexus.api.communications.dto.request;

import com.carenexus.api.common.dto.ValidationPatterns;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.List;

/** POST /api/communications/threads/{threadId}/members (CARE_TEAM conversations only). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AddThreadMembersRequest {

    @Schema(description = "People to add (1 to 50 system ids). Already active members are ignored.",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = MessagingValidation.NO_ENTRIES)
    @Size(max = MessagingValidation.MAX_MEMBERS_PER_REQUEST, message = MessagingValidation.TOO_MANY_ENTRIES)
    private List<@NotNull(message = MessagingValidation.SYSTEM_ID_FORMAT)
            @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = MessagingValidation.SYSTEM_ID_FORMAT) String> systemIds;
}
