package com.carenexus.api.communications;

import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.communications.model.Message;
import com.carenexus.api.communications.model.MessageThread;
import com.carenexus.api.communications.model.Notification;
import com.carenexus.api.communications.repository.NotificationRepository;
import com.carenexus.api.communications.service.CommunicationsAccessService;
import com.carenexus.api.communications.service.CommunicationsAccessService.Caller;
import com.carenexus.api.communications.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Optional;

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

    @InjectMocks
    private NotificationService notificationService;

    private final MessageThread thread = MessageThread.builder().threadId(41).patientSystemId(PATIENT).build();

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
            assertEquals("Urgent message", n.getTitle());
            assertEquals("HIGH", n.getPriority());
            assertEquals(41, n.getThreadId());
            assertEquals(902, n.getMessageId());
            assertEquals(PATIENT, n.getPatientSystemId());
            assertEquals(DOCTOR, n.getActorSystemId());
            assertNull(n.getActionUrl());
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
    public void list_withoutAccessibleThreads_usesPlaceholder_soMessageNotificationsAreExcluded() {
        Caller caller = new Caller("ADM-00001", "ADMIN");
        when(accessService.caller()).thenReturn(caller);
        when(accessService.accessibleThreadIds(caller)).thenReturn(List.of());
        when(notificationRepository.findVisible(eq("ADM-00001"), eq(List.of(-1)), eq(false), any())).thenReturn(Page.empty());

        notificationService.list(false, 0, 500);

        verify(notificationRepository).findVisible(eq("ADM-00001"), eq(List.of(-1)), eq(false),
                argThat(p -> p.getPageSize() == 50));
    }

    @Test
    public void markRead_otherUsersNotification_is404() {
        when(accessService.caller()).thenReturn(new Caller(CAREGIVER, "CAREGIVER"));
        when(notificationRepository.findByNotificationIdAndSystemId(7, CAREGIVER)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class, () -> notificationService.markRead(7));
        assertEquals("Notification not found", ex.getMessage());
    }
}
