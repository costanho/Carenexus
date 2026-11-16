package com.carenexus.api.billing.fee.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Body of {@code PUT /api/doctors/billing/fees}: the calling doctor's own fee overrides. Each listed type is set
 * ({@code amount}) or removed ({@code amount} null or absent, so the clinic default applies again); types that are
 * not listed stay as they are.
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Fee overrides of the calling doctor")
public class UpdateFeesRequest {

    /** Accepted appointment types, case-insensitive and trimmed. */
    public static final String TYPE_PATTERN = "^\\s*(?i)(VIDEO|IN_PERSON|PHONE)\\s*$";
    public static final String TYPE_MESSAGE = "must be one of VIDEO, IN_PERSON, PHONE";

    @NotEmpty
    @Size(max = 3, message = "size must be between 1 and 3")
    @Schema(description = "1 to 3 rows, each appointment type at most once")
    private List<@Valid @NotNull Item> fees;

    @Getter
    @Setter
    @NoArgsConstructor
    @Schema(name = "UpdateFeesRequestItem", description = "Fee of one appointment type")
    public static class Item {

        @NotBlank(message = TYPE_MESSAGE)
        @Pattern(regexp = TYPE_PATTERN, message = TYPE_MESSAGE)
        @Schema(description = "VIDEO, IN_PERSON or PHONE (case-insensitive)", example = "VIDEO")
        private String appointmentType;

        @DecimalMin(value = "0.00", message = "must not be negative")
        @Digits(integer = 6, fraction = 2, message = "must have at most 6 digits and 2 decimals")
        @Schema(description = "0.00 to 999999.99; null removes the override", example = "2000.00", nullable = true)
        private BigDecimal amount;
    }
}
