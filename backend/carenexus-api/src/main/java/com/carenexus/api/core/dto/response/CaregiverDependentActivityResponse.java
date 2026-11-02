package com.carenexus.api.core.dto.response;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaregiverDependentActivityResponse {

    private List<AppointmentResponse> appointments;
    private List<ConsultationResponse> consultations;
}
