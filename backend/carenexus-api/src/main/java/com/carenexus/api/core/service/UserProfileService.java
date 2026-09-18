package com.carenexus.api.core.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.core.dto.request.CreateEmergencyContactRequest;
import com.carenexus.api.core.dto.request.UpdateUserProfileRequest;
import com.carenexus.api.core.dto.request.UpdateUserSettingsRequest;
import com.carenexus.api.core.dto.response.EmergencyContactResponse;
import com.carenexus.api.core.dto.response.UserProfileResponse;
import com.carenexus.api.core.dto.response.UserSettingsResponse;
import com.carenexus.api.core.model.EmergencyContact;
import com.carenexus.api.core.model.Patient;
import com.carenexus.api.core.model.UserSettings;
import com.carenexus.api.core.repository.EmergencyContactRepository;
import com.carenexus.api.core.repository.PatientRepository;
import com.carenexus.api.core.repository.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class UserProfileService {

    private final UserRepository userRepository;
    private final PatientRepository patientRepository;
    private final EmergencyContactRepository emergencyContactRepository;
    private final UserSettingsRepository userSettingsRepository;

    public UserProfileResponse getUserProfile(Integer userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        UserProfileResponse.UserProfileResponseBuilder builder = UserProfileResponse.builder()
                .userId(user.getUserId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .phone(user.getPhone())
                .email(user.getEmail())
                .role(user.getRole())
                .isActive(user.getIsActive())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt());

        if ("PATIENT".equals(user.getRole())) {
            Patient patient = patientRepository.findByUserId(userId)
                    .orElse(null);
            if (patient != null) {
                builder.patientId(patient.getPatientId())
                        .dateOfBirth(patient.getDateOfBirth())
                        .gender(patient.getGender())
                        .bloodType(patient.getBloodType())
                        .allergies(patient.getAllergies())
                        .chronicConditions(patient.getChronicConditions())
                        .healthStatus(patient.getHealthStatus());

                List<EmergencyContactResponse> emergencyContacts = emergencyContactRepository
                        .findByPatientId(patient.getPatientId())
                        .stream()
                        .map(this::mapEmergencyContactToResponse)
                        .collect(Collectors.toList());
                builder.emergencyContacts(emergencyContacts);
            }
        }

        return builder.build();
    }

    public UserProfileResponse updateUserProfile(Integer userId, UpdateUserProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (request.getFirstName() != null && !request.getFirstName().isEmpty()) {
            user.setFirstName(request.getFirstName());
        }
        if (request.getLastName() != null && !request.getLastName().isEmpty()) {
            user.setLastName(request.getLastName());
        }
        if (request.getPhone() != null && !request.getPhone().isEmpty()) {
            user.setPhone(request.getPhone());
        }
        if (request.getEmail() != null && !request.getEmail().isEmpty()) {
            user.setEmail(request.getEmail());
        }

        User updatedUser = userRepository.save(user);

        if ("PATIENT".equals(user.getRole())) {
            Patient patient = patientRepository.findByUserId(userId)
                    .orElse(null);
            if (patient != null) {
                if (request.getDateOfBirth() != null) {
                    patient.setDateOfBirth(request.getDateOfBirth());
                }
                if (request.getGender() != null && !request.getGender().isEmpty()) {
                    patient.setGender(request.getGender());
                }
                if (request.getBloodType() != null && !request.getBloodType().isEmpty()) {
                    patient.setBloodType(request.getBloodType());
                }
                if (request.getAllergies() != null) {
                    patient.setAllergies(request.getAllergies());
                }
                if (request.getChronicConditions() != null) {
                    patient.setChronicConditions(request.getChronicConditions());
                }
                if (request.getHealthStatus() != null && !request.getHealthStatus().isEmpty()) {
                    if (!isValidHealthStatus(request.getHealthStatus())) {
                        throw new RuntimeException("Invalid health status. Must be STABLE, MONITOR, or CRITICAL");
                    }
                    patient.setHealthStatus(request.getHealthStatus());
                }
                patientRepository.save(patient);
            }
        }

        return getUserProfile(userId);
    }

    public EmergencyContactResponse createEmergencyContact(CreateEmergencyContactRequest request) {
        if (request.getPatientId() == null) {
            throw new RuntimeException("Patient ID is required");
        }
        if (request.getName() == null || request.getName().isEmpty()) {
            throw new RuntimeException("Contact name is required");
        }
        if (request.getPhone() == null || request.getPhone().isEmpty()) {
            throw new RuntimeException("Phone number is required");
        }

        if (request.getIsPrimary() != null && request.getIsPrimary()) {
            EmergencyContact existing = emergencyContactRepository
                    .findByPatientIdAndIsPrimaryTrue(request.getPatientId())
                    .orElse(null);
            if (existing != null) {
                existing.setIsPrimary(false);
                emergencyContactRepository.save(existing);
            }
        }

        EmergencyContact contact = EmergencyContact.builder()
                .patientId(request.getPatientId())
                .name(request.getName())
                .relationship(request.getRelationship())
                .phone(request.getPhone())
                .email(request.getEmail())
                .isPrimary(request.getIsPrimary() != null ? request.getIsPrimary() : false)
                .isCaregiver(request.getIsCaregiver() != null ? request.getIsCaregiver() : false)
                .build();

        EmergencyContact savedContact = emergencyContactRepository.save(contact);
        return mapEmergencyContactToResponse(savedContact);
    }

    public List<EmergencyContactResponse> getPatientEmergencyContacts(Integer patientId) {
        return emergencyContactRepository.findByPatientId(patientId).stream()
                .map(this::mapEmergencyContactToResponse)
                .collect(Collectors.toList());
    }

    public EmergencyContactResponse getEmergencyContactById(Integer contactId) {
        EmergencyContact contact = emergencyContactRepository.findById(contactId)
                .orElseThrow(() -> new RuntimeException("Emergency contact not found"));
        return mapEmergencyContactToResponse(contact);
    }

    public EmergencyContactResponse updateEmergencyContact(Integer contactId, CreateEmergencyContactRequest request) {
        EmergencyContact contact = emergencyContactRepository.findById(contactId)
                .orElseThrow(() -> new RuntimeException("Emergency contact not found"));

        if (request.getName() != null && !request.getName().isEmpty()) {
            contact.setName(request.getName());
        }
        if (request.getRelationship() != null) {
            contact.setRelationship(request.getRelationship());
        }
        if (request.getPhone() != null && !request.getPhone().isEmpty()) {
            contact.setPhone(request.getPhone());
        }
        if (request.getEmail() != null) {
            contact.setEmail(request.getEmail());
        }
        if (request.getIsPrimary() != null && request.getIsPrimary()) {
            EmergencyContact existing = emergencyContactRepository
                    .findByPatientIdAndIsPrimaryTrue(contact.getPatientId())
                    .orElse(null);
            if (existing != null && !existing.getContactId().equals(contactId)) {
                existing.setIsPrimary(false);
                emergencyContactRepository.save(existing);
            }
            contact.setIsPrimary(true);
        } else if (request.getIsPrimary() != null && !request.getIsPrimary()) {
            contact.setIsPrimary(false);
        }
        if (request.getIsCaregiver() != null) {
            contact.setIsCaregiver(request.getIsCaregiver());
        }

        EmergencyContact updatedContact = emergencyContactRepository.save(contact);
        return mapEmergencyContactToResponse(updatedContact);
    }

    public void deleteEmergencyContact(Integer contactId) {
        emergencyContactRepository.findById(contactId)
                .orElseThrow(() -> new RuntimeException("Emergency contact not found"));
        emergencyContactRepository.deleteById(contactId);
    }

    public UserSettingsResponse getUserSettings(Integer userId) {
        UserSettings settings = userSettingsRepository.findByUserId(userId)
                .orElseGet(() -> {
                    UserSettings newSettings = UserSettings.builder()
                            .userId(userId)
                            .build();
                    return userSettingsRepository.save(newSettings);
                });
        return mapUserSettingsToResponse(settings);
    }

    public UserSettingsResponse updateUserSettings(Integer userId, UpdateUserSettingsRequest request) {
        UserSettings settings = userSettingsRepository.findByUserId(userId)
                .orElseGet(() -> {
                    UserSettings newSettings = UserSettings.builder()
                            .userId(userId)
                            .build();
                    return userSettingsRepository.save(newSettings);
                });

        if (request.getEmailNotifications() != null) {
            settings.setEmailNotifications(request.getEmailNotifications());
        }
        if (request.getSmsNotifications() != null) {
            settings.setSmsNotifications(request.getSmsNotifications());
        }
        if (request.getPushNotifications() != null) {
            settings.setPushNotifications(request.getPushNotifications());
        }
        if (request.getAppointmentReminders() != null) {
            settings.setAppointmentReminders(request.getAppointmentReminders());
        }
        if (request.getPrescriptionReminders() != null) {
            settings.setPrescriptionReminders(request.getPrescriptionReminders());
        }
        if (request.getLabResultAlerts() != null) {
            settings.setLabResultAlerts(request.getLabResultAlerts());
        }
        if (request.getImagingResultAlerts() != null) {
            settings.setImagingResultAlerts(request.getImagingResultAlerts());
        }
        if (request.getConsultationNotifications() != null) {
            settings.setConsultationNotifications(request.getConsultationNotifications());
        }
        if (request.getProfileVisibility() != null && !request.getProfileVisibility().isEmpty()) {
            if (!isValidProfileVisibility(request.getProfileVisibility())) {
                throw new RuntimeException("Invalid profile visibility. Must be PRIVATE, CONTACTS_ONLY, or PUBLIC");
            }
            settings.setProfileVisibility(request.getProfileVisibility());
        }
        if (request.getDataSharingEnabled() != null) {
            settings.setDataSharingEnabled(request.getDataSharingEnabled());
        }
        if (request.getTwoFactorEnabled() != null) {
            settings.setTwoFactorEnabled(request.getTwoFactorEnabled());
        }
        if (request.getLanguage() != null && !request.getLanguage().isEmpty()) {
            settings.setLanguage(request.getLanguage());
        }
        if (request.getTimezone() != null && !request.getTimezone().isEmpty()) {
            settings.setTimezone(request.getTimezone());
        }
        if (request.getTheme() != null && !request.getTheme().isEmpty()) {
            if (!isValidTheme(request.getTheme())) {
                throw new RuntimeException("Invalid theme. Must be LIGHT or DARK");
            }
            settings.setTheme(request.getTheme());
        }

        UserSettings updatedSettings = userSettingsRepository.save(settings);
        return mapUserSettingsToResponse(updatedSettings);
    }

    private EmergencyContactResponse mapEmergencyContactToResponse(EmergencyContact contact) {
        return EmergencyContactResponse.builder()
                .contactId(contact.getContactId())
                .patientId(contact.getPatientId())
                .name(contact.getName())
                .relationship(contact.getRelationship())
                .phone(contact.getPhone())
                .email(contact.getEmail())
                .isPrimary(contact.getIsPrimary())
                .isCaregiver(contact.getIsCaregiver())
                .createdAt(contact.getCreatedAt())
                .build();
    }

    private UserSettingsResponse mapUserSettingsToResponse(UserSettings settings) {
        return UserSettingsResponse.builder()
                .settingId(settings.getSettingId())
                .userId(settings.getUserId())
                .emailNotifications(settings.getEmailNotifications())
                .smsNotifications(settings.getSmsNotifications())
                .pushNotifications(settings.getPushNotifications())
                .appointmentReminders(settings.getAppointmentReminders())
                .prescriptionReminders(settings.getPrescriptionReminders())
                .labResultAlerts(settings.getLabResultAlerts())
                .imagingResultAlerts(settings.getImagingResultAlerts())
                .consultationNotifications(settings.getConsultationNotifications())
                .profileVisibility(settings.getProfileVisibility())
                .dataSharingEnabled(settings.getDataSharingEnabled())
                .twoFactorEnabled(settings.getTwoFactorEnabled())
                .language(settings.getLanguage())
                .timezone(settings.getTimezone())
                .theme(settings.getTheme())
                .createdAt(settings.getCreatedAt())
                .updatedAt(settings.getUpdatedAt())
                .build();
    }

    private boolean isValidHealthStatus(String status) {
        return status != null && (status.equals("STABLE") || status.equals("MONITOR") || status.equals("CRITICAL"));
    }

    private boolean isValidProfileVisibility(String visibility) {
        return visibility != null && (visibility.equals("PRIVATE") || visibility.equals("CONTACTS_ONLY") || visibility.equals("PUBLIC"));
    }

    private boolean isValidTheme(String theme) {
        return theme != null && (theme.equals("LIGHT") || theme.equals("DARK"));
    }
}
