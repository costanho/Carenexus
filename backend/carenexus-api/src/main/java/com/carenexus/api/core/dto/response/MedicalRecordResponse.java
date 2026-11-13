package com.carenexus.api.core.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A medical record as every record endpoint returns it. All fields are always present; nullable ones are null.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A medical record")
public class MedicalRecordResponse {

    @JsonProperty("recordId")
    private Integer recordId;

    @JsonProperty("patientSystemId")
    private String patientSystemId;

    @JsonProperty("doctorSystemId")
    @Schema(description = "The author: the doctor who filed the record, or the consultation's doctor for an automatic record")
    private String doctorSystemId;

    @JsonProperty("doctorName")
    @Schema(description = "\"Dr. First Last\" of the author; null when the account has no name", nullable = true)
    private String doctorName;

    @JsonProperty("consultationId")
    private Integer consultationId;

    @JsonProperty("recordType")
    @Schema(description = "CONSULT, LAB, IMAGING, PRESCRIPTION, REFERRAL, DISCHARGE, NOTE or OPERATION")
    private String recordType;

    @JsonProperty("title")
    private String title;

    @JsonProperty("description")
    private String description;

    @JsonProperty("treatmentPlan")
    private String treatmentPlan;

    @JsonProperty("observations")
    private String observations;

    @JsonProperty("icdCodes")
    @Schema(description = "Legacy: the stored code list as JSON text, null when there are none. New clients read icdCodeList",
            nullable = true)
    private String icdCodes;

    @JsonProperty("icdCodeList")
    @Schema(description = "The record's ICD-10 codes in stored order; empty when there are none")
    private List<IcdCode> icdCodeList;

    @JsonProperty("source")
    @Schema(description = "MANUAL (filed by a doctor) or CONSULTATION (created when the consultation was completed)")
    private String source;

    @JsonProperty("isDeleted")
    @Schema(description = "Always false: deleted records are never returned")
    private Boolean isDeleted;

    @JsonProperty("deletedAt")
    @Schema(description = "Always null", nullable = true)
    private LocalDateTime deletedAt;

    @JsonProperty("createdAt")
    @Schema(description = "Clinic wall-clock time the record was filed; lists are sorted by it, newest first")
    private LocalDateTime createdAt;

    @JsonProperty("updatedAt")
    @Schema(description = "Equal to createdAt until the first edit; then stamped by the database (not a display time)")
    private LocalDateTime updatedAt;

    /**
     * One ICD-10 code of a record.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(name = "MedicalRecordIcdCode", description = "An ICD-10 code of a medical record")
    public static class IcdCode {

        @JsonProperty("code")
        @Schema(description = "ICD-10 code, upper case, dotted form", example = "J06.9")
        private String code;

        @JsonProperty("description")
        @Schema(description = "Text stored with the code; empty when none is known")
        private String description;

        @JsonProperty("verified")
        @Schema(description = "True when the code is in the terminology catalogue at the time of the read")
        private boolean verified;
    }
}
