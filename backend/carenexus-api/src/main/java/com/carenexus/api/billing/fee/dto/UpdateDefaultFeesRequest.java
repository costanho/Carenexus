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
 * Body of {@code PUT /api/admin/billing/fees}: the clinic default fees. Same shape as {@link UpdateFeesRequest},
 * but every amount is required: a default can be set (to 0.00 when the clinic charges nothing), never removed.
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Clinic default fees")
public class UpdateDefaultFeesRequest {

    @NotEmpty
    @Size(max = 3, message = "size must be between 1 and 3")
    @Schema(description = "1 to 3 rows, each appointment type at most once")
    private List<@Valid @NotNull Item> fees;

    @Getter
    @Setter
    @NoArgsConstructor
    @Schema(name = "UpdateDefaultFeesRequestItem", description = "Default fee of one appointment type")
    public static class Item {

        @NotBlank(message = UpdateFeesRequest.TYPE_MESSAGE)
        @Pattern(regexp = UpdateFeesRequest.TYPE_PATTERN, message = UpdateFeesRequest.TYPE_MESSAGE)
        @Schema(description = "VIDEO, IN_PERSON or PHONE (case-insensitive)", example = "VIDEO")
        private String appointmentType;

        @NotNull
        @DecimalMin(value = "0.00", message = "must not be negative")
        @Digits(integer = 6, fraction = 2, message = "must have at most 6 digits and 2 decimals")
        @Schema(description = "0.00 to 999999.99", example = "1500.00")
        private BigDecimal amount;
    }
}
