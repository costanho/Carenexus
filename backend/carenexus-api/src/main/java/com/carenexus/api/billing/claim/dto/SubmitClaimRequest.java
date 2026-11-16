package com.carenexus.api.billing.claim.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Body of {@code POST /api/billing/invoices/{invoiceId}/claims}. Strings are trimmed when the body is read, so the
 * length rules apply to the trimmed value. The patient is never taken from the body: it is the invoice's patient.
 */
@Getter
@NoArgsConstructor
@Schema(description = "Submit an insurance claim for an issued invoice")
public class SubmitClaimRequest {

    @NotBlank
    @Size(max = 150)
    @Schema(description = "Insurer name, 1 to 150 characters", example = "Jubilee Health",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String insuranceProvider;

    @NotBlank
    @Size(max = 100)
    @Schema(description = "Policy number, 1 to 100 characters", example = "JH-88213-K",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String policyNumber;

    @Size(max = 100)
    @Schema(description = "Member number, at most 100 characters; blank is stored as null", example = "0045712",
            nullable = true)
    private String memberNumber;

    @Positive
    @Digits(integer = 10, fraction = 2, message = "must have at most 10 digits and 2 decimals")
    @Schema(description = "Amount claimed in the invoice's currency; absent or null = the whole outstanding balance. "
            + "Must not exceed the outstanding balance (409)", example = "2320.00", nullable = true)
    private BigDecimal claimAmount;

    @Size(max = 10)
    @ArraySchema(arraySchema = @Schema(description = "Ids of documents of the same patient (uploaded first through "
            + "POST /api/documents), at most 10; duplicates are dropped", nullable = true),
            schema = @Schema(type = "integer", example = "12"))
    private List<@NotNull @Positive Integer> documentIds;

    public void setInsuranceProvider(String insuranceProvider) {
        this.insuranceProvider = trim(insuranceProvider);
    }

    public void setPolicyNumber(String policyNumber) {
        this.policyNumber = trim(policyNumber);
    }

    public void setMemberNumber(String memberNumber) {
        this.memberNumber = trim(memberNumber);
    }

    public void setClaimAmount(BigDecimal claimAmount) {
        this.claimAmount = claimAmount;
    }

    public void setDocumentIds(List<Integer> documentIds) {
        this.documentIds = documentIds;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
