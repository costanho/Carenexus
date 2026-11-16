package com.carenexus.api.communications.notify;

import com.carenexus.api.core.model.UserSettings;
import com.carenexus.api.core.repository.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Decides whether a user wants an in-app notification of a category. Every category except SYSTEM maps to one
 * {@code user_settings} toggle; a user without a settings row gets the column defaults (everything on except
 * care-plan updates).
 *
 * Always delivered, whatever the toggles say: priority CRITICAL and category SYSTEM. The category CRITICAL
 * (clinical alerts) follows {@code critical_alerts} for priorities LOW to HIGH only.
 *
 * Channel flags (email, SMS, push) and quiet hours do not affect in-app notifications; the delivery fan-out
 * evaluates them.
 */
@Component
@RequiredArgsConstructor
public class NotificationPreferences {

    /** Category to the user_settings toggle that can switch it off. SYSTEM has none. */
    private static final Map<NotificationCategory, Function<UserSettings, Boolean>> TOGGLES =
            new EnumMap<>(NotificationCategory.class);

    static {
        TOGGLES.put(NotificationCategory.APPOINTMENT_REMINDER, UserSettings::getAppointmentReminders);
        TOGGLES.put(NotificationCategory.APPOINTMENT_REMINDER_1H, UserSettings::getAppointmentReminder1h);
        TOGGLES.put(NotificationCategory.APPOINTMENT_CHANGE, UserSettings::getAppointmentChangeAlerts);
        TOGGLES.put(NotificationCategory.MEDICATION_REMINDER, UserSettings::getMedicationReminders);
        TOGGLES.put(NotificationCategory.PRESCRIPTION, UserSettings::getPrescriptionReminders);
        TOGGLES.put(NotificationCategory.LAB_RESULT, UserSettings::getLabResultAlerts);
        TOGGLES.put(NotificationCategory.IMAGING_RESULT, UserSettings::getImagingResultAlerts);
        TOGGLES.put(NotificationCategory.CONSULTATION, UserSettings::getConsultationNotifications);
        TOGGLES.put(NotificationCategory.VITALS, UserSettings::getVitalsAlerts);
        TOGGLES.put(NotificationCategory.REFERRAL, UserSettings::getReferralAlerts);
        TOGGLES.put(NotificationCategory.CARE_PLAN, UserSettings::getCarePlanUpdateAlerts);
        TOGGLES.put(NotificationCategory.ACCESS_CHANGE, UserSettings::getAccessChangeAlerts);
        TOGGLES.put(NotificationCategory.LOGIN_ALERT, UserSettings::getLoginAlerts);
        TOGGLES.put(NotificationCategory.BILLING, UserSettings::getBillingNotifications);
        TOGGLES.put(NotificationCategory.VIDEO_CALL, UserSettings::getVideoCallAlerts);
        TOGGLES.put(NotificationCategory.MESSAGE, UserSettings::getMessageNotifications);
        TOGGLES.put(NotificationCategory.CRITICAL, UserSettings::getCriticalAlerts);
        for (NotificationCategory category : NotificationCategory.values()) {
            if (category != NotificationCategory.SYSTEM && !TOGGLES.containsKey(category)) {
                throw new IllegalStateException("No user_settings toggle mapped for notification category " + category);
            }
        }
    }

    /** Entity defaults mirror the column defaults; used for users without a settings row. Never modified. */
    private static final UserSettings DEFAULTS = new UserSettings();

    private final UserSettingsRepository userSettingsRepository;

    /**
     * Whether {@code recipientSystemId} wants an in-app notification of this category and priority.
     *
     * @param priority null is treated as NORMAL
     * @throws IllegalArgumentException when the category is null
     */
    public boolean allows(String recipientSystemId, NotificationCategory category, NotificationPriority priority) {
        if (category == null) {
            throw new IllegalArgumentException("Notification category is required");
        }
        if (priority == NotificationPriority.CRITICAL || category == NotificationCategory.SYSTEM) {
            return true;
        }
        UserSettings settings = recipientSystemId == null ? DEFAULTS
                : userSettingsRepository.findBySystemId(recipientSystemId).orElse(DEFAULTS);
        return enabled(settings, category);
    }

    /** The stored toggle of a category (SYSTEM is always on); a null value falls back to the column default. */
    public static boolean enabled(UserSettings settings, NotificationCategory category) {
        Function<UserSettings, Boolean> toggle = TOGGLES.get(category);
        if (toggle == null) {
            return true;
        }
        Boolean value = settings == null ? null : toggle.apply(settings);
        if (value == null) {
            value = toggle.apply(DEFAULTS);
        }
        return Boolean.TRUE.equals(value);
    }
}
