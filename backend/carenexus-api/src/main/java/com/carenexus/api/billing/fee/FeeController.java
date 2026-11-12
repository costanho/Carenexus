package com.carenexus.api.billing.fee;

import com.carenexus.api.billing.fee.dto.FeeResponse;
import com.carenexus.api.billing.fee.dto.UpdateDefaultFeesRequest;
import com.carenexus.api.billing.fee.dto.UpdateFeesRequest;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.ValidationPatterns;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Consultation fee schedule.
 *
 * - GET /api/billing/fees?doctorSystemId=   any signed-in user; the three fees of a doctor or the clinic defaults
 * - PUT /api/doctors/billing/fees           DOCTOR: set or remove the caller's own overrides
 * - PUT /api/admin/billing/fees             ADMIN: set the clinic defaults
 *
 * Every answer is an array of exactly three rows, in the order VIDEO, IN_PERSON, PHONE.
 */
@RestController
@Validated
@RequiredArgsConstructor
@Tag(name = "Billing: fees", description = "Consultation fee schedule: clinic defaults and doctor overrides")
public class FeeController {

    private final FeeService feeService;

    @GetMapping("/api/billing/fees")
    @Operation(summary = "Get the consultation fees of a doctor or the clinic defaults",
            description = "Any signed-in user. With doctorSystemId each type resolves to the doctor's active override, "
                    + "else the clinic default, else 0.00; without it the clinic defaults are returned. Always three "
                    + "rows (VIDEO, IN_PERSON, PHONE). No side effects, not audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The three fees"),
            @ApiResponse(responseCode = "400", description = "doctorSystemId is not a valid system id (VALIDATION_FAILED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<FeeResponse>> listFees(
            @Parameter(description = "Doctor to resolve the fees for; omit for the clinic defaults", example = "DOC-00001")
            @RequestParam(required = false)
            @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid system id") String doctorSystemId) {
        return ResponseEntity.ok(feeService.listFees(doctorSystemId));
    }

    @PutMapping("/api/doctors/billing/fees")
    @Operation(summary = "Set or remove the calling doctor's fee overrides",
            description = "DOCTOR only, always for the caller's own fees (an ADMIN is refused). Each listed type is "
                    + "upserted as the doctor's override in the current clinic currency; amount null removes the "
                    + "override so the clinic default applies again; types not listed are unchanged. Audited as "
                    + "FEE_UPDATE. Returns the caller's three resolved fees.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's three resolved fees"),
            @ApiResponse(responseCode = "400", description = "Validation failed or an appointment type is listed twice",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not a doctor",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<FeeResponse>> updateOwnFees(@Valid @RequestBody UpdateFeesRequest request) {
        return ResponseEntity.ok(feeService.updateOwnFees(request));
    }

    @PutMapping("/api/admin/billing/fees")
    @Operation(summary = "Set the clinic default fees",
            description = "ADMIN only. Each listed type upserts the clinic default row (active, current clinic "
                    + "currency); types not listed are unchanged; a default cannot be removed, only set (0.00 allowed). "
                    + "Audited as FEE_UPDATE. Returns the three defaults.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The three clinic defaults"),
            @ApiResponse(responseCode = "400", description = "Validation failed or an appointment type is listed twice",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<FeeResponse>> updateDefaultFees(@Valid @RequestBody UpdateDefaultFeesRequest request) {
        return ResponseEntity.ok(feeService.updateDefaultFees(request));
    }
}
