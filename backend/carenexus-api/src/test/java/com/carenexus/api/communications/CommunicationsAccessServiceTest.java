package com.carenexus.api.communications;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.communications.model.MessageThread;
import com.carenexus.api.communications.model.ThreadMember;
import com.carenexus.api.communications.repository.CommunicationUserRepository;
import com.carenexus.api.communications.repository.MessageThreadRepository;
import com.carenexus.api.communications.repository.ThreadMemberRepository;
import com.carenexus.api.communications.service.CommunicationsAccessService;
import com.carenexus.api.communications.service.CommunicationsAccessService.Caller;
import com.carenexus.api.communications.service.CommunicationsAccessService.ThreadAccess;
import com.carenexus.api.core.model.DependentAccess;
import com.carenexus.api.core.repository.DependentAccessRepository;
import com.carenexus.api.core.service.RelationshipService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Messaging access rules (contract 7.1): admin denied, non-member denied, revoked caregiver denied at request time,
 * eligibility needs the patient's messaging approval.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class CommunicationsAccessServiceTest {

    private static final String PATIENT = "PAT-00001";
    private static final String DOCTOR = "DOC-00002";
    private static final String CAREGIVER = "CGV-00001";
    private static final String OTHER_CAREGIVER = "CGV-00002";

    @Mock private AuthorizationService authorizationService;
    @Mock private RelationshipService relationshipService;
    @Mock private DependentAccessRepository dependentAccessRepository;
    @Mock private MessageThreadRepository threadRepository;
    @Mock private ThreadMemberRepository memberRepository;
    @Mock private CommunicationUserRepository userRepository;
    @Mock private AuditService auditService;

    @InjectMocks
    private CommunicationsAccessService accessService;

    @Test
    public void admin_isRejectedOnEveryConversationEndpoint() {
        when(authorizationService.currentSystemId()).thenReturn("ADM-00001");
        when(authorizationService.currentRole()).thenReturn("ADMIN");

        AuthorizationException ex = assertThrows(AuthorizationException.class, () -> accessService.requireCaller());
        assertEquals("Administrators cannot access conversations", ex.getMessage());
    }

    @Test
    public void unknownThread_is404() {
        when(threadRepository.findById(99)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> accessService.requireThreadAccess(new Caller(DOCTOR, "DOCTOR"), 99));
        assertEquals("Conversation not found", ex.getMessage());
    }

    @Test
    public void nonMember_isDenied_andAudited() {
        when(threadRepository.findById(41)).thenReturn(Optional.of(thread()));
        when(memberRepository.findByThreadIdAndSystemId(41, OTHER_CAREGIVER)).thenReturn(Optional.empty());

        AuthorizationException ex = assertThrows(AuthorizationException.class,
                () -> accessService.requireThreadAccess(new Caller(OTHER_CAREGIVER, "CAREGIVER"), 41));
        assertEquals("You no longer have access to this conversation", ex.getMessage());
        verify(auditService).logAccessDenied(eq(OTHER_CAREGIVER), eq("MESSAGE_THREAD"), eq(41), eq(PATIENT), isNull(), anyString());
        verify(authorizationService, never()).requirePatientAccess(anyString(), any(), any());
    }

    @Test
    public void formerMember_isDenied() {
        when(threadRepository.findById(41)).thenReturn(Optional.of(thread()));
        ThreadMember removed = member(CAREGIVER, "CAREGIVER", 0);
        removed.setRemovedAt(Instant.now());
        removed.setRemovalReason(ThreadMember.REASON_ACCESS_REVOKED);
        when(memberRepository.findByThreadIdAndSystemId(41, CAREGIVER)).thenReturn(Optional.of(removed));

        AuthorizationException ex = assertThrows(AuthorizationException.class,
                () -> accessService.requireThreadAccess(new Caller(CAREGIVER, "CAREGIVER"), 41));
        assertEquals("You no longer have access to this conversation", ex.getMessage());
    }

    @Test
    public void caregiverStillMember_butPermissionRevoked_isDeniedImmediately() {
        when(threadRepository.findById(41)).thenReturn(Optional.of(thread()));
        when(memberRepository.findByThreadIdAndSystemId(41, CAREGIVER)).thenReturn(Optional.of(member(CAREGIVER, "CAREGIVER", 0)));
        doThrow(new AuthorizationException("Your proxy access for this patient does not allow messaging the care team"))
                .when(authorizationService).requirePatientAccess(PATIENT, PatientPermission.MESSAGING, 41);

        AuthorizationException ex = assertThrows(AuthorizationException.class,
                () -> accessService.requireThreadAccess(new Caller(CAREGIVER, "CAREGIVER"), 41));
        assertEquals("You no longer have access to this conversation", ex.getMessage());
    }

    @Test
    public void activeMember_withCurrentAccess_isAllowed() {
        MessageThread thread = thread();
        ThreadMember member = member(DOCTOR, "DOCTOR", 0);
        when(threadRepository.findById(41)).thenReturn(Optional.of(thread));
        when(memberRepository.findByThreadIdAndSystemId(41, DOCTOR)).thenReturn(Optional.of(member));

        ThreadAccess access = accessService.requireThreadAccess(new Caller(DOCTOR, "DOCTOR"), 41);

        assertSame(thread, access.thread());
        assertSame(member, access.member());
        verify(authorizationService).requirePatientAccess(PATIENT, PatientPermission.MESSAGING, 41);
    }

    @Test
    public void eligibleMembers_includeOnlyCaregiversWithPatientApproval() {
        when(relationshipService.getRelatedDoctorsForPatient(PATIENT)).thenReturn(List.of(DOCTOR));
        when(dependentAccessRepository.findAllCaregiversForDependent(PATIENT)).thenReturn(List.of(
                grant(CAREGIVER, true, true),          // approved
                grant(OTHER_CAREGIVER, true, false))); // requested, not approved
        when(userRepository.findBySystemIdIn(anyCollection())).thenReturn(List.of(
                user(PATIENT, "PATIENT"), user(DOCTOR, "DOCTOR"), user(CAREGIVER, "CAREGIVER"), user(OTHER_CAREGIVER, "CAREGIVER")));

        Map<String, User> eligible = accessService.eligibleMembers(PATIENT);

        assertEquals(List.of(PATIENT, DOCTOR, CAREGIVER), List.copyOf(eligible.keySet()));
    }

    @Test
    public void accessibleThreadIds_dropThreadsOfPatientsTheCaregiverCanNoLongerMessageFor() {
        when(memberRepository.findActiveThreadPatientsForUser(CAREGIVER)).thenReturn(List.of(
                new Object[]{41, PATIENT}, new Object[]{42, "PAT-00002"}));
        DependentAccess approved = grant(CAREGIVER, true, true);
        DependentAccess other = grant(CAREGIVER, true, false);
        other.setPatientSystemId("PAT-00002");
        when(dependentAccessRepository.findAllDependentsForCaregiver(CAREGIVER)).thenReturn(List.of(approved, other));

        assertEquals(List.of(41), accessService.accessibleThreadIds(new Caller(CAREGIVER, "CAREGIVER")));
    }

    @Test
    public void accessibleThreadIds_emptyForAdmin() {
        assertTrue(accessService.accessibleThreadIds(new Caller("ADM-00001", "ADMIN")).isEmpty());
        verifyNoInteractions(memberRepository);
    }

    // ==================== helpers ====================

    static MessageThread thread() {
        return MessageThread.builder().threadId(41).patientSystemId(PATIENT).conversationType("CARE_TEAM")
                .threadType("CARE_TEAM").build();
    }

    static ThreadMember member(String systemId, String role, int historyStart) {
        return ThreadMember.builder().threadId(41).systemId(systemId).memberRole(role).joinedAt(Instant.now())
                .historyStartMessageId(historyStart).lastReadMessageId(historyStart).build();
    }

    static User user(String systemId, String role) {
        return User.builder().systemId(systemId).role(role).isActive(true).firstName("First" + systemId).lastName("Last").build();
    }

    static DependentAccess grant(String caregiver, boolean flag, boolean approved) {
        return DependentAccess.builder()
                .patientSystemId(PATIENT)
                .caregiverSystemId(caregiver)
                .canMessageCareTeam(flag)
                .messagingApprovedAt(approved ? Instant.now() : null)
                .messagingApprovedBySystemId(approved ? PATIENT : null)
                .build();
    }
}
