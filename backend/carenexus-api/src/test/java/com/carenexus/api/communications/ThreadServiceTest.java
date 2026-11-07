package com.carenexus.api.communications;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.communications.dto.request.AddThreadMembersRequest;
import com.carenexus.api.communications.dto.request.CreateThreadRequest;
import com.carenexus.api.communications.model.MessageThread;
import com.carenexus.api.communications.model.ThreadMember;
import com.carenexus.api.communications.repository.MessageRepository;
import com.carenexus.api.communications.repository.MessageThreadRepository;
import com.carenexus.api.communications.repository.NotificationRepository;
import com.carenexus.api.communications.repository.ThreadMemberRepository;
import com.carenexus.api.communications.service.CommunicationsAccessService;
import com.carenexus.api.communications.service.CommunicationsAccessService.Caller;
import com.carenexus.api.communications.service.CommunicationsAccessService.ThreadAccess;
import com.carenexus.api.communications.service.ThreadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Thread rules: join-time history boundary for added members, PRIVATE de-duplication and shape, membership
 * management permissions, removal after caregiver access changes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ThreadServiceTest {

    private static final String PATIENT = "PAT-00001";
    private static final String DOCTOR = "DOC-00002";
    private static final String CAREGIVER = "CGV-00001";

    @Mock private CommunicationsAccessService accessService;
    @Mock private MessageThreadRepository threadRepository;
    @Mock private ThreadMemberRepository memberRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private AuditService auditService;

    @InjectMocks
    private ThreadService threadService;

    private MessageThread careTeam;
    private Map<String, User> eligible;

    @BeforeEach
    public void setUp() {
        careTeam = MessageThread.builder().threadId(41).patientSystemId(PATIENT).conversationType("CARE_TEAM")
                .threadType("CARE_TEAM").createdAt(LocalDateTime.now()).lastMessageId(905).build();
        eligible = new LinkedHashMap<>();
        eligible.put(PATIENT, user(PATIENT, "PATIENT"));
        eligible.put(DOCTOR, user(DOCTOR, "DOCTOR"));
        eligible.put(CAREGIVER, user(CAREGIVER, "CAREGIVER"));
        when(accessService.eligibleMembers(PATIENT)).thenReturn(eligible);
        when(accessService.usersById(anyCollection())).thenReturn(new HashMap<>(eligible));
        when(memberRepository.findActiveByThreadIds(anyCollection())).thenReturn(List.of());
        when(messageRepository.findUnreadStats(anyString(), anyCollection())).thenReturn(List.of());
        when(messageRepository.findLastVisible(anyString(), anyCollection())).thenReturn(List.of());
        when(threadRepository.save(any(MessageThread.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Caller callerIs(String systemId, String role, MessageThread thread, ThreadMember member) {
        Caller caller = new Caller(systemId, role);
        when(accessService.requireCaller()).thenReturn(caller);
        when(accessService.requireThreadAccess(caller, thread.getThreadId())).thenReturn(new ThreadAccess(thread, member));
        return caller;
    }

    // ==================== HISTORY BOUNDARY ====================

    @Test
    public void addMember_newMemberStartsAtCurrentNewestMessage() {
        callerIs(DOCTOR, "DOCTOR", careTeam, member(DOCTOR, "DOCTOR"));
        when(messageRepository.findMaxMessageId(41)).thenReturn(905);
        when(memberRepository.findByThreadIdAndSystemId(41, CAREGIVER)).thenReturn(Optional.empty());

        threadService.addMembers(41, new AddThreadMembersRequest(List.of(CAREGIVER)));

        ThreadMember added = savedMembers().get(0);
        assertEquals(CAREGIVER, added.getSystemId());
        assertEquals("CAREGIVER", added.getMemberRole());
        assertEquals(905, added.getHistoryStartMessageId());
        assertEquals(905, added.getLastReadMessageId());
        assertEquals(DOCTOR, added.getAddedBySystemId());
        assertNotNull(added.getJoinedAt());
    }

    @Test
    public void addMember_locksThreadRowBeforeReadingTheBoundary() {
        callerIs(DOCTOR, "DOCTOR", careTeam, member(DOCTOR, "DOCTOR"));
        when(messageRepository.findMaxMessageId(41)).thenReturn(905);
        when(memberRepository.findByThreadIdAndSystemId(41, CAREGIVER)).thenReturn(Optional.empty());

        threadService.addMembers(41, new AddThreadMembersRequest(List.of(CAREGIVER)));

        // Same lock as MessageService.sendMessage, taken before MAX(message_id) is read
        var order = inOrder(threadRepository, messageRepository);
        order.verify(threadRepository).findForUpdateById(41);
        order.verify(messageRepository).findMaxMessageId(41);
    }

    @Test
    public void addMember_reAddingFormerMember_resetsRowWithNewBoundary() {
        callerIs(PATIENT, "PATIENT", careTeam, member(PATIENT, "PATIENT"));
        when(messageRepository.findMaxMessageId(41)).thenReturn(950);
        ThreadMember former = member(CAREGIVER, "CAREGIVER");
        former.setRemovedAt(Instant.now());
        former.setRemovalReason(ThreadMember.REASON_ACCESS_REVOKED);
        former.setArchivedAt(Instant.now());
        when(memberRepository.findByThreadIdAndSystemId(41, CAREGIVER)).thenReturn(Optional.of(former));

        threadService.addMembers(41, new AddThreadMembersRequest(List.of(CAREGIVER)));

        assertTrue(former.isActive());
        assertNull(former.getRemovalReason());
        assertNull(former.getArchivedAt());
        assertEquals(950, former.getHistoryStartMessageId());
        assertEquals(950, former.getLastReadMessageId());
    }

    @Test
    public void addMember_ineligiblePerson_is400_withoutLeakingTheirName() {
        callerIs(DOCTOR, "DOCTOR", careTeam, member(DOCTOR, "DOCTOR"));
        User stranger = user("CGV-00009", "CAREGIVER");
        when(accessService.usersById(List.of("CGV-00009"))).thenReturn(Map.of("CGV-00009", stranger));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> threadService.addMembers(41, new AddThreadMembersRequest(List.of("CGV-00009"))));
        assertEquals("This person cannot be added to this conversation", ex.getMessage());
        verify(memberRepository, never()).saveAll(any());
    }

    @Test
    public void addMember_privateThread_is409() {
        MessageThread privateThread = MessageThread.builder().threadId(41).patientSystemId(PATIENT)
                .conversationType("PRIVATE").threadType("PATIENT_DOCTOR").build();
        callerIs(DOCTOR, "DOCTOR", privateThread, member(DOCTOR, "DOCTOR"));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> threadService.addMembers(41, new AddThreadMembersRequest(List.of(CAREGIVER))));
        assertEquals("Members of a private conversation cannot be changed", ex.getMessage());
    }

    @Test
    public void addMember_byCaregiver_is403() {
        callerIs(CAREGIVER, "CAREGIVER", careTeam, member(CAREGIVER, "CAREGIVER"));

        AuthorizationException ex = assertThrows(AuthorizationException.class,
                () -> threadService.addMembers(41, new AddThreadMembersRequest(List.of(DOCTOR))));
        assertEquals("Only the patient or a doctor can change members", ex.getMessage());
    }

    // ==================== REMOVE / LEAVE ====================

    @Test
    public void removeMember_patientCannotBeRemoved() {
        callerIs(DOCTOR, "DOCTOR", careTeam, member(DOCTOR, "DOCTOR"));
        assertThrows(ConflictException.class, () -> threadService.removeMember(41, PATIENT));
    }

    @Test
    public void removeMember_lastDoctorCannotLeave() {
        ThreadMember doctor = member(DOCTOR, "DOCTOR");
        callerIs(DOCTOR, "DOCTOR", careTeam, doctor);
        when(memberRepository.findActiveByThreadId(41)).thenReturn(List.of(member(PATIENT, "PATIENT"), doctor));

        ConflictException ex = assertThrows(ConflictException.class, () -> threadService.removeMember(41, DOCTOR));
        assertEquals("A care team conversation needs at least one doctor", ex.getMessage());
    }

    @Test
    public void removeMember_caregiverLeaving_setsLeft() {
        ThreadMember caregiver = member(CAREGIVER, "CAREGIVER");
        callerIs(CAREGIVER, "CAREGIVER", careTeam, caregiver);

        threadService.removeMember(41, CAREGIVER);

        assertNotNull(caregiver.getLeftAt());
        assertEquals(ThreadMember.REASON_LEFT, caregiver.getRemovalReason());
        assertFalse(caregiver.isActive());
    }

    // ==================== CREATE ====================

    @Test
    public void createPrivate_existingPairReturned_insteadOfDuplicate() {
        Caller caller = new Caller(PATIENT, "PATIENT");
        when(accessService.requireCaller()).thenReturn(caller);
        MessageThread existing = MessageThread.builder().threadId(7).patientSystemId(PATIENT).conversationType("PRIVATE")
                .threadType("PATIENT_DOCTOR").createdAt(LocalDateTime.now()).build();
        when(threadRepository.findPrivateThreadsBetween(PATIENT, PATIENT, DOCTOR)).thenReturn(List.of(existing));

        ThreadService.ThreadResult result = threadService.createThread(CreateThreadRequest.builder()
                .type("PRIVATE").memberSystemIds(List.of(DOCTOR)).build());

        assertFalse(result.created());
        assertEquals(7, result.thread().getThreadId());
        verify(accessService).requireCanActFor(PATIENT);
        verify(memberRepository, never()).saveAll(any());
    }

    @Test
    public void createPrivate_patientAndCaregiver_isNotAnAllowedShape() {
        when(accessService.requireCaller()).thenReturn(new Caller(PATIENT, "PATIENT"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> threadService.createThread(CreateThreadRequest.builder()
                .patientSystemId(PATIENT).type("PRIVATE").memberSystemIds(List.of(CAREGIVER)).build()));
        assertEquals("A private conversation is between a doctor and the patient or one of their caregivers", ex.getMessage());
    }

    @Test
    public void createCareTeam_addsPatientAutomatically_withZeroBoundary() {
        when(accessService.requireCaller()).thenReturn(new Caller(CAREGIVER, "CAREGIVER"));
        when(threadRepository.save(any(MessageThread.class))).thenAnswer(inv -> {
            MessageThread t = inv.getArgument(0);
            t.setThreadId(55);
            t.setCreatedAt(LocalDateTime.now());
            return t;
        });

        ThreadService.ThreadResult result = threadService.createThread(CreateThreadRequest.builder()
                .patientSystemId(PATIENT).type("CARE_TEAM").subject("Blood pressure follow-up")
                .memberSystemIds(List.of(DOCTOR)).clientThreadId("0b6f6c1e-0c1e-4a71").build());

        assertTrue(result.created());
        List<ThreadMember> members = savedMembers();
        assertEquals(Set.of(CAREGIVER, PATIENT, DOCTOR), new HashSet<>(members.stream().map(ThreadMember::getSystemId).toList()));
        members.forEach(m -> {
            assertEquals(0, m.getHistoryStartMessageId());
            assertEquals(0, m.getLastReadMessageId());
            assertEquals(CAREGIVER, m.getAddedBySystemId());
        });
        verify(auditService).logClinicalAccess("CREATE", "MESSAGE_THREAD", 55, PATIENT);
    }

    @Test
    public void createCareTeam_withoutDoctor_is400() {
        when(accessService.requireCaller()).thenReturn(new Caller(PATIENT, "PATIENT"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> threadService.createThread(CreateThreadRequest.builder()
                .type("CARE_TEAM").memberSystemIds(List.of(CAREGIVER)).build()));
        assertEquals("A care team conversation needs at least one doctor", ex.getMessage());
    }

    // ==================== ACCESS CHANGE ====================

    @Test
    public void caregiverAccessRemoved_endsMembershipsAsAccessRevoked_andClearsTheirNotifications() {
        ThreadMember a = member(CAREGIVER, "CAREGIVER");
        a.setArchivedAt(Instant.now());
        ThreadMember b = member(CAREGIVER, "CAREGIVER");
        b.setThreadId(42);
        when(memberRepository.findActiveByUserAndPatient(CAREGIVER, PATIENT)).thenReturn(List.of(a, b));

        int removed = threadService.removeCaregiverAfterAccessChange(CAREGIVER, PATIENT);

        assertEquals(2, removed);
        for (ThreadMember m : List.of(a, b)) {
            assertNotNull(m.getRemovedAt());
            assertEquals(ThreadMember.REASON_ACCESS_REVOKED, m.getRemovalReason());
            assertNull(m.getArchivedAt());
            assertFalse(m.isActive());
        }
        verify(notificationRepository).markThreadsRead(eq(CAREGIVER), eq(List.of(41, 42)), any(LocalDateTime.class));
    }

    // ==================== helpers ====================

    @SuppressWarnings("unchecked")
    private List<ThreadMember> savedMembers() {
        ArgumentCaptor<Iterable<ThreadMember>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(memberRepository).saveAll(captor.capture());
        List<ThreadMember> list = new ArrayList<>();
        captor.getValue().forEach(list::add);
        return list;
    }

    private static ThreadMember member(String systemId, String role) {
        return ThreadMember.builder().threadId(41).systemId(systemId).memberRole(role).joinedAt(Instant.now())
                .historyStartMessageId(0).lastReadMessageId(0).build();
    }

    private static User user(String systemId, String role) {
        return User.builder().systemId(systemId).role(role).isActive(true).firstName("F" + systemId).lastName("L").build();
    }
}
