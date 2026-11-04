package com.carenexus.api.communications;

import com.carenexus.api.common.event.CaregiverAccessChangedEvent;
import com.carenexus.api.communications.listener.CommunicationsAccessListener;
import com.carenexus.api.communications.service.ThreadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CommunicationsAccessListenerTest {

    @Mock
    private ThreadService threadService;

    @InjectMocks
    private CommunicationsAccessListener listener;

    @Test
    public void revokedGrant_removesCaregiverFromPatientConversations() {
        listener.onCaregiverAccessChanged(new CaregiverAccessChangedEvent(3, "PAT-00001", "CGV-00001", true, false, false));
        verify(threadService).removeCaregiverAfterAccessChange("CGV-00001", "PAT-00001");
    }

    @Test
    public void messagingTurnedOff_removesCaregiver() {
        listener.onCaregiverAccessChanged(new CaregiverAccessChangedEvent(3, "PAT-00001", "CGV-00001", false, false, true));
        verify(threadService).removeCaregiverAfterAccessChange("CGV-00001", "PAT-00001");
    }

    @Test
    public void messagingStillAllowed_changesNothing() {
        listener.onCaregiverAccessChanged(new CaregiverAccessChangedEvent(3, "PAT-00001", "CGV-00001", false, true, false));
        verify(threadService, never()).removeCaregiverAfterAccessChange(anyString(), anyString());
    }

    @Test
    public void failures_areSwallowed() {
        when(threadService.removeCaregiverAfterAccessChange(anyString(), anyString())).thenThrow(new IllegalStateException("db"));
        assertDoesNotThrow(() -> listener.onCaregiverAccessChanged(
                new CaregiverAccessChangedEvent(3, "PAT-00001", "CGV-00001", true, false, false)));
    }
}
