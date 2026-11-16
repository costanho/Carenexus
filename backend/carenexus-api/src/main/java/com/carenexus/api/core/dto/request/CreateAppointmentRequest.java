package com.carenexus.api.core.dto.request;

import com.carenexus.api.common.dto.ValidationPatterns;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Body of {@code POST /api/appointments} (create) and {@code PUT /api/appointments/{id}} (partial edit).
 * The constraints never make a field required: what is required on create is checked by AppointmentService,
 * and on PUT an absent or null field is left unchanged.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Appointment booking or edit. On PUT every field is optional; patient, doctor and facility cannot be changed.")
public class CreateAppointmentRequest {

    static final int MAX_TEXT = 2000;
    static final int MAX_PHONE = 20;

    @Schema(description = "Patient system id. PATIENT callers may omit it (defaults to themselves); everyone else must send it",
            example = "PAT-00001")
    @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid ID such as PAT-00001")
    private String patientSystemId;

    @Schema(description = "Doctor system id. DOCTOR callers may omit it (defaults to themselves)", example = "DOC-00001")
    @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid ID such as DOC-00001")
    private String doctorSystemId;

    @Schema(description = "Active facility of an IN_PERSON visit; null for VIDEO and PHONE", example = "1", nullable = true)
    @Positive(message = "must be a positive number")
    private Integer facilityId;

    @Schema(description = "Start in clinic wall-clock time, yyyy-MM-ddTHH:mm:00 without offset", example = "2026-11-20T10:30:00")
    private LocalDateTime scheduledAt;

    @Schema(description = "Length in minutes, 5 to 240 in steps of 5 (default 30 on create)", example = "30")
    @Min(value = 5, message = "must be between 5 and 240")
    @Max(value = 240, message = "must be between 5 and 240")
    private Integer durationMinutes;

    @Schema(description = "IN_PERSON (default), VIDEO or PHONE; case-insensitive", example = "IN_PERSON")
    private String type;

    @Schema(description = "Why the patient comes, up to 2000 characters", nullable = true)
    @Size(max = MAX_TEXT, message = "must be at most 2000 characters")
    private String reasonForVisit;

    @Schema(description = "Legacy field: accepted and ignored (calls are managed by the API)", deprecated = true)
    private String videoConsultationLink;

    @Schema(description = "Notes, up to 2000 characters", nullable = true)
    @Size(max = MAX_TEXT, message = "must be at most 2000 characters")
    private String notes;

    @Schema(description = "PHONE only: number the doctor calls. When omitted the patient's number on file is used. "
            + "Ignored for other types", example = "+263771234567", nullable = true)
    @Size(max = MAX_PHONE, message = "must be at most 20 characters")
    @Pattern(regexp = ValidationPatterns.PHONE, message = "must be a valid phone number")
    private String contactPhone;
}
