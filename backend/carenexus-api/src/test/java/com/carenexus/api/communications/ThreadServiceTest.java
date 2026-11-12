package com.carenexus.api.communications;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.communications.dto.request.ArchiveThreadRequest;
import com.carenexus.api.communications.dto.response.ThreadResponse;
import com.carenexus.api.communications.model.Message;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

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

    private Caller stubCaller(Caller caller) {
        when(accessService.requireCaller()).thenReturn(caller);
        when(accessService.requireMessagingCaller()).thenReturn(caller);
        return caller;
    }

    private Caller callerIs(String systemId, String role, MessageThread thread, ThreadMember member) {
        Caller caller = new Caller(systemId, role);
        stubCaller(caller);
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
        stubCaller(caller);
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
        stubCaller(new Caller(PATIENT, "PATIENT"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> threadService.createThread(CreateThreadRequest.builder()
                .patientSystemId(PATIENT).type("PRIVATE").memberSystemIds(List.of(CAREGIVER)).build()));
        assertEquals("A private conversation is between a doctor and the patient or one of their caregivers", ex.getMessage());
    }

    @Test
    public void createCareTeam_addsPatientAutomatically_withZeroBoundary() {
        stubCaller(new Caller(CAREGIVER, "CAREGIVER"));
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
        stubCaller(new Caller(PATIENT, "PATIENT"));

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

    // ==================== HARDENING ====================

    @Test
    public void createThread_supportType_is400WithTypedException() {
        stubCaller(new Caller(PATIENT, "PATIENT"));
        BadRequestException ex = assertThrows(BadRequestException.class, () -> threadService.createThread(
                CreateThreadRequest.builder().type("SUPPORT").build()));
        assertEquals("Conversation type must be PRIVATE or CARE_TEAM", ex.getMessage());
    }

    @Test
    public void createThread_doctorWithoutPatient_is400() {
        stubCaller(new Caller(DOCTOR, "DOCTOR"));
        BadRequestException ex = assertThrows(BadRequestException.class, () -> threadService.createThread(
                CreateThreadRequest.builder().type("CARE_TEAM").build()));
        assertEquals("Patient ID is required", ex.getMessage());
    }

    @Test
    public void listThreads_withoutFilter_includesSupportAndAuditsOneRowPerPatient() {
        Caller caller = stubCaller(new Caller(PATIENT, "PATIENT"));
        when(accessService.actablePatients(caller)).thenReturn(Set.of(PATIENT));
        MessageThread support = supportThread(57, PATIENT);
        when(threadRepository.findForMemberWithSupport(eq(PATIENT), anyCollection(), eq(true), eq(false), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(support, careTeam), inv.getArgument(4), 2));

        PageResponse<ThreadResponse> page = threadService.listThreads(null, false, -3, 500);

        assertEquals(2, page.getContent().size());
        verify(threadRepository).findForMemberWithSupport(eq(PATIENT), eq(Set.of(PATIENT)), eq(true), eq(false),
                eq(PageRequest.of(0, 50)));
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Collection<MessageThread>> rows = ArgumentCaptor.forClass((Class) Collection.class);
        verify(auditService).logListRead(eq("MESSAGE_THREAD"), rows.capture(), any());
        assertEquals(2, rows.getValue().size());
    }

    @Test
    public void listThreads_withPatientFilter_leavesSupportOut() {
        Caller caller = stubCaller(new Caller(DOCTOR, "DOCTOR"));
        when(accessService.actablePatients(caller)).thenReturn(Set.of(PATIENT));
        when(threadRepository.findForMemberWithSupport(anyString(), anyCollection(), anyBoolean(), anyBoolean(), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(), inv.getArgument(4), 0));

        threadService.listThreads(PATIENT, false, 0, 20);

        verify(threadRepository).findForMemberWithSupport(eq(DOCTOR), eq(Set.of(PATIENT)), eq(false), eq(false), any(Pageable.class));
    }

    @Test
    public void listThreads_caregiverWithoutGrants_stillSeesOwnSupportThreads() {
        Caller caller = stubCaller(new Caller(CAREGIVER, "CAREGIVER"));
        when(accessService.actablePatients(caller)).thenReturn(Set.of());
        when(threadRepository.findForMemberWithSupport(anyString(), anyCollection(), anyBoolean(), anyBoolean(), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(), inv.getArgument(4), 0));

        threadService.listThreads(null, false, 0, 20);

        verify(threadRepository).findForMemberWithSupport(eq(CAREGIVER), eq(List.of(MessageThreadRepository.NO_PATIENT)),
                eq(true), eq(false), any(Pageable.class));
    }

    @Test
    public void supportThread_memberChangesAre409_forOpenerAndAdministrator() {
        MessageThread support = supportThread(57, CAREGIVER);
        callerIs(CAREGIVER, "CAREGIVER", support, member(CAREGIVER, "CAREGIVER"));
        ConflictException add = assertThrows(ConflictException.class, () -> threadService.addMembers(57,
                AddThreadMembersRequest.builder().systemIds(List.of(DOCTOR)).build()));
        assertEquals("Members of a support conversation cannot be changed", add.getMessage());
        ConflictException leave = assertThrows(ConflictException.class, () -> threadService.removeMember(57, CAREGIVER));
        assertEquals("Members of a support conversation cannot be changed", leave.getMessage());

        stubCaller(new Caller("ADM-00001", "ADMIN"));
        when(accessService.requireThread(57)).thenReturn(support);
        assertThrows(ConflictException.class, () -> threadService.removeMember(57, CAREGIVER));
        ConflictException archive = assertThrows(ConflictException.class, () -> threadService.setArchived(57,
                ArchiveThreadRequest.builder().archived(true).build()));
        assertEquals("Support conversations are closed, not archived", archive.getMessage());
        verify(memberRepository, never()).save(any());
    }

    @Test
    public void supportThread_openerMayArchive() {
        MessageThread support = supportThread(57, CAREGIVER);
        ThreadMember opener = member(CAREGIVER, "CAREGIVER");
        callerIs(CAREGIVER, "CAREGIVER", support, opener);

        threadService.setArchived(57, ArchiveThreadRequest.builder().archived(true).build());

        assertNotNull(opener.getArchivedAt());
        verify(memberRepository).save(opener);
    }

    @Test
    public void supportThread_responseHasNoPatient_supportLabels_andClosedState() {
        MessageThread support = supportThread(57, CAREGIVER);
        support.setClosedAt(LocalDateTime.now());
        Caller caller = new Caller(CAREGIVER, "CAREGIVER");
        Map<String, User> users = new HashMap<>(eligible);
        users.put("ADM-00001", user("ADM-00001", "ADMIN"));
        when(accessService.usersById(anyCollection())).thenReturn(users);
        ThreadMember admin = member("ADM-00001", "ADMIN");
        admin.setThreadId(57);
        ThreadMember opener = member(CAREGIVER, "CAREGIVER");
        opener.setThreadId(57);
        when(memberRepository.findActiveByThreadIds(anyCollection())).thenReturn(List.of(opener, admin));
        Message last = Message.builder().messageId(931).threadId(57).senderSystemId("ADM-00001").body("Thanks")
                .isUrgent(true).isDeleted(false).sentAt(LocalDateTime.now()).build();
        when(messageRepository.findLastVisible(anyString(), anyCollection())).thenReturn(List.of(last));
        when(messageRepository.findUnreadStats(anyString(), anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{57, 1L, 1L}));

        ThreadResponse response = threadService.toResponse(caller, support);

        assertEquals("SUPPORT", response.getType());
        assertNull(response.getPatientSystemId());
        assertNull(response.getPatientName());
        assertEquals("F" + CAREGIVER + " L (Caregiver)", response.getMembers().get(0).getLabel());
        assertEquals("FADM-00001 L (Support)", response.getMembers().get(1).getLabel());
        assertEquals("FADM-00001 L (Support)", response.getLastMessage().getSenderLabel());
        assertFalse(response.getHasUrgentUnread());
        assertFalse(response.getCanManageMembers());
        assertTrue(response.getClosed());
        assertNotNull(response.getClosedAt());
    }

    @Test
    public void clinicalThread_isNeverClosed() {
        ThreadResponse response = threadService.toResponse(new Caller(PATIENT, "PATIENT"), careTeam);
        assertFalse(response.getClosed());
        assertNull(response.getClosedAt());
    }

    private static MessageThread supportThread(int id, String opener) {
        return MessageThread.builder().threadId(id).patientSystemId(null).conversationType(MessageThread.SUPPORT)
                .threadType(MessageThread.THREAD_TYPE_PATIENT_SUPPORT).createdBySystemId(opener)
                .createdAt(LocalDateTime.now()).build();
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
