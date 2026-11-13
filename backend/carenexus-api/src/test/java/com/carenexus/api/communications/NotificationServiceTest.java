package com.carenexus.api.communications;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.communications.dto.response.NotificationResponse;
import com.carenexus.api.communications.model.Message;
import com.carenexus.api.communications.model.MessageThread;
import com.carenexus.api.communications.model.Notification;
import com.carenexus.api.communications.notify.NotificationPreferences;
import com.carenexus.api.communications.repository.NotificationRepository;
import com.carenexus.api.communications.service.CommunicationsAccessService;
import com.carenexus.api.communications.service.CommunicationsAccessService.Caller;
import com.carenexus.api.communications.service.NotificationService;
import com.carenexus.api.core.model.UserSettings;
import com.carenexus.api.core.repository.UserSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class NotificationServiceTest {

    private static final String PATIENT = "PAT-00001";
    private static final String DOCTOR = "DOC-00002";
    private static final String CAREGIVER = "CGV-00001";

    @Mock private NotificationRepository notificationRepository;
    @Mock private CommunicationsAccessService accessService;
    @Mock private UserSettingsRepository userSettingsRepository;
    @Mock private AuditService auditService;

    private NotificationService notificationService;

    private final MessageThread thread = MessageThread.builder().threadId(41).patientSystemId(PATIENT).build();

    @BeforeEach
    public void setUp() {
        // The real preference rules over the mocked settings table
        notificationService = new NotificationService(notificationRepository, accessService,
                new NotificationPreferences(userSettingsRepository), auditService);
    }

    private void callerIs(String systemId, String role) {
        Caller caller = new Caller(systemId, role);
        when(accessService.caller()).thenReturn(caller);
        when(accessService.accessibleThreadIds(caller)).thenReturn(List.of());
    }

    // ==================== MESSAGE NOTIFICATIONS ====================

    @Test
    public void messageNotification_isGeneric_neverContainsTheBody() {
        Message message = Message.builder().messageId(902).threadId(41).senderSystemId(DOCTOR)
                .body("Please take the new dose with food.").isUrgent(true).build();

        notificationService.createMessageNotification(CAREGIVER, thread, message, "Dr. Amara Osei", "Thomas Dube");
        notificationService.createMessageNotification(PATIENT, thread, message, "Dr. Amara Osei", "Thomas Dube");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(captor.capture());
        Notification toCaregiver = captor.getAllValues().get(0);
        Notification toPatient = captor.getAllValues().get(1);
        assertEquals("Dr. Amara Osei sent a message about Thomas Dube", toCaregiver.getMessage());
        assertEquals("Dr. Amara Osei sent you a message", toPatient.getMessage());
        for (Notification n : captor.getAllValues()) {
            assertFalse(n.getMessage().contains("dose"));
            assertEquals("MESSAGE", n.getType());
            assertEquals("MESSAGE", n.getCategory());
            assertEquals("Urgent message", n.getTitle());
            assertEquals("HIGH", n.getPriority());
            assertEquals(41, n.getThreadId());
            assertEquals(902, n.getMessageId());
            assertEquals(PATIENT, n.getPatientSystemId());
            assertEquals(DOCTOR, n.getActorSystemId());
            assertNull(n.getActionUrl());
            assertNull(n.getTarget(), "thread messages are opened by threadId, not by a target");
            assertNull(n.getDedupeKey());
        }
    }

    @Test
    public void messageNotification_isIdempotentPerRecipientAndMessage() {
        when(notificationRepository.existsBySystemIdAndMessageId(CAREGIVER, 902)).thenReturn(true);
        Message message = Message.builder().messageId(902).senderSystemId(DOCTOR).isUrgent(false).build();

        notificationService.createMessageNotification(CAREGIVER, thread, message, "Dr. Amara Osei", "Thomas Dube");

        verify(notificationRepository, never()).save(any());
    }

    @Test
    public void messageFromThePatient_neverSaysAboutThePatientThemself() {
        Message fromPatient = Message.builder().messageId(903).threadId(41).senderSystemId(PATIENT)
                .body("My readings are attached").isUrgent(false).build();

        notificationService.createMessageNotification(DOCTOR, thread, fromPatient, "Thomas Dube", "Thomas Dube");
        notificationService.createMessageNotification(CAREGIVER, thread, fromPatient, "Thomas Dube", "Thomas Dube");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(captor.capture());
        for (Notification n : captor.getAllValues()) {
            assertEquals("Thomas Dube sent you a message", n.getMessage());
            assertEquals("New message", n.getTitle());
            assertEquals("NORMAL", n.getPriority());
            assertFalse(n.getMessage().contains("readings"));
        }
    }

    @Test
    public void messageInAThreadWithoutAPatient_neverSaysAboutNull() {
        MessageThread noPatient = MessageThread.builder().threadId(77).patientSystemId(null).build();
        Message message = Message.builder().messageId(910).threadId(77).senderSystemId("ADM-00001").isUrgent(false).build();

        notificationService.createMessageNotification(CAREGIVER, noPatient, message, "Grace Moyo (Support)", null);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertEquals("Grace Moyo (Support) sent you a message", captor.getValue().getMessage());
        assertNull(captor.getValue().getPatientSystemId());
    }

    @Test
    public void messageNotificationsTurnedOff_noRowIsCreated() {
        UserSettings off = new UserSettings();
        off.setSystemId(CAREGIVER);
        off.setMessageNotifications(false);
        when(userSettingsRepository.findBySystemId(CAREGIVER)).thenReturn(Optional.of(off));
        Message message = Message.builder().messageId(904).senderSystemId(DOCTOR).isUrgent(true).build();

        notificationService.createMessageNotification(CAREGIVER, thread, message, "Dr. Amara Osei", "Thomas Dube");
        notificationService.createMessageNotification(PATIENT, thread, message, "Dr. Amara Osei", "Thomas Dube");

        // Only the patient (no settings row = default on) gets a notification, even though the message is urgent
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(1)).save(captor.capture());
        assertEquals(PATIENT, captor.getValue().getSystemId());
    }

    // ==================== CAREGIVER APPROVAL ====================

    @Test
    public void caregiverApprovalRequest_notifiesPatient_withCaregiverAccessTarget() {
        User sarah = User.builder().systemId(CAREGIVER).firstName("Sarah").lastName("Dube").role("CAREGIVER").build();
        when(accessService.usersById(anyCollection())).thenReturn(new java.util.HashMap<>(Map.of(CAREGIVER, sarah)));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        notificationService.createCaregiverApprovalNotification(PATIENT, CAREGIVER, true, true);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification n = captor.getValue();
        assertEquals(PATIENT, n.getSystemId());
        assertEquals("SYSTEM", n.getType());
        assertEquals("SYSTEM", n.getCategory());
        assertEquals("CAREGIVER_ACCESS", n.getTarget());
        assertEquals("Caregiver approval needed", n.getTitle());
        assertEquals("Sarah Dube is waiting for your approval to message your care team and join your video calls", n.getMessage());
        assertEquals(NotificationService.ACTION_CAREGIVER_ACCESS, n.getActionUrl());
        assertEquals(CAREGIVER, n.getActorSystemId());
        assertNull(n.getThreadId());

        n.setNotificationId(9);
        n.setCreatedAt(LocalDateTime.now());
        callerIs(PATIENT, "PATIENT");
        when(notificationRepository.findVisible(eq(PATIENT), anyCollection(), eq(false), eq(""), any(LocalDateTime.class), any()))
                .thenReturn(new PageImpl<>(List.of(n)));
        NotificationResponse listed = notificationService.list(false, 0, 20).getContent().get(0);
        assertEquals("CAREGIVER_ACCESS", listed.getTarget());
        assertEquals("SYSTEM", listed.getCategory());
        assertEquals(NotificationService.ACTION_CAREGIVER_ACCESS, listed.getActionUrl());
        assertNull(listed.getResourceType());
        assertNull(listed.getResourceId());
    }

    @Test
    public void caregiverApproval_isNotPreferenceGated() {
        UserSettings allOff = new UserSettings();
        allOff.setSystemId(PATIENT);
        allOff.setAccessChangeAlerts(false);
        allOff.setMessageNotifications(false);
        allOff.setCriticalAlerts(false);
        when(userSettingsRepository.findBySystemId(PATIENT)).thenReturn(Optional.of(allOff));
        when(accessService.usersById(anyCollection())).thenReturn(new java.util.HashMap<>());

        notificationService.createCaregiverApprovalNotification(PATIENT, CAREGIVER, false, true);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertEquals("A caregiver is waiting for your approval to join your video calls", captor.getValue().getMessage());
    }

    @Test
    public void caregiverApprovalResolved_marksThePatientsNotificationRead() {
        notificationService.resolveCaregiverApprovalNotifications(PATIENT, CAREGIVER);

        verify(notificationRepository).markReadForActorAndAction(eq(PATIENT), eq(CAREGIVER),
                eq(NotificationService.ACTION_CAREGIVER_ACCESS), any(LocalDateTime.class));
    }

    // ==================== LIST ====================

    @Test
    public void list_withoutAccessibleThreads_usesPlaceholder_soMessageNotificationsAreExcluded() {
        callerIs("ADM-00001", "ADMIN");
        when(notificationRepository.findVisible(eq("ADM-00001"), eq(List.of(-1)), eq(false), eq(""),
                any(LocalDateTime.class), any())).thenReturn(Page.empty());

        notificationService.list(false, -3, 500);

        verify(notificationRepository).findVisible(eq("ADM-00001"), eq(List.of(-1)), eq(false), eq(""),
                any(LocalDateTime.class), argThat(p -> p.getPageSize() == 50 && p.getPageNumber() == 0 && p.getSort().isUnsorted()));
    }

    @Test
    public void list_typeFilter_isTrimmedAndUpperCased_unknownTypeIs400() {
        callerIs(PATIENT, "PATIENT");
        when(notificationRepository.findVisible(any(), anyCollection(), anyBoolean(), any(), any(LocalDateTime.class), any()))
                .thenReturn(Page.empty());

        notificationService.list(true, "  lab_result ", 0, 20);
        verify(notificationRepository).findVisible(eq(PATIENT), anyCollection(), eq(true), eq("LAB_RESULT"),
                any(LocalDateTime.class), any());

        notificationService.list(false, "   ", 0, 20);
        verify(notificationRepository).findVisible(eq(PATIENT), anyCollection(), eq(false), eq(""),
                any(LocalDateTime.class), any());

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> notificationService.list(false, "INVOICE", 0, 20));
        assertEquals("Unknown notification type", ex.getMessage());
        assertNull(ex.getCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void list_mapsTheNewFields_andAuditsThePageByPatient() {
        callerIs(CAREGIVER, "CAREGIVER");
        Notification lab = Notification.builder().notificationId(214).systemId(CAREGIVER).type("LAB_RESULT")
                .category("LAB_RESULT").target("LAB_RESULTS").resourceType("LAB_RESULT").resourceId(88)
                .title("New lab result").message("A lab result for Thomas Dube is available").priority("NORMAL")
                .isRead(false).patientSystemId(PATIENT).createdAt(LocalDateTime.now())
                .actionUrl("/dashboard/proxy?section=documents-results&patient=PAT-00001&id=88&tab=labs").build();
        when(notificationRepository.findVisible(eq(CAREGIVER), anyCollection(), eq(false), eq(""), any(LocalDateTime.class), any()))
                .thenReturn(new PageImpl<>(List.of(lab)));

        NotificationResponse listed = notificationService.list(false, null, 0, 20).getContent().get(0);

        assertEquals("LAB_RESULT", listed.getCategory());
        assertEquals("LAB_RESULTS", listed.getTarget());
        assertEquals("LAB_RESULT", listed.getResourceType());
        assertEquals(88, listed.getResourceId());
        assertEquals(PATIENT, listed.getPatientSystemId());
        assertFalse(listed.getRead());

        ArgumentCaptor<Collection<Notification>> rows = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Function<Notification, String>> patientOf = ArgumentCaptor.forClass(Function.class);
        verify(auditService).logListRead(eq("NOTIFICATION"), rows.capture(), patientOf.capture());
        assertEquals(List.of(lab), List.copyOf(rows.getValue()));
        assertEquals(PATIENT, patientOf.getValue().apply(lab));
    }

    @Test
    public void rowWithoutCategory_answersWithTheDefaultForItsType() {
        callerIs(PATIENT, "PATIENT");
        Notification oldMessage = Notification.builder().notificationId(1).systemId(PATIENT).type("MESSAGE")
                .title("New message").message("x").priority("NORMAL").isRead(true).createdAt(LocalDateTime.now()).build();
        Notification oldSystem = Notification.builder().notificationId(2).systemId(PATIENT).type("SYSTEM")
                .title("Caregiver approval needed").message("x").priority("NORMAL").isRead(true)
                .actionUrl(NotificationService.ACTION_CAREGIVER_ACCESS).createdAt(LocalDateTime.now()).build();
        when(notificationRepository.findVisible(eq(PATIENT), anyCollection(), eq(false), eq(""), any(LocalDateTime.class), any()))
                .thenReturn(new PageImpl<>(List.of(oldMessage, oldSystem)));

        List<NotificationResponse> listed = notificationService.list(false, 0, 20).getContent();

        assertEquals("MESSAGE", listed.get(0).getCategory());
        assertNull(listed.get(0).getTarget());
        assertEquals("SYSTEM", listed.get(1).getCategory());
        assertEquals("CAREGIVER_ACCESS", listed.get(1).getTarget());
    }

    // ==================== COUNTS ====================

    @Test
    public void unreadCount_usesTheVisibilityRule_andIsNotAudited() {
        callerIs(PATIENT, "PATIENT");
        when(notificationRepository.countVisibleUnread(eq(PATIENT), eq(List.of(-1)), any(LocalDateTime.class))).thenReturn(5L);
        when(notificationRepository.countVisibleUnreadMessages(eq(PATIENT), eq(List.of(-1)), any(LocalDateTime.class))).thenReturn(3L);

        var counts = notificationService.unreadCount();

        assertEquals(5L, counts.getUnreadCount());
        assertEquals(3L, counts.getUnreadMessageCount());
        verifyNoInteractions(auditService);
    }

    // ==================== READ STATE ====================

    @Test
    public void markRead_otherUsersNotification_is404() {
        when(accessService.caller()).thenReturn(new Caller(CAREGIVER, "CAREGIVER"));
        when(notificationRepository.findByNotificationIdAndSystemId(7, CAREGIVER)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class, () -> notificationService.markRead(7));
        assertEquals("Notification not found", ex.getMessage());
        verifyNoInteractions(auditService);
    }

    @Test
    public void markRead_auditsTheTransitionOnly_andKeepsTheFirstReadTime() {
        when(accessService.caller()).thenReturn(new Caller(CAREGIVER, "CAREGIVER"));
        Notification unread = Notification.builder().notificationId(214).systemId(CAREGIVER).type("LAB_RESULT")
                .isRead(false).patientSystemId(PATIENT).build();
        when(notificationRepository.findByNotificationIdAndSystemId(214, CAREGIVER)).thenReturn(Optional.of(unread));

        notificationService.markRead(214);

        assertTrue(unread.getIsRead());
        LocalDateTime firstReadAt = unread.getReadAt();
        assertNotNull(firstReadAt);
        verify(notificationRepository).save(unread);
        verify(auditService).logClinicalAccess("UPDATE", "NOTIFICATION", 214, PATIENT);

        notificationService.markRead(214);

        assertEquals(firstReadAt, unread.getReadAt());
        verify(notificationRepository, times(1)).save(unread);
        verify(auditService, times(1)).logClinicalAccess(anyString(), anyString(), any(), any());
    }

    @Test
    public void markAllRead_auditsOnlyWhenSomethingChanged() {
        when(accessService.caller()).thenReturn(new Caller(PATIENT, "PATIENT"));
        when(notificationRepository.markAllRead(eq(PATIENT), any(LocalDateTime.class))).thenReturn(0, 4);

        notificationService.markAllRead();
        verifyNoInteractions(auditService);

        notificationService.markAllRead();
        verify(auditService).logClinicalAccess("UPDATE", "NOTIFICATION", null, null);
    }
}
