package com.carenexus.api.core.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.common.event.CaregiverAccessChangedEvent;
import com.carenexus.api.core.model.CareTeam;
import com.carenexus.api.core.model.DependentAccess;
import com.carenexus.api.core.repository.CareTeamRepository;
import com.carenexus.api.core.repository.DependentAccessRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RelationshipService (repositories mocked; no database needed).
 * The repository queries themselves (expiry/revocation filters, appointment/consultation links) are JPQL and are
 * exercised against PostgreSQL by the integration tests.
 */
@ExtendWith(MockitoExtension.class)
public class RelationshipServiceTest {

    private static final String DOCTOR = "DOC-00001";
    private static final String LINDA = "PAT-00001";
    private static final String MARY = "PAT-00002";
    private static final String SARAH = "CGV-00001";

    @Mock
    private CareTeamRepository careTeamRepository;

    @Mock
    private DependentAccessRepository dependentAccessRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private RelationshipService relationshipService;

    // ==================== RELATED DOCTORS (messaging / calls eligibility) ====================

    @Test
    public void relatedDoctors_unionOfCareTeamAppointmentsConsultations_deduplicated_activeDoctorsOnly() {
        when(careTeamRepository.findAllDoctorsForPatient(LINDA)).thenReturn(List.of(careTeam(DOCTOR, LINDA, "PRIMARY_DOCTOR")));
        when(careTeamRepository.findAppointmentDoctorSystemIdsForPatient(LINDA)).thenReturn(List.of("DOC-00002", DOCTOR));
        when(careTeamRepository.findConsultationDoctorSystemIdsForPatient(LINDA)).thenReturn(List.of("DOC-00003", "DOC-00002"));
        when(userRepository.findBySystemId(DOCTOR)).thenReturn(Optional.of(user(DOCTOR, "DOCTOR", true)));
        when(userRepository.findBySystemId("DOC-00002")).thenReturn(Optional.of(user("DOC-00002", "DOCTOR", true)));
        when(userRepository.findBySystemId("DOC-00003")).thenReturn(Optional.of(user("DOC-00003", "DOCTOR", false)));

        assertEquals(List.of(DOCTOR, "DOC-00002"), relationshipService.getRelatedDoctorsForPatient(LINDA));
    }

    @Test
    public void relatedDoctors_none() {
        when(careTeamRepository.findAllDoctorsForPatient(LINDA)).thenReturn(List.of());
        when(careTeamRepository.findAppointmentDoctorSystemIdsForPatient(LINDA)).thenReturn(List.of());
        when(careTeamRepository.findConsultationDoctorSystemIdsForPatient(LINDA)).thenReturn(List.of());

        assertTrue(relationshipService.getRelatedDoctorsForPatient(LINDA).isEmpty());
        assertTrue(relationshipService.getRelatedDoctorsForPatient(null).isEmpty());
    }

    @Test
    public void revokeCaregiverAccess_clearsApprovals_andPublishesEvent() {
        DependentAccess access = access(SARAH, LINDA);
        access.setAccessId(3);
        access.setCanMessageCareTeam(true);
        access.setMessagingApprovedAt(java.time.Instant.now());
        access.setMessagingApprovedBySystemId(LINDA);
        when(dependentAccessRepository.findActiveCaregiverAccessToDependents(SARAH, LINDA)).thenReturn(Optional.of(access));
        when(dependentAccessRepository.save(any(DependentAccess.class))).thenAnswer(inv -> inv.getArgument(0));

        relationshipService.revokeCaregiverAccessToDependent(SARAH, LINDA);

        assertFalse(access.getIsActive());
        assertNull(access.getMessagingApprovedAt());
        verify(eventPublisher).publishEvent((Object) new CaregiverAccessChangedEvent(3, LINDA, SARAH, true, false, false));
    }

    // ==================== DOCTOR-PATIENT ====================

    @Test
    public void doctorAssigned_viaCareTeam() {
        when(careTeamRepository.findActiveDoctorPatientRelationship(DOCTOR, LINDA))
                .thenReturn(Optional.of(careTeam(DOCTOR, LINDA, "PRIMARY_DOCTOR")));

        assertTrue(relationshipService.isDoctorAssignedToPatient(DOCTOR, LINDA));
        verify(careTeamRepository, never()).existsAppointmentLink(anyString(), anyString());
    }

    @Test
    public void doctorAssigned_viaAppointment() {
        when(careTeamRepository.findActiveDoctorPatientRelationship(DOCTOR, LINDA)).thenReturn(Optional.empty());
        when(careTeamRepository.existsAppointmentLink(DOCTOR, LINDA)).thenReturn(true);

        assertTrue(relationshipService.isDoctorAssignedToPatient(DOCTOR, LINDA));
    }

    @Test
    public void doctorAssigned_viaConsultation() {
        when(careTeamRepository.findActiveDoctorPatientRelationship(DOCTOR, LINDA)).thenReturn(Optional.empty());
        when(careTeamRepository.existsAppointmentLink(DOCTOR, LINDA)).thenReturn(false);
        when(careTeamRepository.existsConsultationLink(DOCTOR, LINDA)).thenReturn(true);

        assertTrue(relationshipService.isDoctorAssignedToPatient(DOCTOR, LINDA));
    }

    @Test
    public void doctorNotAssigned_whenNoLinkAtAll() {
        when(careTeamRepository.findActiveDoctorPatientRelationship(DOCTOR, MARY)).thenReturn(Optional.empty());
        when(careTeamRepository.existsAppointmentLink(DOCTOR, MARY)).thenReturn(false);
        when(careTeamRepository.existsConsultationLink(DOCTOR, MARY)).thenReturn(false);

        assertFalse(relationshipService.isDoctorAssignedToPatient(DOCTOR, MARY));
    }

    @Test
    public void doctorNotAssigned_nullIds() {
        assertFalse(relationshipService.isDoctorAssignedToPatient(null, LINDA));
        assertFalse(relationshipService.isDoctorAssignedToPatient(DOCTOR, null));
        verifyNoInteractions(careTeamRepository);
    }

    @Test
    public void patientsForDoctor_unionOfCareTeamAppointmentsAndConsultations_deduplicated() {
        when(careTeamRepository.findAllPatientsForDoctor(DOCTOR)).thenReturn(List.of(careTeam(DOCTOR, LINDA, "DOCTOR")));
        when(careTeamRepository.findAppointmentPatientSystemIdsForDoctor(DOCTOR)).thenReturn(List.of(LINDA, MARY));
        when(careTeamRepository.findConsultationPatientSystemIdsForDoctor(DOCTOR)).thenReturn(List.of(MARY));

        List<String> patients = relationshipService.getPatientsForDoctor(DOCTOR);

        assertEquals(List.of(LINDA, MARY), patients);
    }

    @Test
    public void doctorsForPatient_fromCareTeam() {
        when(careTeamRepository.findAllDoctorsForPatient(LINDA)).thenReturn(List.of(
                careTeam(DOCTOR, LINDA, "PRIMARY_DOCTOR"), careTeam("DOC-00002", LINDA, "SPECIALIST")));

        assertEquals(List.of(DOCTOR, "DOC-00002"), relationshipService.getDoctorsForPatient(LINDA));
    }

    @Test
    public void revokeDoctor_setsInactiveAndUnassignedAt() {
        CareTeam team = careTeam(DOCTOR, LINDA, "PRIMARY_DOCTOR");
        when(careTeamRepository.findActiveDoctorPatientRelationship(DOCTOR, LINDA)).thenReturn(Optional.of(team));

        relationshipService.revokeDoctorAccessToPatient(DOCTOR, LINDA);

        assertFalse(team.getIsActive());
        assertNotNull(team.getUnassignedAt());
        verify(careTeamRepository).save(team);
    }

    // ==================== CAREGIVER / DEPENDENT ACCESS ====================

    @Test
    public void caregiverAccess_comesFromDependentAccessOnly() {
        when(dependentAccessRepository.findActiveCaregiverAccessToDependents(SARAH, LINDA))
                .thenReturn(Optional.of(access(SARAH, LINDA)));

        assertTrue(relationshipService.hasAccessToDependent(SARAH, LINDA));
        assertTrue(relationshipService.isCaregiverAssignedToPatient(SARAH, LINDA));
        verify(careTeamRepository, never()).findActiveCaregiverPatientRelationship(anyString(), anyString());
    }

    @Test
    public void caregiverAccess_absentWhenRepositoryFindsNoActiveRow() {
        // expired / revoked / inactive rows are filtered by the repository query
        when(dependentAccessRepository.findActiveCaregiverAccessToDependents(SARAH, MARY)).thenReturn(Optional.empty());

        assertFalse(relationshipService.hasAccessToDependent(SARAH, MARY));
        assertTrue(relationshipService.getActiveDependentAccess(SARAH, MARY).isEmpty());
    }

    @Test
    public void getActiveDependentAccess_nullIds() {
        assertTrue(relationshipService.getActiveDependentAccess(null, LINDA).isEmpty());
        verifyNoInteractions(dependentAccessRepository);
    }

    @Test
    public void permissionTypeForDependent_delegatesToAccessLevelQuery() {
        when(dependentAccessRepository.findAccessWithPermissionType(SARAH, LINDA, "FULL_ACCESS"))
                .thenReturn(Optional.of(access(SARAH, LINDA)));
        when(dependentAccessRepository.findAccessWithPermissionType(SARAH, LINDA, "VIEW_ONLY"))
                .thenReturn(Optional.empty());

        assertTrue(relationshipService.hasPermissionTypeForDependent(SARAH, LINDA, "FULL_ACCESS"));
        assertFalse(relationshipService.hasPermissionTypeForDependent(SARAH, LINDA, "VIEW_ONLY"));
    }

    @Test
    public void dependentsForCaregiver_andCaregiversForPatient() {
        when(dependentAccessRepository.findAllDependentsForCaregiver(SARAH))
                .thenReturn(List.of(access(SARAH, LINDA), access(SARAH, MARY)));
        when(dependentAccessRepository.findAllCaregiversForDependent(LINDA))
                .thenReturn(List.of(access(SARAH, LINDA)));

        assertEquals(List.of(LINDA, MARY), relationshipService.getDependentsForCaregiver(SARAH));
        assertEquals(List.of(LINDA, MARY), relationshipService.getPatientsForCaregiver(SARAH));
        assertEquals(List.of(SARAH), relationshipService.getCaregiversForPatient(LINDA));
    }

    @Test
    public void revokeCaregiver_setsInactiveAndRevokedAt() {
        DependentAccess access = access(SARAH, LINDA);
        when(dependentAccessRepository.findActiveCaregiverAccessToDependents(SARAH, LINDA)).thenReturn(Optional.of(access));

        relationshipService.revokeCaregiverAccessToDependent(SARAH, LINDA);

        assertFalse(access.getIsActive());
        assertNotNull(access.getRevokedAt());
        verify(dependentAccessRepository).save(access);
    }

    @Test
    public void revokeCaregiver_noActiveRow_noSave() {
        when(dependentAccessRepository.findActiveCaregiverAccessToDependents(SARAH, LINDA)).thenReturn(Optional.empty());

        relationshipService.revokeCaregiverAccessToDependent(SARAH, LINDA);

        verify(dependentAccessRepository, never()).save(any());
    }

    // ==================== COMBINED ====================

    @Test
    public void canAccessPatientData_self() {
        assertTrue(relationshipService.canAccessPatientData(LINDA, LINDA));
    }

    @Test
    public void canAccessPatientData_family() {
        when(careTeamRepository.findActiveDoctorPatientRelationship(SARAH, LINDA)).thenReturn(Optional.empty());
        when(dependentAccessRepository.findActiveCaregiverAccessToDependents(SARAH, LINDA))
                .thenReturn(Optional.of(access(SARAH, LINDA)));

        assertTrue(relationshipService.canAccessPatientData(SARAH, LINDA));
    }

    @Test
    public void canAccessPatientData_noRelationship() {
        when(careTeamRepository.findActiveDoctorPatientRelationship(SARAH, LINDA)).thenReturn(Optional.empty());
        when(dependentAccessRepository.findActiveCaregiverAccessToDependents(SARAH, LINDA)).thenReturn(Optional.empty());

        assertFalse(relationshipService.canAccessPatientData(SARAH, LINDA));
    }

    // ==================== HELPERS ====================

    private static CareTeam careTeam(String provider, String patient, String role) {
        return CareTeam.builder()
                .providerSystemId(provider)
                .patientSystemId(patient)
                .role(role)
                .isActive(true)
                .build();
    }

    private static User user(String systemId, String role, boolean active) {
        User user = new User();
        user.setSystemId(systemId);
        user.setRole(role);
        user.setIsActive(active);
        return user;
    }

    private static DependentAccess access(String caregiver, String patient) {
        return DependentAccess.builder()
                .caregiverSystemId(caregiver)
                .patientSystemId(patient)
                .accessLevel("FULL_ACCESS")
                .isActive(true)
                .build();
    }
}
