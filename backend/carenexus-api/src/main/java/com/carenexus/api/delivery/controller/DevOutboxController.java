package com.carenexus.api.delivery.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.delivery.dto.response.OutboundMessageResponse;
import com.carenexus.api.delivery.service.DevOutboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * View of the outbox (ADMIN; developer profiles, or outside production while email uses the local provider; 404 on
 * any other installation, production always).
 *
 * - GET /api/admin/dev/outbound-messages?channel=&to=&template=&page=&size=   newest first
 */
@RestController
@RequestMapping("/api/admin/dev/outbound-messages")
@RequiredArgsConstructor
@Validated
@Tag(name = "Development outbox", description = "Read-only view of outbound email, SMS and push messages; "
        + "exists only on developer installations and on non-production installations whose email provider is local")
public class DevOutboxController {

    private final DevOutboxService devOutboxService;

    @GetMapping
    @Operation(summary = "List outbound messages (development and local-email staging installations only)",
            description = "ADMIN only, and only while a developer profile (local, docker, test) is active or, outside "
                    + "production, while email uses the local provider; every other installation (production "
                    + "always) answers 404. Newest first (createdAt, then messageId, descending). "
                    + "Filters combine with AND; a blank filter is ignored. The rendered body is final as soon as "
                    + "the row exists, whatever its status. Read-only, nothing is audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of the outbox"),
            @ApiResponse(responseCode = "400", description = "Invalid filter (code VALIDATION_FAILED) or page / size not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Not available on this installation",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<OutboundMessageResponse>> list(
            @Parameter(description = "EMAIL, SMS or PUSH (case-insensitive); exact match")
            @RequestParam(required = false)
            @Pattern(regexp = "^\\s*(EMAIL|SMS|PUSH)?\\s*$", flags = Pattern.Flag.CASE_INSENSITIVE,
                    message = "must be EMAIL, SMS or PUSH") String channel,
            @Parameter(description = "Case-insensitive 'contains' on the recipient address or the recipient system id")
            @RequestParam(required = false) @Size(max = DevOutboxService.MAX_TO_LENGTH) String to,
            @Parameter(description = "Template id, case-insensitive exact match (PUSH rows have none)")
            @RequestParam(required = false) @Size(max = DevOutboxService.MAX_TEMPLATE_LENGTH) String template,
            @Parameter(description = "Zero-based page; a negative value becomes 0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100")
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(devOutboxService.search(channel, to, template, page, size));
    }
}
