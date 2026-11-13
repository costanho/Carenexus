package com.carenexus.api.admin.clinician;

import com.carenexus.api.admin.clinician.repository.ClinicianRepository;
import com.carenexus.api.admin.user.repository.AdminUserRepository;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.communications.notify.NotificationPublisher;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.delivery.OutboundMessageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression: the clinician-approval email must link to a route the web app actually has. The sign-in page is
 * /auth/login/web/login-web; a bare /auth/login falls through to the Not found page of the static export.
 */
class ClinicianApprovalEmailLinkTest {

    private static final String DOC = "DOC-00042";

    @Test
    @SuppressWarnings("unchecked")
    void approvalEmailLinksToTheWebSignInRoute() {
        ClinicianRepository clinicianRepository = mock(ClinicianRepository.class);
        AdminUserRepository adminUserRepository = mock(AdminUserRepository.class);
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        OutboundMessageService outbound = mock(OutboundMessageService.class);
        ClinicianVerificationService service = new ClinicianVerificationService(clinicianRepository,
                adminUserRepository, mock(AdminDoctorMapper.class), authorizationService, mock(AuditService.class),
                mock(ApplicationEventPublisher.class), mock(NotificationPublisher.class), outbound,
                Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC), "https://app.example.org/");

        User user = new User();
        user.setSystemId(DOC);
        user.setRole("DOCTOR");
        user.setEmail("doctor@example.org");
        user.setFirstName("Ada");
        Doctor doctor = new Doctor();
        doctor.setSystemId(DOC);
        doctor.setVerificationStatus(Doctor.VERIFICATION_PENDING);
        doctor.setLicenseNo("MDPCZ-2019-04417");
        when(authorizationService.currentSystemId()).thenReturn("ADM-00001");
        when(adminUserRepository.findBySystemId(DOC)).thenReturn(Optional.of(user));
        when(clinicianRepository.findBySystemId(DOC)).thenReturn(Optional.of(doctor));

        service.verify(DOC);

        ArgumentCaptor<Map<String, String>> model = ArgumentCaptor.forClass(Map.class);
        verify(outbound).queueEmail(eq("doctor@example.org"), eq(DOC),
                eq(ClinicianVerificationService.TEMPLATE_APPROVED), model.capture(), anyString());
        assertEquals("https://app.example.org/auth/login/web/login-web", model.getValue().get("link"));
    }
}
