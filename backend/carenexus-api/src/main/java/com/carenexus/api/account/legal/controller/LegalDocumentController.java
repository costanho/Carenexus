package com.carenexus.api.account.legal.controller;

import com.carenexus.api.account.legal.dto.response.LegalCurrentResponse;
import com.carenexus.api.account.legal.dto.response.LegalDocumentResponse;
import com.carenexus.api.account.legal.service.LegalService;
import com.carenexus.api.common.dto.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public legal documents (no token; {@code GET /api/legal/**} is permitAll in SecurityConfig).
 *
 * - GET /api/legal/current               current version of the terms and the privacy policy, without text
 * - GET /api/legal/documents/{docType}   current text of one document ({@code terms} or {@code privacy})
 */
@RestController
@RequestMapping("/api/legal")
@RequiredArgsConstructor
@Tag(name = "Legal documents", description = "Terms of service and privacy policy (public)")
public class LegalDocumentController {

    private final LegalService legalService;

    @GetMapping("/current")
    @SecurityRequirements
    @Operation(summary = "Get the current version of the terms and the privacy policy",
            description = "Public; a token is ignored. Returns the version, title and effective date of the current "
                    + "Terms of Service (terms) and Privacy Policy (privacy), without the text. The register form "
                    + "sends terms.version and privacy.version back as acceptedTermsVersion / "
                    + "acceptedPrivacyVersion. No side effects.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Both current versions"))
    public ResponseEntity<LegalCurrentResponse> current() {
        return ResponseEntity.ok(legalService.current());
    }

    @GetMapping("/documents/{docType}")
    @SecurityRequirements
    @Operation(summary = "Get the current text of the terms or the privacy policy",
            description = "Public; a token is ignored. Returns the current version of one document with its text in "
                    + "the restricted Markdown subset. Only the current version can be read. No side effects.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The current version with its text"),
            @ApiResponse(responseCode = "404", description = "docType is not terms or privacy",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<LegalDocumentResponse> document(
            @Parameter(description = "terms or privacy, case-insensitive", example = "terms")
            @PathVariable String docType) {
        return ResponseEntity.ok(legalService.currentDocument(docType));
    }
}
