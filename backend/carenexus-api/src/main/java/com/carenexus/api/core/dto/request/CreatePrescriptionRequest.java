package com.carenexus.api.core.dto.request;

import com.carenexus.api.common.dto.ValidationPatterns;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Body of {@code POST /api/prescriptions}. The prescriber is always the authenticated doctor and a new prescription
 * is always ACTIVE: {@code doctorSystemId} and {@code status} are accepted for compatibility, ignored, and hidden from
 * the documented contract. Lengths are checked on the value as sent; the server trims before storing.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Issue a prescription")
public class CreatePrescriptionRequest {

    public static final String ROUTE_PATTERN =
            "^\\s*((ORAL|TOPICAL|INJECTION|INHALED|SUBLINGUAL|RECTAL|IV)\\s*)?$";
    public static final String ROUTE_MESSAGE = "must be one of ORAL, TOPICAL, INJECTION, INHALED, SUBLINGUAL, RECTAL, IV";
    public static final String TIME_MESSAGE = "must be a time in HH:mm format";
    public static final int MAX_SCHEDULE_TIMES = 8;

    @JsonProperty("patientSystemId")
    @NotBlank(message = "is required")
    @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid system id")
    @Schema(description = "Patient the prescription is for", example = "PAT-00001", requiredMode = Schema.RequiredMode.REQUIRED)
    private String patientSystemId;

    /** Ignored by the server: the prescriber is always the authenticated caller. Kept for compatibility. */
    @JsonProperty("doctorSystemId")
    @Schema(hidden = true)
    private String doctorSystemId;

    @JsonProperty("consultationId")
    @Positive(message = "must be a positive number")
    @Schema(description = "Consultation the prescription is issued from; must belong to the same patient", example = "318")
    private Integer consultationId;

    @JsonProperty("medicationName")
    @NotBlank(message = "is required")
    @Size(max = 200, message = "must be at most 200 characters")
    @Schema(description = "Medication (brand) name; immutable after issue", example = "Amlodipine", requiredMode = Schema.RequiredMode.REQUIRED)
    private String medicationName;

    @JsonProperty("genericName")
    @Size(max = 200, message = "must be at most 200 characters")
    @Schema(description = "Generic name; immutable after issue", example = "amlodipine besylate")
    private String genericName;

    @JsonProperty("dosage")
    @NotBlank(message = "is required")
    @Size(max = 100, message = "must be at most 100 characters")
    @Schema(description = "Dose per administration; immutable after issue", example = "5 mg", requiredMode = Schema.RequiredMode.REQUIRED)
    private String dosage;

    @JsonProperty("frequency")
    @NotBlank(message = "is required")
    @Size(max = 100, message = "must be at most 100 characters")
    @Schema(description = "Free text frequency; immutable after issue", example = "Once daily", requiredMode = Schema.RequiredMode.REQUIRED)
    private String frequency;

    @JsonProperty("route")
    @Pattern(regexp = ROUTE_PATTERN, flags = Pattern.Flag.CASE_INSENSITIVE, message = ROUTE_MESSAGE)
    @Schema(description = "Administration route (trimmed, case-insensitive); default ORAL",
            allowableValues = {"ORAL", "TOPICAL", "INJECTION", "INHALED", "SUBLINGUAL", "RECTAL", "IV"}, example = "ORAL")
    private String route;

    @JsonProperty("quantity")
    @Size(max = 50, message = "must be at most 50 characters")
    @Schema(description = "Free text quantity to dispense", example = "30 tablets")
    private String quantity;

    @JsonProperty("refillsAllowed")
    @Min(value = 0, message = "must be between 0 and 99")
    @Max(value = 99, message = "must be between 0 and 99")
    @Schema(description = "Number of refills, 0 to 99; default 0", example = "2")
    private Integer refillsAllowed;

    @JsonProperty("specialInstructions")
    @Size(max = 2000, message = "must be at most 2000 characters")
    @Schema(description = "Instructions for the patient", example = "Take in the morning with water.")
    private String specialInstructions;

    /** Ignored by the server: a new prescription is always ACTIVE. Kept for compatibility. */
    @JsonProperty("status")
    @Schema(hidden = true)
    private String status;

    @JsonProperty("prescribedDate")
    @Schema(description = "Clinic day of issue (yyyy-MM-dd), not after clinic today; default clinic today", example = "2026-11-10")
    private LocalDate prescribedDate;

    @JsonProperty("expiryDate")
    @Schema(description = "Last valid day (yyyy-MM-dd, inclusive), not before prescribedDate; null = no expiry", example = "2027-02-10")
    private LocalDate expiryDate;

    @JsonProperty("scheduleTimes")
    @Size(max = MAX_SCHEDULE_TIMES, message = "must contain at most 8 times")
    @ArraySchema(arraySchema = @Schema(description = "Dose times (clinic wall-clock HH:mm), at most 8; duplicates are dropped. "
            + "Passed to the medication schedule, not returned by this API"),
            schema = @Schema(example = "08:00"), maxItems = MAX_SCHEDULE_TIMES)
    private List<@NotNull(message = TIME_MESSAGE) @Pattern(regexp = ValidationPatterns.TIME_HH_MM, message = TIME_MESSAGE) String> scheduleTimes;
}
