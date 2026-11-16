import { Fragment } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  colors, ErrorView, fontSize, Icon, LoadingView, type RoleTheme, Screen, SectionCard, space, StatusBadge,
  STATUS_TONES, SwitchField,
} from '@/components/web/ui';
import { type NotificationChannelsState, useNotificationChannels } from '@/hooks/use-notification-channels';
import { useResource } from '@/hooks/use-resource';
import { type SettingsField, type UserSettingsState, useUserSettings } from '@/hooks/use-user-settings';
import type { AppRole } from '@/lib/auth/auth.types';
import { getClinicTimeZone } from '@/lib/settings/api';
import {
  type CategoryGroup, CRITICAL_ALWAYS_DELIVERED, SETTINGS_CATEGORIES, SETTINGS_CHANNELS,
} from '@/lib/settings/categories';
import type { CategoryField, ChannelField, UpdateUserSettings, UserSettings } from '@/lib/settings/types';
import { QuietHoursCard } from './quiet-hours-card';
import { TimeZoneCard } from './time-zone-card';

export interface UserSettingsScreenProps {
  /** Whose dashboard the screen is on: decides which notification switches are offered. */
  role:      AppRole;
  /** The dashboard's accent colours; the role's own theme when omitted. */
  theme?:    RoleTheme;
  /** Page heading. Default "Settings". */
  title?:    string;
  subtitle?: string;
}

const DEFAULT_SUBTITLE = 'Choose what you are notified about and how. Changes are saved as soon as you make them.';

/** The message of the last refused change when it was a change of exactly this one setting. */
function errorFor(state: UserSettingsState, field: SettingsField): string | null {
  const failure = state.failure;
  return failure && failure.fields.length === 1 && failure.fields[0] === field ? failure.error.message : null;
}

/** The change that sets one switch. */
function togglePatch(field: ChannelField | CategoryField, value: boolean): UpdateUserSettings {
  const patch: UpdateUserSettings = {};
  patch[field] = value;
  return patch;
}

/** "Saving…" / "Changes saved" beside the page title, announced politely. Always mounted so changes are announced. */
function SaveStatus({ state }: { state: UserSettingsState }) {
  const text = state.saving ? 'Saving…' : state.failure ? '' : state.savedCount > 0 ? 'Changes saved' : '';
  return (
    <View style={styles.status} role="status" aria-live="polite">
      {text && !state.saving ? <Icon name="checkmark-circle-outline" size={16} color={STATUS_TONES.success.fg} /> : null}
      <Text style={styles.statusText}>{text}</Text>
    </View>
  );
}

function Divider() {
  return <View style={styles.divider} />;
}

/** How notifications reach the person: the in-app list (always) and the external channels this installation has. */
function ChannelsCard({ settings, state, channels }: {
  settings: UserSettings;
  state:    UserSettingsState;
  channels: NotificationChannelsState;
}) {
  const available = channels.channels ? SETTINGS_CHANNELS.filter(c => channels.channels![c.key].available) : [];
  return (
    <SectionCard title="How you are notified" subtitle="Choose how you receive notifications">
      <View style={styles.fixedRow}>
        <View style={styles.fixedText}>
          <Text style={styles.fixedLabel}>In CareNexus</Text>
          <Text style={styles.fixedNote}>
            Every notification appears in your notification list. This cannot be switched off.
          </Text>
        </View>
        <StatusBadge label="Always on" tone="success" />
      </View>

      {channels.channels === null ? (
        <>
          <Divider />
          {channels.loading
            ? <LoadingView compact label="Checking which other channels are available" />
            : <ErrorView compact message={channels.error ?? 'Could not check which delivery channels are available.'} onRetry={channels.refetch} />}
        </>
      ) : available.length === 0 ? (
        <>
          <Divider />
          <Text style={styles.note}>Notifications are shown in CareNexus only.</Text>
        </>
      ) : (
        <>
          {available.map(channel => {
            const local = channels.channels![channel.key].provider === 'local';
            return (
              <Fragment key={channel.key}>
                <Divider />
                <SwitchField
                  label={channel.label}
                  description={local ? `${channel.description} ${channel.localNote}` : channel.description}
                  value={settings[channel.field]}
                  onChange={value => { void state.update(togglePatch(channel.field, value)); }}
                  error={errorFor(state, channel.field)}
                />
              </Fragment>
            );
          })}
          <Divider />
          <Text style={styles.note}>
            Switching a channel off stops every message on it, critical alerts included. They still appear in CareNexus.
          </Text>
        </>
      )}
    </SectionCard>
  );
}

/** One group of category switches. */
function CategoryCard({ group, settings, state }: {
  group:    CategoryGroup;
  settings: UserSettings;
  state:    UserSettingsState;
}) {
  return (
    <SectionCard title={group.title} subtitle={group.subtitle}>
      {group.items.map((item, index) => (
        <Fragment key={item.field}>
          {index > 0 ? <Divider /> : null}
          <SwitchField
            label={item.label}
            description={item.field === 'criticalAlerts' ? `${item.description} ${CRITICAL_ALWAYS_DELIVERED}` : item.description}
            value={settings[item.field]}
            onChange={value => { void state.update(togglePatch(item.field, value)); }}
            error={errorFor(state, item.field)}
          />
        </Fragment>
      ))}
    </SectionCard>
  );
}

function SettingsBody({ role, settings, state }: { role: AppRole; settings: UserSettings; state: UserSettingsState }) {
  const channels = useNotificationChannels();
  // Only a label depends on it, and the request falls back to the zone the web already uses for clinic times.
  const clinicZone = useResource(getClinicTimeZone, []).data;

  return (
    <View style={styles.column}>
      <ChannelsCard settings={settings} state={state} channels={channels} />
      {SETTINGS_CATEGORIES[role].map(group => (
        <CategoryCard key={group.key} group={group} settings={settings} state={state} />
      ))}
      <QuietHoursCard
        settings={settings}
        update={state.update}
        clinicZone={clinicZone}
        externalChannelsAvailable={channels.channels
          ? SETTINGS_CHANNELS.some(c => channels.channels![c.key].available)
          : null}
      />
      <TimeZoneCard settings={settings} update={state.update} clinicZone={clinicZone} />
    </View>
  );
}

/**
 * Notification settings of the signed-in account, for every role: delivery channels, the notification categories
 * the role receives, quiet hours and the time zone. Every control changes something the server acts on; each
 * switch and the time zone save as soon as they are changed and go back to their saved value if the server
 * refuses the change.
 */
export function UserSettingsScreen({ role, theme, title = 'Settings', subtitle = DEFAULT_SUBTITLE }: UserSettingsScreenProps) {
  const state = useUserSettings();
  const { settings } = state;
  return (
    <Screen title={title} subtitle={subtitle} theme={theme} role={role} actions={<SaveStatus state={state} />}>
      {settings ? (
        <SettingsBody role={role} settings={settings} state={state} />
      ) : state.loading ? (
        <LoadingView label="Loading your settings…" />
      ) : (
        <ErrorView message={state.error ?? 'Could not load your settings. Please try again.'} onRetry={state.refetch} />
      )}
    </Screen>
  );
}

export default UserSettingsScreen;

const styles = StyleSheet.create({
  column: { width: '100%', maxWidth: 760, gap: space.lg },

  status:     { minHeight: 24, flexDirection: 'row', alignItems: 'center', gap: space.xs },
  statusText: { fontSize: fontSize.sm, fontWeight: '600', color: colors.textMuted },

  divider: { height: 1, backgroundColor: colors.border },

  fixedRow:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.md, paddingVertical: space.xs },
  fixedText:  { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0, gap: 2 },
  fixedLabel: { fontSize: fontSize.md, fontWeight: '600', color: colors.text },
  fixedNote:  { fontSize: fontSize.xs, color: colors.textMuted, lineHeight: 17 },

  note: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
});
