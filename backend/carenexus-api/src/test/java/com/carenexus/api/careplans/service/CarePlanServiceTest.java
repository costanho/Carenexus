package com.carenexus.api.careplans.service;

import com.carenexus.api.careplans.dto.request.CarePlanReviewRequest;
import com.carenexus.api.careplans.dto.request.CarePlanStatusRequest;
import com.carenexus.api.careplans.dto.request.GoalProgressRequest;
import com.carenexus.api.careplans.dto.response.CarePlanResponse;
import com.carenexus.api.careplans.model.CarePlan;
import com.carenexus.api.careplans.model.CarePlanGoal;
import com.carenexus.api.careplans.repository.CarePlanGoalRepository;
import com.carenexus.api.careplans.repository.CarePlanInterventionRepository;
import com.carenexus.api.careplans.repository.CarePlanRepository;
import com.carenexus.api.common.event.CarePlanChangedEvent;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.communications.notify.NotificationPublisher;
import com.carenexus.api.core.service.ConsultationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit rules of {@link CarePlanService} that do not need a database: the status transition table and its no-op,
 * the review date rules against the clinic clock, the goal-progress check order and the status filter parsing.
 * The HTTP-level behaviour is proven by CarePlanReleaseGateTest and CarePlanValidationTest.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CarePlanServiceTest {

    private static final ZoneId CLINIC = ZoneId.of("Africa/Harare");
    /** 2026-11-10 09:00 in the clinic zone. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T07:00:00Z"), CLINIC);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final String PATIENT = "PAT-00001";
    private static final String DOCTOR = "DOC-00001";

    @Mock private CarePlanRepository carePlanRepository;
    @Mock private CarePlanGoalRepository goalRepository;
    @Mock private CarePlanInterventionRepository interventionRepository;
    @Mock private AuthorizationService authorizationService;
    @Mock private ConsultationService consultationService;
    @Mock private AuditService auditService;
    @Mock private NotificationPublisher notificationPublisher;
    @Mock private ApplicationEventPublisher eventPublisher;

    private CarePlanService service;

    @BeforeEach
    void setUp() {
        service = new CarePlanService(carePlanRepository, goalRepository, interventionRepository,
                authorizationService, consultationService, auditService, notificationPublisher, eventPublisher,
                CLOCK);
        when(authorizationService.currentSystemId()).thenReturn(DOCTOR);
        when(authorizationService.currentRole()).thenReturn(AuthorizationService.DOCTOR);
        when(carePlanRepository.save(any(CarePlan.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(goalRepository.findByPlanIdOrderByPositionAscGoalIdAsc(anyInt())).thenReturn(List.of());
        when(interventionRepository.findByPlanIdOrderByPositionAscInterventionIdAsc(anyInt())).thenReturn(List.of());
    }

    // ==================== STATUS ====================

    @ParameterizedTest
    @CsvSource({
            "ACTIVE, ON_HOLD", "ACTIVE, COMPLETED", "ACTIVE, DISCONTINUED",
            "ON_HOLD, ACTIVE", "ON_HOLD, COMPLETED", "ON_HOLD, DISCONTINUED"})
    void status_allowedTransitions_publishOnceWithTheStoredReviewDate(String from, String to) {
        CarePlan plan = plan(from);
        when(carePlanRepository.findByIdForUpdate(7)).thenReturn(Optional.of(plan));

        CarePlanResponse response = service.changeStatus(7, new CarePlanStatusRequest(" " + to.toLowerCase() + " "));

        assertEquals(to, response.status());
        assertEquals(TODAY.plusMonths(1), response.reviewDate(), "the review date is never cleared here");
        ArgumentCaptor<CarePlanChangedEvent> event = ArgumentCaptor.forClass(CarePlanChangedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals(CarePlanChangedEvent.STATUS_CHANGED, event.getValue().kind());
        assertEquals(to, event.getValue().status());
        assertEquals(DOCTOR, event.getValue().actorSystemId());
        assertEquals(TODAY.plusMonths(1), event.getValue().reviewDate());
        verify(auditService).logClinicalAccess("UPDATE", CarePlanService.RESOURCE, 7, PATIENT);
        verify(notificationPublisher).publishToPatientCircle(eq(PATIENT), eq(PatientPermission.CARE_PLANS), any());
    }

    @ParameterizedTest
    @CsvSource({
            "COMPLETED, ACTIVE", "COMPLETED, ON_HOLD", "COMPLETED, DISCONTINUED",
            "DISCONTINUED, ACTIVE", "DISCONTINUED, ON_HOLD", "DISCONTINUED, COMPLETED"})
    void status_awayFromATerminalStatus_is409WithoutSideEffects(String from, String to) {
        when(carePlanRepository.findByIdForUpdate(7)).thenReturn(Optional.of(plan(from)));

        ConflictException error = assertThrows(ConflictException.class,
                () -> service.changeStatus(7, new CarePlanStatusRequest(to)));

        assertEquals(CarePlanService.TERMINAL_MESSAGE, error.getMessage());
        verifyNoInteractions(eventPublisher, notificationPublisher, auditService);
        verify(carePlanRepository, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource({"ACTIVE", "ON_HOLD", "COMPLETED", "DISCONTINUED"})
    void status_sameStatus_isANoOpWithoutSideEffects(String status) {
        when(carePlanRepository.findByIdForUpdate(7)).thenReturn(Optional.of(plan(status)));

        assertEquals(status, service.changeStatus(7, new CarePlanStatusRequest(status)).status());

        verifyNoInteractions(eventPublisher, notificationPublisher, auditService);
        verify(carePlanRepository, never()).save(any());
    }

    @Test
    void status_authorCheckComesBeforeTheTransitionCheck() {
        when(carePlanRepository.findByIdForUpdate(7)).thenReturn(Optional.of(plan(CarePlan.COMPLETED)));
        doThrow(new AuthorizationException("Only the doctor who created this item can change it"))
                .when(consultationService).requireAuthorDoctor(eq(DOCTOR), eq(PATIENT), anyString(), eq(7), eq(false));

        assertThrows(AuthorizationException.class, () -> service.changeStatus(7, new CarePlanStatusRequest("ACTIVE")));
    }

    @Test
    void status_missingPlan_is404() {
        when(carePlanRepository.findByIdForUpdate(7)).thenReturn(Optional.empty());

        ResourceNotFoundException error = assertThrows(ResourceNotFoundException.class,
                () -> service.changeStatus(7, new CarePlanStatusRequest("ACTIVE")));
        assertEquals("Care plan not found", error.getMessage());
    }

    // ==================== REVIEW ====================

    @Test
    void review_stampsClinicTime_setsTheNextDate_andPublishes() {
        CarePlan plan = plan(CarePlan.ON_HOLD);
        when(carePlanRepository.findByIdForUpdate(7)).thenReturn(Optional.of(plan));

        CarePlanResponse response = service.review(7, new CarePlanReviewRequest(TODAY, "  Stable.  "));

        assertEquals(LocalDateTime.of(2026, 11, 10, 9, 0), response.lastReviewedAt());
        assertEquals("Stable.", response.lastReviewNote());
        assertEquals(TODAY, response.reviewDate(), "today in the clinic zone is allowed");
        ArgumentCaptor<CarePlanChangedEvent> event = ArgumentCaptor.forClass(CarePlanChangedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals(CarePlanChangedEvent.REVIEWED, event.getValue().kind());
        assertEquals(TODAY, event.getValue().reviewDate());
    }

    @Test
    void review_withoutANextDate_clearsTheReviewDate() {
        when(carePlanRepository.findByIdForUpdate(7)).thenReturn(Optional.of(plan(CarePlan.ACTIVE)));

        CarePlanResponse response = service.review(7, new CarePlanReviewRequest(null, " "));

        assertNull(response.reviewDate());
        assertNull(response.lastReviewNote());
    }

    @Test
    void review_rejectsYesterday_andADateBeforeTheStart() {
        when(carePlanRepository.findByIdForUpdate(7)).thenReturn(Optional.of(plan(CarePlan.ACTIVE)));
        BadRequestException past = assertThrows(BadRequestException.class,
                () -> service.review(7, new CarePlanReviewRequest(TODAY.minusDays(1), null)));
        assertEquals("nextReviewDate: must not be in the past", past.getMessage());

        CarePlan future = plan(CarePlan.ACTIVE);
        future.setStartDate(TODAY.plusDays(10));
        when(carePlanRepository.findByIdForUpdate(8)).thenReturn(Optional.of(future));
        BadRequestException beforeStart = assertThrows(BadRequestException.class,
                () -> service.review(8, new CarePlanReviewRequest(TODAY.plusDays(2), null)));
        assertEquals("nextReviewDate: must not be before startDate", beforeStart.getMessage());
        verifyNoInteractions(eventPublisher, notificationPublisher);
    }

    // ==================== GOAL PROGRESS ====================

    @Test
    void goal_patientCannotTouchADroppedGoal_beforeTheNothingGivenCheck() {
        asPatient();
        when(carePlanRepository.findByIdForUpdate(7)).thenReturn(Optional.of(plan(CarePlan.ACTIVE)));
        when(goalRepository.findByGoalIdAndPlanId(3, 7)).thenReturn(Optional.of(goal(CarePlanGoal.DROPPED)));

        AuthorizationException error = assertThrows(AuthorizationException.class,
                () -> service.updateGoal(7, 3, new GoalProgressRequest(null, null)));
        assertEquals(CarePlanService.DROPPED_GOAL_MESSAGE, error.getMessage());
    }

    @Test
    void goal_terminalPlanIs409_beforeTheDroppedRule() {
        asPatient();
        when(carePlanRepository.findByIdForUpdate(7)).thenReturn(Optional.of(plan(CarePlan.DISCONTINUED)));
        when(goalRepository.findByGoalIdAndPlanId(3, 7)).thenReturn(Optional.of(goal(CarePlanGoal.DROPPED)));

        assertThrows(ConflictException.class,
                () -> service.updateGoal(7, 3, new GoalProgressRequest("OPEN", null)));
    }

    @Test
    void goal_progressPublishesNothing_andLeavesThePlanTimestamp() {
        asPatient();
        CarePlan plan = plan(CarePlan.ACTIVE);
        LocalDateTime planUpdatedAt = plan.getUpdatedAt();
        CarePlanGoal goal = goal(CarePlanGoal.OPEN);
        when(carePlanRepository.findByIdForUpdate(7)).thenReturn(Optional.of(plan));
        when(goalRepository.findByGoalIdAndPlanId(3, 7)).thenReturn(Optional.of(goal));

        service.updateGoal(7, 3, new GoalProgressRequest("achieved", "Done"));

        assertEquals(CarePlanGoal.ACHIEVED, goal.getStatus());
        assertEquals("Done", goal.getProgressNote());
        assertEquals(LocalDateTime.of(2026, 11, 10, 9, 0), goal.getUpdatedAt());
        assertEquals(planUpdatedAt, plan.getUpdatedAt());
        verify(authorizationService).requirePatientAccess(PATIENT, PatientPermission.MANAGE_CARE_PLAN, 7);
        verify(auditService).logClinicalAccess("UPDATE", CarePlanService.RESOURCE, 7, PATIENT);
        verifyNoInteractions(eventPublisher, notificationPublisher);
        verify(carePlanRepository, never()).save(any());
    }

    @Test
    void goal_unchangedValues_areNotAudited() {
        asPatient();
        CarePlanGoal goal = goal(CarePlanGoal.IN_PROGRESS);
        goal.setProgressNote("Same");
        when(carePlanRepository.findByIdForUpdate(7)).thenReturn(Optional.of(plan(CarePlan.ACTIVE)));
        when(goalRepository.findByGoalIdAndPlanId(3, 7)).thenReturn(Optional.of(goal));

        service.updateGoal(7, 3, new GoalProgressRequest("IN_PROGRESS", " Same "));

        verify(goalRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    // ==================== STATUS FILTER ====================

    @Test
    void statusFilter_isTrimmedCaseInsensitiveAndBlankMeansAll() {
        assertEquals("ON_HOLD", CarePlanService.planStatusFilter(" on_hold "));
        assertNull(CarePlanService.planStatusFilter("  "));
        assertNull(CarePlanService.planStatusFilter(null));
        BadRequestException error = assertThrows(BadRequestException.class,
                () -> CarePlanService.planStatusFilter("PAUSED"));
        assertEquals("status: must be one of ACTIVE, ON_HOLD, COMPLETED, DISCONTINUED", error.getMessage());
    }

    // ==================== HELPERS ====================

    private void asPatient() {
        when(authorizationService.currentSystemId()).thenReturn(PATIENT);
        when(authorizationService.currentRole()).thenReturn(AuthorizationService.PATIENT);
    }

    private static CarePlan plan(String status) {
        LocalDateTime created = LocalDateTime.of(2026, 11, 1, 8, 30);
        return CarePlan.builder()
                .planId(7)
                .patientSystemId(PATIENT)
                .doctorSystemId(DOCTOR)
                .title("Blood pressure management")
                .status(status)
                .startDate(TODAY.minusDays(9))
                .reviewDate(TODAY.plusMonths(1))
                .createdAt(created)
                .updatedAt(created)
                .build();
    }

    private static CarePlanGoal goal(String status) {
        LocalDateTime created = LocalDateTime.of(2026, 11, 1, 8, 30);
        return CarePlanGoal.builder()
                .goalId(3)
                .planId(7)
                .description("Walk 30 minutes")
                .status(status)
                .position(0)
                .createdAt(created)
                .updatedAt(created)
                .build();
    }
}
