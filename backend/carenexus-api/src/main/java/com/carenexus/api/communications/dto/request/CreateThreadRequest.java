package com.carenexus.api.communications.dto.request;

import com.carenexus.api.common.dto.ValidationPatterns;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.List;

/** POST /api/communications/threads: start a PRIVATE or CARE_TEAM conversation (or get the existing one). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateThreadRequest {

    @Schema(description = "Patient the conversation is about. Optional for a patient (defaults to the caller), "
            + "required for doctors and caregivers.", example = "PAT-00001")
    @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = MessagingValidation.SYSTEM_ID_FORMAT)
    private String patientSystemId;

    @Schema(description = "PRIVATE or CARE_TEAM (case-insensitive). SUPPORT conversations are opened through "
            + "POST /api/communications/support-thread.", example = "PRIVATE", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = MessagingValidation.REQUIRED)
    private String type;

    @Schema(description = "Optional subject, at most 200 characters; blank is stored as no subject.")
    @Size(max = MessagingValidation.MAX_SUBJECT_LENGTH, message = MessagingValidation.SUBJECT_TOO_LONG)
    private String subject;

    @Schema(description = "PRIVATE: exactly the one other member. CARE_TEAM: optional extra members (the patient is "
            + "always added). At most 50; the caller's own id is ignored.")
    @Size(max = MessagingValidation.MAX_MEMBERS_PER_REQUEST, message = MessagingValidation.TOO_MANY_ENTRIES)
    private List<@NotNull(message = MessagingValidation.SYSTEM_ID_FORMAT)
            @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = MessagingValidation.SYSTEM_ID_FORMAT) String> memberSystemIds;

    @Schema(description = "Client retry key: a repeat with the same key returns the conversation created first.",
            example = "0b6f6c1e-0c1e-4a71-9f61-4b8f2d0d8f10")
    @Pattern(regexp = MessagingValidation.CLIENT_ID, message = MessagingValidation.CLIENT_ID_FORMAT)
    private String clientThreadId;
}
