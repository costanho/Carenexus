package com.carenexus.api.corrections.dto.request;

import com.carenexus.api.common.dto.ValidationPatterns;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of {@code POST /api/correction-requests}: asks for a correction of one clinical record.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A request to correct one clinical record")
public class CreateCorrectionRequest {

    public static final String RESOURCE_TYPE_PATTERN =
            "^(MEDICAL_RECORD|LAB_RESULT|IMAGING_RESULT|PRESCRIPTION|CONSULTATION)$";
    public static final int MAX_TEXT_LENGTH = 2000;

    @Schema(description = "Patient whose record is concerned. A patient may omit it (defaults to the caller) or must "
            + "send their own id; a caregiver must send the dependent's id.", example = "PAT-00001")
    @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid system id")
    private String patientSystemId;

    @Schema(description = "Kind of record", requiredMode = Schema.RequiredMode.REQUIRED, example = "LAB_RESULT",
            allowableValues = {"MEDICAL_RECORD", "LAB_RESULT", "IMAGING_RESULT", "PRESCRIPTION", "CONSULTATION"})
    @NotBlank(message = "is required")
    @Pattern(regexp = RESOURCE_TYPE_PATTERN,
            message = "must be one of MEDICAL_RECORD, LAB_RESULT, IMAGING_RESULT, PRESCRIPTION, CONSULTATION")
    private String resourceType;

    @Schema(description = "Id of the record: recordId, resultId, imagingId, prescriptionId or consultationId",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "318", minimum = "1")
    @NotNull(message = "is required")
    @Positive(message = "must be a positive number")
    private Integer resourceId;

    @Schema(description = "What should be corrected (trimmed, 1 to 2000 characters)",
            requiredMode = Schema.RequiredMode.REQUIRED, maxLength = MAX_TEXT_LENGTH,
            example = "The test date is wrong: the blood sample was taken on 3 November, not 30 October.")
    @NotBlank(message = "is required")
    @Size(max = MAX_TEXT_LENGTH, message = "must be at most 2000 characters")
    private String requestedText;
}
