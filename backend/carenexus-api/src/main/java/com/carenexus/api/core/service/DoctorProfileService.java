package com.carenexus.api.core.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
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
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DoctorProfileService {

    private final DoctorRepository doctorRepository;
    private final UserRepository userRepository;
    private final AppointmentRepository appointmentRepository;
    private final ConsultationRepository consultationRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final MedicalRecordRepository medicalRecordRepository;
    private final PatientRepository patientRepository;

    public DoctorProfileResponse getDoctorProfile(Integer userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Doctor doctor = doctorRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Doctor profile not found"));

        List<Appointment> appointments = appointmentRepository.findByDoctorId(doctor.getDoctorId());
        List<Consultation> consultations = consultationRepository.findByDoctorId(doctor.getDoctorId());

        Set<Integer> uniquePatients = appointments.stream()
                .map(Appointment::getPatientId)
                .collect(Collectors.toSet());

        long completedConsultations = consultations.stream()
                .filter(c -> "COMPLETED".equals(c.getStatus()))
                .count();

        List<MedicalRecord> medicalRecords = medicalRecordRepository.findByDoctorIdAndIsDeletedFalse(doctor.getDoctorId());

        int totalPrescriptions = prescriptionRepository.findByDoctorId(doctor.getDoctorId()).size();

        return DoctorProfileResponse.builder()
                .doctorId(doctor.getDoctorId())
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

    @Transactional
    public DoctorProfileResponse updateDoctorProfile(Integer userId, UpdateDoctorProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Doctor doctor = doctorRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Doctor profile not found"));

        if (request.getFirstName() != null && !request.getFirstName().isEmpty()) {
            user.setFirstName(request.getFirstName());
        }
        if (request.getLastName() != null && !request.getLastName().isEmpty()) {
            user.setLastName(request.getLastName());
        }
        if (request.getEmail() != null && !request.getEmail().isEmpty()) {
            user.setEmail(request.getEmail());
        }
        if (request.getPhone() != null && !request.getPhone().isEmpty()) {
            user.setPhone(request.getPhone());
        }

        if (request.getSpecialization() != null && !request.getSpecialization().isEmpty()) {
            doctor.setSpecialization(request.getSpecialization());
        }
        if (request.getLicenseNo() != null && !request.getLicenseNo().isEmpty()) {
            doctor.setLicenseNo(request.getLicenseNo());
        }
        if (request.getBio() != null) {
            doctor.setBio(request.getBio());
        }
        if (request.getIsActive() != null) {
            doctor.setIsActive(request.getIsActive());
            user.setIsActive(request.getIsActive());
        }

        userRepository.save(user);
        doctorRepository.save(doctor);

        return getDoctorProfile(userId);
    }

    public DoctorProfileResponse getDoctorProfileById(Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));

        return getDoctorProfile(doctor.getUserId());
    }

    public List<Doctor> getAllActiveDoctors() {
        return doctorRepository.findAll().stream()
                .filter(Doctor::getIsActive)
                .collect(Collectors.toList());
    }

    public List<DoctorProfileResponse> getAllActiveDoctorsProfiles() {
        return getAllActiveDoctors().stream()
                .map(doctor -> getDoctorProfile(doctor.getUserId()))
                .collect(Collectors.toList());
    }

    public List<DoctorProfileResponse> searchDoctorsBySpecialization(String specialization) {
        return getAllActiveDoctors().stream()
                .filter(doctor -> doctor.getSpecialization() != null &&
                        doctor.getSpecialization().toLowerCase().contains(specialization.toLowerCase()))
                .map(doctor -> getDoctorProfile(doctor.getUserId()))
                .collect(Collectors.toList());
    }

    public List<DoctorProfileResponse> searchDoctorsByName(String searchTerm) {
        String lowerSearchTerm = searchTerm.toLowerCase();
        return getAllActiveDoctors().stream()
                .filter(doctor -> {
                    User user = userRepository.findById(doctor.getUserId()).orElse(null);
                    return user != null && (user.getFirstName().toLowerCase().contains(lowerSearchTerm) ||
                            user.getLastName().toLowerCase().contains(lowerSearchTerm) ||
                            user.getEmail().toLowerCase().contains(lowerSearchTerm));
                })
                .map(doctor -> getDoctorProfile(doctor.getUserId()))
                .collect(Collectors.toList());
    }

    public List<DoctorProfileResponse> getDoctorsBySpecializationAndActive(String specialization, Boolean isActive) {
        return doctorRepository.findAll().stream()
                .filter(doctor -> (isActive == null || doctor.getIsActive().equals(isActive)) &&
                        (specialization == null || (doctor.getSpecialization() != null &&
                                doctor.getSpecialization().toLowerCase().contains(specialization.toLowerCase()))))
                .map(doctor -> getDoctorProfile(doctor.getUserId()))
                .collect(Collectors.toList());
    }

    public DoctorProfileResponse updateDoctorSpecialization(Integer userId, String specialization) {
        Doctor doctor = doctorRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Doctor profile not found"));

        doctor.setSpecialization(specialization);
        doctorRepository.save(doctor);

        return getDoctorProfile(userId);
    }

    public DoctorProfileResponse updateDoctorBio(Integer userId, String bio) {
        Doctor doctor = doctorRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Doctor profile not found"));

        doctor.setBio(bio);
        doctorRepository.save(doctor);

        return getDoctorProfile(userId);
    }

    @Transactional
    public DoctorProfileResponse toggleDoctorStatus(Integer userId) {
        Doctor doctor = doctorRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Doctor profile not found"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        boolean newStatus = !doctor.getIsActive();
        doctor.setIsActive(newStatus);
        user.setIsActive(newStatus);

        userRepository.save(user);
        doctorRepository.save(doctor);

        return getDoctorProfile(userId);
    }

    public DoctorProfileResponse activateDoctor(Integer userId) {
        Doctor doctor = doctorRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Doctor profile not found"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        doctor.setIsActive(true);
        user.setIsActive(true);

        userRepository.save(user);
        doctorRepository.save(doctor);

        return getDoctorProfile(userId);
    }

    public DoctorProfileResponse deactivateDoctor(Integer userId) {
        Doctor doctor = doctorRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Doctor profile not found"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        doctor.setIsActive(false);
        user.setIsActive(false);

        userRepository.save(user);
        doctorRepository.save(doctor);

        return getDoctorProfile(userId);
    }
}
