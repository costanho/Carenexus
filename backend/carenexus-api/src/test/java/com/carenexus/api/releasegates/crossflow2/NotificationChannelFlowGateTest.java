package com.carenexus.api.releasegates.crossflow2;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.core.service.AppointmentService;
import com.carenexus.api.delivery.job.OutboundMessageWorker;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.carenexus.api.core.appointments.AppointmentFixtures.booking;
import static com.carenexus.api.core.appointments.AppointmentFixtures.clinicDay;
import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.clinicNow;
import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.count;
import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.insertAppointment;
import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.withKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-slice gate of notification delivery: a clinical change (be-appointments-core) notifies through the publisher
 * (be-notification-fanout), whose fan-out queues outbound messages (be-foundation-delivery-storage) only for the
 * channels each recipient has switched on and can be reached on (the switches are saved through the profile settings
 * API of be-profile-settings, the push device through the device API). Quiet hours hold a message until the end of
 * the window read in the recipient's own time zone, and a change that rolls back leaves neither a notification nor an
 * outbound message, so the worker sends nothing.
 */
class NotificationChannelFlowGateTest extends CommittedIntegrationTest {

    private static final String APPOINTMENTS = "/api/appointments";
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final String BOOKED = "APPT_BOOKED:";

    @Autowired
    private OutboundMessageWorker worker;

    @Autowired
    private AppointmentService appointmentService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void appointmentCircle() {
        grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        grantAccess(otherCaregiver, patient, a -> a.setCanViewAppointments(true));
    }

    // ==================== HELPERS ====================

    private void saveSettings(User user, Map<String, Object> changes) throws Exception {
        MvcResult result = doPatch("/api/profile/settings", user, changes);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    private void registerDevice(User user, String token) throws Exception {
        MvcResult result = doPost("/api/notifications/devices", user, Map.of("platform", "ANDROID", "token", token));
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    /** The doctor books a visit for the patient (the doctor is the actor, so the patient circle is notified). */
    private int doctorBooks() throws Exception {
        MvcResult result = doPost(APPOINTMENTS, doctor,
                booking(patient.getSystemId(), doctor.getSystemId(), clinicDay(3, 10, 0), "IN_PERSON"));
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result).path("appointmentId").asInt();
    }

    private Integer notificationId(User recipient, String dedupeKey) {
        return jdbc.queryForObject("SELECT notification_id FROM notifications WHERE system_id = ? AND dedupe_key = ?",
                Integer.class, recipient.getSystemId(), dedupeKey);
    }

    /** Channels queued for the recipient's notification, in queue order. */
    private List<String> channelsOf(User recipient, String dedupeKey) {
        return jdbc.queryForList("SELECT channel FROM outbound_messages WHERE notification_id = ? ORDER BY message_id",
                String.class, notificationId(recipient, dedupeKey));
    }

    private List<Map<String, Object>> outboxOf(User recipient) {
        return jdbc.queryForList("SELECT message_id, channel, status, recipient_address, next_attempt_at, created_at "
                + "FROM outbound_messages WHERE recipient_system_id = ? ORDER BY message_id", recipient.getSystemId());
    }

    private static Instant instant(Object timestamp) {
        if (timestamp instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (timestamp instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        throw new AssertionError("Unexpected timestamp " + timestamp);
    }

    /** A three-hour quiet window that holds "now" in {@code zone}: from an hour ago until two hours ahead. */
    private static Map<String, Object> quietWindowAroundNow(ZonedDateTime nowInZone, String zone) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("quietHoursEnabled", true);
        body.put("quietHoursStart", nowInZone.minusHours(1).format(HH_MM));
        body.put("quietHoursEnd", nowInZone.plusHours(2).format(HH_MM));
        body.put("timezone", zone);
        return body;
    }

    // ==================== ENABLED CHANNELS ====================

    @Test
    void outboundMessagesAreQueuedOnlyForTheChannelsEachRecipientHasOn_andTheWorkerSendsThemOnce() throws Exception {
        saveSettings(patient, Map.of("smsNotifications", false));
        registerDevice(caregiver, "device-token-gate5-caregiver-0001");
        saveSettings(otherCaregiver, Map.of("emailNotifications", false, "smsNotifications", false,
                "pushNotifications", false));

        int appointmentId = doctorBooks();
        String key = BOOKED + appointmentId;

        // Every member of the circle gets the in-app notification once
        for (User member : List.of(patient, caregiver, otherCaregiver)) {
            assertEquals(1, withKey(jdbc, member.getSystemId(), key), member.getSystemId());
        }
        assertEquals(0, withKey(jdbc, doctor.getSystemId(), key), "the doctor is the actor");

        // External channels only where switched on and reachable
        assertEquals(List.of("EMAIL"), channelsOf(patient, key), "SMS off, no push device");
        assertEquals(List.of("EMAIL", "SMS", "PUSH"), channelsOf(caregiver, key));
        assertEquals(List.of(), channelsOf(otherCaregiver, key), "every channel switched off");
        assertEquals(patient.getEmail(), outboxOf(patient).get(0).get("recipient_address"));
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM outbound_messages WHERE recipient_system_id IN (?, ?, ?)",
                doctor.getSystemId(), otherPatient.getSystemId(), admin.getSystemId()));

        // The local providers deliver every due row once
        assertEquals(4, worker.sweepOnce());
        assertEquals(0, worker.sweepOnce());
        for (User member : List.of(patient, caregiver)) {
            for (Map<String, Object> row : outboxOf(member)) {
                assertEquals("SENT", row.get("status"), row.toString());
            }
        }
    }

    // ==================== QUIET HOURS ====================

    @Test
    void quietHoursHoldTheMessageUntilTheWindowEnds_inTheRecipientsOwnTimeZone() throws Exception {
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        ZoneId saoPaulo = ZoneId.of("America/Sao_Paulo");
        ZonedDateTime tokyoNow = ZonedDateTime.now(tokyo).truncatedTo(ChronoUnit.MINUTES);
        // The same wall-clock window for both: it holds "now" in Tokyo; in Sao Paulo (twelve hours behind) it does not
        saveSettings(patient, quietWindowAroundNow(tokyoNow, "Asia/Tokyo"));
        saveSettings(caregiver, quietWindowAroundNow(tokyoNow, "America/Sao_Paulo"));
        assertTrue(ZonedDateTime.now(saoPaulo).getHour() != tokyoNow.getHour());

        Instant before = Instant.now();
        int appointmentId = doctorBooks();
        String key = BOOKED + appointmentId;
        assertEquals(1, withKey(jdbc, patient.getSystemId(), key), "the in-app notification is never held");
        assertEquals(1, withKey(jdbc, caregiver.getSystemId(), key));

        // The patient's messages wait until the window ends in Tokyo (stored as an instant)
        Instant windowEnd = tokyoNow.plusHours(2).toInstant();
        List<Map<String, Object>> held = outboxOf(patient);
        assertEquals(List.of("EMAIL", "SMS"), held.stream().map(row -> row.get("channel")).toList());
        for (Map<String, Object> row : held) {
            assertEquals(windowEnd, instant(row.get("next_attempt_at")), row.toString());
            assertEquals("PENDING", row.get("status"));
        }
        // The caregiver, outside the window in their own zone, is due at once
        List<Map<String, Object>> due = outboxOf(caregiver);
        assertEquals(2, due.size(), due.toString());
        for (Map<String, Object> row : due) {
            Instant next = instant(row.get("next_attempt_at"));
            assertTrue(!next.isAfter(Instant.now()) && !next.isBefore(before.minusSeconds(5)), row.toString());
        }

        // The worker sends what is due (the caregiver's and the other caregiver's two each) and keeps the held rows
        assertEquals(4, worker.sweepOnce());
        for (Map<String, Object> row : outboxOf(caregiver)) {
            assertEquals("SENT", row.get("status"), row.toString());
        }
        for (Map<String, Object> row : outboxOf(patient)) {
            assertEquals("PENDING", row.get("status"), row.toString());
        }
    }

    // ==================== ROLLBACK ====================

    @Test
    void aChangeThatRollsBack_storesNoNotification_queuesNoOutboundMessage_andSendsNothing() throws Exception {
        int appointmentId = insertAppointment(jdbc, patient.getSystemId(), doctor.getSystemId(),
                clinicNow().plusDays(2).withHour(11).withMinute(0), 30, "IN_PERSON", "SCHEDULED");
        String key = "APPT_CANCELLED:" + appointmentId;

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                    // The cancellation notifies the circle inside this transaction; a later step then fails
                    appointmentService.cancelBySystem(appointmentId, "The doctor is no longer available");
                    throw new IllegalStateException("a later step of the same change failed");
                }));
        assertEquals("a later step of the same change failed", failure.getMessage());

        assertEquals("SCHEDULED", jdbc.queryForObject("SELECT status FROM appointments WHERE appointment_id = ?",
                String.class, appointmentId));
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM notifications"));
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM outbound_messages"));
        assertEquals(0, worker.sweepOnce());

        // The same change committed notifies and queues once
        appointmentService.cancelBySystem(appointmentId, "The doctor is no longer available");
        for (User member : List.of(patient, doctor, caregiver, otherCaregiver)) {
            assertEquals(1, withKey(jdbc, member.getSystemId(), key), member.getSystemId());
        }
        assertEquals(List.of("EMAIL", "SMS"), channelsOf(patient, key));
    }
}
