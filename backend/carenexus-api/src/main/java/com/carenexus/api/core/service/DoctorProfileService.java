package com.carenexus.api.core.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.core.dto.request.UpdateDoctorProfileRequest;
import com.carenexus.api.core.dto.response.DoctorProfileResponse;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.Consultation;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.model.MedicalRecord;
import com.carenexus.api.core.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Doctor profiles.
 *
 * Visibility: the full profile (email, phone, licence number, practice statistics) is for the doctor and admins.
 * Directory methods (list/search/public) return {@link #toPublicProfile} views and only include "listable"
 * doctors: users.is_active AND doctors.is_active. Self-registered doctors start with both flags false
 * (pending verification) until an admin calls {@link #activateDoctor}.
 *
 * The class default is read-only, so every write method MUST carry its own @Transactional; otherwise the
 * changes are silently never flushed.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DoctorProfileService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");

    private final DoctorRepository doctorRepository;
    private final UserRepository userRepository;
    private final AppointmentRepository appointmentRepository;
    private final ConsultationRepository consultationRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final MedicalRecordRepository medicalRecordRepository;
    private final PatientRepository patientRepository;

    public DoctorProfileResponse getDoctorProfile(String systemId) {
        User user = userRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Doctor doctor = doctorRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found"));

        List<Appointment> appointments = appointmentRepository.findByDoctorSystemId(systemId);
        List<Consultation> consultations = consultationRepository.findByDoctorSystemId(systemId);

        Set<String> uniquePatients = appointments.stream()
                .map(Appointment::getPatientSystemId)
                .collect(Collectors.toSet());

        long completedConsultations = consultations.stream()
                .filter(c -> "COMPLETED".equals(c.getStatus()))
                .count();

        List<MedicalRecord> medicalRecords = medicalRecordRepository.findByDoctorSystemIdAndIsDeletedFalse(systemId);

        int totalPrescriptions = prescriptionRepository.findByDoctorSystemId(systemId).size();

        return DoctorProfileResponse.builder()
                .doctorSystemId(doctor.getSystemId())
                .userId(user.getUserId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .specialization(doctor.getSpecialization())
                .licenseNo(doctor.getLicenseNo())
                .bio(doctor.getBio())
                .isActive(doctor.getIsActive())
                .role(user.getRole())
                .totalPatients(uniquePatients.size())
                .totalAppointments(appointments.size())
                .totalConsultations(consultations.size())
                .completedConsultations((int) completedConsultations)
                .totalPrescriptions(totalPrescriptions)
                .totalMedicalRecords(medicalRecords.size())
                .doctorCreatedAt(doctor.getCreatedAt())
                .userCreatedAt(user.getCreatedAt())
                .userUpdatedAt(user.getUpdatedAt())
                .build();
    }

    /**
     * Update a doctor profile. Activation state is never changed here (use the admin status endpoints).
     * The licence number is part of clinician verification, so only an admin ({@code asAdmin}) may change it.
     */
    @Transactional
    public DoctorProfileResponse updateDoctorProfile(String systemId, UpdateDoctorProfileRequest request, boolean asAdmin) {
        User user = userRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Doctor doctor = doctorRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found"));

        if (request.getFirstName() != null && !request.getFirstName().isBlank()) {
            user.setFirstName(requireMaxLength(request.getFirstName().trim(), 100, "First name"));
        }
        if (request.getLastName() != null && !request.getLastName().isBlank()) {
            user.setLastName(requireMaxLength(request.getLastName().trim(), 100, "Last name"));
        }
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
            if (email.length() > 150 || !EMAIL_PATTERN.matcher(email).matches()) {
                throw new RuntimeException("Please enter a valid email address");
            }
            if (!email.equalsIgnoreCase(user.getEmail()) && userRepository.existsByEmailIgnoreCase(email)) {
                throw new ConflictException("Email already registered");
            }
            user.setEmail(email);
        }
        if (request.getPhone() != null && !request.getPhone().isBlank()) {
            String phone = requireMaxLength(request.getPhone().trim(), 20, "Phone number");
            userRepository.findByPhone(phone)
                    .filter(other -> !other.getSystemId().equals(systemId))
                    .ifPresent(other -> {
                        throw new ConflictException("Phone number already registered");
                    });
            user.setPhone(phone);
        }

        if (request.getSpecialization() != null && !request.getSpecialization().isBlank()) {
            doctor.setSpecialization(requireMaxLength(request.getSpecialization().trim(), 150, "Specialization"));
        }
        if (request.getLicenseNo() != null && !request.getLicenseNo().isBlank()
                && !request.getLicenseNo().trim().equals(doctor.getLicenseNo())) {
            if (!asAdmin) {
                throw new RuntimeException("License number changes must be made by an administrator");
            }
            String licenseNo = requireMaxLength(request.getLicenseNo().trim(), 50, "License number");
            doctorRepository.findByLicenseNo(licenseNo)
                    .filter(other -> !other.getSystemId().equals(systemId))
                    .ifPresent(other -> {
                        throw new ConflictException("License number is already registered to another doctor");
                    });
            doctor.setLicenseNo(licenseNo);
        }
        if (request.getBio() != null) {
            doctor.setBio(request.getBio());
        }
        // request.isActive is ignored on purpose: activation is the admin verification path

        userRepository.save(user);
        doctorRepository.save(doctor);

        return getDoctorProfile(systemId);
    }

    /**
     * Full profile of a doctor by system ID (caller must be that doctor or an admin).
     */
    public DoctorProfileResponse getDoctorProfileById(String doctorSystemId) {
        Doctor doctor = doctorRepository.findBySystemId(doctorSystemId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));

        return getDoctorProfile(doctor.getSystemId());
    }

    /**
     * Public directory view of one doctor. Pending or deactivated doctors are reported as not found.
     */
    public DoctorProfileResponse getPublicDoctorProfile(String doctorSystemId) {
        Doctor doctor = doctorRepository.findBySystemId(doctorSystemId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));
        User user = userRepository.findBySystemId(doctorSystemId)
                .filter(u -> isListable(doctor, u))
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));
        return toPublicProfile(doctor, user);
    }

    /**
     * Doctors that are verified/active and whose account is active.
     */
    public List<Doctor> getAllActiveDoctors() {
        return doctorRepository.findAll().stream()
                .filter(doctor -> userRepository.findBySystemId(doctor.getSystemId())
                        .map(user -> isListable(doctor, user))
                        .orElse(false))
                .collect(Collectors.toList());
    }

    /**
     * Public directory (used by patients for booking).
     */
    public List<DoctorProfileResponse> getAllActiveDoctorsProfiles() {
        return toPublicProfiles(getAllActiveDoctors());
    }

    public List<DoctorProfileResponse> searchDoctorsBySpecialization(String specialization) {
        String term = specialization == null ? "" : specialization.toLowerCase(Locale.ROOT);
        return toPublicProfiles(getAllActiveDoctors().stream()
                .filter(doctor -> doctor.getSpecialization() != null &&
                        doctor.getSpecialization().toLowerCase(Locale.ROOT).contains(term))
                .collect(Collectors.toList()));
    }

    public List<DoctorProfileResponse> searchDoctorsByName(String searchTerm) {
        String lowerSearchTerm = searchTerm == null ? "" : searchTerm.toLowerCase(Locale.ROOT);
        return getAllActiveDoctors().stream()
                .map(doctor -> userRepository.findBySystemId(doctor.getSystemId())
                        .filter(user -> (user.getFirstName() + " " + user.getLastName()).toLowerCase(Locale.ROOT).contains(lowerSearchTerm))
                        .map(user -> toPublicProfile(doctor, user))
                        .orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Public filter: listable doctors, optionally by specialization.
     */
    public List<DoctorProfileResponse> getActiveDoctorsBySpecialization(String specialization) {
        if (specialization == null || specialization.isBlank()) {
            return getAllActiveDoctorsProfiles();
        }
        return searchDoctorsBySpecialization(specialization);
    }

    /**
     * Admin filter over ALL doctors (including pending / deactivated), full profiles.
     */
    public List<DoctorProfileResponse> getDoctorsBySpecializationAndActive(String specialization, Boolean isActive) {
        return doctorRepository.findAll().stream()
                .filter(doctor -> (isActive == null || isActive.equals(doctor.getIsActive())) &&
                        (specialization == null || (doctor.getSpecialization() != null &&
                                doctor.getSpecialization().toLowerCase(Locale.ROOT).contains(specialization.toLowerCase(Locale.ROOT)))))
                .map(doctor -> getDoctorProfile(doctor.getSystemId()))
                .collect(Collectors.toList());
    }

    /**
     * Doctors awaiting verification: users.is_active = false AND doctors.is_active = false.
     */
    public List<DoctorProfileResponse> getPendingDoctors() {
        return doctorRepository.findAll().stream()
                .filter(doctor -> !Boolean.TRUE.equals(doctor.getIsActive()))
                .filter(doctor -> userRepository.findBySystemId(doctor.getSystemId())
                        .map(user -> !Boolean.TRUE.equals(user.getIsActive()))
                        .orElse(false))
                .map(doctor -> getDoctorProfile(doctor.getSystemId()))
                .collect(Collectors.toList());
    }

    @Transactional
    public DoctorProfileResponse updateDoctorSpecialization(String systemId, String specialization) {
        Doctor doctor = doctorRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found"));

        if (specialization == null || specialization.isBlank()) {
            throw new RuntimeException("Specialization is required");
        }
        doctor.setSpecialization(requireMaxLength(specialization.trim(), 150, "Specialization"));
        doctorRepository.save(doctor);

        return getDoctorProfile(systemId);
    }

    @Transactional
    public DoctorProfileResponse updateDoctorBio(String systemId, String bio) {
        Doctor doctor = doctorRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found"));

        doctor.setBio(bio);
        doctorRepository.save(doctor);

        return getDoctorProfile(systemId);
    }

    /**
     * Admin only: flips activation (both doctors.is_active and users.is_active).
     */
    @Transactional
    public DoctorProfileResponse toggleDoctorStatus(String systemId) {
        Doctor doctor = doctorRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found"));

        return Boolean.TRUE.equals(doctor.getIsActive()) ? deactivateDoctor(systemId) : activateDoctor(systemId);
    }

    /**
     * Admin only: verifies / re-activates a doctor (sets doctors.is_active and users.is_active to true).
     * This is the approval path for self-registered doctors.
     */
    @Transactional
    public DoctorProfileResponse activateDoctor(String systemId) {
        return setDoctorActive(systemId, true);
    }

    /**
     * Admin only: deactivates a doctor (both flags false). The caller should also revoke the doctor's
     * refresh tokens (AuthService.logout); the JWT filter already rejects inactive users on every request.
     */
    @Transactional
    public DoctorProfileResponse deactivateDoctor(String systemId) {
        return setDoctorActive(systemId, false);
    }

    private DoctorProfileResponse setDoctorActive(String systemId, boolean active) {
        Doctor doctor = doctorRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found"));

        User user = userRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        doctor.setIsActive(active);
        user.setIsActive(active);

        userRepository.save(user);
        doctorRepository.save(doctor);

        return getDoctorProfile(systemId);
    }

    // ==================== HELPERS ====================

    private static boolean isListable(Doctor doctor, User user) {
        return Boolean.TRUE.equals(doctor.getIsActive())
                && Boolean.TRUE.equals(user.getIsActive())
                && "DOCTOR".equals(user.getRole());
    }

    private List<DoctorProfileResponse> toPublicProfiles(List<Doctor> doctors) {
        return doctors.stream()
                .map(doctor -> userRepository.findBySystemId(doctor.getSystemId())
                        .map(user -> toPublicProfile(doctor, user))
                        .orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Directory view: no email, phone, licence number, practice statistics or timestamps.
     */
    private DoctorProfileResponse toPublicProfile(Doctor doctor, User user) {
        return DoctorProfileResponse.builder()
                .doctorSystemId(doctor.getSystemId())
                .userId(user.getUserId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .specialization(doctor.getSpecialization())
                .bio(doctor.getBio())
                .isActive(doctor.getIsActive())
                .build();
    }

    private static String requireMaxLength(String value, int max, String label) {
        if (value.length() > max) {
            throw new RuntimeException(label + " must be at most " + max + " characters");
        }
        return value;
    }
}
