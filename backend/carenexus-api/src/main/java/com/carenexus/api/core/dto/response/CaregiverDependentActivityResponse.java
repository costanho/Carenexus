package com.carenexus.api.core.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.List;

/**
 * Legacy form of GET /api/caregiver/dependents/activity (no query parameter): the default window (last 90 days plus
 * all future rows), at most 200 rows per list, newest first. Use the paged form instead.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Legacy activity feed: appointments and consultations of the default window, at most 200 each")
public class CaregiverDependentActivityResponse {

    private List<AppointmentResponse> appointments;
    private List<ConsultationResponse> consultations;
}
