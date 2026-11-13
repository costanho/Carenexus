import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import { Button, colors, fontSize, FormError, SectionCard, space, SwitchField, TimeField } from '@/components/web/ui';
import type { SettingsUpdateResult } from '@/hooks/use-user-settings';
import { applyFieldErrors } from '@/lib/api/form-errors';
import {
  describeQuietWindow, QUIET_HOURS_DEFAULT_END, QUIET_HOURS_DEFAULT_START, type QuietHoursFormData, quietHoursSchema,
} from '@/lib/settings/quiet-hours';
import { timeZoneName } from '@/lib/settings/time-zones';
import type { UpdateUserSettings, UserSettings } from '@/lib/settings/types';

const FIELDS = ['quietHoursStart', 'quietHoursEnd'] as const;

/** The stored times, or the suggested ones while none were ever saved. */
function storedTimes(settings: UserSettings): QuietHoursFormData {
  return {
    quietHoursStart: settings.quietHoursStart ?? QUIET_HOURS_DEFAULT_START,
    quietHoursEnd:   settings.quietHoursEnd ?? QUIET_HOURS_DEFAULT_END,
  };
}

/**
 * Quiet hours: an on/off switch that saves at once, and a start and an end time saved with their own button
 * (a time is typed in parts, so saving on every keystroke would store half-entered values). Switching quiet
 * hours on sends the times on screen with it, which covers the first switch-on, when no times are stored yet.
 */
export function QuietHoursCard({ settings, update, clinicZone, externalChannelsAvailable }: {
  settings:   UserSettings;
  update:     (patch: UpdateUserSettings) => Promise<SettingsUpdateResult>;
  /** The clinic's zone id, for naming the zone the window is applied in; null while unknown. */
  clinicZone: string | null;
  /**
   * Whether this installation can send on any external channel (email, SMS or push). Null while the channels are
   * loading or could not be loaded; the card then behaves as if one were available.
   */
  externalChannelsAvailable: boolean | null;
}) {
  // Quiet hours hold back only email, SMS and push; with none of them in use the controls would change nothing.
  const inert = externalChannelsAvailable === false;
  const stored = storedTimes(settings);
  const { control, handleSubmit, reset, trigger, getValues, setError, formState: { isDirty } } = useForm<QuietHoursFormData>({
    resolver: zodResolver(quietHoursSchema),
    defaultValues: stored,
  });
  const [savingTimes, setSavingTimes] = useState(false);
  const [switchError, setSwitchError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  // Times that changed on the server (a reload, another tab's save) replace the ones on screen, unless the person
  // is in the middle of editing them.
  useEffect(() => {
    if (!isDirty) reset({ quietHoursStart: stored.quietHoursStart, quietHoursEnd: stored.quietHoursEnd });
    // `isDirty` is read, not watched: becoming pristine must not by itself reset the form.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stored.quietHoursStart, stored.quietHoursEnd, reset]);

  const applyResult = (result: SettingsUpdateResult): string | null => {
    if (result.ok) {
      reset(storedTimes(result.data));
      return null;
    }
    return result.error ? applyFieldErrors(result.error, setError, FIELDS) : null;
  };

  const onToggle = async (enabled: boolean) => {
    setSwitchError(null);
    setFormError(null);
    if (!enabled) {
      const result = await update({ quietHoursEnabled: false });
      if (!result.ok && result.error) setSwitchError(result.error.message);
      return;
    }
    // Invalid times are reported on their fields and nothing is sent.
    if (!(await trigger(undefined, { shouldFocus: true }))) return;
    const times = getValues();
    const result = await update({
      quietHoursEnabled: true,
      quietHoursStart:   times.quietHoursStart.trim(),
      quietHoursEnd:     times.quietHoursEnd.trim(),
    });
    setSwitchError(applyResult(result));
  };

  const onSaveTimes = handleSubmit(async times => {
    setSwitchError(null);
    setFormError(null);
    setSavingTimes(true);
    const result = await update({ quietHoursStart: times.quietHoursStart, quietHoursEnd: times.quietHoursEnd });
    setSavingTimes(false);
    setFormError(applyResult(result));
  });

  const windowText = settings.quietHoursEnabled ? describeQuietWindow(settings.quietHoursStart, settings.quietHoursEnd) : '';
  const zone = settings.timezone
    ? `${timeZoneName(settings.timezone)} time`
    : clinicZone ? `clinic time (${timeZoneName(clinicZone)})` : 'clinic time';

  return (
    <SectionCard title="Quiet hours" subtitle="A daily window in which email, SMS and push messages wait.">
      <SwitchField
        label="Quiet hours"
        description="Hold email, SMS and push messages during the hours below and send them when quiet hours end."
        value={settings.quietHoursEnabled}
        onChange={onToggle}
        error={switchError}
        disabled={inert}
      />
      {inert ? (
        <Text style={styles.note}>
          Quiet hours apply to email, SMS and push messages, which are not used on this installation. Notifications
          in CareNexus are never held back.
        </Text>
      ) : null}

      <View style={styles.times}>
        <View style={styles.time}>
          <TimeField label="Start" control={control} name="quietHoursStart" required disabled={inert} />
        </View>
        <View style={styles.time}>
          <TimeField label="End" control={control} name="quietHoursEnd" required disabled={inert} />
        </View>
      </View>
      <FormError message={formError} />
      <Button
        label="Save times"
        accessibilityLabel="Save times for quiet hours"
        variant="secondary"
        onPress={() => { void onSaveTimes(); }}
        loading={savingTimes}
        disabled={inert || !isDirty}
      />

      {windowText && !inert ? <Text style={styles.window}>Quiet hours run from {windowText}, in {zone}.</Text> : null}
      {inert ? null : (
        <Text style={styles.note}>
          Alerts sent with critical priority ignore quiet hours and are sent at once. Every other message waits and is
          sent when quiet hours end. Notifications in CareNexus are never held back. An end time earlier than the start
          time means quiet hours run past midnight.
        </Text>
      )}
    </SectionCard>
  );
}

const styles = StyleSheet.create({
  times:  { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  time:   { flexGrow: 1, flexShrink: 1, flexBasis: 140, minWidth: 0, maxWidth: 220 },
  window: { fontSize: fontSize.sm, fontWeight: '600', color: colors.text, lineHeight: 19 },
  note:   { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
});
