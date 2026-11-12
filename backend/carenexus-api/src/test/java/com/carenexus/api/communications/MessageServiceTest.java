package com.carenexus.api.communications;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.communications.dto.request.MarkThreadReadRequest;
import com.carenexus.api.communications.dto.request.SendMessageRequest;
import com.carenexus.api.communications.dto.response.ThreadMessagesResponse;
import com.carenexus.api.communications.dto.response.ThreadReadResponse;
import com.carenexus.api.communications.model.Message;
import com.carenexus.api.communications.model.MessageThread;
import com.carenexus.api.communications.model.ThreadMember;
import com.carenexus.api.communications.repository.MessageRepository;
import com.carenexus.api.communications.repository.MessageThreadRepository;
import com.carenexus.api.communications.repository.NotificationRepository;
import com.carenexus.api.communications.repository.ThreadMemberRepository;
import com.carenexus.api.communications.service.CommunicationsAccessService;
import com.carenexus.api.communications.service.CommunicationsAccessService.Caller;
import com.carenexus.api.communications.service.CommunicationsAccessService.ThreadAccess;
import com.carenexus.api.communications.service.MessageService;
import com.carenexus.api.communications.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Message rules: join-time history boundary, idempotent send by clientMessageId, notifications only for members
 * who still have access, read position never moves back, denied callers never reach the data.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class MessageServiceTest {

    private static final String PATIENT = "PAT-00001";
    private static final String DOCTOR = "DOC-00002";
    private static final String CAREGIVER = "CGV-00001";
    private static final String CLIENT_ID = "c5d6a0de-6a43-4d2e-9b8e-2c1f9b1f6f0a";

    @Mock private CommunicationsAccessService accessService;
    @Mock private MessageRepository messageRepository;
    @Mock private MessageThreadRepository threadRepository;
    @Mock private ThreadMemberRepository memberRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private NotificationService notificationService;
    @Mock private AuditService auditService;

    @InjectMocks
    private MessageService messageService;

    private MessageThread thread;
    private ThreadMember doctorMember;

    @BeforeEach
    public void setUp() {
        thread = MessageThread.builder().threadId(41).patientSystemId(PATIENT).conversationType("CARE_TEAM")
                .threadType("CARE_TEAM").lastMessageId(905).build();
        doctorMember = member(DOCTOR, "DOCTOR", 0, 0);
        Map<String, User> users = new HashMap<>();
        users.put(PATIENT, user(PATIENT, "PATIENT", "Thomas", "Dube"));
        users.put(DOCTOR, user(DOCTOR, "DOCTOR", "Amara", "Osei"));
        users.put(CAREGIVER, user(CAREGIVER, "CAREGIVER", "Sarah", "Dube"));
        when(accessService.usersById(anyCollection())).thenReturn(users);
    }

    private void callerIs(String systemId, String role, ThreadMember member) {
        Caller caller = new Caller(systemId, role);
        when(accessService.requireCaller()).thenReturn(caller);
        when(accessService.requireThreadAccess(caller, 41)).thenReturn(new ThreadAccess(thread, member));
    }

    // ==================== HISTORY BOUNDARY ====================

    @Test
    public void history_noCursor_onlyMessagesAfterJoinBoundary() {
        ThreadMember lateCaregiver = member(CAREGIVER, "CAREGIVER", 900, 900);
        callerIs(CAREGIVER, "CAREGIVER", lateCaregiver);
        when(messageRepository.findVisibleNewest(eq(41), eq(900), any())).thenReturn(List.of(msg(902, DOCTOR), msg(901, DOCTOR)));

        ThreadMessagesResponse response = messageService.listMessages(41, null, null, null);

        verify(messageRepository).findVisibleNewest(41, 900, PageRequest.of(0, 31));
        assertEquals(List.of(901, 902), response.getMessages().stream().map(m -> m.getMessageId()).toList());
        assertFalse(response.getHasMore());
        assertEquals(900, response.getLastReadMessageId());
        verify(auditService).logClinicalAccess("READ", "MESSAGE_THREAD", 41, PATIENT);
    }

    @Test
    public void history_before_respectsBoundary_andReportsHasMore() {
        ThreadMember lateCaregiver = member(CAREGIVER, "CAREGIVER", 900, 900);
        callerIs(CAREGIVER, "CAREGIVER", lateCaregiver);
        when(messageRepository.findVisibleBefore(eq(41), eq(900), eq(950), any()))
                .thenReturn(List.of(msg(949, DOCTOR), msg(948, DOCTOR), msg(947, DOCTOR)));

        ThreadMessagesResponse response = messageService.listMessages(41, 950, null, 2);

        verify(messageRepository).findVisibleBefore(41, 900, 950, PageRequest.of(0, 3));
        assertEquals(List.of(948, 949), response.getMessages().stream().map(m -> m.getMessageId()).toList());
        assertTrue(response.getHasMore());
    }

    @Test
    public void history_afterCursorBelowBoundary_isClampedToBoundary_andPollingIsNotAudited() {
        ThreadMember lateCaregiver = member(CAREGIVER, "CAREGIVER", 900, 900);
        callerIs(CAREGIVER, "CAREGIVER", lateCaregiver);
        when(messageRepository.findVisibleAfter(eq(41), eq(900), any())).thenReturn(List.of());

        messageService.listMessages(41, null, 10, null);

        verify(messageRepository).findVisibleAfter(41, 900, PageRequest.of(0, 31));
        verify(auditService, never()).logClinicalAccess(anyString(), anyString(), any(), anyString());
    }

    @Test
    public void history_beforeAndAfterTogether_is400() {
        callerIs(DOCTOR, "DOCTOR", doctorMember);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> messageService.listMessages(41, 10, 5, null));
        assertEquals("Use either before or after, not both", ex.getMessage());
    }

    @Test
    public void history_otherSendersClientIdIsHidden() {
        callerIs(CAREGIVER, "CAREGIVER", member(CAREGIVER, "CAREGIVER", 0, 0));
        Message mine = msg(903, CAREGIVER);
        mine.setClientMessageId("mine-123456");
        Message theirs = msg(902, DOCTOR);
        theirs.setClientMessageId("theirs-123456");
        when(messageRepository.findVisibleNewest(eq(41), eq(0), any())).thenReturn(List.of(mine, theirs));

        ThreadMessagesResponse response = messageService.listMessages(41, null, null, null);

        assertNull(response.getMessages().get(0).getClientMessageId());
        assertFalse(response.getMessages().get(0).getMine());
        assertEquals("mine-123456", response.getMessages().get(1).getClientMessageId());
        assertTrue(response.getMessages().get(1).getMine());
        assertEquals("Sarah Dube (Caregiver for Thomas Dube)", response.getMessages().get(1).getSenderLabel());
    }

    // ==================== DENIED CALLERS ====================

    @Test
    public void revokedOrNonMember_neverReachesMessages() {
        Caller caller = new Caller(CAREGIVER, "CAREGIVER");
        when(accessService.requireCaller()).thenReturn(caller);
        when(accessService.requireThreadAccess(caller, 41))
                .thenThrow(new AuthorizationException("You no longer have access to this conversation"));

        assertThrows(AuthorizationException.class, () -> messageService.listMessages(41, null, null, null));
        assertThrows(AuthorizationException.class, () -> messageService.sendMessage(41, send("hello")));
        verifyNoInteractions(messageRepository, notificationService);
    }

    // ==================== SEND ====================

    @Test
    public void send_sameClientMessageId_returnsStoredMessage_withoutDuplicate() {
        callerIs(DOCTOR, "DOCTOR", doctorMember);
        Message stored = msg(902, DOCTOR);
        stored.setClientMessageId(CLIENT_ID);
        when(messageRepository.findByThreadIdAndSenderSystemIdAndClientMessageId(41, DOCTOR, CLIENT_ID)).thenReturn(Optional.of(stored));

        MessageService.SendResult result = messageService.sendMessage(41, send("different body on retry"));

        assertFalse(result.created());
        assertEquals(902, result.message().getMessageId());
        assertEquals("stored body", result.message().getBody());
        verify(messageRepository, never()).saveAndFlush(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    public void send_new_storesMessageFromJwtSender_andNotifiesOnlyMembersWhoStillHaveAccess() {
        callerIs(DOCTOR, "DOCTOR", doctorMember);
        ThreadMember patientMember = member(PATIENT, "PATIENT", 0, 0);
        patientMember.setArchivedAt(Instant.now());
        ThreadMember revokedCaregiver = member(CAREGIVER, "CAREGIVER", 0, 0);
        when(memberRepository.findActiveByThreadId(41)).thenReturn(List.of(doctorMember, patientMember, revokedCaregiver));
        when(accessService.memberCanActFor(PATIENT, "PATIENT", PATIENT)).thenReturn(true);
        when(accessService.memberCanActFor(CAREGIVER, "CAREGIVER", PATIENT)).thenReturn(false);
        when(messageRepository.findByThreadIdAndSenderSystemIdAndClientMessageId(any(), any(), any())).thenReturn(Optional.empty());
        when(messageRepository.saveAndFlush(any(Message.class))).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            m.setMessageId(906);
            return m;
        });

        MessageService.SendResult result = messageService.sendMessage(41, SendMessageRequest.builder()
                .clientMessageId(CLIENT_ID).body("  Please take the new dose with food.  ").urgent(true).build());

        assertTrue(result.created());
        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).saveAndFlush(saved.capture());
        assertEquals(DOCTOR, saved.getValue().getSenderSystemId());
        assertEquals(PATIENT, saved.getValue().getPatientSystemId());
        assertNull(saved.getValue().getRecipientSystemId());
        assertEquals("Please take the new dose with food.", saved.getValue().getBody());
        assertTrue(saved.getValue().getIsUrgent());

        assertEquals(906, thread.getLastMessageId());
        assertNotNull(thread.getLastMessageAt());
        assertEquals(906, doctorMember.getLastReadMessageId());
        assertNull(patientMember.getArchivedAt());

        verify(notificationService).createMessageNotification(eq(PATIENT), eq(thread), any(Message.class),
                eq("Dr. Amara Osei"), eq("Thomas Dube"));
        verify(notificationService, never()).createMessageNotification(eq(CAREGIVER), any(), any(), any(), any());
        verify(notificationService, never()).createMessageNotification(eq(DOCTOR), any(), any(), any(), any());
        verify(auditService).logClinicalAccess("CREATE", "MESSAGE", 906, PATIENT);
        assertEquals(CLIENT_ID, result.message().getClientMessageId());
        assertTrue(result.message().getMine());
    }

    @Test
    public void send_validation() {
        callerIs(DOCTOR, "DOCTOR", doctorMember);
        when(messageRepository.findByThreadIdAndSenderSystemIdAndClientMessageId(any(), any(), any())).thenReturn(Optional.empty());

        assertEquals("A valid clientMessageId is required", assertThrows(RuntimeException.class,
                () -> messageService.sendMessage(41, SendMessageRequest.builder().clientMessageId("short").body("x").build())).getMessage());
        assertEquals("Message cannot be empty", assertThrows(RuntimeException.class,
                () -> messageService.sendMessage(41, send("   "))).getMessage());
        assertEquals("Messages can be at most 4000 characters", assertThrows(RuntimeException.class,
                () -> messageService.sendMessage(41, send("a".repeat(4001)))).getMessage());
        verify(messageRepository, never()).saveAndFlush(any());
    }

    // ==================== READ POSITION ====================

    @Test
    public void markRead_isCappedAtLastMessage_neverMovesBack_andMarksNotifications() {
        ThreadMember member = member(CAREGIVER, "CAREGIVER", 0, 903);
        callerIs(CAREGIVER, "CAREGIVER", member);
        when(messageRepository.countUnread(41, 905, CAREGIVER)).thenReturn(0L);
        // The cap is the newest stored message, not the cached thread pointer (which a racing send could lag)
        when(messageRepository.findMaxMessageId(41)).thenReturn(905);

        ThreadReadResponse response = messageService.markRead(41, new MarkThreadReadRequest(10_000));
        assertEquals(905, response.getLastReadMessageId());
        assertEquals(0L, response.getUnreadCount());
        verify(notificationRepository).markThreadReadUpTo(eq(CAREGIVER), eq(41), eq(10_000), any(LocalDateTime.class));

        ThreadReadResponse back = messageService.markRead(41, new MarkThreadReadRequest(1));
        assertEquals(905, back.getLastReadMessageId());
    }

    // ==================== helpers ====================

    private static SendMessageRequest send(String body) {
        return SendMessageRequest.builder().clientMessageId(CLIENT_ID).body(body).urgent(false).build();
    }

    private static ThreadMember member(String systemId, String role, int historyStart, int lastRead) {
        return ThreadMember.builder().threadId(41).systemId(systemId).memberRole(role).joinedAt(Instant.now())
                .historyStartMessageId(historyStart).lastReadMessageId(lastRead).build();
    }

    private static Message msg(int id, String sender) {
        return Message.builder().messageId(id).threadId(41).senderSystemId(sender).patientSystemId(PATIENT)
                .body("stored body").isUrgent(false).isDeleted(false).sentAt(LocalDateTime.now()).build();
    }

    private static User user(String systemId, String role, String first, String last) {
        return User.builder().systemId(systemId).role(role).isActive(true).firstName(first).lastName(last).build();
    }
}
