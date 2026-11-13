package com.carenexus.api.account.legal.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Locale;

/**
 * Body of {@code POST /api/users/me/legal/accept}. Values are trimmed when set (a blank value counts as absent);
 * the document type is also upper-cased, so Bean Validation sees the value the service uses.
 */
@Getter
@NoArgsConstructor
@Schema(description = "Accept the current version of one legal document")
public class AcceptLegalRequest {

    @NotBlank
    @Pattern(regexp = "^(TERMS|PRIVACY)$", message = "must be TERMS or PRIVACY")
    @Schema(description = "Document type, case-insensitive", example = "PRIVACY", allowableValues = {"TERMS", "PRIVACY"})
    private String docType;

    @NotBlank
    @Size(max = 20)
    @Schema(description = "Version of the document the user was shown; must be the current version (exact match)",
            example = "1")
    private String version;

    public AcceptLegalRequest(String docType, String version) {
        setDocType(docType);
        setVersion(version);
    }

    public void setDocType(String docType) {
        String cleaned = clean(docType);
        this.docType = cleaned == null ? null : cleaned.toUpperCase(Locale.ROOT);
    }

    public void setVersion(String version) {
        this.version = clean(version);
    }

    static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
