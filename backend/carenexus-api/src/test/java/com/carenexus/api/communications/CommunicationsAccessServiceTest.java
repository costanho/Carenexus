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
    public void admin_isRejectedOnTheRoutesWithoutAThread() {
        when(authorizationService.currentSystemId()).thenReturn("ADM-00001");
        when(authorizationService.currentRole()).thenReturn("ADMIN");

        AuthorizationException ex = assertThrows(AuthorizationException.class, () -> accessService.requireMessagingCaller());
        assertEquals("Administrators cannot access conversations", ex.getMessage());
        // Thread routes resolve the caller first and refuse administrators per conversation type
        assertEquals(new Caller("ADM-00001", "ADMIN"), accessService.requireCaller());
    }

    @Test
    public void admin_onClinicalThread_isDeniedAndAudited_evenWhenAMemberRowExists() {
        when(threadRepository.findById(41)).thenReturn(Optional.of(thread()));
        when(memberRepository.findByThreadIdAndSystemId(41, "ADM-00001"))
                .thenReturn(Optional.of(member("ADM-00001", "ADMIN", 0)));

        AuthorizationException ex = assertThrows(AuthorizationException.class,
                () -> accessService.requireThreadAccess(new Caller("ADM-00001", "ADMIN"), 41));
        assertEquals("Administrators cannot access conversations", ex.getMessage());
        verify(auditService).logAccessDenied(eq("ADM-00001"), eq("MESSAGE_THREAD"), eq(41), eq(PATIENT), isNull(), anyString());
    }

    @Test
    public void admin_onUnknownThread_is404() {
        when(threadRepository.findById(99)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> accessService.requireThreadAccess(new Caller("ADM-00001", "ADMIN"), 99));
    }

    @Test
    public void admin_onSupportThread_needsAnAdminMembership() {
        when(threadRepository.findById(57)).thenReturn(Optional.of(supportThread()));
        Caller admin = new Caller("ADM-00001", "ADMIN");
        when(memberRepository.findByThreadIdAndSystemId(57, "ADM-00001")).thenReturn(Optional.empty());
        AuthorizationException ex = assertThrows(AuthorizationException.class, () -> accessService.requireThreadAccess(admin, 57));
        assertEquals("You no longer have access to this conversation", ex.getMessage());

        ThreadMember joined = member("ADM-00001", "ADMIN", 0);
        joined.setThreadId(57);
        when(memberRepository.findByThreadIdAndSystemId(57, "ADM-00001")).thenReturn(Optional.of(joined));
        assertSame(joined, accessService.requireThreadAccess(admin, 57).member());
        verify(authorizationService, never()).requirePatientAccess(any(), any(), any());
    }

    @Test
    public void supportThread_openerNeedsNoPatientAccess_othersAreDenied() {
        when(threadRepository.findById(57)).thenReturn(Optional.of(supportThread()));
        ThreadMember opener = member(CAREGIVER, "CAREGIVER", 0);
        opener.setThreadId(57);
        when(memberRepository.findByThreadIdAndSystemId(57, CAREGIVER)).thenReturn(Optional.of(opener));

        assertSame(opener, accessService.requireThreadAccess(new Caller(CAREGIVER, "CAREGIVER"), 57).member());
        verify(authorizationService, never()).requirePatientAccess(any(), any(), any());

        when(memberRepository.findByThreadIdAndSystemId(57, PATIENT)).thenReturn(Optional.empty());
        AuthorizationException ex = assertThrows(AuthorizationException.class,
                () -> accessService.requireThreadAccess(new Caller(PATIENT, "PATIENT"), 57));
        assertEquals("You no longer have access to this conversation", ex.getMessage());
    }

    @Test
    public void memberCanActFor_supportThread_openerYes_administratorsNo() {
        assertTrue(accessService.memberCanActFor(CAREGIVER, "CAREGIVER", null));
        assertTrue(accessService.memberCanActFor(DOCTOR, "DOCTOR", null));
        assertFalse(accessService.memberCanActFor("ADM-00001", "ADMIN", null));
        assertFalse(accessService.memberCanActFor(null, "PATIENT", null));
        verifyNoInteractions(relationshipService);
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
    public void accessibleThreadIds_includeOwnSupportThreads_evenWithoutAnyGrant() {
        when(memberRepository.findActiveThreadPatientsForUser(CAREGIVER)).thenReturn(List.of(
                new Object[]{41, PATIENT}, new Object[]{57, null}));
        when(dependentAccessRepository.findAllDependentsForCaregiver(CAREGIVER)).thenReturn(List.of());

        assertEquals(List.of(57), accessService.accessibleThreadIds(new Caller(CAREGIVER, "CAREGIVER")));
    }

    @Test
    public void accessibleThreadIds_forAdmin_onlySupportThreads() {
        when(memberRepository.findActiveThreadPatientsForUser("ADM-00001")).thenReturn(List.of(
                new Object[]{41, PATIENT}, new Object[]{57, null}));

        assertEquals(List.of(57), accessService.accessibleThreadIds(new Caller("ADM-00001", "ADMIN")));
        verifyNoInteractions(relationshipService, dependentAccessRepository);
    }

    // ==================== helpers ====================

    static MessageThread thread() {
        return MessageThread.builder().threadId(41).patientSystemId(PATIENT).conversationType("CARE_TEAM")
                .threadType("CARE_TEAM").build();
    }

    static MessageThread supportThread() {
        return MessageThread.builder().threadId(57).patientSystemId(null).conversationType(MessageThread.SUPPORT)
                .threadType(MessageThread.THREAD_TYPE_PATIENT_SUPPORT).createdBySystemId(CAREGIVER).build();
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
