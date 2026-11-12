package com.carenexus.api.admin.clinician.dto.request;

import com.carenexus.api.admin.user.dto.request.AdminText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /api/admin/doctors/{systemId}/licence}: replaces all four licence fields. Text is trimmed and a
 * blank optional value clears the field; the country is stored upper-case; the licence number keeps its case.
 */
@Schema(description = "Licence details of a clinician (full replacement of the four fields)")
public record LicenceRequest(
        @NotBlank(message = "Licence number is required")
        @Size(max = 50, message = "Licence number must be at most 50 characters")
        @Schema(description = "Unique among doctors, compared case-insensitively", example = "MDPCZ-2019-04471", maxLength = 50)
        String licenseNo,

        @Size(max = 150, message = "Licensing body must be at most 150 characters")
        @Schema(example = "Medical and Dental Practitioners Council of Zimbabwe", nullable = true, maxLength = 150)
        String licensingBody,

        @Pattern(regexp = "^[A-Za-z]{2}$", message = "Licence country must be a two-letter country code")
        @Schema(description = "ISO 3166-1 alpha-2, stored upper-case", example = "ZW", nullable = true)
        String licenseCountry,

        @Size(max = 150, message = "Specialization must be at most 150 characters")
        @Schema(example = "Cardiology", nullable = true, maxLength = 150)
        String specialization) {

    public LicenceRequest {
        licenseNo = AdminText.clean(licenseNo);
        licensingBody = AdminText.clean(licensingBody);
        licenseCountry = AdminText.cleanUpper(licenseCountry);
        specialization = AdminText.clean(specialization);
    }
}
