package com.carenexus.api.billing.method.dto;

import com.carenexus.api.common.validation.NoCardNumber;
import com.carenexus.api.common.dto.ValidationPatterns;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Body of POST /api/billing/payment-methods.
 * <p>
 * Every string is trimmed when it is read and a blank string counts as absent, so the size constraints apply to the
 * trimmed value. The format constraints apply whenever a field is present, whatever the {@code type}; which fields a
 * type requires is checked by the service after Bean Validation, and fields that do not belong to the type are not
 * stored.
 * <p>
 * There is deliberately no field for a card number, a CVV, an expiry date or a gateway token: such JSON properties
 * are unknown properties and are dropped.
 */
@Getter
@NoArgsConstructor
@Schema(description = "Save a payment method (masked detail only)")
public class CreatePaymentMethodRequest {

    public static final String TYPE_PATTERN = "(?i)^(CARD|MPESA|INSURANCE)$";
    public static final String TYPE_MESSAGE = "must be one of CARD, MPESA, INSURANCE";

    @NotBlank
    @Pattern(regexp = TYPE_PATTERN, message = TYPE_MESSAGE)
    @Schema(description = "CARD, MPESA or INSURANCE (case-insensitive)", example = "MPESA",
            allowableValues = {"CARD", "MPESA", "INSURANCE"})
    private String type;

    @Size(max = 100)
    @NoCardNumber
    @Schema(description = "CARD only: card brand as free text (for example Visa). Ignored for MPESA and INSURANCE",
            nullable = true, example = "Visa", maxLength = 100)
    private String provider;

    @Pattern(regexp = "^\\d{4}$", message = "must be exactly 4 digits")
    @Schema(description = "CARD only (required): the last four digits of the card. Ignored for the other types",
            nullable = true, example = "4242", pattern = "^\\d{4}$")
    private String lastFour;

    @Size(max = 20)
    @Pattern(regexp = ValidationPatterns.PHONE, message = "must be a valid phone number")
    @Schema(description = "MPESA only (required): the M-PESA phone number. Stored for payments, never returned",
            nullable = true, example = "+254 712 345 678", maxLength = 20)
    private String mpesaPhone;

    @Size(max = 100)
    @NoCardNumber
    @Schema(description = "INSURANCE only (required): name of the insurer", nullable = true,
            example = "Jubilee Insurance", maxLength = 100)
    private String insurerName;

    @Size(min = 4, max = 100)
    @NoCardNumber(requireCardNetworkPrefix = true)
    @Schema(description = "INSURANCE only (required): policy number, 4 to 100 characters, not a payment card number. "
            + "Stored, never returned",
            nullable = true, example = "JUB-2026-778841", minLength = 4, maxLength = 100)
    private String policyNumber;

    @Size(max = 100)
    @NoCardNumber(requireCardNetworkPrefix = true)
    @Schema(description = "INSURANCE only (optional): member number, not a payment card number. Stored, never returned",
            nullable = true,
            example = "M-00231", maxLength = 100)
    private String memberNumber;

    @Schema(description = "Make it the default method (the first saved method always becomes the default)",
            nullable = true, defaultValue = "false")
    private Boolean makeDefault;

    public void setType(String type) {
        this.type = clean(type);
    }

    public void setProvider(String provider) {
        this.provider = clean(provider);
    }

    public void setLastFour(String lastFour) {
        this.lastFour = clean(lastFour);
    }

    public void setMpesaPhone(String mpesaPhone) {
        this.mpesaPhone = clean(mpesaPhone);
    }

    public void setInsurerName(String insurerName) {
        this.insurerName = clean(insurerName);
    }

    public void setPolicyNumber(String policyNumber) {
        this.policyNumber = clean(policyNumber);
    }

    public void setMemberNumber(String memberNumber) {
        this.memberNumber = clean(memberNumber);
    }

    public void setMakeDefault(Boolean makeDefault) {
        this.makeDefault = makeDefault;
    }

    /** True only when the client asked for it explicitly. */
    public boolean wantsDefault() {
        return Boolean.TRUE.equals(makeDefault);
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
