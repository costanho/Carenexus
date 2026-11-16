package com.carenexus.api.reminders.service;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.core.service.ConsultationService;
import com.carenexus.api.reminders.dto.request.CreateReminderRequest;
import com.carenexus.api.reminders.dto.request.UpdateReminderRequest;
import com.carenexus.api.reminders.dto.response.ReminderResponse;
import com.carenexus.api.reminders.model.Reminder;
import com.carenexus.api.reminders.repository.ReminderJdbc;
import com.carenexus.api.reminders.repository.ReminderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Reminders addressed to a patient: list, create (manual), edit, pause and resume, delete (manual).
 *
 * Who may do what (every public method checks the current caller first):
 * <ul>
 *   <li>list: {@code requirePatientAccess(patient, PROFILE)}: the patient, a related doctor, a caregiver with any
 *       active grant, an admin;</li>
 *   <li>create: the patient for themself, a caregiver whose grant gives MANAGE_CARE_PLAN, a treating doctor
 *       ({@link ConsultationService#requireTreatingDoctor}); never an admin;</li>
 *   <li>edit, pause, resume: {@code requirePatientAccess(patient, MANAGE_CARE_PLAN)}; automatic reminders accept
 *       only pause and resume;</li>
 *   <li>delete: the same access, then a doctor or caregiver must also be the creator; manual reminders only.</li>
 * </ul>
 * Reminders addressed to someone other than the patient (doctor review reminders) are managed by events only and
 * answer 404 here. Every successful read and write is audited with resource {@value #RESOURCE}. Times are clinic
 * wall-clock ({@link Clock} in {@code carenexus.scheduling.time-zone}).
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ReminderService {

    public static final String RESOURCE = "REMINDER";

    /** Active manual reminders one patient may have (checked on create). */
    public static final int MAX_ACTIVE_MANUAL = 50;

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_ENDED = "ENDED";

    static final String NOT_FOUND_MESSAGE = "Reminder not found";
    static final String DELETE_DENIED_MESSAGE =
            "Only the person who created this reminder, the patient or an administrator can delete it";
    static final String AUTOMATIC_EDIT_MESSAGE = "Automatic reminders can only be paused or resumed";
    static final String AUTOMATIC_DELETE_MESSAGE = "Automatic reminders cannot be deleted. Pause them instead.";
    static final String ENDED_MESSAGE = "This reminder has ended. It has no upcoming time to resume.";
    static final String NO_UPCOMING_MESSAGE = "The reminder has no upcoming time. Choose a later date or time.";
    static final String CAP_MESSAGE =
            "This patient already has 50 active reminders. Pause or delete one before adding another.";
    static final String TYPE_MESSAGE = "type: must be one of MEDICATION, APPOINTMENT, LAB_FOLLOWUP, CARE_PLAN, "
            + "CHECK_IN, VITAL_SIGN";
    static final String FREQUENCY_MESSAGE = "frequency: must be one of ONCE, DAILY, WEEKLY, MONTHLY";
    static final String END_BEFORE_START_MESSAGE = "endDate: must not be before startDate";
    static final String END_SET_AND_CLEARED_MESSAGE = "endDate: cannot be set and cleared in the same request";
    static final String EMPTY_PATCH_MESSAGE = "Provide at least one field to change";

    private final ReminderRepository reminderRepository;
    private final ReminderJdbc reminderJdbc;
    private final AuthorizationService authorizationService;
    private final ConsultationService consultationService;
    private final AuditService auditService;
    private final Clock clock;

    // ==================== LIST ====================

    /**
     * A page of the reminders whose patient and recipient are {@code patientSystemId}: active first, soonest next
     * due time, newest id. {@code active} true = active only, false = paused and ended only, null = all.
     */
    @Transactional(readOnly = true)
    public PageResponse<ReminderResponse> listForPatient(String patientSystemId, Boolean active, int page, int size) {
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.PROFILE);
        Page<Reminder> result = reminderRepository.findForPatient(patientSystemId, active,
                PageParams.of(page, size, Sort.unsorted()));
        boolean mayManage = authorizationService.canAccessPatient(patientSystemId, PatientPermission.MANAGE_CARE_PLAN);
        String caller = authorizationService.currentSystemId();
        String role = authorizationService.currentRole();
        LocalDateTime now = now();
        auditService.logListRead(RESOURCE, result.getContent(), Reminder::getPatientSystemId);
        return PageResponse.of(result, result.getContent().stream()
                .map(reminder -> toResponse(reminder, now, canDelete(reminder, mayManage, caller, role)))
                .toList());
    }

    // ==================== CREATE ====================

    /**
     * Creates an active manual reminder for the patient (who is also the recipient). Check order: caller (403/404),
     * type and frequency, end date, an upcoming occurrence (400), the per-patient cap (409).
     */
    public ReminderResponse create(CreateReminderRequest request) {
        String patientSystemId = request.patientSystemId();
        requireCreator(patientSystemId);

        String type = requireType(request.type());
        String frequency = requireFrequency(request.frequency());
        LocalTime time = LocalTime.parse(request.reminderTime());
        LocalDate endDate = Reminder.FREQUENCY_ONCE.equals(frequency) ? null : request.endDate();
        if (endDate != null && endDate.isBefore(request.startDate())) {
            throw validation(END_BEFORE_START_MESSAGE);
        }
        LocalDateTime now = now();
        LocalDateTime nextDueAt = new ReminderSchedule(frequency, request.startDate(), endDate, time)
                .firstAtOrAfter(now)
                .orElseThrow(() -> validation(NO_UPCOMING_MESSAGE));

        reminderJdbc.lockPatientReminders(patientSystemId);
        if (reminderRepository.countActiveManual(patientSystemId) >= MAX_ACTIVE_MANUAL) {
            throw new ConflictException(CAP_MESSAGE);
        }

        String caller = authorizationService.currentSystemId();
        Reminder reminder = reminderRepository.save(Reminder.builder()
                .patientSystemId(patientSystemId)
                .recipientSystemId(patientSystemId)
                .createdBySystemId(caller)
                .type(type)
                .title(request.title())
                .message(request.message())
                .reminderTime(time)
                .frequency(frequency)
                .startDate(request.startDate())
                .endDate(endDate)
                .isActive(true)
                .nextDueAt(nextDueAt)
                .sourceType(Reminder.SOURCE_MANUAL)
                .createdAt(now)
                .build());
        auditService.logClinicalAccess("CREATE", RESOURCE, reminder.getReminderId(), patientSystemId);
        // The creator may always delete their own manual reminder while their access lasts
        return toResponse(reminder, now, true);
    }

    // ==================== UPDATE ====================

    /**
     * Partial update on the merged state. Check order: 404, 403, 409 (automatic), 400 (service rules), then 409
     * (ended) or 400 (no upcoming time). A request that changes nothing returns the current state.
     */
    public ReminderResponse update(Integer reminderId, UpdateReminderRequest request) {
        Reminder reminder = lockForPatient(reminderId);
        String patientSystemId = reminder.getPatientSystemId();
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.MANAGE_CARE_PLAN, RESOURCE,
                reminderId);
        if (!reminder.isManual() && request.touchesContent()) {
            throw new ConflictException(AUTOMATIC_EDIT_MESSAGE);
        }
        if (request.isEmpty()) {
            throw validation(EMPTY_PATCH_MESSAGE);
        }
        boolean clearEnd = Boolean.TRUE.equals(request.clearEndDate());
        if (clearEnd && request.endDate() != null) {
            throw validation(END_SET_AND_CLEARED_MESSAGE);
        }

        // Merged state
        String type = request.type() == null ? reminder.getType() : requireType(request.type());
        String frequency = request.frequency() == null ? reminder.getFrequency() : requireFrequency(request.frequency());
        String title = request.title() == null ? reminder.getTitle() : request.title();
        String message = request.message() == null ? reminder.getMessage() : request.message();
        LocalTime time = request.reminderTime() == null ? reminder.getReminderTime()
                : LocalTime.parse(request.reminderTime());
        LocalDate startDate = request.startDate() == null ? reminder.getStartDate() : request.startDate();
        LocalDate endDate = clearEnd ? null : (request.endDate() == null ? reminder.getEndDate() : request.endDate());
        if (Reminder.FREQUENCY_ONCE.equals(frequency)) {
            endDate = null;
        } else if (endDate != null && endDate.isBefore(startDate)) {
            throw validation(END_BEFORE_START_MESSAGE);
        }

        boolean wasActive = reminder.active();
        boolean willBeActive = request.isActive() == null ? wasActive : request.isActive();
        boolean scheduleChanged = !Objects.equals(time, reminder.getReminderTime())
                || !Objects.equals(frequency, reminder.getFrequency())
                || !Objects.equals(startDate, reminder.getStartDate())
                || !Objects.equals(endDate, reminder.getEndDate());
        boolean textChanged = !Objects.equals(type, reminder.getType())
                || !Objects.equals(title, reminder.getTitle())
                || !Objects.equals(message, reminder.getMessage());

        LocalDateTime now = now();
        LocalDateTime nextDueAt = reminder.getNextDueAt();
        if (!willBeActive) {
            nextDueAt = null;
        } else if (!wasActive || scheduleChanged) {
            ReminderSchedule schedule = new ReminderSchedule(frequency, startDate, endDate, time);
            if (!wasActive && !scheduleChanged
                    && !ReminderSchedule.of(reminder).hasOccurrenceAtOrAfter(now)) {
                throw new ConflictException(ENDED_MESSAGE);
            }
            Optional<LocalDateTime> next = schedule.firstAtOrAfter(now);
            if (next.isEmpty()) {
                throw scheduleChanged ? validation(NO_UPCOMING_MESSAGE) : new ConflictException(ENDED_MESSAGE);
            }
            nextDueAt = next.get();
        }

        boolean changed = scheduleChanged || textChanged || wasActive != willBeActive
                || !Objects.equals(nextDueAt, reminder.getNextDueAt());
        if (changed) {
            reminder.setType(type);
            reminder.setTitle(title);
            reminder.setMessage(message);
            reminder.setReminderTime(time);
            reminder.setFrequency(frequency);
            reminder.setStartDate(startDate);
            reminder.setEndDate(endDate);
            reminder.setIsActive(willBeActive);
            reminder.setNextDueAt(nextDueAt);
            reminder = reminderRepository.save(reminder);
            auditService.logClinicalAccess("UPDATE", RESOURCE, reminderId, patientSystemId);
        }
        return toResponse(reminder, now, canDelete(reminder, true, authorizationService.currentSystemId(),
                authorizationService.currentRole()));
    }

    // ==================== DELETE ====================

    /**
     * Deletes a manual reminder. Check order: 404, 403 (patient access), 409 (automatic), 403 (a doctor or caregiver
     * who is not the creator).
     */
    public void delete(Integer reminderId) {
        Reminder reminder = lockForPatient(reminderId);
        String patientSystemId = reminder.getPatientSystemId();
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.MANAGE_CARE_PLAN, RESOURCE,
                reminderId);
        if (!reminder.isManual()) {
            throw new ConflictException(AUTOMATIC_DELETE_MESSAGE);
        }
        String caller = authorizationService.currentSystemId();
        String role = authorizationService.currentRole();
        if (!mayDeleteAsCaller(reminder, caller, role)) {
            try {
                auditService.logAccessDenied(caller, RESOURCE, reminderId, patientSystemId, null,
                        "Caller did not create this reminder");
            } catch (RuntimeException auditFailure) {
                log.error("Failed to write ACCESS_DENIED audit row: {}", auditFailure.getClass().getName());
            }
            throw new AuthorizationException(DELETE_DENIED_MESSAGE, RESOURCE, reminderId);
        }
        reminderRepository.delete(reminder);
        auditService.logClinicalAccess("DELETE", RESOURCE, reminderId, patientSystemId);
    }

    // ==================== CHECKS ====================

    /** The reminder with a row lock; 404 when missing or not addressed to its patient. */
    private Reminder lockForPatient(Integer reminderId) {
        return reminderRepository.findByIdForUpdate(reminderId)
                .filter(Reminder::addressedToPatient)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_MESSAGE));
    }

    /**
     * Who may create a reminder for the patient: the patient themself and a caregiver whose grant gives
     * MANAGE_CARE_PLAN (patient-access check), or a treating doctor. Admins and other roles get 403.
     */
    private void requireCreator(String patientSystemId) {
        authorizationService.requireRole(AuthorizationService.PATIENT, AuthorizationService.CAREGIVER,
                AuthorizationService.DOCTOR);
        if (AuthorizationService.DOCTOR.equals(authorizationService.currentRole())) {
            consultationService.requireTreatingDoctor(patientSystemId, null, PatientPermission.MANAGE_CARE_PLAN);
            return;
        }
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.MANAGE_CARE_PLAN);
    }

    /**
     * Whether endpoint 4 would succeed for the caller: manual, access with MANAGE_CARE_PLAN, and for a doctor or
     * caregiver also the creator.
     */
    private static boolean canDelete(Reminder reminder, boolean mayManage, String caller, String role) {
        return mayManage && reminder.isManual() && mayDeleteAsCaller(reminder, caller, role);
    }

    private static boolean mayDeleteAsCaller(Reminder reminder, String caller, String role) {
        if (AuthorizationService.PATIENT.equals(role) || AuthorizationService.ADMIN.equals(role)) {
            return true;
        }
        return caller != null && caller.equals(reminder.getCreatedBySystemId());
    }

    static String requireType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!Reminder.TYPES.contains(type)) {
            throw validation(TYPE_MESSAGE);
        }
        return type;
    }

    static String requireFrequency(String value) {
        String frequency = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!Reminder.FREQUENCIES.contains(frequency)) {
            throw validation(FREQUENCY_MESSAGE);
        }
        return frequency;
    }

    private static BadRequestException validation(String message) {
        return new BadRequestException(ErrorResponse.VALIDATION_FAILED, message);
    }

    // ==================== MAPPING ====================

    /** ACTIVE, or for a stored inactive flag PAUSED when an occurrence is still ahead, otherwise ENDED. */
    static String status(Reminder reminder, LocalDateTime now) {
        if (reminder.active()) {
            return STATUS_ACTIVE;
        }
        return ReminderSchedule.of(reminder).hasOccurrenceAtOrAfter(now) ? STATUS_PAUSED : STATUS_ENDED;
    }

    private static ReminderResponse toResponse(Reminder reminder, LocalDateTime now, boolean canDelete) {
        return new ReminderResponse(
                reminder.getReminderId(),
                reminder.getPatientSystemId(),
                reminder.getType(),
                reminder.getTitle(),
                reminder.getMessage(),
                ReminderText.TIME.format(reminder.getReminderTime()),
                reminder.getFrequency(),
                reminder.getStartDate(),
                reminder.getEndDate(),
                reminder.active(),
                status(reminder, now),
                reminder.getNextDueAt(),
                reminder.getLastSentAt(),
                reminder.getSourceType(),
                reminder.getSourceId(),
                ReminderText.sourceLabel(reminder.getSourceType()),
                reminder.getCreatedBySystemId(),
                canDelete,
                reminder.getCreatedAt());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
