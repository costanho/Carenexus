import { api } from '@/lib/auth/auth.interceptor';
import { CLINIC_TIME_ZONE } from '@/lib/time/clinic';
import { isKnownTimeZone } from './time-zones';
import type {
  ChannelStatus, NotificationChannels, PublicClinicSettings, SettingsToggleField, UpdateUserSettings, UserSettings,
} from './types';

// One function per endpoint the settings screen uses.

/** What the server answers for an account that never saved a setting (be-profile-settings 3.4). */
export const TOGGLE_DEFAULTS: Readonly<Record<SettingsToggleField, boolean>> = {
  emailNotifications: true, smsNotifications: true, pushNotifications: true,
  appointmentReminders: true, appointmentReminder1h: true, appointmentChangeAlerts: true,
  prescriptionReminders: true, medicationReminders: true, labResultAlerts: true, imagingResultAlerts: true,
  consultationNotifications: true, vitalsAlerts: true, criticalAlerts: true, referralAlerts: true,
  carePlanUpdateAlerts: false, accessChangeAlerts: true, loginAlerts: true, billingNotifications: true,
  videoCallAlerts: true, messageNotifications: true,
  quietHoursEnabled: false,
};

const TOGGLE_FIELDS = Object.keys(TOGGLE_DEFAULTS) as SettingsToggleField[];

const TIME_OF_DAY = /^([01]\d|2[0-3]):[0-5]\d(:[0-5]\d(\.\d+)?)?$/;

/** "HH:mm" from "HH:mm" or "HH:mm:ss"; null for anything else. */
function timeOfDay(value: unknown): string | null {
  return typeof value === 'string' && TIME_OF_DAY.test(value.trim()) ? value.trim().slice(0, 5) : null;
}

function text(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

/**
 * The settings as the screen uses them. A switch the response does not carry as a boolean takes the documented
 * default, so one missing field never leaves a switch without a state.
 */
export function normalizeUserSettings(raw: unknown): UserSettings {
  const data = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>;
  const toggles = {} as Record<SettingsToggleField, boolean>;
  for (const field of TOGGLE_FIELDS) {
    const value = data[field];
    toggles[field] = typeof value === 'boolean' ? value : TOGGLE_DEFAULTS[field];
  }
  const zone = text(data.timezone).trim();
  return {
    settingId: typeof data.settingId === 'number' ? data.settingId : 0,
    userId:    typeof data.userId === 'number' ? data.userId : null,
    systemId:  text(data.systemId),
    ...toggles,
    quietHoursStart: timeOfDay(data.quietHoursStart),
    quietHoursEnd:   timeOfDay(data.quietHoursEnd),
    timezone:        zone || null,
    createdAt: text(data.createdAt),
    updatedAt: text(data.updatedAt),
  };
}

/** GET /api/profile/settings: the caller's own settings (defaults when nothing was saved yet). */
export async function getUserSettings(): Promise<UserSettings> {
  const { data } = await api.get<unknown>('/api/profile/settings');
  return normalizeUserSettings(data);
}

/** PATCH /api/profile/settings: changes only the fields sent and returns the whole settings after the change. */
export async function patchUserSettings(patch: UpdateUserSettings): Promise<UserSettings> {
  const { data } = await api.patch<unknown>('/api/profile/settings', patch);
  return normalizeUserSettings(data);
}

function channelStatus(raw: unknown): ChannelStatus {
  const data = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>;
  return {
    available: data.available === true,
    provider:  text(data.provider).trim().toLowerCase(),
  };
}

/** GET /api/notifications/channels: which external channels this installation can send on. */
export async function getNotificationChannels(): Promise<NotificationChannels> {
  const { data } = await api.get<unknown>('/api/notifications/channels');
  const body = (data && typeof data === 'object' ? data : {}) as Record<string, unknown>;
  return { email: channelStatus(body.email), sms: channelStatus(body.sms), push: channelStatus(body.push) };
}

/** GET /api/settings/public, reduced to the fields this domain reads. */
export async function getPublicClinicSettings(): Promise<PublicClinicSettings> {
  const { data } = await api.get<unknown>('/api/settings/public');
  const body = (data && typeof data === 'object' ? data : {}) as Record<string, unknown>;
  const zone = text(body.clinicTimeZone).trim();
  return { clinicTimeZone: zone };
}

let clinicZone: Promise<string> | null = null;

/**
 * The clinic's time zone id, for the "Use clinic time" choice. It is an installation property, so one successful
 * answer is kept for the session. When the request fails, or the answer is not a zone this browser knows, the
 * zone the web already formats clinic times in (`CLINIC_TIME_ZONE`) is used; a failure is not kept, so the next
 * visit asks again.
 */
export function getClinicTimeZone(): Promise<string> {
  if (!clinicZone) {
    const request = getPublicClinicSettings().then(s => {
      if (!isKnownTimeZone(s.clinicTimeZone)) throw new Error('No usable clinic time zone in the response');
      return s.clinicTimeZone;
    });
    clinicZone = request;
    request.catch(() => { if (clinicZone === request) clinicZone = null; });
  }
  return clinicZone.catch(() => CLINIC_TIME_ZONE);
}

/**
 * The settings as they will read once `patch` is saved: the same rules the server applies (an omitted field is
 * unchanged, a blank time zone means clinic time). Used to show a change before the server has answered.
 */
export function applySettingsPatch(settings: UserSettings, patch: UpdateUserSettings): UserSettings {
  const { timezone, quietHoursStart, quietHoursEnd, ...toggles } = patch;
  const next: UserSettings = { ...settings };
  for (const field of TOGGLE_FIELDS) {
    const value = toggles[field];
    if (typeof value === 'boolean') next[field] = value;
  }
  if (typeof quietHoursStart === 'string') next.quietHoursStart = quietHoursStart;
  if (typeof quietHoursEnd === 'string') next.quietHoursEnd = quietHoursEnd;
  if (typeof timezone === 'string') next.timezone = timezone.trim() || null;
  return next;
}
