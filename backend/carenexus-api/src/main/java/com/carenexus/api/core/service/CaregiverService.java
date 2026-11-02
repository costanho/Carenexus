package com.carenexus.api.core.service;

import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.core.dto.request.UpdateDependentAccessRequest;
import com.carenexus.api.core.dto.response.AppointmentResponse;
import com.carenexus.api.core.dto.response.CaregiverDependentActivityResponse;
import com.carenexus.api.core.dto.response.CaregiverDependentResponse;
import com.carenexus.api.core.dto.response.ConsultationResponse;
import com.carenexus.api.core.model.DependentAccess;
import com.carenexus.api.core.repository.DependentAccessRepository;
import com.carenexus.api.core.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CaregiverService {

    private final DependentAccessRepository dependentAccessRepository;
    private final UserRepository userRepository;
    private final PatientRepository patientRepository;
    private final AppointmentService appointmentService;
    private final ConsultationService consultationService;

    public List<CaregiverDependentResponse> getDependentsForCaregiver(String caregiverSystemId) {
        return dependentAccessRepository.findAllDependentsForCaregiver(caregiverSystemId)
                .stream()
                .map(this::toEnrichedResponse)
                .collect(Collectors.toList());
    }

    public CaregiverDependentActivityResponse getActivityForDependents(String caregiverSystemId) {
        List<DependentAccess> accesses = dependentAccessRepository.findAllDependentsForCaregiver(caregiverSystemId);

        Map<String, String> nameMap = accesses.stream()
                .map(DependentAccess::getPatientSystemId)
                .distinct()
                .collect(Collectors.toMap(
                        id -> id,
                        id -> userRepository.findBySystemId(id)
                                .map(u -> u.getFirstName() + " " + u.getLastName())
                                .orElse(id)
                ));

        List<String> appointmentPatientIds = accesses.stream()
                .filter(a -> Boolean.TRUE.equals(a.getCanViewAppointments()))
                .map(DependentAccess::getPatientSystemId)
                .collect(Collectors.toList());

        List<String> consultationPatientIds = accesses.stream()
                .filter(a -> Boolean.TRUE.equals(a.getCanViewConsultations()))
                .map(DependentAccess::getPatientSystemId)
                .collect(Collectors.toList());

        List<AppointmentResponse> appointments = appointmentService.getAppointmentsForPatients(appointmentPatientIds);
        appointments.forEach(a -> a.setPatientName(nameMap.get(a.getPatientSystemId())));

        List<ConsultationResponse> consultations = consultationService.getConsultationsForPatients(consultationPatientIds);
        consultations.forEach(c -> c.setPatientName(nameMap.get(c.getPatientSystemId())));

        return CaregiverDependentActivityResponse.builder()
                .appointments(appointments)
                .consultations(consultations)
                .build();
    }

    @Transactional
    public CaregiverDependentResponse updateDependentAccess(String caregiverSystemId, Integer accessId,
                                                             UpdateDependentAccessRequest request) {
        DependentAccess access = dependentAccessRepository.findById(accessId)
                .orElseThrow(() -> new RuntimeException("Access record not found"));

        if (!access.getCaregiverSystemId().equals(caregiverSystemId)) {
            throw new RuntimeException("Not authorized to update this access record");
        }

        if (request.getAccessLevel() != null)          access.setAccessLevel(request.getAccessLevel());
        if (request.getExpiresAt() != null)            access.setExpiresAt(request.getExpiresAt());
        if (request.getCanViewRecords() != null)       access.setCanViewRecords(request.getCanViewRecords());
        if (request.getCanViewAppointments() != null)  access.setCanViewAppointments(request.getCanViewAppointments());
        if (request.getCanViewMedications() != null)   access.setCanViewMedications(request.getCanViewMedications());
        if (request.getCanViewLabResults() != null)    access.setCanViewLabResults(request.getCanViewLabResults());
        if (request.getCanViewImaging() != null)       access.setCanViewImaging(request.getCanViewImaging());
        if (request.getCanMessageCareTeam() != null)   access.setCanMessageCareTeam(request.getCanMessageCareTeam());
        if (request.getCanViewConsultations() != null) access.setCanViewConsultations(request.getCanViewConsultations());
        if (request.getCanScheduleAppointments() != null) access.setCanScheduleAppointments(request.getCanScheduleAppointments());
        if (request.getCanManageCarePlan() != null)    access.setCanManageCarePlan(request.getCanManageCarePlan());

        return toEnrichedResponse(dependentAccessRepository.save(access));
    }

    private CaregiverDependentResponse toEnrichedResponse(DependentAccess access) {
        CaregiverDependentResponse.CaregiverDependentResponseBuilder builder = CaregiverDependentResponse.builder()
                .accessId(access.getAccessId())
                .caregiverSystemId(access.getCaregiverSystemId())
                .patientSystemId(access.getPatientSystemId())
                .relationship(access.getRelationship())
                .accessLevel(access.getAccessLevel())
                .canViewRecords(access.getCanViewRecords())
                .canViewAppointments(access.getCanViewAppointments())
                .canViewMedications(access.getCanViewMedications())
                .canViewLabResults(access.getCanViewLabResults())
                .canViewImaging(access.getCanViewImaging())
                .canMessageCareTeam(access.getCanMessageCareTeam())
                .canViewConsultations(access.getCanViewConsultations())
                .canScheduleAppointments(access.getCanScheduleAppointments())
                .canManageCarePlan(access.getCanManageCarePlan())
                .authorizedBySystemId(access.getAuthorizedBySystemId())
                .authorizedDate(access.getAuthorizedDate())
                .expiresAt(access.getExpiresAt())
                .isActive(access.getIsActive())
                .revokedAt(access.getRevokedAt());

        userRepository.findBySystemId(access.getPatientSystemId()).ifPresent(user ->
                builder.patientName(user.getFirstName() + " " + user.getLastName())
                       .patientEmail(user.getEmail())
        );

        patientRepository.findBySystemId(access.getPatientSystemId()).ifPresent(patient ->
                builder.patientHealthStatus(patient.getHealthStatus())
                       .patientDateOfBirth(patient.getDateOfBirth())
                       .patientGender(patient.getGender())
                       .patientBloodType(patient.getBloodType())
        );

        return builder.build();
    }
}
