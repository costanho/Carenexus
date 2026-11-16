package com.carenexus.api.core.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A prescription as every prescription endpoint returns it. All fields are always present; a nullable field is
 * {@code null}, never omitted. Schedule times are not part of it (the medication module owns them).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "A prescription")
public class PrescriptionResponse {

    @JsonProperty("prescriptionId")
    @Schema(description = "Row id", example = "512")
    private Integer prescriptionId;

    @JsonProperty("patientSystemId")
    @Schema(description = "Patient", example = "PAT-00001")
    private String patientSystemId;

    @JsonProperty("doctorSystemId")
    @Schema(description = "Prescriber: always the doctor who issued it", example = "DOC-00002")
    private String doctorSystemId;

    @JsonProperty("doctorName")
    @Schema(description = "Prescriber display name (\"Dr. First Last\"); null when the account has no name",
            example = "Dr. James Wilson", nullable = true)
    private String doctorName;

    @JsonProperty("consultationId")
    @Schema(description = "Consultation it was issued from", example = "318", nullable = true)
    private Integer consultationId;

    @JsonProperty("medicationName")
    @Schema(description = "Medication name (max 200)", example = "Amlodipine")
    private String medicationName;

    @JsonProperty("genericName")
    @Schema(description = "Generic name (max 200)", example = "amlodipine besylate", nullable = true)
    private String genericName;

    @JsonProperty("dosage")
    @Schema(description = "Dose (max 100)", example = "5 mg")
    private String dosage;

    @JsonProperty("frequency")
    @Schema(description = "Free text frequency (max 100)", example = "Once daily")
    private String frequency;

    @JsonProperty("route")
    @Schema(description = "Administration route",
            allowableValues = {"ORAL", "TOPICAL", "INJECTION", "INHALED", "SUBLINGUAL", "RECTAL", "IV"}, example = "ORAL")
    private String route;

    @JsonProperty("quantity")
    @Schema(description = "Free text quantity (max 50)", example = "30 tablets", nullable = true)
    private String quantity;

    @JsonProperty("refillsAllowed")
    @Schema(description = "Refills, 0 to 99", example = "2")
    private Integer refillsAllowed;

    @JsonProperty("specialInstructions")
    @Schema(description = "Instructions (max 2000)", nullable = true)
    private String specialInstructions;

    @JsonProperty("status")
    @Schema(description = "Stored status; writes are governed by it",
            allowableValues = {"ACTIVE", "ON_HOLD", "COMPLETED", "CANCELLED", "EXPIRED"}, example = "ACTIVE")
    private String status;

    @JsonProperty("effectiveStatus")
    @Schema(description = "EXPIRED when the stored status is ACTIVE or ON_HOLD and expiryDate is before clinic today "
            + "(what the hourly expiry job stores next); otherwise equal to status",
            allowableValues = {"ACTIVE", "ON_HOLD", "COMPLETED", "CANCELLED", "EXPIRED"}, example = "ACTIVE")
    private String effectiveStatus;

    @JsonProperty("prescribedDate")
    @Schema(description = "Clinic day of issue (yyyy-MM-dd)", example = "2026-10-20")
    private LocalDate prescribedDate;

    @JsonProperty("expiryDate")
    @Schema(description = "Last valid day (yyyy-MM-dd, inclusive); null = no expiry", example = "2027-02-10", nullable = true)
    private LocalDate expiryDate;

    @JsonProperty("createdAt")
    @Schema(description = "Row creation, clinic wall-clock date-time without zone", example = "2026-10-20T10:42:07.318442")
    private LocalDateTime createdAt;
}
