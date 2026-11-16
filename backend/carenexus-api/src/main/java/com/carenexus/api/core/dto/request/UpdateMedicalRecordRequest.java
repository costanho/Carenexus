package com.carenexus.api.core.dto.request;

import com.carenexus.api.terminology.dto.request.IcdCodeInput;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.List;

/**
 * Body of PUT /api/medical-records/{id}: a partial update. A field that is absent or null is left unchanged; a
 * blank optional text clears it; an empty {@code icdCodes} array removes every code and a non-empty one replaces
 * the list. Patient, author, consultation and source cannot be changed (such properties are ignored).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Changes to a medical record (absent or null = unchanged)")
public class UpdateMedicalRecordRequest {

    @JsonProperty("recordType")
    @Pattern(regexp = CreateMedicalRecordRequest.RECORD_TYPE_PATTERN, flags = Pattern.Flag.CASE_INSENSITIVE,
            message = CreateMedicalRecordRequest.RECORD_TYPE_MESSAGE)
    @Schema(description = "CONSULT, LAB, IMAGING, PRESCRIPTION, REFERRAL, DISCHARGE, NOTE or OPERATION", example = "NOTE")
    private String recordType;

    @JsonProperty("title")
    @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank")
    @Size(max = CreateMedicalRecordRequest.MAX_TITLE, message = "must be at most 200 characters")
    @Schema(description = "When present: not blank, max 200 characters")
    private String title;

    @JsonProperty("description")
    @Size(max = CreateMedicalRecordRequest.MAX_TEXT, message = "must be at most 10000 characters")
    @Schema(description = "Max 10000 characters; a blank text clears it")
    private String description;

    @JsonProperty("treatmentPlan")
    @Size(max = CreateMedicalRecordRequest.MAX_TEXT, message = "must be at most 10000 characters")
    @Schema(description = "Max 10000 characters; a blank text clears it")
    private String treatmentPlan;

    @JsonProperty("observations")
    @Size(max = CreateMedicalRecordRequest.MAX_TEXT, message = "must be at most 10000 characters")
    @Schema(description = "Max 10000 characters; a blank text clears it")
    private String observations;

    @JsonProperty("icdCodes")
    @Size(max = CreateMedicalRecordRequest.MAX_ICD_CODES, message = "must contain at most 20 codes")
    @Schema(description = "Replaces the whole code list (up to 20); [] removes every code")
    private List<@NotNull(message = "must not be null") @Valid IcdCodeInput> icdCodes;
}
