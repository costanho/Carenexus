package com.carenexus.api.core.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class UserProfileService {

    private final UserRepository userRepository;
    private final PatientRepository patientRepository;
    private final EmergencyContactRepository emergencyContactRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final ObjectMapper objectMapper;

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");

    /**
     * Uppercase role of the user (PATIENT, DOCTOR, CAREGIVER, ADMIN). 404 if the user does not exist.
     */
    public String getUserRole(String systemId) {
        return userRepository.findBySystemId(systemId)
                .map(User::getRole)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    /**
     * Patient that owns this emergency contact (used for authorization before reading/changing it).
     */
    public String getEmergencyContactPatientSystemId(Integer contactId) {
        return emergencyContactRepository.findById(contactId)
                .map(EmergencyContact::getPatientSystemId)
                .orElseThrow(() -> new ResourceNotFoundException("Emergency contact not found"));
    }

    public UserProfileResponse getUserProfile(String systemId) {
        User user = userRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

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
            Patient patient = patientRepository.findBySystemId(systemId)
                    .orElse(null);
            if (patient != null) {
                builder.patientSystemId(patient.getSystemId())
                        .dateOfBirth(patient.getDateOfBirth())
                        .gender(patient.getGender())
                        .bloodType(patient.getBloodType())
                        .allergies(patient.getAllergies())
                        .chronicConditions(patient.getChronicConditions())
                        .healthStatus(patient.getHealthStatus());

                List<EmergencyContactResponse> emergencyContacts = emergencyContactRepository
                        .findByPatientSystemId(systemId)
                        .stream()
                        .map(this::mapEmergencyContactToResponse)
                        .collect(Collectors.toList());
                builder.emergencyContacts(emergencyContacts);
            }
        }

        return builder.build();
    }

    public UserProfileResponse updateUserProfile(String systemId, UpdateUserProfileRequest request) {
        User user = userRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (request.getFirstName() != null && !request.getFirstName().isEmpty()) {
            user.setFirstName(request.getFirstName());
        }
        if (request.getLastName() != null && !request.getLastName().isEmpty()) {
            user.setLastName(request.getLastName());
        }
        if (request.getPhone() != null && !request.getPhone().isBlank()) {
            String phone = request.getPhone().trim();
            if (phone.length() > 20) {
                throw new RuntimeException("Phone number must be at most 20 characters");
            }
            if (!phone.equals(user.getPhone())) {
                userRepository.findByPhone(phone)
                        .filter(other -> !other.getSystemId().equals(systemId))
                        .ifPresent(other -> {
                            throw new ConflictException("Phone number already registered");
                        });
            }
            user.setPhone(phone);
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

        User updatedUser = userRepository.save(user);

        if ("PATIENT".equals(user.getRole())) {
            Patient patient = patientRepository.findBySystemId(systemId)
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
                    patient.setAllergies(requireJsonArray(request.getAllergies(), "Allergies"));
                }
                if (request.getChronicConditions() != null) {
                    patient.setChronicConditions(requireJsonArray(request.getChronicConditions(), "Chronic conditions"));
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

        return getUserProfile(systemId);
    }

    public EmergencyContactResponse createEmergencyContact(CreateEmergencyContactRequest request) {
        if (request.getPatientSystemId() == null || request.getPatientSystemId().isBlank()) {
            throw new RuntimeException("Patient System ID is required");
        }
        if (patientRepository.findBySystemId(request.getPatientSystemId()).isEmpty()) {
            throw new ResourceNotFoundException("Patient not found");
        }
        if (request.getName() == null || request.getName().isEmpty()) {
            throw new RuntimeException("Contact name is required");
        }
        if (request.getPhone() == null || request.getPhone().isEmpty()) {
            throw new RuntimeException("Phone number is required");
        }

        if (request.getIsPrimary() != null && request.getIsPrimary()) {
            EmergencyContact existing = emergencyContactRepository
                    .findByPatientSystemIdAndIsPrimaryTrue(request.getPatientSystemId())
                    .orElse(null);
            if (existing != null) {
                existing.setIsPrimary(false);
                emergencyContactRepository.save(existing);
            }
        }

        EmergencyContact contact = EmergencyContact.builder()
                .patientSystemId(request.getPatientSystemId())
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

    public List<EmergencyContactResponse> getPatientEmergencyContacts(String patientSystemId) {
        return emergencyContactRepository.findByPatientSystemId(patientSystemId).stream()
                .map(this::mapEmergencyContactToResponse)
                .collect(Collectors.toList());
    }

    public EmergencyContactResponse getEmergencyContactById(Integer contactId) {
        EmergencyContact contact = emergencyContactRepository.findById(contactId)
                .orElseThrow(() -> new ResourceNotFoundException("Emergency contact not found"));
        return mapEmergencyContactToResponse(contact);
    }

    public EmergencyContactResponse updateEmergencyContact(Integer contactId, CreateEmergencyContactRequest request) {
        EmergencyContact contact = emergencyContactRepository.findById(contactId)
                .orElseThrow(() -> new ResourceNotFoundException("Emergency contact not found"));

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
                    .findByPatientSystemIdAndIsPrimaryTrue(contact.getPatientSystemId())
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
                .orElseThrow(() -> new ResourceNotFoundException("Emergency contact not found"));
        emergencyContactRepository.deleteById(contactId);
    }

    public UserSettingsResponse getUserSettings(String systemId) {
        UserSettings settings = userSettingsRepository.findBySystemId(systemId)
                .orElseGet(() -> {
                    UserSettings newSettings = UserSettings.builder()
                            .systemId(systemId)
                            .build();
                    return userSettingsRepository.save(newSettings);
                });
        return mapUserSettingsToResponse(settings);
    }

    public UserSettingsResponse updateUserSettings(String systemId, UpdateUserSettingsRequest request) {
        UserSettings settings = userSettingsRepository.findBySystemId(systemId)
                .orElseGet(() -> {
                    UserSettings newSettings = UserSettings.builder()
                            .systemId(systemId)
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
        if (request.getCriticalAlerts() != null) {
            settings.setCriticalAlerts(request.getCriticalAlerts());
        }
        if (request.getMedicationReminders() != null) {
            settings.setMedicationReminders(request.getMedicationReminders());
        }
        if (request.getVitalsAlerts() != null) {
            settings.setVitalsAlerts(request.getVitalsAlerts());
        }
        if (request.getAppointmentReminder1h() != null) {
            settings.setAppointmentReminder1h(request.getAppointmentReminder1h());
        }
        if (request.getAppointmentChangeAlerts() != null) {
            settings.setAppointmentChangeAlerts(request.getAppointmentChangeAlerts());
        }
        if (request.getVideoCallAlerts() != null) {
            settings.setVideoCallAlerts(request.getVideoCallAlerts());
        }
        if (request.getMessageNotifications() != null) {
            settings.setMessageNotifications(request.getMessageNotifications());
        }
        if (request.getCarePlanUpdateAlerts() != null) {
            settings.setCarePlanUpdateAlerts(request.getCarePlanUpdateAlerts());
        }
        if (request.getReferralAlerts() != null) {
            settings.setReferralAlerts(request.getReferralAlerts());
        }
        if (request.getLoginAlerts() != null) {
            settings.setLoginAlerts(request.getLoginAlerts());
        }
        if (request.getAccessChangeAlerts() != null) {
            settings.setAccessChangeAlerts(request.getAccessChangeAlerts());
        }
        if (request.getWeeklySummary() != null) {
            settings.setWeeklySummary(request.getWeeklySummary());
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
            // No second factor is implemented yet; storing TRUE would claim protection that does not exist
            if (request.getTwoFactorEnabled()) {
                throw new RuntimeException("Two-factor authentication is not available yet");
            }
            settings.setTwoFactorEnabled(false);
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
                .patientSystemId(contact.getPatientSystemId())
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
                .systemId(settings.getSystemId())
                .emailNotifications(settings.getEmailNotifications())
                .smsNotifications(settings.getSmsNotifications())
                .pushNotifications(settings.getPushNotifications())
                .appointmentReminders(settings.getAppointmentReminders())
                .prescriptionReminders(settings.getPrescriptionReminders())
                .labResultAlerts(settings.getLabResultAlerts())
                .imagingResultAlerts(settings.getImagingResultAlerts())
                .consultationNotifications(settings.getConsultationNotifications())
                .criticalAlerts(settings.getCriticalAlerts())
                .medicationReminders(settings.getMedicationReminders())
                .vitalsAlerts(settings.getVitalsAlerts())
                .appointmentReminder1h(settings.getAppointmentReminder1h())
                .appointmentChangeAlerts(settings.getAppointmentChangeAlerts())
                .videoCallAlerts(settings.getVideoCallAlerts())
                .messageNotifications(settings.getMessageNotifications())
                .carePlanUpdateAlerts(settings.getCarePlanUpdateAlerts())
                .referralAlerts(settings.getReferralAlerts())
                .loginAlerts(settings.getLoginAlerts())
                .accessChangeAlerts(settings.getAccessChangeAlerts())
                .weeklySummary(settings.getWeeklySummary())
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

    /**
     * allergies / chronic_conditions are jsonb: the value must be a JSON array (e.g. ["Penicillin"]),
     * otherwise Postgres rejects it with an opaque error.
     */
    private String requireJsonArray(String value, String label) {
        if (value.isBlank()) {
            return "[]";
        }
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node == null || !node.isArray()) {
                throw new RuntimeException(label + " must be a list");
            }
            return node.toString();
        } catch (JsonProcessingException e) {
            throw new RuntimeException(label + " must be a list");
        }
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
