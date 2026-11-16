package com.carenexus.api.terminology.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One row of the ICD-10 type-ahead.
 */
@Schema(description = "An ICD-10 catalogue entry")
public record IcdCodeResponse(
        @Schema(description = "ICD-10 code in the dotted upper-case form the record endpoints accept", example = "J02.9")
        String code,
        @Schema(description = "Catalogue description (max 255 characters)", example = "Acute pharyngitis, unspecified")
        String description) {
}
