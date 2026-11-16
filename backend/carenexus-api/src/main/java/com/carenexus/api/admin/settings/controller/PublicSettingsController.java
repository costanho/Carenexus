package com.carenexus.api.admin.settings.controller;

import com.carenexus.api.admin.settings.SystemSettingsService;
import com.carenexus.api.admin.settings.dto.response.PublicSettingsResponse;
import com.carenexus.api.common.dto.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings every signed-in user may read (the path is deliberately outside {@code /api/admin}).
 *
 * - GET /api/settings/public  clinic name, support contact and clinic hours for Help &amp; Support
 *
 * "Public" means "not admin-only": the route still requires an access token.
 */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
@Tag(name = "Settings", description = "Clinic details shown to every signed-in user")
public class PublicSettingsController {

    private final SystemSettingsService systemSettingsService;

    @GetMapping("/public")
    @Operation(summary = "Get the clinic name, support contact and clinic hours",
            description = "Any signed-in user (patient, doctor, caregiver, administrator); the answer does not depend "
                    + "on the caller. Support contacts are null when none is configured. No side effects.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Clinic details"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PublicSettingsResponse> getPublicSettings() {
        return ResponseEntity.ok(systemSettingsService.publicSettings());
    }
}
