package com.carenexus.api.communications.notify;

import com.carenexus.api.core.model.UserSettings;
import com.carenexus.api.core.repository.UserSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * The preference matrix: every category is governed by exactly its own user_settings toggle; CRITICAL priority and
 * the SYSTEM category are always delivered; a user without a settings row gets the column defaults.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationPreferencesTest {

    private static final String USER = "PAT-00001";

    /** Category to the setter of the user_settings field that switches it off (the documented mapping). */
    static final Map<NotificationCategory, BiConsumer<UserSettings, Boolean>> TOGGLE_SETTERS =
            new EnumMap<>(NotificationCategory.class);

    static {
        TOGGLE_SETTERS.put(NotificationCategory.APPOINTMENT_REMINDER, UserSettings::setAppointmentReminders);
        TOGGLE_SETTERS.put(NotificationCategory.APPOINTMENT_REMINDER_1H, UserSettings::setAppointmentReminder1h);
        TOGGLE_SETTERS.put(NotificationCategory.APPOINTMENT_CHANGE, UserSettings::setAppointmentChangeAlerts);
        TOGGLE_SETTERS.put(NotificationCategory.MEDICATION_REMINDER, UserSettings::setMedicationReminders);
        TOGGLE_SETTERS.put(NotificationCategory.PRESCRIPTION, UserSettings::setPrescriptionReminders);
        TOGGLE_SETTERS.put(NotificationCategory.LAB_RESULT, UserSettings::setLabResultAlerts);
        TOGGLE_SETTERS.put(NotificationCategory.IMAGING_RESULT, UserSettings::setImagingResultAlerts);
        TOGGLE_SETTERS.put(NotificationCategory.CONSULTATION, UserSettings::setConsultationNotifications);
        TOGGLE_SETTERS.put(NotificationCategory.VITALS, UserSettings::setVitalsAlerts);
        TOGGLE_SETTERS.put(NotificationCategory.REFERRAL, UserSettings::setReferralAlerts);
        TOGGLE_SETTERS.put(NotificationCategory.CARE_PLAN, UserSettings::setCarePlanUpdateAlerts);
        TOGGLE_SETTERS.put(NotificationCategory.ACCESS_CHANGE, UserSettings::setAccessChangeAlerts);
        TOGGLE_SETTERS.put(NotificationCategory.LOGIN_ALERT, UserSettings::setLoginAlerts);
        TOGGLE_SETTERS.put(NotificationCategory.BILLING, UserSettings::setBillingNotifications);
        TOGGLE_SETTERS.put(NotificationCategory.VIDEO_CALL, UserSettings::setVideoCallAlerts);
        TOGGLE_SETTERS.put(NotificationCategory.MESSAGE, UserSettings::setMessageNotifications);
        TOGGLE_SETTERS.put(NotificationCategory.CRITICAL, UserSettings::setCriticalAlerts);
    }

    /** Settings with every category toggle set to {@code value}. */
    static UserSettings allToggles(String systemId, boolean value) {
        UserSettings settings = new UserSettings();
        settings.setSystemId(systemId);
        TOGGLE_SETTERS.values().forEach(setter -> setter.accept(settings, value));
        return settings;
    }

    @Mock private UserSettingsRepository userSettingsRepository;

    private NotificationPreferences preferences;

    @BeforeEach
    void setUp() {
        preferences = new NotificationPreferences(userSettingsRepository);
    }

    private void stored(UserSettings settings) {
        when(userSettingsRepository.findBySystemId(USER)).thenReturn(Optional.of(settings));
    }

    @Test
    void everyCategoryExceptSystem_hasAToggle() {
        for (NotificationCategory category : NotificationCategory.values()) {
            assertEquals(category != NotificationCategory.SYSTEM, TOGGLE_SETTERS.containsKey(category), category.name());
        }
    }

    @Test
    void matrix_switchingOneToggleOff_suppressesExactlyItsOwnCategory() {
        for (NotificationCategory off : TOGGLE_SETTERS.keySet()) {
            UserSettings settings = allToggles(USER, true);
            TOGGLE_SETTERS.get(off).accept(settings, false);
            stored(settings);
            for (NotificationCategory category : NotificationCategory.values()) {
                boolean expected = category != off;
                for (NotificationPriority priority : new NotificationPriority[]{
                        NotificationPriority.LOW, NotificationPriority.NORMAL, NotificationPriority.HIGH}) {
                    assertEquals(expected, preferences.allows(USER, category, priority),
                            category + " at " + priority + " with " + off + " switched off");
                }
            }
        }
    }

    @Test
    void matrix_switchingOneToggleOn_deliversExactlyItsOwnCategory_plusSystem() {
        for (NotificationCategory on : TOGGLE_SETTERS.keySet()) {
            UserSettings settings = allToggles(USER, false);
            TOGGLE_SETTERS.get(on).accept(settings, true);
            stored(settings);
            for (NotificationCategory category : NotificationCategory.values()) {
                boolean expected = category == on || category == NotificationCategory.SYSTEM;
                assertEquals(expected, preferences.allows(USER, category, NotificationPriority.NORMAL),
                        category + " with only " + on + " switched on");
            }
        }
    }

    @Test
    void criticalPriority_isAlwaysDelivered_whateverTheCategoryAndToggle() {
        stored(allToggles(USER, false));
        for (NotificationCategory category : NotificationCategory.values()) {
            assertTrue(preferences.allows(USER, category, NotificationPriority.CRITICAL), category.name());
        }
    }

    @Test
    void systemCategory_isNeverSuppressible() {
        stored(allToggles(USER, false));
        for (NotificationPriority priority : NotificationPriority.values()) {
            assertTrue(preferences.allows(USER, NotificationCategory.SYSTEM, priority), priority.name());
        }
        assertTrue(preferences.allows(USER, NotificationCategory.SYSTEM, null));
    }

    @Test
    void criticalCategory_followsCriticalAlerts_onlyBelowCriticalPriority() {
        UserSettings settings = allToggles(USER, true);
        settings.setCriticalAlerts(false);
        stored(settings);

        assertFalse(preferences.allows(USER, NotificationCategory.CRITICAL, NotificationPriority.LOW));
        assertFalse(preferences.allows(USER, NotificationCategory.CRITICAL, NotificationPriority.NORMAL));
        assertFalse(preferences.allows(USER, NotificationCategory.CRITICAL, NotificationPriority.HIGH));
        assertTrue(preferences.allows(USER, NotificationCategory.CRITICAL, NotificationPriority.CRITICAL));
    }

    @Test
    void userWithoutSettingsRow_getsTheColumnDefaults_everythingOnExceptCarePlanUpdates() {
        when(userSettingsRepository.findBySystemId(USER)).thenReturn(Optional.empty());
        for (NotificationCategory category : NotificationCategory.values()) {
            assertEquals(category != NotificationCategory.CARE_PLAN,
                    preferences.allows(USER, category, NotificationPriority.NORMAL), category.name());
        }
        assertTrue(preferences.allows(USER, NotificationCategory.CARE_PLAN, NotificationPriority.CRITICAL));
    }

    @Test
    void nullPriority_isTreatedAsNormal_nullCategoryIsAProgrammingError() {
        stored(allToggles(USER, false));
        assertFalse(preferences.allows(USER, NotificationCategory.BILLING, null));
        assertThrows(IllegalArgumentException.class, () -> preferences.allows(USER, null, NotificationPriority.NORMAL));
    }

    @Test
    void aToggleThatWasNeverStored_fallsBackToItsDefault() {
        UserSettings settings = new UserSettings();
        settings.setSystemId(USER);
        settings.setBillingNotifications(null);
        settings.setCarePlanUpdateAlerts(null);
        assertTrue(NotificationPreferences.enabled(settings, NotificationCategory.BILLING));
        assertFalse(NotificationPreferences.enabled(settings, NotificationCategory.CARE_PLAN));
        assertTrue(NotificationPreferences.enabled(null, NotificationCategory.LAB_RESULT));
    }
}
