import { useMemo, useState } from 'react';
import { SectionCard, SelectField, type SelectOption } from '@/components/web/ui';
import type { SettingsUpdateResult } from '@/hooks/use-user-settings';
import { timeZoneLabel, timeZoneOptions } from '@/lib/settings/time-zones';
import type { UpdateUserSettings, UserSettings } from '@/lib/settings/types';

/** Value of the "Use clinic time" choice: an empty time zone is what the server stores as "clinic time". */
const CLINIC_TIME = '';

/**
 * The account's time zone. Choosing a zone saves it at once; "Use clinic time" stores no zone. The list is the
 * browser's; the server checks the id and its refusal is shown under the field.
 */
export function TimeZoneCard({ settings, update, clinicZone }: {
  settings:   UserSettings;
  update:     (patch: UpdateUserSettings) => Promise<SettingsUpdateResult>;
  /** The clinic's zone id, shown in the "Use clinic time" choice; null while unknown. */
  clinicZone: string | null;
}) {
  const [error, setError] = useState<string | null>(null);

  const options = useMemo<SelectOption[]>(() => [
    { value: CLINIC_TIME, label: clinicZone ? `Use clinic time: ${timeZoneLabel(clinicZone)}` : 'Use clinic time' },
    ...timeZoneOptions(settings.timezone),
  ], [clinicZone, settings.timezone]);

  const onChange = async (zone: string) => {
    if (zone === (settings.timezone ?? CLINIC_TIME)) return;
    setError(null);
    const result = await update({ timezone: zone });
    if (!result.ok && result.error) setError(result.error.message);
  };

  return (
    <SectionCard title="Time zone" subtitle="Where you are, when that is not where the clinic is.">
      <SelectField
        label="Time zone"
        value={settings.timezone ?? CLINIC_TIME}
        onChange={onChange}
        options={options}
        error={error}
        hint="Quiet hours are applied in this time zone, and reminders show the clinic time together with your local time. Appointment times on screen stay in clinic time."
      />
    </SectionCard>
  );
}
