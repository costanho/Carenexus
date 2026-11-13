package com.carenexus.api.account.legal.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Locale;

/**
 * Body of {@code POST /api/admin/legal/documents}. The type, version and title are trimmed when set (a blank value
 * counts as absent) and the type is upper-cased; the text is stored exactly as sent.
 */
@Getter
@NoArgsConstructor
@Schema(description = "A new version of the terms of service or the privacy policy")
public class PublishLegalDocumentRequest {

    public static final String VERSION_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._-]{0,19}$";

    @NotBlank
    @Pattern(regexp = "^(TERMS|PRIVACY)$", message = "must be TERMS or PRIVACY")
    @Schema(description = "Document type, case-insensitive", example = "PRIVACY", allowableValues = {"TERMS", "PRIVACY"})
    private String docType;

    @NotBlank
    @Pattern(regexp = VERSION_PATTERN, message = "must be 1 to 20 letters, digits, dots, dashes or underscores")
    @Schema(description = "Version label, unique per document type", example = "2")
    private String version;

    @NotBlank
    @Size(max = 200)
    @Schema(description = "Display title", example = "Privacy Policy")
    private String title;

    @NotBlank
    @Size(max = 100000)
    @Setter
    @Schema(description = "Text in the restricted Markdown subset (#, ##, ### headings, paragraphs, '- ' bullets, "
            + "**bold**, [text](https://...) links); stored as sent")
    private String contentMd;

    @PastOrPresent
    @Setter
    @Schema(description = "When the version takes effect (ISO-8601 instant). Omit for 'now'. Must not be in the "
            + "future nor earlier than the current version's effectiveAt.", example = "2026-11-20T08:00:00Z",
            nullable = true)
    private Instant effectiveAt;

    public void setDocType(String docType) {
        String cleaned = AcceptLegalRequest.clean(docType);
        this.docType = cleaned == null ? null : cleaned.toUpperCase(Locale.ROOT);
    }

    public void setVersion(String version) {
        this.version = AcceptLegalRequest.clean(version);
    }

    public void setTitle(String title) {
        this.title = AcceptLegalRequest.clean(title);
    }
}
