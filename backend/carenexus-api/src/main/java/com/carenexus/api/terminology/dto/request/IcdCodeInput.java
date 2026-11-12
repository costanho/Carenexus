package com.carenexus.api.terminology.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One ICD-10 code sent with a medical record. The server trims and upper-cases {@code code}, checks it with the
 * terminology provider (a well-formed code outside the catalogue is accepted as unverified) and fills a missing
 * description from the catalogue.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "An ICD-10 code attached to a medical record")
public class IcdCodeInput {

    @JsonProperty("code")
    @NotBlank(message = "is required")
    @Schema(description = "ICD-10 code, dotted form; trimmed and upper-cased by the server. Must match "
            + "^[A-Z][0-9][0-9A-Z](\\.[0-9A-Z]{1,4})?$", example = "J06.9", requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;

    @JsonProperty("description")
    @Size(max = 255, message = "must be at most 255 characters")
    @Schema(description = "Optional text stored with the code (max 255). When blank the catalogue description is "
            + "stored, or an empty text for a code outside the catalogue", example = "Acute upper respiratory infection, unspecified")
    private String description;
}
