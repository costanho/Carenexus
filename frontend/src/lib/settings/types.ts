// Types of the user-settings API (`/api/profile/settings`), the delivery-channel status
// (`GET /api/notifications/channels`) and the part of the public clinic settings this domain reads.
// Field names are the backend's, unchanged.

/** `UserSettingsResponse`: the signed-in account's notification settings. */
export interface UserSettings {
  settingId: number;
  /** Legacy field, always null. */
  userId:    number | null;
  systemId:  string;

  // Channels. In-app notifications are always on and have no switch.
  emailNotifications: boolean;
  smsNotifications:   boolean;
  pushNotifications:  boolean;

  // Categories: switching one off stops the notifications of that category.
  appointmentReminders:      boolean;
  appointmentReminder1h:     boolean;
  appointmentChangeAlerts:   boolean;
  prescriptionReminders:     boolean;
  medicationReminders:       boolean;
  labResultAlerts:           boolean;
  imagingResultAlerts:       boolean;
  consultationNotifications: boolean;
  vitalsAlerts:              boolean;
  criticalAlerts:            boolean;
  referralAlerts:            boolean;
  carePlanUpdateAlerts:      boolean;
  accessChangeAlerts:        boolean;
  loginAlerts:               boolean;
  billingNotifications:      boolean;
  videoCallAlerts:           boolean;
  messageNotifications:      boolean;

  quietHoursEnabled: boolean;
  /** "HH:mm" wall-clock time in `timezone` (clinic time when that is null); inclusive. */
  quietHoursStart:   string | null;
  /** "HH:mm"; exclusive. A start later than the end means the window crosses midnight. */
  quietHoursEnd:     string | null;
  /** IANA zone id; null = clinic time. */
  timezone:          string | null;

  /** Clinic wall-clock date-times (no offset). */
  createdAt: string;
  updatedAt: string;
}

/** The on/off fields of `UserSettings`. */
export type SettingsToggleField = {
  [K in keyof UserSettings]: UserSettings[K] extends boolean ? K : never
}[keyof UserSettings];

/** The three external delivery channels. */
export type ChannelField = 'emailNotifications' | 'smsNotifications' | 'pushNotifications';

/** The switches that turn a notification category on or off. */
export type CategoryField = Exclude<SettingsToggleField, ChannelField | 'quietHoursEnabled'>;

/**
 * Body of `PATCH /api/profile/settings`. Only the fields sent change; an omitted field is left as it is.
 * `timezone: ''` means "use clinic time" (the server then stores null). Times are "HH:mm" and cannot be cleared.
 */
export type UpdateUserSettings = Partial<Pick<UserSettings, SettingsToggleField>> & {
  quietHoursStart?: string;
  quietHoursEnd?:   string;
  timezone?:        string;
};

/** One external channel of this installation. */
export interface ChannelStatus {
  /** False = messages on this channel are never sent; the switch is not shown. */
  available: boolean;
  /** `local` = messages are recorded on this installation instead of being sent; any other value is a real provider. */
  provider:  string;
}

/** `GET /api/notifications/channels`. The same for every user; the in-app channel is always on and not listed. */
export interface NotificationChannels {
  email: ChannelStatus;
  sms:   ChannelStatus;
  push:  ChannelStatus;
}

/** The fields of `GET /api/settings/public` (`PublicSettingsResponse`) that the settings screen reads. */
export interface PublicClinicSettings {
  /** IANA zone id of the clinic. */
  clinicTimeZone: string;
}
