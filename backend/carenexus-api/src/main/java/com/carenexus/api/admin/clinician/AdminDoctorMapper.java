package com.carenexus.api.admin.clinician;

import com.carenexus.api.admin.clinician.dto.response.AdminDoctorResponse;
import com.carenexus.api.admin.clinician.provider.LicenseRegistryClient;
import com.carenexus.api.admin.clinician.provider.RegistryStatus;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.core.model.Doctor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Builds {@link AdminDoctorResponse} from a doctors row and its users row, asking the licence registry seam for
 * {@code registryStatus}. A doctor without a licence number is not looked up ({@code UNVERIFIED_MANUAL_REVIEW}).
 */
@Component
@RequiredArgsConstructor
public class AdminDoctorMapper {

    private final LicenseRegistryClient licenseRegistryClient;

    public AdminDoctorResponse toResponse(Doctor doctor, User user) {
        return new AdminDoctorResponse(
                doctor.getSystemId(),
                user.getUserId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getPhone(),
                user.getEmailVerifiedAt() != null,
                doctor.getSpecialization(),
                doctor.getLicenseNo(),
                doctor.getLicensingBody(),
                doctor.getLicenseCountry(),
                doctor.getVerificationStatus(),
                registryStatus(doctor).name(),
                doctor.getRejectionReason(),
                doctor.getVerifiedBySystemId(),
                doctor.getVerifiedAt(),
                Boolean.TRUE.equals(doctor.getIsActive()),
                doctor.getCreatedAt());
    }

    private RegistryStatus registryStatus(Doctor doctor) {
        String licenseNo = doctor.getLicenseNo();
        if (licenseNo == null || licenseNo.isBlank()) {
            return RegistryStatus.UNVERIFIED_MANUAL_REVIEW;
        }
        RegistryStatus status = licenseRegistryClient.lookup(licenseNo, doctor.getLicensingBody(), doctor.getLicenseCountry());
        return status == null ? RegistryStatus.UNVERIFIED_MANUAL_REVIEW : status;
    }
}
