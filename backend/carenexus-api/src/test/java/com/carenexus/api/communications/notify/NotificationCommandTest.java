package com.carenexus.api.communications.notify;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationCommandTest {

    @Test
    void builder_defaultsPriorityToNormal_andParamsToAnEmptyMap() {
        NotificationCommand command = NotificationCommand.builder()
                .recipientSystemId("DOC-00001")
                .type(NotificationType.APPOINTMENT)
                .category(NotificationCategory.APPOINTMENT_CHANGE)
                .title("New appointment")
                .message("Thomas Dube booked an appointment")
                .build();

        assertEquals(NotificationPriority.NORMAL, command.priority());
        assertTrue(command.params().isEmpty());
        assertNull(command.target());
        assertNull(command.dedupeKey());
        assertNull(command.expiresAt());

        // The canonical constructor applies the same defaults
        NotificationCommand direct = new NotificationCommand("DOC-00001", NotificationType.SYSTEM,
                NotificationCategory.SYSTEM, null, "t", "m", null, null, null, null, null, null, null, null);
        assertEquals(NotificationPriority.NORMAL, direct.priority());
        assertTrue(direct.params().isEmpty());
        assertEquals(NotificationPriority.NORMAL, NotificationCommand.builder().priority(null).build().priority());
    }

    @Test
    void params_areCopied_keepTheirOrder_dropNulls_andCannotBeChanged() {
        Map<String, String> source = new HashMap<>();
        source.put("tab", "reminders");
        source.put("ignored", null);
        NotificationCommand command = NotificationCommand.builder()
                .params(source)
                .param("thread", "77")
                .param("skipped", null)
                .param(null, "x")
                .build();
        source.put("later", "1");

        assertEquals(List.of("tab", "thread"), List.copyOf(command.params().keySet()));
        assertEquals("reminders", command.params().get("tab"));
        assertThrows(UnsupportedOperationException.class, () -> command.params().put("a", "b"));
    }

    @Test
    void toBuilder_copiesEveryField_soATemplateCanBeReaddressed() {
        LocalDateTime expires = LocalDateTime.of(2026, 11, 12, 9, 0);
        NotificationCommand template = NotificationCommand.builder()
                .recipientSystemId("PAT-00001")
                .type(NotificationType.LAB_RESULT)
                .category(NotificationCategory.LAB_RESULT)
                .priority(NotificationPriority.HIGH)
                .title("New lab result")
                .message("A lab result is available")
                .target(NotificationTarget.LAB_RESULTS)
                .param("tab", "labs")
                .resourceType("LAB_RESULT")
                .resourceId(88)
                .patientSystemId("PAT-00001")
                .actorSystemId("DOC-00001")
                .dedupeKey("LAB_RESULT:88")
                .expiresAt(expires)
                .build();

        assertEquals(template, template.toBuilder().build());

        NotificationCommand forCaregiver = template.toBuilder().recipientSystemId("CGV-00001").build();
        assertEquals("CGV-00001", forCaregiver.recipientSystemId());
        assertEquals("PAT-00001", template.recipientSystemId(), "the template is not changed");
        assertEquals(template.dedupeKey(), forCaregiver.dedupeKey());
        assertEquals(template.params(), forCaregiver.params());
        assertEquals(expires, forCaregiver.expiresAt());
    }
}
