package com.carenexus.api.lifecycle.service;

import com.carenexus.api.common.event.UserDeactivatedEvent;
import com.carenexus.api.common.model.AuditLog;
import com.carenexus.api.core.service.AppointmentService;
import com.carenexus.api.core.service.CaregiverService;
import com.carenexus.api.lifecycle.repository.LifecycleAccountRepository;
import com.carenexus.api.lifecycle.repository.LifecycleAppointmentRepository;
import com.carenexus.api.lifecycle.repository.LifecycleAuditRepository;
import com.carenexus.api.lifecycle.repository.LifecycleGrantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Failure isolation of a clean-up run (api/be-account-lifecycle.md 5.2 / 5.5) for throwables that are not runtime
 * exceptions: a checked exception thrown sneakily is one failed unit and the step goes on; an {@link Error} that
 * escapes a step is counted as a failure of that step, the later steps still run, and the run still writes exactly
 * one audit row.
 */
class AccountDeactivationEffectsServiceTest {

    private AppointmentService appointmentService;
    private CaregiverService caregiverService;
    private LifecycleAppointmentRepository appointmentRepository;
    private LifecycleGrantRepository grantRepository;
    private LifecycleAccountRepository accountRepository;
    private LifecycleAuditRepository auditRepository;
    private AccountDeactivationEffectsService service;

    @BeforeEach
    void setUp() {
        appointmentService = mock(AppointmentService.class);
        caregiverService = mock(CaregiverService.class);
        appointmentRepository = mock(LifecycleAppointmentRepository.class);
        grantRepository = mock(LifecycleGrantRepository.class);
        accountRepository = mock(LifecycleAccountRepository.class);
        auditRepository = mock(LifecycleAuditRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        when(accountRepository.findRole(anyString())).thenReturn(Optional.of("ADMIN"));
        when(auditRepository.save(any(AuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service = new AccountDeactivationEffectsService(appointmentService, caregiverService, appointmentRepository,
                grantRepository, accountRepository, auditRepository, transactionManager,
                Clock.fixed(Instant.parse("2026-11-11T09:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void anErrorEscapingAStep_isCountedForThatStep_theNextStepRuns_andOneAuditRowIsWritten() {
        when(appointmentRepository.findActiveAsDoctorAfter(anyString(), anyList(), any(), anyInt(), any()))
                .thenThrow(new LinkageError("broken class"));
        when(accountRepository.countPendingReferralsAsSpecialist("DOC-00001")).thenReturn(3L);

        DeactivationEffects effects = service.run(new UserDeactivatedEvent("DOC-00001", "DOCTOR", "ADM-00001", "ADMIN"));

        assertNotNull(effects);
        assertEquals(1, effects.failures(DeactivationStep.APPOINTMENTS));
        assertEquals(3, effects.pendingReferrals(), "the step after the failed one still ran");
        AuditLog row = savedAuditRow();
        assertEquals("FAILURE", row.getStatus());
        assertEquals("cause=ADMIN; role=DOCTOR; appointmentsCancelled=0; grantsRevoked=0; remindersDeactivated=0; "
                + "accessRequestsCancelled=0; pendingReferrals=3; failed=APPOINTMENTS:1", row.getErrorMessage());
    }

    @Test
    void anErrorFromOneGrant_endsTheGrantsStep_butTheAccessRequestStepAndTheAuditRowStillRun() {
        when(grantRepository.findUnrevokedAsCaregiverAfter(eq("CGV-00001"), eq(0), any()))
                .thenReturn(List.of(10, 11));
        doThrow(new StackOverflowError()).when(caregiverService)
                .revokeCaregiverAccessAsPatient("ADM-00001", true, 10);
        when(accountRepository.tableExists("caregiver_access_requests")).thenReturn(true);
        when(accountRepository.cancelPendingAccessRequestsOfCaregiver("CGV-00001")).thenReturn(2);

        DeactivationEffects effects = service.run(
                new UserDeactivatedEvent("CGV-00001", "CAREGIVER", "ADM-00001", "ADMIN"));

        assertEquals(1, effects.failures(DeactivationStep.GRANTS));
        assertEquals(2, effects.accessRequestsCancelled());
        assertEquals("cause=ADMIN; role=CAREGIVER; appointmentsCancelled=0; grantsRevoked=0; remindersDeactivated=0; "
                + "accessRequestsCancelled=2; pendingReferrals=0; failed=GRANTS:1", savedAuditRow().getErrorMessage());
    }

    @Test
    void aCheckedExceptionThrownSneakilyByOneGrant_isOneFailedUnit_andTheOtherGrantsAreStillRevoked() {
        when(grantRepository.findUnrevokedAsPatientAfter(eq("PAT-00001"), eq(0), any()))
                .thenReturn(List.of(20, 21));
        doAnswer(invocation -> {
            throw sneaky(new java.io.IOException("disk"));
        }).when(caregiverService).revokeCaregiverAccessAsPatient("PAT-00001", true, 20);
        when(appointmentRepository.findActiveAsPatientAfter(anyString(), anyList(), any(), anyInt(), any()))
                .thenReturn(List.of());

        DeactivationEffects effects = service.run(
                new UserDeactivatedEvent("PAT-00001", "PATIENT", "PAT-00001", "CLOSURE"));

        verify(caregiverService).revokeCaregiverAccessAsPatient("PAT-00001", true, 21);
        assertEquals(1, effects.grantsRevoked());
        assertEquals(1, effects.failures(DeactivationStep.GRANTS));
        assertEquals("FAILURE", savedAuditRow().getStatus());
    }

    @Test
    void aFailingRoleLookup_fallsBackToThePrefix_andStillEndsWithTheAuditRow() {
        when(accountRepository.findRole("PAT-00001")).thenThrow(new IllegalStateException("db down"));

        DeactivationEffects effects = service.run(new UserDeactivatedEvent("PAT-00001", null, null, "ADMIN"));

        assertEquals("PATIENT", effects.role(), "the prefix stands in for the stored role");
        assertEquals("SUCCESS", savedAuditRow().getStatus());
    }

    private AuditLog savedAuditRow() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditRepository, times(1)).save(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable failure) throws T {
        throw (T) failure;
    }
}
