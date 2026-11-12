package com.carenexus.api.lifecycle.service;

import com.carenexus.api.common.event.UserDeactivatedEvent;
import com.carenexus.api.common.model.AuditLog;
import com.carenexus.api.core.dto.response.AppointmentResponse;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.service.AppointmentService;
import com.carenexus.api.core.service.CaregiverService;
import com.carenexus.api.lifecycle.repository.LifecycleAccountRepository;
import com.carenexus.api.lifecycle.repository.LifecycleAppointmentRepository;
import com.carenexus.api.lifecycle.repository.LifecycleAuditRepository;
import com.carenexus.api.lifecycle.repository.LifecycleGrantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The downstream clean-up of a deactivated or closed account (api/be-account-lifecycle.md 5.2). It runs once per
 * {@link UserDeactivatedEvent}, after the deactivating transaction committed:
 * <table>
 *   <caption>Steps by role and cause</caption>
 *   <tr><th>Role</th><th>Cause</th><th>Steps, in order</th></tr>
 *   <tr><td>DOCTOR</td><td>any</td><td>APPOINTMENTS (as doctor), REFERRALS (count only)</td></tr>
 *   <tr><td>CAREGIVER</td><td>any</td><td>GRANTS (as caregiver), ACCESS_REQUESTS (as caregiver)</td></tr>
 *   <tr><td>PATIENT</td><td>CLOSURE</td><td>APPOINTMENTS, GRANTS, REMINDERS, ACCESS_REQUESTS (as patient)</td></tr>
 *   <tr><td>PATIENT</td><td>ADMIN, SUSPENSION</td><td>none: an administrative deactivation only blocks sign-in</td></tr>
 *   <tr><td>ADMIN, unknown</td><td>any</td><td>none</td></tr>
 * </table>
 * Every run, also one without steps, ends with exactly one {@code ACCOUNT_DEACTIVATION_EFFECTS} audit row carrying the
 * counts. Clinical records, messages, invoices, care-team rows and audit rows are never touched.
 * <p>
 * Every unit (one appointment, one grant, one native statement, one page read, the audit row) runs in its own
 * {@code REQUIRES_NEW} transaction: a unit that throws is rolled back alone, logged at ERROR with the step, the row id
 * and the exception class (never a message), counted as failed, and the run continues. A throwable that still
 * escapes a step (an {@link Error}) ends that step only: it is counted as one failed unit of the step, the later
 * steps run, and the audit row is written in a {@code finally} block. A guard that is false (a table
 * or column of a later migration that does not exist) skips the step with count 0 and is not a failure. A repeated
 * event is harmless: every action is a no-op on rows already cancelled, revoked, inactive or no longer pending.
 * <p>
 * There is no caller authorisation: the only caller is the lifecycle listener, acting for the administrator or the
 * user whose request published the event; the services it calls write their own audit rows for that caller.
 */
@Slf4j
@Service
public class AccountDeactivationEffectsService {

    public static final String AUDIT_ACTION = "ACCOUNT_DEACTIVATION_EFFECTS";
    public static final String AUDIT_RESOURCE_TYPE = "ACCOUNT";

    /** Reason passed to cancelBySystem for a deactivated or closed doctor (shown verbatim to the patient circle). */
    public static final String DOCTOR_UNAVAILABLE_REASON = "The doctor is no longer available";
    /** Reason passed to cancelBySystem for a closed patient account. */
    public static final String PATIENT_CLOSED_REASON = "The patient's CareNexus account was closed";

    static final int PAGE_SIZE = 200;
    private static final int MAX_SUMMARY_LENGTH = 500;
    private static final List<String> ACTIVE_APPOINTMENT_STATUSES =
            List.of(AppointmentService.SCHEDULED, AppointmentService.RESCHEDULED);

    private static final String ROLE_DOCTOR = "DOCTOR";
    private static final String ROLE_PATIENT = "PATIENT";
    private static final String ROLE_CAREGIVER = "CAREGIVER";

    private final AppointmentService appointmentService;
    private final CaregiverService caregiverService;
    private final LifecycleAppointmentRepository appointmentRepository;
    private final LifecycleGrantRepository grantRepository;
    private final LifecycleAccountRepository accountRepository;
    private final LifecycleAuditRepository auditRepository;
    private final Clock clock;
    private final TransactionTemplate unitTx;
    private final TransactionTemplate readTx;

    public AccountDeactivationEffectsService(AppointmentService appointmentService,
                                             CaregiverService caregiverService,
                                             LifecycleAppointmentRepository appointmentRepository,
                                             LifecycleGrantRepository grantRepository,
                                             LifecycleAccountRepository accountRepository,
                                             LifecycleAuditRepository auditRepository,
                                             PlatformTransactionManager transactionManager,
                                             Clock clock) {
        this.appointmentService = appointmentService;
        this.caregiverService = caregiverService;
        this.appointmentRepository = appointmentRepository;
        this.grantRepository = grantRepository;
        this.accountRepository = accountRepository;
        this.auditRepository = auditRepository;
        this.clock = clock;
        this.unitTx = new TransactionTemplate(transactionManager);
        this.unitTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx.setReadOnly(true);
    }

    /**
     * One clean-up run for the event, then its audit row. Never throws for a failed unit; the result carries the
     * counts and the failures. Returns null (and does nothing) for an event without a system id.
     */
    public DeactivationEffects run(UserDeactivatedEvent event) {
        String systemId = trimToNull(event == null ? null : event.systemId());
        if (systemId == null) {
            log.warn("Account deactivation effects skipped: the event has no system id");
            return null;
        }
        String cause = normalizeCause(event.cause());
        String role = resolveRole(systemId, event.role());
        DeactivationEffects effects = new DeactivationEffects(cause, role);

        String actor = event.actorSystemId();
        try {
            switch (role) {
                case ROLE_DOCTOR -> {
                    step(effects, systemId, DeactivationStep.APPOINTMENTS,
                            () -> cancelAppointments(effects, systemId, true, DOCTOR_UNAVAILABLE_REASON));
                    step(effects, systemId, DeactivationStep.REFERRALS,
                            () -> countPendingReferrals(effects, systemId));
                }
                case ROLE_CAREGIVER -> {
                    step(effects, systemId, DeactivationStep.GRANTS,
                            () -> revokeGrants(effects, systemId, actor, true));
                    step(effects, systemId, DeactivationStep.ACCESS_REQUESTS,
                            () -> cancelAccessRequests(effects, systemId, true));
                }
                case ROLE_PATIENT -> {
                    if (UserDeactivatedEvent.CLOSURE.equals(cause)) {
                        // Appointments first, so the caregivers are still told about the cancellations.
                        step(effects, systemId, DeactivationStep.APPOINTMENTS,
                                () -> cancelAppointments(effects, systemId, false, PATIENT_CLOSED_REASON));
                        step(effects, systemId, DeactivationStep.GRANTS,
                                () -> revokeGrants(effects, systemId, actor, false));
                        step(effects, systemId, DeactivationStep.REMINDERS,
                                () -> deactivateReminders(effects, systemId));
                        step(effects, systemId, DeactivationStep.ACCESS_REQUESTS,
                                () -> cancelAccessRequests(effects, systemId, false));
                    }
                }
                default -> { }
            }
        } finally {
            // Exactly one audit row per event, whatever escaped the steps.
            writeAuditRow(event, systemId, effects);
        }
        return effects;
    }

    /**
     * Runs one step. The step's units catch their own exceptions; anything that still escapes (an {@link Error}, or a
     * checked exception thrown sneakily) is counted as one failed unit of this step, and the run goes on with the
     * next step.
     */
    private static void step(DeactivationEffects effects, String systemId, DeactivationStep step, Runnable body) {
        try {
            body.run();
        } catch (Throwable escaped) {
            unitFailed(effects, step, systemId, null, escaped);
        }
    }

    // ==================== STEPS ====================

    private void cancelAppointments(DeactivationEffects effects, String systemId, boolean asDoctor, String reason) {
        LocalDateTime afterAt = LocalDateTime.now(clock);
        int afterId = Integer.MAX_VALUE; // with afterAt = now: every appointment that starts after now
        while (true) {
            LocalDateTime cursorAt = afterAt;
            int cursorId = afterId;
            List<Appointment> page = readPage(effects, DeactivationStep.APPOINTMENTS, systemId, () -> asDoctor
                    ? appointmentRepository.findActiveAsDoctorAfter(systemId, ACTIVE_APPOINTMENT_STATUSES,
                            cursorAt, cursorId, PageRequest.of(0, PAGE_SIZE))
                    : appointmentRepository.findActiveAsPatientAfter(systemId, ACTIVE_APPOINTMENT_STATUSES,
                            cursorAt, cursorId, PageRequest.of(0, PAGE_SIZE)));
            if (page == null) {
                return;
            }
            for (Appointment appointment : page) {
                Integer appointmentId = appointment.getAppointmentId();
                try {
                    AppointmentResponse result = unitTx.execute(status ->
                            appointmentService.cancelBySystem(appointmentId, reason));
                    if (result != null && AppointmentService.CANCELLED.equals(result.getStatus())) {
                        effects.appointmentCancelled();
                    }
                } catch (Exception failure) {
                    unitFailed(effects, DeactivationStep.APPOINTMENTS, systemId, appointmentId, failure);
                }
            }
            if (page.size() < PAGE_SIZE) {
                return;
            }
            Appointment last = page.get(page.size() - 1);
            afterAt = last.getScheduledAt();
            afterId = last.getAppointmentId();
        }
    }

    private void revokeGrants(DeactivationEffects effects, String systemId, String actorSystemId, boolean asCaregiver) {
        String caller = trimToNull(actorSystemId) != null ? actorSystemId.trim() : systemId;
        int afterId = 0;
        while (true) {
            int cursor = afterId;
            List<Integer> page = readPage(effects, DeactivationStep.GRANTS, systemId, () -> asCaregiver
                    ? grantRepository.findUnrevokedAsCaregiverAfter(systemId, cursor, PageRequest.of(0, PAGE_SIZE))
                    : grantRepository.findUnrevokedAsPatientAfter(systemId, cursor, PageRequest.of(0, PAGE_SIZE)));
            if (page == null) {
                return;
            }
            for (Integer accessId : page) {
                try {
                    unitTx.executeWithoutResult(status ->
                            caregiverService.revokeCaregiverAccessAsPatient(caller, true, accessId));
                    effects.grantRevoked();
                } catch (Exception failure) {
                    unitFailed(effects, DeactivationStep.GRANTS, systemId, accessId, failure);
                }
            }
            if (page.size() < PAGE_SIZE) {
                return;
            }
            afterId = page.get(page.size() - 1);
        }
    }

    private void deactivateReminders(DeactivationEffects effects, String systemId) {
        try {
            Boolean[] columns = readTx.execute(status -> new Boolean[]{
                    accountRepository.columnExists("reminders", "recipient_system_id"),
                    accountRepository.columnExists("reminders", "next_due_at")});
            if (columns == null || !Boolean.TRUE.equals(columns[0])) {
                return; // be-reminders (V54) not migrated: nothing is addressed by recipient yet
            }
            boolean withNextDue = Boolean.TRUE.equals(columns[1]);
            Integer updated = unitTx.execute(status -> withNextDue
                    ? accountRepository.deactivateRemindersOfRecipient(systemId)
                    : accountRepository.deactivateRemindersOfRecipientWithoutNextDue(systemId));
            effects.remindersDeactivated(updated == null ? 0 : updated);
        } catch (Exception failure) {
            unitFailed(effects, DeactivationStep.REMINDERS, systemId, null, failure);
        }
    }

    private void cancelAccessRequests(DeactivationEffects effects, String systemId, boolean asCaregiver) {
        try {
            Boolean present = readTx.execute(status -> accountRepository.tableExists("caregiver_access_requests"));
            if (!Boolean.TRUE.equals(present)) {
                return; // be-caregiver-requests-consent (V44) not migrated
            }
            Integer updated = unitTx.execute(status -> asCaregiver
                    ? accountRepository.cancelPendingAccessRequestsOfCaregiver(systemId)
                    : accountRepository.cancelPendingAccessRequestsOfPatient(systemId));
            effects.accessRequestsCancelled(updated == null ? 0 : updated);
        } catch (Exception failure) {
            unitFailed(effects, DeactivationStep.ACCESS_REQUESTS, systemId, null, failure);
        }
    }

    private void countPendingReferrals(DeactivationEffects effects, String systemId) {
        try {
            Long pending = readTx.execute(status -> accountRepository.countPendingReferralsAsSpecialist(systemId));
            effects.pendingReferrals(pending == null ? 0 : pending);
        } catch (Exception failure) {
            unitFailed(effects, DeactivationStep.REFERRALS, systemId, null, failure);
        }
    }

    // ==================== AUDIT ====================

    private void writeAuditRow(UserDeactivatedEvent event, String systemId, DeactivationEffects effects) {
        String actor = trimToNull(event.actorSystemId());
        String summary = truncate(effects.summary());
        String status = effects.anyFailed() ? "FAILURE" : "SUCCESS";
        try {
            unitTx.executeWithoutResult(tx -> auditRepository.save(AuditLog.builder()
                    .systemId(actor)
                    .actorRole(actor == null ? null : actorRole(actor))
                    .action(AUDIT_ACTION)
                    .resourceType(AUDIT_RESOURCE_TYPE)
                    .resourceId(null)
                    .targetSystemId(systemId)
                    .patientSystemId(systemId.startsWith("PAT-") ? systemId : null)
                    .status(status)
                    .errorMessage(summary)
                    .timestamp(LocalDateTime.now(clock))
                    .build()));
        } catch (Exception failure) {
            log.error("Account deactivation effects for {}: audit row not written ({})",
                    systemId, failure.getClass().getName());
        }
        if (effects.anyFailed()) {
            log.warn("Account deactivation effects for {} finished with failures: {}", systemId, summary);
        } else {
            log.info("Account deactivation effects for {}: {}", systemId, summary);
        }
    }

    /** The actor's stored role; for an id that is not (or no longer) stored, the role its prefix stands for. */
    private String actorRole(String actorSystemId) {
        return accountRepository.findRole(actorSystemId)
                .map(role -> role.trim().toUpperCase(Locale.ROOT))
                .orElseGet(() -> roleFromPrefix(actorSystemId));
    }

    // ==================== HELPERS ====================

    /** One page read in its own read-only transaction; null (and one failed unit) when it throws. */
    private <T> List<T> readPage(DeactivationEffects effects, DeactivationStep step, String systemId,
                                 Supplier<List<T>> query) {
        try {
            List<T> page = readTx.execute(status -> query.get());
            return page == null ? List.of() : page;
        } catch (Exception failure) {
            unitFailed(effects, step, systemId, null, failure);
            return null;
        }
    }

    private static void unitFailed(DeactivationEffects effects, DeactivationStep step, String systemId,
                                   Integer rowId, Throwable failure) {
        effects.failed(step);
        log.error("Account deactivation effects for {}: step {} failed for row {} ({})",
                systemId, step, rowId == null ? "-" : rowId, failure.getClass().getName());
    }

    /** Role from the event, else the stored one; trimmed and upper-cased. */
    private String resolveRole(String systemId, String eventRole) {
        String role = trimToNull(eventRole);
        if (role != null) {
            return role.toUpperCase(Locale.ROOT);
        }
        try {
            String stored = readTx.execute(status -> accountRepository.findRole(systemId).orElse(null));
            if (stored != null && !stored.isBlank()) {
                return stored.trim().toUpperCase(Locale.ROOT);
            }
        } catch (Exception failure) {
            log.error("Account deactivation effects for {}: role lookup failed ({})",
                    systemId, failure.getClass().getName());
        }
        String fromPrefix = roleFromPrefix(systemId);
        return fromPrefix != null ? fromPrefix : "UNKNOWN";
    }

    /** Trimmed, upper-cased cause; {@code SUSPENSION} is kept as reported, a missing cause counts as ADMIN. */
    private static String normalizeCause(String cause) {
        String value = trimToNull(cause);
        return value == null ? UserDeactivatedEvent.ADMIN : value.toUpperCase(Locale.ROOT);
    }

    private static String roleFromPrefix(String systemId) {
        if (systemId.startsWith("PAT-")) return ROLE_PATIENT;
        if (systemId.startsWith("DOC-")) return ROLE_DOCTOR;
        if (systemId.startsWith("CGV-")) return ROLE_CAREGIVER;
        if (systemId.startsWith("ADM-")) return "ADMIN";
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value) {
        return value.length() <= MAX_SUMMARY_LENGTH ? value : value.substring(0, MAX_SUMMARY_LENGTH);
    }
}
