package com.carenexus.api.core.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.core.dto.request.UpdateDoctorProfileRequest;
import com.carenexus.api.core.dto.response.DoctorProfileResponse;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.repository.DoctorDirectoryRepository;
import com.carenexus.api.core.repository.DoctorDirectoryRepository.DirectoryRow;
import com.carenexus.api.core.repository.DoctorPatientQueryRepository;
import com.carenexus.api.core.repository.DoctorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Doctor profiles and the doctor directory.
 *
 * Views of {@link DoctorProfileResponse}:
 * - full (the doctor, an administrator): every field; counters come from COUNT queries;
 * - public (directory, active list, public profile of a listed doctor): id, account id, names, specialization, bio
 *   and isActive = true;
 * - name-only (public profile of a doctor who is not listed): id, names and isActive = false.
 *
 * "Listed" (directory, bookable) = users.is_active AND users.role = 'DOCTOR' AND doctors.is_active AND
 * doctors.verification_status = 'APPROVED' ({@link DoctorDirectoryRepository}).
 *
 * The sign-in email is never changed here (email-change flow only); a doctor cannot change their licence number or
 * activation. Profile updates are audited as account events PROFILE_UPDATE / DOCTOR_PROFILE.
 *
 * The class default is read-only, so every write method carries its own @Transactional.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DoctorProfileService {

    public static final String RESOURCE = "DOCTOR_PROFILE";

    /** Rows of the deprecated active-doctors array and of the deprecated pending list. */
    public static final int LEGACY_LIST_CAP = 200;

    static final String EMAIL_REFUSED = "Email cannot be changed here. Use Change email in your security settings";
    static final String LICENCE_ADMIN_ONLY = "License number changes must be made by an administrator";

    private final DoctorRepository doctorRepository;
    private final UserRepository userRepository;
    private final DoctorDirectoryRepository directoryRepository;
    private final DoctorPatientQueryRepository patientQueryRepository;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final Clock clock;

    // ==================== OWN AND ADMIN READS (full view) ====================

    /** The calling doctor's own profile (DOCTOR only). */
    public DoctorProfileResponse getMyProfile() {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        return fullProfile(authorizationService.currentSystemId());
    }

    /**
     * Full profile of a doctor; the caller must be that doctor or an administrator.
     * 404 "User not found" / "Doctor profile not found" when a row is missing.
     */
    public DoctorProfileResponse getDoctorProfile(String systemId) {
        authorizationService.requireDoctorSelf(systemId);
        return fullProfile(systemId);
    }

    /**
     * Full profile of a doctor by system id (that doctor or an administrator); 404 "Doctor not found".
     */
    public DoctorProfileResponse getDoctorProfileById(String doctorSystemId) {
        authorizationService.requireDoctorSelf(doctorSystemId);
        if (doctorRepository.findBySystemId(doctorSystemId).isEmpty()) {
            throw new ResourceNotFoundException("Doctor not found");
        }
        return fullProfile(doctorSystemId);
    }

    // ==================== UPDATES ====================

    /** The calling doctor updates their own profile (no email, licence or activation change). */
    @Transactional
    public DoctorProfileResponse updateMyProfile(UpdateDoctorProfileRequest request) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        return applyUpdate(authorizationService.currentSystemId(), request, false);
    }

    /**
     * Partial update of a doctor profile. {@code asAdmin = true}: the caller must be an administrator and may change
     * the licence number; otherwise the caller must be that doctor and the licence number cannot change.
     * The email is never changed here and activation is never changed here.
     */
    @Transactional
    public DoctorProfileResponse updateDoctorProfile(String systemId, UpdateDoctorProfileRequest request, boolean asAdmin) {
        if (asAdmin) {
            authorizationService.requireAdmin();
        } else {
            authorizationService.requireRole(AuthorizationService.DOCTOR);
            if (!authorizationService.currentSystemId().equals(systemId)) {
                throw new AuthorizationException("You can only access your own doctor data");
            }
        }
        return applyUpdate(systemId, request, asAdmin);
    }

    private DoctorProfileResponse applyUpdate(String systemId, UpdateDoctorProfileRequest request, boolean asAdmin) {
        User user = userRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Doctor doctor = doctorRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found"));
        if (request == null) {
            return fullProfile(user, doctor);
        }

        // Refusals first, so a request that fails changes nothing.
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            throw new BadRequestException(EMAIL_REFUSED);
        }
        String licenseNo = request.getLicenseNo();
        boolean licenceChanges = licenseNo != null && !licenseNo.isBlank()
                && (doctor.getLicenseNo() == null || !licenseNo.trim().equalsIgnoreCase(doctor.getLicenseNo()));
        if (licenceChanges) {
            if (!asAdmin) {
                throw new BadRequestException(LICENCE_ADMIN_ONLY);
            }
            if (doctorRepository.existsByLicenseNoIgnoreCaseAndSystemIdNot(licenseNo.trim(), systemId)) {
                throw new ConflictException("License number is already registered to another doctor");
            }
        }
        String phone = blankToNull(request.getPhone());
        if (phone != null && !phone.equals(user.getPhone())) {
            userRepository.findByPhone(phone)
                    .filter(other -> !other.getSystemId().equals(systemId))
                    .ifPresent(other -> {
                        throw new ConflictException("Phone number already registered");
                    });
        }

        String firstName = blankToNull(request.getFirstName());
        if (firstName != null) {
            user.setFirstName(firstName);
        }
        String lastName = blankToNull(request.getLastName());
        if (lastName != null) {
            user.setLastName(lastName);
        }
        if (phone != null) {
            user.setPhone(phone);
        }
        String specialization = blankToNull(request.getSpecialization());
        if (specialization != null) {
            doctor.setSpecialization(specialization);
        }
        if (request.getBio() != null) {
            String bio = request.getBio().trim();
            doctor.setBio(bio.isEmpty() ? null : bio);
        }
        if (licenceChanges) {
            doctor.setLicenseNo(licenseNo.trim());
        }
        // request.isActive is ignored on purpose: activation is an administrator decision (be-admin-users).
        user.setUpdatedAt(LocalDateTime.now(clock));

        userRepository.save(user);
        doctorRepository.save(doctor);
        auditService.logAccountEvent(authorizationService.currentSystemId(), "PROFILE_UPDATE", RESOURCE, systemId,
                "SUCCESS");
        return fullProfile(user, doctor);
    }

    // ==================== DIRECTORY (any signed-in user, public view) ====================

    /**
     * One page of listed doctors, ordered by last name, first name, system id.
     *
     * @param query          case-insensitive "contains" on first name, last name, "first last" or specialization
     * @param specialization case-insensitive "contains" on specialization
     */
    public PageResponse<DoctorProfileResponse> getDirectory(String query, String specialization, int page, int size) {
        authorizationService.currentSystemId();
        Pageable pageable = PageParams.of(page, size, null);
        String q = blankToNull(query);
        String spec = blankToNull(specialization);
        long total = directoryRepository.countListed(q, spec);
        List<DoctorProfileResponse> content = total == 0 ? List.of()
                : directoryRepository.findListed(q, spec, pageable.getPageSize(), pageable.getOffset()).stream()
                .map(DoctorProfileService::toPublicProfile)
                .toList();
        int totalPages = (int) ((total + pageable.getPageSize() - 1) / pageable.getPageSize());
        return PageResponse.<DoctorProfileResponse>builder()
                .content(content)
                .page(pageable.getPageNumber())
                .size(pageable.getPageSize())
                .totalElements(total)
                .totalPages(totalPages)
                .build();
    }

    /**
     * Deprecated array form of the directory: the first {@link #LEGACY_LIST_CAP} listed doctors in directory order.
     */
    public List<DoctorProfileResponse> getAllActiveDoctorsProfiles() {
        authorizationService.currentSystemId();
        return directoryRepository.findListed(null, null, LEGACY_LIST_CAP, 0).stream()
                .map(DoctorProfileService::toPublicProfile)
                .toList();
    }

    /**
     * Public profile: the public view for a listed doctor; the name-only view (isActive false) for an existing doctor
     * who is not listed, so past patients still see the real name. 404 "Doctor not found" when there is no clinician
     * or account row.
     */
    public DoctorProfileResponse getPublicDoctorProfile(String doctorSystemId) {
        authorizationService.currentSystemId();
        Doctor doctor = doctorRepository.findBySystemId(doctorSystemId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));
        User user = userRepository.findBySystemId(doctorSystemId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));
        if (directoryRepository.isListed(doctorSystemId)) {
            return toPublicProfile(new DirectoryRow(doctor.getSystemId(), user.getUserId(), user.getFirstName(),
                    user.getLastName(), doctor.getSpecialization(), doctor.getBio()));
        }
        return DoctorProfileResponse.builder()
                .doctorSystemId(doctor.getSystemId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .isActive(false)
                .build();
    }

    // ==================== LEGACY ADMIN METHODS (deprecated; be-admin-users owns verification) ====================

    /**
     * Doctors awaiting verification (verification_status PENDING), oldest first, at most {@link #LEGACY_LIST_CAP}.
     *
     * @deprecated administrators use the clinician queue of the admin API.
     */
    @Deprecated
    public List<DoctorProfileResponse> getPendingDoctors() {
        authorizationService.requireAdmin();
        return doctorRepository.findByVerificationStatusOrderByCreatedAtAscDoctorIdAsc(Doctor.VERIFICATION_PENDING,
                        PageRequest.of(0, LEGACY_LIST_CAP)).stream()
                .map(doctor -> userRepository.findBySystemId(doctor.getSystemId())
                        .map(user -> fullProfile(user, doctor))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * Flips activation. Administrators only.
     *
     * @deprecated use the admin user and clinician verification endpoints.
     */
    @Deprecated
    @Transactional
    public DoctorProfileResponse toggleDoctorStatus(String systemId) {
        authorizationService.requireAdmin();
        Doctor doctor = doctorRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found"));
        return Boolean.TRUE.equals(doctor.getIsActive()) ? deactivateDoctor(systemId) : activateDoctor(systemId);
    }

    /**
     * Approves a doctor: doctors.is_active and users.is_active true, verification_status APPROVED, stamped with the
     * administrator and the time. Administrators only.
     *
     * @deprecated use the admin clinician verification endpoints (they also check the licence).
     */
    @Deprecated
    @Transactional
    public DoctorProfileResponse activateDoctor(String systemId) {
        authorizationService.requireAdmin();
        return setDoctorActive(systemId, true);
    }

    /**
     * Suspends a doctor: both active flags false, verification_status SUSPENDED, stamped with the administrator and
     * the time. The caller should also revoke the doctor's sessions; the JWT filter already rejects inactive users.
     * Administrators only.
     *
     * @deprecated use the admin user deactivation endpoint.
     */
    @Deprecated
    @Transactional
    public DoctorProfileResponse deactivateDoctor(String systemId) {
        authorizationService.requireAdmin();
        return setDoctorActive(systemId, false);
    }

    private DoctorProfileResponse setDoctorActive(String systemId, boolean active) {
        Doctor doctor = doctorRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found"));
        User user = userRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        LocalDateTime now = LocalDateTime.now(clock);
        doctor.setIsActive(active);
        doctor.setVerificationStatus(active ? Doctor.VERIFICATION_APPROVED : Doctor.VERIFICATION_SUSPENDED);
        doctor.setVerifiedBySystemId(authorizationService.currentSystemId());
        doctor.setVerifiedAt(now);
        if (active) {
            doctor.setRejectionReason(null);
        }
        user.setIsActive(active);
        user.setUpdatedAt(now);

        userRepository.save(user);
        doctorRepository.save(doctor);
        auditService.logAccountEvent(authorizationService.currentSystemId(),
                active ? "DOCTOR_VERIFY" : "USER_DEACTIVATE", RESOURCE, systemId, "SUCCESS");
        return fullProfile(user, doctor);
    }

    // ==================== VIEWS ====================

    private DoctorProfileResponse fullProfile(String systemId) {
        User user = userRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Doctor doctor = doctorRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found"));
        return fullProfile(user, doctor);
    }

    /** Full view; every counter is a COUNT query. */
    private DoctorProfileResponse fullProfile(User user, Doctor doctor) {
        String id = doctor.getSystemId();
        return DoctorProfileResponse.builder()
                .doctorSystemId(id)
                .userId(user.getUserId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .specialization(doctor.getSpecialization())
                .licenseNo(doctor.getLicenseNo())
                .licensingBody(doctor.getLicensingBody())
                .licenseCountry(doctor.getLicenseCountry())
                .verificationStatus(doctor.getVerificationStatus())
                .bio(doctor.getBio())
                .isActive(doctor.getIsActive())
                .role(user.getRole())
                .totalPatients(toInt(patientQueryRepository.countRelatedPatients(id,
                        DoctorPatientQueryRepository.Filter.NONE)))
                .totalAppointments(toInt(doctorRepository.countAppointmentsOfDoctor(id)))
                .totalConsultations(toInt(doctorRepository.countConsultationsOfDoctor(id)))
                .completedConsultations(toInt(doctorRepository.countCompletedConsultationsOfDoctor(id)))
                .totalPrescriptions(toInt(doctorRepository.countPrescriptionsOfDoctor(id)))
                .totalMedicalRecords(toInt(doctorRepository.countMedicalRecordsOfDoctor(id)))
                .doctorCreatedAt(doctor.getCreatedAt())
                .userCreatedAt(user.getCreatedAt())
                .userUpdatedAt(user.getUpdatedAt())
                .build();
    }

    /** Public view: no email, phone, licence, verification data, counters or timestamps. */
    private static DoctorProfileResponse toPublicProfile(DirectoryRow row) {
        return DoctorProfileResponse.builder()
                .doctorSystemId(row.doctorSystemId())
                .userId(row.userId())
                .firstName(row.firstName())
                .lastName(row.lastName())
                .specialization(row.specialization())
                .bio(row.bio())
                .isActive(true)
                .build();
    }

    private static int toInt(long value) {
        return (int) Math.min(value, Integer.MAX_VALUE);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
