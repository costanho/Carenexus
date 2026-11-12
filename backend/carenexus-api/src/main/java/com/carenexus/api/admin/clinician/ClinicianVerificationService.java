package com.carenexus.api.admin.clinician;

import com.carenexus.api.account.email.service.AccountEmailLinks;
import com.carenexus.api.admin.clinician.dto.request.LicenceRequest;
import com.carenexus.api.admin.clinician.dto.response.AdminDoctorResponse;
import com.carenexus.api.admin.clinician.repository.ClinicianRepository;
import com.carenexus.api.admin.user.repository.AdminUserRepository;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.event.DoctorVerificationDecidedEvent;
import com.carenexus.api.common.event.UserReactivatedEvent;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.communications.notify.NotificationCategory;
import com.carenexus.api.communications.notify.NotificationCommand;
import com.carenexus.api.communications.notify.NotificationPriority;
import com.carenexus.api.communications.notify.NotificationPublisher;
import com.carenexus.api.communications.notify.NotificationType;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.delivery.OutboundMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Clinician verification: the queue of doctors by verification status, approval, rejection and licence correction.
 *
 * Status machine (doctors.verification_status; users.is_active and doctors.is_active are true only in APPROVED):
 * <pre>
 *   PENDING   -> APPROVED  verify (licence number required)
 *   REJECTED  -> APPROVED  verify (re-opens a rejection, usually after a licence correction)
 *   SUSPENDED -> APPROVED  verify (reactivation; also through the user activate endpoint)
 *   PENDING   -> REJECTED  reject (reason required)
 *   APPROVED  -> APPROVED  verify: no-op;  REJECTED -> REJECTED  reject: no-op
 *   APPROVED, SUSPENDED -> REJECTED: illegal (409)
 * </pre>
 * Suspension (APPROVED -> SUSPENDED) happens when the doctor's account is deactivated (UserAdminService). Licence
 * edits are allowed in every status and never change it. Every method is ADMIN only.
 */
@Service
@Slf4j
@Transactional
public class ClinicianVerificationService {

    public static final String RESOURCE_ACCOUNT = "ACCOUNT";
    public static final String RESOURCE_LICENCE = "LICENCE";
    public static final String CODE_LICENCE_REQUIRED = "LICENCE_REQUIRED";
    public static final String CODE_ACCOUNT_CLOSED = "ACCOUNT_CLOSED";
    public static final String TEMPLATE_APPROVED = "account-clinician-approved";
    public static final String TEMPLATE_REJECTED = "account-clinician-rejected";

    static final String DOCTOR_NOT_FOUND = "Doctor not found";
    private static final Set<String> STATUSES = Set.of(Doctor.VERIFICATION_PENDING, Doctor.VERIFICATION_APPROVED,
            Doctor.VERIFICATION_REJECTED, Doctor.VERIFICATION_SUSPENDED);
    private static final DateTimeFormatter DEDUPE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ClinicianRepository clinicianRepository;
    private final AdminUserRepository adminUserRepository;
    private final AdminDoctorMapper doctorMapper;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final NotificationPublisher notificationPublisher;
    private final OutboundMessageService outboundMessageService;
    private final Clock clock;
    private final String webBaseUrl;

    public ClinicianVerificationService(ClinicianRepository clinicianRepository,
                                        AdminUserRepository adminUserRepository,
                                        AdminDoctorMapper doctorMapper,
                                        AuthorizationService authorizationService,
                                        AuditService auditService,
                                        ApplicationEventPublisher eventPublisher,
                                        NotificationPublisher notificationPublisher,
                                        OutboundMessageService outboundMessageService,
                                        Clock clock,
                                        @Value("${carenexus.web.base-url:http://localhost:8081}") String webBaseUrl) {
        this.clinicianRepository = clinicianRepository;
        this.adminUserRepository = adminUserRepository;
        this.doctorMapper = doctorMapper;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.notificationPublisher = notificationPublisher;
        this.outboundMessageService = outboundMessageService;
        this.clock = clock;
        this.webBaseUrl = stripTrailingSlash(webBaseUrl);
    }

    // ==================== QUEUE ====================

    /** Doctors waiting for verification (status PENDING), oldest registration first. */
    @Transactional(readOnly = true)
    public PageResponse<AdminDoctorResponse> listPending(Integer page, Integer size) {
        authorizationService.requireAdmin();
        return list(Doctor.VERIFICATION_PENDING, page, size);
    }

    /**
     * Clinicians by verification status (null or blank = every status), oldest registration first.
     *
     * @throws BadRequestException for an unknown status
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminDoctorResponse> listByStatus(String status, Integer page, Integer size) {
        authorizationService.requireAdmin();
        String normalized = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        if (normalized != null && !STATUSES.contains(normalized)) {
            throw new BadRequestException("Invalid status. Must be PENDING, APPROVED, REJECTED or SUSPENDED");
        }
        return list(normalized, page, size);
    }

    private PageResponse<AdminDoctorResponse> list(String status, Integer page, Integer size) {
        // The order is part of the query (registration time, then row id); the page request carries none.
        Pageable pageable = PageParams.of(page, size, Sort.unsorted());
        Page<Object[]> rows = status == null
                ? clinicianRepository.findAllWithUser(pageable)
                : clinicianRepository.findWithUserByStatus(status, pageable);
        return PageResponse.of(rows, row -> doctorMapper.toResponse((Doctor) row[0], (User) row[1]));
    }

    // ==================== DECISIONS ====================

    /**
     * Approve a clinician (from PENDING, REJECTED or SUSPENDED): status APPROVED, both active flags on, decision
     * stamped, rejection and deactivation reasons cleared; audit DOCTOR_VERIFY, {@link DoctorVerificationDecidedEvent},
     * {@link UserReactivatedEvent} when the doctor was suspended, approval email and in-app notification.
     * An already approved clinician is returned unchanged.
     *
     * @throws ResourceNotFoundException "Doctor not found" when the id is not a clinician account
     * @throws ConflictException         ACCOUNT_CLOSED for a closed account, LICENCE_REQUIRED without a licence number
     */
    public AdminDoctorResponse verify(String systemId) {
        authorizationService.requireAdmin();
        String adminSystemId = authorizationService.currentSystemId();
        Clinician clinician = findClinician(systemId);
        User user = clinician.user();
        Doctor doctor = clinician.doctor();

        if (user.getClosedAt() != null) {
            throw new ConflictException(CODE_ACCOUNT_CLOSED, "This account is closed. Reactivate the account first.");
        }
        if (Doctor.VERIFICATION_APPROVED.equals(doctor.getVerificationStatus())) {
            return doctorMapper.toResponse(doctor, user);
        }
        if (doctor.getLicenseNo() == null || doctor.getLicenseNo().isBlank()) {
            throw new ConflictException(CODE_LICENCE_REQUIRED,
                    "A licence number is required before this clinician can be approved.");
        }

        boolean wasSuspended = Doctor.VERIFICATION_SUSPENDED.equals(doctor.getVerificationStatus());
        LocalDateTime now = now();
        doctor.setVerificationStatus(Doctor.VERIFICATION_APPROVED);
        doctor.setIsActive(true);
        doctor.setVerifiedBySystemId(adminSystemId);
        doctor.setVerifiedAt(now);
        doctor.setRejectionReason(null);
        clinicianRepository.save(doctor);
        user.setIsActive(true);
        adminUserRepository.save(user);
        adminUserRepository.updateDeactivationReason(systemId, null);

        auditService.logAccountEvent(adminSystemId, "DOCTOR_VERIFY", RESOURCE_ACCOUNT, systemId, "SUCCESS");
        eventPublisher.publishEvent(new DoctorVerificationDecidedEvent(systemId, adminSystemId,
                Doctor.VERIFICATION_APPROVED, null));
        if (wasSuspended) {
            eventPublisher.publishEvent(new UserReactivatedEvent(systemId, user.getRole(), adminSystemId));
        }
        sendApproval(user, doctor, adminSystemId);
        return doctorMapper.toResponse(doctor, user);
    }

    /**
     * Reject a pending clinician registration: status REJECTED with the reason, decision stamped, both active flags
     * stay off; audit DOCTOR_REJECT, {@link DoctorVerificationDecidedEvent} and the rejection email (which includes
     * the reason). An already rejected clinician is returned unchanged (the stored reason is kept).
     *
     * @throws ResourceNotFoundException "Doctor not found"
     * @throws ConflictException         when the clinician is APPROVED or SUSPENDED
     */
    public AdminDoctorResponse reject(String systemId, String reason) {
        authorizationService.requireAdmin();
        String adminSystemId = authorizationService.currentSystemId();
        String cleanReason = reason == null ? null : reason.trim();
        if (cleanReason == null || cleanReason.isEmpty()) {
            throw new BadRequestException("Reason is required");
        }
        Clinician clinician = findClinician(systemId);
        User user = clinician.user();
        Doctor doctor = clinician.doctor();

        String status = doctor.getVerificationStatus();
        if (Doctor.VERIFICATION_REJECTED.equals(status)) {
            return doctorMapper.toResponse(doctor, user);
        }
        if (!Doctor.VERIFICATION_PENDING.equals(status)) {
            throw new ConflictException("Only a pending clinician can be rejected. "
                    + "Deactivate the account to suspend an approved clinician.");
        }

        LocalDateTime now = now();
        doctor.setVerificationStatus(Doctor.VERIFICATION_REJECTED);
        doctor.setRejectionReason(cleanReason);
        doctor.setVerifiedBySystemId(adminSystemId);
        doctor.setVerifiedAt(now);
        doctor.setIsActive(false);
        clinicianRepository.save(doctor);
        if (Boolean.TRUE.equals(user.getIsActive())) {
            user.setIsActive(false);
            adminUserRepository.save(user);
        }

        auditService.logAccountEvent(adminSystemId, "DOCTOR_REJECT", RESOURCE_ACCOUNT, systemId, "SUCCESS");
        eventPublisher.publishEvent(new DoctorVerificationDecidedEvent(systemId, adminSystemId,
                Doctor.VERIFICATION_REJECTED, cleanReason));
        if (user.getEmail() != null) {
            Map<String, String> model = new LinkedHashMap<>();
            model.put("name", user.getFirstName());
            model.put("reason", cleanReason);
            outboundMessageService.queueEmail(user.getEmail(), systemId, TEMPLATE_REJECTED, model,
                    "CLINICIAN_REJECTED:" + systemId + ":" + DEDUPE_TIME.format(now));
        }
        return doctorMapper.toResponse(doctor, user);
    }

    /**
     * Enter or correct the licence details (full replacement of the four fields; allowed in every status, never
     * changes it). Audited LICENCE_UPDATE on every successful call.
     *
     * @throws ResourceNotFoundException "Doctor not found"
     * @throws ConflictException         when another doctor holds the licence number (case-insensitive)
     */
    public AdminDoctorResponse updateLicence(String systemId, LicenceRequest request) {
        authorizationService.requireAdmin();
        String adminSystemId = authorizationService.currentSystemId();
        if (request == null || request.licenseNo() == null) {
            throw new BadRequestException("Licence number is required");
        }
        Clinician clinician = findClinician(systemId);
        Doctor doctor = clinician.doctor();

        if (clinicianRepository.licenceTakenByAnother(request.licenseNo(), systemId)) {
            throw new ConflictException("Licence number already registered");
        }
        doctor.setLicenseNo(request.licenseNo());
        doctor.setLicensingBody(request.licensingBody());
        doctor.setLicenseCountry(request.licenseCountry());
        doctor.setSpecialization(request.specialization());
        clinicianRepository.save(doctor);

        auditService.logAccountEvent(adminSystemId, "LICENCE_UPDATE", RESOURCE_LICENCE, systemId, "SUCCESS");
        return doctorMapper.toResponse(doctor, clinician.user());
    }

    // ==================== HELPERS ====================

    private record Clinician(User user, Doctor doctor) {
    }

    private Clinician findClinician(String systemId) {
        User user = adminUserRepository.findBySystemId(systemId)
                .filter(u -> "DOCTOR".equals(u.getRole()))
                .orElseThrow(() -> new ResourceNotFoundException(DOCTOR_NOT_FOUND));
        Doctor doctor = clinicianRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException(DOCTOR_NOT_FOUND));
        return new Clinician(user, doctor);
    }

    /** Approval email (when the doctor has an address) and the in-app notice, both deduplicated per decision. */
    private void sendApproval(User user, Doctor doctor, String adminSystemId) {
        String stamp = DEDUPE_TIME.format(doctor.getVerifiedAt());
        String systemId = doctor.getSystemId();
        if (user.getEmail() != null) {
            Map<String, String> model = new LinkedHashMap<>();
            model.put("name", user.getFirstName());
            model.put("link", webBaseUrl + AccountEmailLinks.WEB_LOGIN_PATH);
            outboundMessageService.queueEmail(user.getEmail(), systemId, TEMPLATE_APPROVED, model,
                    "CLINICIAN_APPROVED:" + systemId + ":" + stamp);
        }
        notificationPublisher.publish(NotificationCommand.builder()
                .recipientSystemId(systemId)
                .type(NotificationType.SYSTEM)
                .category(NotificationCategory.SYSTEM)
                .priority(NotificationPriority.NORMAL)
                .title("Your clinician account is approved")
                .message("An administrator verified your licence. You can now use your CareNexus clinician account.")
                .resourceType(RESOURCE_ACCOUNT)
                .actorSystemId(adminSystemId)
                .dedupeKey("DOCTOR_APPROVED:" + systemId + ":" + stamp)
                .build());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "http://localhost:8081";
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
