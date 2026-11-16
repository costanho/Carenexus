package com.carenexus.api.core.dto.request;

import com.carenexus.api.common.dto.ValidationPatterns;
import com.carenexus.api.terminology.dto.request.IcdCodeInput;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.List;

/**
 * Body of POST /api/medical-records. Lengths are checked on the value as sent; the server trims the texts before
 * storing them (an optional text that is blank after trimming is stored as null). The author is always the calling
 * doctor: {@code doctorSystemId} is accepted for compatibility and ignored.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A medical record to file")
public class CreateMedicalRecordRequest {

    /** Accepted values of {@code recordType} (any case, surrounding spaces allowed). */
    public static final String RECORD_TYPE_PATTERN =
            "^\\s*(CONSULT|LAB|IMAGING|PRESCRIPTION|REFERRAL|DISCHARGE|NOTE|OPERATION)\\s*$";
    public static final String RECORD_TYPE_MESSAGE =
            "must be one of CONSULT, LAB, IMAGING, PRESCRIPTION, REFERRAL, DISCHARGE, NOTE, OPERATION";
    public static final int MAX_TITLE = 200;
    public static final int MAX_TEXT = 10000;
    public static final int MAX_ICD_CODES = 20;

    @JsonProperty("patientSystemId")
    @NotBlank(message = "is required")
    @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid system id")
    @Schema(description = "The patient the record is about", example = "PAT-00001", requiredMode = Schema.RequiredMode.REQUIRED)
    private String patientSystemId;

    /** Ignored by the server: the author doctor is always the authenticated caller. Kept for compatibility. */
    @JsonProperty("doctorSystemId")
    @Schema(hidden = true)
    private String doctorSystemId;

    @JsonProperty("consultationId")
    @Positive(message = "must be a positive number")
    @Schema(description = "Consultation of this patient the record belongs to", example = "318")
    private Integer consultationId;

    @JsonProperty("recordType")
    @NotBlank(message = "is required")
    @Pattern(regexp = RECORD_TYPE_PATTERN, flags = Pattern.Flag.CASE_INSENSITIVE, message = RECORD_TYPE_MESSAGE)
    @Schema(description = "CONSULT, LAB, IMAGING, PRESCRIPTION, REFERRAL, DISCHARGE, NOTE or OPERATION (trimmed, upper-cased)",
            example = "NOTE", requiredMode = Schema.RequiredMode.REQUIRED)
    private String recordType;

    @JsonProperty("title")
    @NotBlank(message = "is required")
    @Size(max = MAX_TITLE, message = "must be at most 200 characters")
    @Schema(description = "Title, max 200 characters", example = "Review of persistent cough",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    @JsonProperty("description")
    @Size(max = MAX_TEXT, message = "must be at most 10000 characters")
    @Schema(description = "Max 10000 characters")
    private String description;

    @JsonProperty("treatmentPlan")
    @Size(max = MAX_TEXT, message = "must be at most 10000 characters")
    @Schema(description = "Max 10000 characters")
    private String treatmentPlan;

    @JsonProperty("observations")
    @Size(max = MAX_TEXT, message = "must be at most 10000 characters")
    @Schema(description = "Max 10000 characters")
    private String observations;

    @JsonProperty("icdCodes")
    @Size(max = MAX_ICD_CODES, message = "must contain at most 20 codes")
    @Schema(description = "Up to 20 ICD-10 codes; a repeated code is dropped (first occurrence kept)")
    private List<@NotNull(message = "must not be null") @Valid IcdCodeInput> icdCodes;
}
