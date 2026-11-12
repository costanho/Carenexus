package com.carenexus.api.core.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDate;

/**
 * Body of {@code PUT /api/prescriptions/{id}}: the complete editable state of an issued prescription. All three
 * fields are applied on every call (null expiry date or instructions clear the stored value). Any other property
 * is ignored: medication, dosage, frequency, route and quantity never change after issue (cancel and reissue).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Editable part of an issued prescription (full replacement of these three fields)")
public class UpdatePrescriptionRequest {

    @JsonProperty("refillsAllowed")
    @NotNull(message = "is required")
    @Min(value = 0, message = "must be between 0 and 99")
    @Max(value = 99, message = "must be between 0 and 99")
    @Schema(description = "Number of refills, 0 to 99", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer refillsAllowed;

    @JsonProperty("expiryDate")
    @Schema(description = "Last valid day (yyyy-MM-dd, inclusive), not before the stored prescribedDate; null clears it",
            example = "2027-03-31")
    private LocalDate expiryDate;

    @JsonProperty("specialInstructions")
    @Size(max = 2000, message = "must be at most 2000 characters")
    @Schema(description = "Instructions for the patient; null or blank clears them", example = "Take in the morning with water.")
    private String specialInstructions;
}
