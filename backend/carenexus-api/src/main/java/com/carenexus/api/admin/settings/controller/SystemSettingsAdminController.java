package com.carenexus.api.admin.settings.controller;

import com.carenexus.api.admin.settings.SystemSettingsService;
import com.carenexus.api.admin.settings.dto.request.UpdateSystemSettingRequest;
import com.carenexus.api.admin.settings.dto.response.SystemSettingResponse;
import com.carenexus.api.common.dto.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * System settings for administrators (ADMIN only: route rule {@code /api/admin/**} plus the service check).
 *
 * - GET    /api/admin/settings        every setting with its effective value, default and limits
 * - PUT    /api/admin/settings/{key}  store the override of one setting
 * - DELETE /api/admin/settings/{key}  remove the override (restore the default)
 *
 * {@code key} is the dotted name of the setting, for example {@code billing.tax-rate}.
 */
@RestController
@RequestMapping("/api/admin/settings")
@RequiredArgsConstructor
@Tag(name = "Admin: system settings", description = "Clinic-wide settings an administrator can change at runtime")
public class SystemSettingsAdminController {

    private final SystemSettingsService systemSettingsService;

    @GetMapping
    @Operation(summary = "List every system setting",
            description = "ADMIN only. Returns all settings in a fixed order (hard cap: the number of settings), each "
                    + "with its effective value, the default it falls back to, its type and limits, and who last "
                    + "changed it. Not paged. No side effects.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "All settings"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<SystemSettingResponse>> listSettings() {
        return ResponseEntity.ok(systemSettingsService.list());
    }

    @PutMapping("/{key}")
    @Operation(summary = "Change one system setting",
            description = "ADMIN only. Stores the value as the override of the setting (created or replaced, also "
                    + "when unchanged) after validating it against the type and limits of the setting. Every reader "
                    + "uses the new value on its next read. Writes one audit row (SETTINGS_UPDATE / SYSTEM_SETTING).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The setting after the change"),
            @ApiResponse(responseCode = "400", description = "Unknown setting key, malformed body, or a value that is "
                    + "blank or not valid for the setting (code VALIDATION_FAILED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<SystemSettingResponse> updateSetting(
            @Parameter(description = "Dotted name of the setting, case-insensitive", example = "billing.tax-rate")
            @PathVariable String key,
            @Valid @RequestBody UpdateSystemSettingRequest request) {
        return ResponseEntity.ok(systemSettingsService.update(key, request.getValue()));
    }

    @DeleteMapping("/{key}")
    @Operation(summary = "Restore the default of one system setting",
            description = "ADMIN only. Removes the stored override so the default applies again. A setting without "
                    + "an override is left unchanged and the answer is still 204. Writes one audit row "
                    + "(SETTINGS_UPDATE / SYSTEM_SETTING) when an override was removed.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "The default is in force"),
            @ApiResponse(responseCode = "400", description = "Unknown setting key",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> resetSetting(
            @Parameter(description = "Dotted name of the setting, case-insensitive", example = "billing.tax-rate")
            @PathVariable String key) {
        systemSettingsService.reset(key);
        return ResponseEntity.noContent().build();
    }
}
