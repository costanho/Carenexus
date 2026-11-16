package com.carenexus.api.careplans.service;

import com.carenexus.api.careplans.dto.request.CarePlanGoalRequest;
import com.carenexus.api.careplans.dto.request.CarePlanGoalUpdateRequest;
import com.carenexus.api.careplans.dto.request.CarePlanInterventionRequest;
import com.carenexus.api.careplans.dto.request.CarePlanReviewRequest;
import com.carenexus.api.careplans.dto.request.CarePlanStatusRequest;
import com.carenexus.api.careplans.dto.request.CreateCarePlanRequest;
import com.carenexus.api.careplans.dto.request.GoalProgressRequest;
import com.carenexus.api.careplans.dto.request.UpdateCarePlanRequest;
import com.carenexus.api.careplans.dto.response.CarePlanGoalResponse;
import com.carenexus.api.careplans.dto.response.CarePlanInterventionResponse;
import com.carenexus.api.careplans.dto.response.CarePlanResponse;
import com.carenexus.api.careplans.model.CarePlan;
import com.carenexus.api.careplans.model.CarePlanGoal;
import com.carenexus.api.careplans.model.CarePlanIntervention;
import com.carenexus.api.careplans.repository.CarePlanGoalRepository;
import com.carenexus.api.careplans.repository.CarePlanInterventionRepository;
import com.carenexus.api.careplans.repository.CarePlanRepository;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.event.CarePlanChangedEvent;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.communications.notify.NotificationCategory;
import com.carenexus.api.communications.notify.NotificationCommand;
import com.carenexus.api.communications.notify.NotificationPriority;
import com.carenexus.api.communications.notify.NotificationPublisher;
import com.carenexus.api.communications.notify.NotificationTarget;
import com.carenexus.api.communications.notify.NotificationType;
import com.carenexus.api.core.service.ConsultationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Care plans: a doctor-authored plan for one patient, with ordered goals and interventions, a next review date and a
 * status (ACTIVE, ON_HOLD, COMPLETED, DISCONTINUED; the last two are terminal and make the plan read-only).
 *
 * Who may do what (every public method checks the current caller first):
 * <ul>
 *   <li>create: a treating doctor ({@link ConsultationService#requireTreatingDoctor}); the caller is the author;</li>
 *   <li>read: {@code requirePatientAccess(patient, CARE_PLANS)}: the patient, a related doctor, a caregiver whose
 *       grant gives CARE_PLANS, an admin; the doctor list is the calling doctor's own plans;</li>
 *   <li>edit, status, review: the author doctor only;</li>
 *   <li>goal progress: the patient, a caregiver with MANAGE_CARE_PLAN, or the author doctor; only the doctor may set
 *       DROPPED or touch a dropped goal.</li>
 * </ul>
 * Create, edit, review and a real status change publish {@link CarePlanChangedEvent} and notify the patient circle
 * (category CARE_PLAN, target CARE_PLANS). Goal progress publishes nothing. Every successful read and write is
 * audited with resource {@value #RESOURCE}.
 *
 * Times are clinic wall-clock ({@link Clock} in {@code carenexus.scheduling.time-zone}).
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class CarePlanService {

    public static final String RESOURCE = "CARE_PLAN";

    static final String TERMINAL_MESSAGE = "A completed or discontinued care plan cannot be changed";
    static final String NOT_FOUND_MESSAGE = "Care plan not found";
    static final String GOAL_NOT_FOUND_MESSAGE = "Goal not found";
    static final String DROPPED_GOAL_MESSAGE = "Only the doctor can drop a goal or change a dropped goal";
    static final String PLAN_STATUS_VALUES = "ACTIVE, ON_HOLD, COMPLETED, DISCONTINUED";

    /** Fixed list order: newest first, deterministic. */
    static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("planId"));

    /** Plan status transitions (same status is a no-op handled before this table is consulted). */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            CarePlan.ACTIVE, Set.of(CarePlan.ON_HOLD, CarePlan.COMPLETED, CarePlan.DISCONTINUED),
            CarePlan.ON_HOLD, Set.of(CarePlan.ACTIVE, CarePlan.COMPLETED, CarePlan.DISCONTINUED),
            CarePlan.COMPLETED, Set.of(),
            CarePlan.DISCONTINUED, Set.of());

    private static final Comparator<CarePlanGoal> GOAL_ORDER =
            Comparator.comparing(CarePlanGoal::getPosition).thenComparing(CarePlanGoal::getGoalId);
    private static final Comparator<CarePlanIntervention> INTERVENTION_ORDER =
            Comparator.comparing(CarePlanIntervention::getPosition)
                    .thenComparing(CarePlanIntervention::getInterventionId);

    private static final Map<String, String> STATUS_LABELS = Map.of(
            CarePlan.ACTIVE, "Active",
            CarePlan.ON_HOLD, "On hold",
            CarePlan.COMPLETED, "Completed",
            CarePlan.DISCONTINUED, "Discontinued");

    private final CarePlanRepository carePlanRepository;
    private final CarePlanGoalRepository goalRepository;
    private final CarePlanInterventionRepository interventionRepository;
    private final AuthorizationService authorizationService;
    private final ConsultationService consultationService;
    private final AuditService auditService;
    private final NotificationPublisher notificationPublisher;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // ==================== CREATE ====================

    /**
     * Creates an ACTIVE plan for the patient, authored by the calling doctor. Goals start OPEN.
     */
    public CarePlanResponse create(CreateCarePlanRequest request) {
        String doctorSystemId = consultationService.requireTreatingDoctor(
                request.patientSystemId(), request.consultationId(), PatientPermission.CARE_PLANS);

        List<CarePlanGoalRequest> goalItems = request.goals() == null ? List.of() : request.goals();
        List<CarePlanInterventionRequest> interventionItems =
                request.interventions() == null ? List.of() : request.interventions();
        requireDates(request.startDate(), request.targetDate(), request.reviewDate(),
                goalItems.stream().map(CarePlanGoalRequest::targetDate).toList());

        LocalDateTime now = now();
        CarePlan plan = carePlanRepository.save(CarePlan.builder()
                .patientSystemId(request.patientSystemId())
                .doctorSystemId(doctorSystemId)
                .consultationId(request.consultationId())
                .title(trimToNull(request.title()))
                .description(trimToNull(request.description()))
                .status(CarePlan.ACTIVE)
                .startDate(request.startDate())
                .targetDate(request.targetDate())
                .reviewDate(request.reviewDate())
                .createdAt(now)
                .updatedAt(now)
                .build());

        List<CarePlanGoal> goals = new ArrayList<>();
        for (int i = 0; i < goalItems.size(); i++) {
            CarePlanGoalRequest item = goalItems.get(i);
            goals.add(newGoal(plan.getPlanId(), item.description(), item.targetDate(), i, now));
        }
        goals = goalRepository.saveAll(goals);
        List<CarePlanIntervention> interventions = interventionRepository.saveAll(
                newInterventions(plan.getPlanId(), interventionItems));

        auditService.logClinicalAccess("CREATE", RESOURCE, plan.getPlanId(), plan.getPatientSystemId());
        publishChange(plan, CarePlanChangedEvent.CREATED, doctorSystemId);
        notifyCircle(plan, doctorSystemId, "New care plan", "A new care plan has been created by the care team.",
                "CARE_PLAN_CREATED:" + plan.getPlanId());
        return toResponse(plan, goals, interventions);
    }

    // ==================== READ ====================

    /**
     * One plan with its goals and interventions. 404 for a missing plan, then the patient-access check.
     */
    @Transactional(readOnly = true)
    public CarePlanResponse get(Integer planId) {
        CarePlan plan = carePlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_MESSAGE));
        authorizationService.requirePatientAccess(plan.getPatientSystemId(), PatientPermission.CARE_PLANS, planId);
        auditService.logClinicalAccess("READ", RESOURCE, planId, plan.getPatientSystemId());
        return load(plan);
    }

    /**
     * A page of every plan of one patient (whoever authored it), newest first, optionally of one status.
     */
    @Transactional(readOnly = true)
    public PageResponse<CarePlanResponse> listForPatient(String patientSystemId, String status, int page, int size) {
        String statusFilter = planStatusFilter(status);
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.CARE_PLANS);
        Pageable pageable = PageParams.of(page, size, LIST_SORT);
        Page<CarePlan> result = carePlanRepository.findForPatient(patientSystemId, statusFilter, pageable);
        auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        return PageResponse.of(result, toResponses(result.getContent()));
    }

    /**
     * A page of the plans the calling doctor authored, newest first, optionally of one status and one patient.
     */
    @Transactional(readOnly = true)
    public PageResponse<CarePlanResponse> listForDoctor(String status, String patientSystemId, int page, int size) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        String statusFilter = planStatusFilter(status);
        String patientFilter = trimToNull(patientSystemId);
        String doctorSystemId = authorizationService.currentSystemId();
        Pageable pageable = PageParams.of(page, size, LIST_SORT);
        Page<CarePlan> result = carePlanRepository.findForDoctor(doctorSystemId, statusFilter, patientFilter, pageable);
        auditService.logListRead(RESOURCE, result.getContent(), CarePlan::getPatientSystemId);
        return PageResponse.of(result, toResponses(result.getContent()));
    }

    // ==================== AUTHOR DOCTOR WRITES ====================

    /**
     * Replaces the editable content. Goals sent with their id keep their status and progress note; goals left out
     * are removed; interventions are replaced as a whole.
     */
    public CarePlanResponse update(Integer planId, UpdateCarePlanRequest request) {
        CarePlan plan = lockPlan(planId);
        requireAuthor(plan);
        requireNotTerminal(plan);

        List<CarePlanGoalUpdateRequest> goalItems = request.goals();
        requireDates(request.startDate(), request.targetDate(), request.reviewDate(),
                goalItems.stream().map(CarePlanGoalUpdateRequest::targetDate).toList());

        List<CarePlanGoal> existing = goalRepository.findByPlanIdOrderByPositionAscGoalIdAsc(planId);
        Map<Integer, CarePlanGoal> existingById = existing.stream()
                .collect(Collectors.toMap(CarePlanGoal::getGoalId, Function.identity()));
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < goalItems.size(); i++) {
            Integer goalId = goalItems.get(i).goalId();
            if (goalId == null) {
                continue;
            }
            if (!existingById.containsKey(goalId)) {
                throw validation("goals[" + i + "].goalId: does not belong to this care plan");
            }
            if (!seen.add(goalId)) {
                throw validation("goals[" + i + "].goalId: is listed more than once");
            }
        }

        LocalDateTime now = now();
        plan.setTitle(trimToNull(request.title()));
        plan.setDescription(trimToNull(request.description()));
        plan.setStartDate(request.startDate());
        plan.setTargetDate(request.targetDate());
        plan.setReviewDate(request.reviewDate());
        plan.setUpdatedAt(now);
        plan = carePlanRepository.save(plan);

        List<CarePlanGoal> removed = existing.stream().filter(goal -> !seen.contains(goal.getGoalId())).toList();
        if (!removed.isEmpty()) {
            goalRepository.deleteAll(removed);
            goalRepository.flush();
        }
        List<CarePlanGoal> goals = new ArrayList<>();
        for (int i = 0; i < goalItems.size(); i++) {
            CarePlanGoalUpdateRequest item = goalItems.get(i);
            if (item.goalId() == null) {
                goals.add(newGoal(planId, item.description(), item.targetDate(), i, now));
                continue;
            }
            CarePlanGoal goal = existingById.get(item.goalId());
            String description = trimToNull(item.description());
            boolean changed = !Objects.equals(goal.getDescription(), description)
                    || !Objects.equals(goal.getTargetDate(), item.targetDate())
                    || !Objects.equals(goal.getPosition(), i);
            if (changed) {
                goal.setDescription(description);
                goal.setTargetDate(item.targetDate());
                goal.setPosition(i);
                goal.setUpdatedAt(now);
            }
            goals.add(goal);
        }
        goals = goalRepository.saveAll(goals);

        interventionRepository.deleteByPlanId(planId);
        List<CarePlanIntervention> interventions = interventionRepository.saveAll(
                newInterventions(planId, request.interventions()));

        String actor = authorizationService.currentSystemId();
        auditService.logClinicalAccess("UPDATE", RESOURCE, planId, plan.getPatientSystemId());
        publishChange(plan, CarePlanChangedEvent.UPDATED, actor);
        notifyCircle(plan, actor, "Care plan updated", "A care plan has been updated by the care team.",
                "CARE_PLAN_UPDATED:" + planId + ":" + today());
        return toResponse(plan, goals, interventions);
    }

    /**
     * Changes the plan status. ACTIVE and ON_HOLD interchange; COMPLETED and DISCONTINUED are terminal. The current
     * status is a no-op without side effects.
     */
    public CarePlanResponse changeStatus(Integer planId, CarePlanStatusRequest request) {
        String target = request.status().trim().toUpperCase(Locale.ROOT);
        CarePlan plan = lockPlan(planId);
        requireAuthor(plan);
        if (target.equals(plan.getStatus())) {
            return load(plan);
        }
        if (!TRANSITIONS.getOrDefault(plan.getStatus(), Set.of()).contains(target)) {
            throw new ConflictException(TERMINAL_MESSAGE);
        }

        plan.setStatus(target);
        plan.setUpdatedAt(now());
        plan = carePlanRepository.save(plan);

        String actor = authorizationService.currentSystemId();
        auditService.logClinicalAccess("UPDATE", RESOURCE, planId, plan.getPatientSystemId());
        publishChange(plan, CarePlanChangedEvent.STATUS_CHANGED, actor);
        notifyCircle(plan, actor, "Care plan status changed",
                "A care plan is now " + STATUS_LABELS.get(target) + ".",
                "CARE_PLAN_STATUS:" + planId + ":" + target + ":" + today());
        return load(plan);
    }

    /**
     * Records a review now and sets (or clears) the next review date.
     */
    public CarePlanResponse review(Integer planId, CarePlanReviewRequest request) {
        CarePlan plan = lockPlan(planId);
        requireAuthor(plan);
        requireNotTerminal(plan);

        LocalDate next = request.nextReviewDate();
        if (next != null && next.isBefore(today())) {
            throw validation("nextReviewDate: must not be in the past");
        }
        if (next != null && next.isBefore(plan.getStartDate())) {
            throw validation("nextReviewDate: must not be before startDate");
        }

        LocalDateTime now = now();
        plan.setLastReviewedAt(now);
        plan.setLastReviewNote(trimToNull(request.note()));
        plan.setReviewDate(next);
        plan.setUpdatedAt(now);
        plan = carePlanRepository.save(plan);

        String actor = authorizationService.currentSystemId();
        auditService.logClinicalAccess("UPDATE", RESOURCE, planId, plan.getPatientSystemId());
        publishChange(plan, CarePlanChangedEvent.REVIEWED, actor);
        notifyCircle(plan, actor, "Care plan reviewed", "A care plan has been reviewed by the doctor.",
                "CARE_PLAN_REVIEWED:" + planId + ":" + today());
        return load(plan);
    }

    // ==================== GOAL PROGRESS ====================

    /**
     * Goal progress (status and / or progress note). Check order: plan 404, caller 403, goal 404, plan 409,
     * dropped-goal 403, nothing given 400. Publishes no event and no notification.
     */
    public CarePlanResponse updateGoal(Integer planId, Integer goalId, GoalProgressRequest request) {
        CarePlan plan = lockPlan(planId);
        boolean doctor = requireGoalEditor(plan);
        CarePlanGoal goal = goalRepository.findByGoalIdAndPlanId(goalId, planId)
                .orElseThrow(() -> new ResourceNotFoundException(GOAL_NOT_FOUND_MESSAGE));
        requireNotTerminal(plan);

        String status = request.status() == null ? null : request.status().trim().toUpperCase(Locale.ROOT);
        if (!doctor && (CarePlanGoal.DROPPED.equals(goal.getStatus()) || CarePlanGoal.DROPPED.equals(status))) {
            throw new AuthorizationException(DROPPED_GOAL_MESSAGE, RESOURCE, planId);
        }
        if (status == null && request.progressNote() == null) {
            throw validation("Provide a status or a progress note");
        }

        boolean changed = false;
        if (status != null && !status.equals(goal.getStatus())) {
            goal.setStatus(status);
            changed = true;
        }
        if (request.progressNote() != null) {
            String note = trimToNull(request.progressNote());
            if (!Objects.equals(note, goal.getProgressNote())) {
                goal.setProgressNote(note);
                changed = true;
            }
        }
        if (changed) {
            goal.setUpdatedAt(now());
            goalRepository.save(goal);
            auditService.logClinicalAccess("UPDATE", RESOURCE, planId, plan.getPatientSystemId());
        }
        return load(plan);
    }

    // ==================== CHECKS ====================

    private CarePlan lockPlan(Integer planId) {
        return carePlanRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_MESSAGE));
    }

    /** Role DOCTOR (else 403 "Your role is not authorized ...") and the plan's author (else 403, audited). */
    private void requireAuthor(CarePlan plan) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        consultationService.requireAuthorDoctor(plan.getDoctorSystemId(), plan.getPatientSystemId(), RESOURCE,
                plan.getPlanId(), false);
    }

    /**
     * Who may record goal progress: the patient and a caregiver with MANAGE_CARE_PLAN (through the patient-access
     * check), or the author doctor. Admins are refused.
     *
     * @return true when the caller is the author doctor (who may also drop and reopen goals)
     */
    private boolean requireGoalEditor(CarePlan plan) {
        authorizationService.requireRole(AuthorizationService.PATIENT, AuthorizationService.CAREGIVER,
                AuthorizationService.DOCTOR);
        if (AuthorizationService.DOCTOR.equals(authorizationService.currentRole())) {
            consultationService.requireAuthorDoctor(plan.getDoctorSystemId(), plan.getPatientSystemId(), RESOURCE,
                    plan.getPlanId(), false);
            return true;
        }
        authorizationService.requirePatientAccess(plan.getPatientSystemId(), PatientPermission.MANAGE_CARE_PLAN,
                plan.getPlanId());
        return false;
    }

    private static void requireNotTerminal(CarePlan plan) {
        if (plan.isTerminal()) {
            throw new ConflictException(TERMINAL_MESSAGE);
        }
    }

    /** The plan's target and review dates, and every goal's target date, are not before its start date. */
    private static void requireDates(LocalDate startDate, LocalDate targetDate, LocalDate reviewDate,
                                     List<LocalDate> goalTargetDates) {
        if (targetDate != null && targetDate.isBefore(startDate)) {
            throw validation("targetDate: must not be before startDate");
        }
        if (reviewDate != null && reviewDate.isBefore(startDate)) {
            throw validation("reviewDate: must not be before startDate");
        }
        for (int i = 0; i < goalTargetDates.size(); i++) {
            LocalDate goalTarget = goalTargetDates.get(i);
            if (goalTarget != null && goalTarget.isBefore(startDate)) {
                throw validation("goals[" + i + "].targetDate: must not be before startDate");
            }
        }
    }

    /** The upper-cased status filter, or null for none. Blank means no filter; an unknown value is a 400. */
    static String planStatusFilter(String status) {
        String value = trimToNull(status);
        if (value == null) {
            return null;
        }
        value = value.toUpperCase(Locale.ROOT);
        if (!CarePlan.STATUSES.contains(value)) {
            throw validation("status: must be one of " + PLAN_STATUS_VALUES);
        }
        return value;
    }

    private static BadRequestException validation(String message) {
        return new BadRequestException(ErrorResponse.VALIDATION_FAILED, message);
    }

    // ==================== SIDE EFFECTS ====================

    private void publishChange(CarePlan plan, String kind, String actorSystemId) {
        eventPublisher.publishEvent(new CarePlanChangedEvent(plan.getPlanId(), plan.getPatientSystemId(),
                plan.getDoctorSystemId(), actorSystemId, kind, plan.getStatus(), plan.getReviewDate()));
    }

    /**
     * The patient plus every caregiver holding CARE_PLANS. Generic text only (never the title, a goal or a note).
     * The publisher skips the actor, honours care_plan_update_alerts and drops repeated dedupe keys.
     */
    private void notifyCircle(CarePlan plan, String actorSystemId, String title, String message, String dedupeKey) {
        notificationPublisher.publishToPatientCircle(plan.getPatientSystemId(), PatientPermission.CARE_PLANS,
                NotificationCommand.builder()
                        .type(NotificationType.SYSTEM)
                        .category(NotificationCategory.CARE_PLAN)
                        .priority(NotificationPriority.NORMAL)
                        .title(title)
                        .message(message)
                        .target(NotificationTarget.CARE_PLANS)
                        .param("id", String.valueOf(plan.getPlanId()))
                        .resourceType(RESOURCE)
                        .resourceId(plan.getPlanId())
                        .patientSystemId(plan.getPatientSystemId())
                        .actorSystemId(actorSystemId)
                        .dedupeKey(dedupeKey)
                        .build());
    }

    // ==================== MAPPING ====================

    private CarePlanGoal newGoal(Integer planId, String description, LocalDate targetDate, int position,
                                 LocalDateTime now) {
        return CarePlanGoal.builder()
                .planId(planId)
                .description(trimToNull(description))
                .targetDate(targetDate)
                .status(CarePlanGoal.OPEN)
                .position(position)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private static List<CarePlanIntervention> newInterventions(Integer planId, List<CarePlanInterventionRequest> items) {
        List<CarePlanIntervention> interventions = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            CarePlanInterventionRequest item = items.get(i);
            interventions.add(CarePlanIntervention.builder()
                    .planId(planId)
                    .description(trimToNull(item.description()))
                    .frequency(trimToNull(item.frequency()))
                    .responsible(trimToNull(item.responsible()))
                    .position(i)
                    .build());
        }
        return interventions;
    }

    private CarePlanResponse load(CarePlan plan) {
        return toResponse(plan,
                goalRepository.findByPlanIdOrderByPositionAscGoalIdAsc(plan.getPlanId()),
                interventionRepository.findByPlanIdOrderByPositionAscInterventionIdAsc(plan.getPlanId()));
    }

    /** Responses for a page of plans, with the children batch-loaded in two IN queries. */
    private List<CarePlanResponse> toResponses(Collection<CarePlan> plans) {
        if (plans.isEmpty()) {
            return List.of();
        }
        List<Integer> ids = plans.stream().map(CarePlan::getPlanId).toList();
        Map<Integer, List<CarePlanGoal>> goals = new HashMap<>();
        goalRepository.findByPlanIdInOrderByPlanIdAscPositionAscGoalIdAsc(ids)
                .forEach(goal -> goals.computeIfAbsent(goal.getPlanId(), id -> new ArrayList<>()).add(goal));
        Map<Integer, List<CarePlanIntervention>> interventions = new HashMap<>();
        interventionRepository.findByPlanIdInOrderByPlanIdAscPositionAscInterventionIdAsc(ids)
                .forEach(item -> interventions.computeIfAbsent(item.getPlanId(), id -> new ArrayList<>()).add(item));
        return plans.stream()
                .map(plan -> toResponse(plan,
                        goals.getOrDefault(plan.getPlanId(), List.of()),
                        interventions.getOrDefault(plan.getPlanId(), List.of())))
                .toList();
    }

    private static CarePlanResponse toResponse(CarePlan plan, List<CarePlanGoal> goals,
                                               List<CarePlanIntervention> interventions) {
        return new CarePlanResponse(
                plan.getPlanId(),
                plan.getPatientSystemId(),
                plan.getDoctorSystemId(),
                plan.getConsultationId(),
                plan.getTitle(),
                plan.getDescription(),
                plan.getStatus(),
                plan.getStartDate(),
                plan.getTargetDate(),
                plan.getReviewDate(),
                plan.getLastReviewedAt(),
                plan.getLastReviewNote(),
                goals.stream()
                        .sorted(GOAL_ORDER)
                        .map(CarePlanService::toGoalResponse)
                        .toList(),
                interventions.stream()
                        .sorted(INTERVENTION_ORDER)
                        .map(CarePlanService::toInterventionResponse)
                        .toList(),
                plan.getCreatedAt(),
                plan.getUpdatedAt());
    }

    private static CarePlanGoalResponse toGoalResponse(CarePlanGoal goal) {
        return new CarePlanGoalResponse(goal.getGoalId(), goal.getDescription(), goal.getTargetDate(),
                goal.getStatus(), goal.getProgressNote(), goal.getPosition(), goal.getUpdatedAt());
    }

    private static CarePlanInterventionResponse toInterventionResponse(CarePlanIntervention intervention) {
        return new CarePlanInterventionResponse(intervention.getInterventionId(), intervention.getDescription(),
                intervention.getFrequency(), intervention.getResponsible(), intervention.getPosition());
    }

    // ==================== HELPERS ====================

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
