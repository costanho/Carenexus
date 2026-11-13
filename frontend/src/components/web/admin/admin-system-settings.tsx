import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useMemo, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { StyleSheet, Text, type TextInputProps, View } from 'react-native';
import {
  ariaLevel, Banner, Button, colors, ErrorView, fontSize, InfoRow, LoadingView, NoAccessView, Screen, SectionCard,
  space, StatusBadge, TextField,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { useAdminSystemSettings } from '@/hooks/use-admin-ops-settings';
import { restoreSystemSettingDefault, updateSystemSetting } from '@/lib/admin/ops-api';
import {
  formatSettingValue, fractionAsPercent, OTHER_SETTINGS_GROUP, settingFormSchema, settingInputHint, settingLabel,
  settingMeta, type SettingFormValues, type SettingType, type SystemSetting,
} from '@/lib/admin/ops-types';
import { ROLE_THEME, type SectionProps } from '@/lib/nav/sections';
import { formatClinicDateTime } from '@/lib/time/clinic';
import { confirmAction } from '@/lib/ui/dialogs';

// Admin section `system-settings`: the values an administrator can change while the system runs. Each setting
// shows its value in force, its default, what it affects, an editor that checks the value the way the server
// does, and (for a custom value) a way back to the default.

const INPUT_MODE: Record<SettingType, TextInputProps['inputMode']> = {
  STRING: 'text', EMAIL: 'email', PHONE: 'tel', CURRENCY: 'text', INT: 'numeric', DECIMAL: 'decimal',
};

/** "must be between 1 and 168" becomes "Must be between 1 and 168." */
function asSentence(text: string): string {
  const trimmed = text.trim();
  if (!trimmed) return trimmed;
  const capitalised = trimmed.charAt(0).toUpperCase() + trimmed.slice(1);
  return /[.!?]$/.test(capitalised) ? capitalised : `${capitalised}.`;
}

function SettingRow({ setting, first, onChanged }: {
  setting:   SystemSetting;
  /** The first row of its card draws no divider above it. */
  first:     boolean;
  onChanged: (next: SystemSetting) => void;
}) {
  const label = settingLabel(setting.key);
  const meta = settingMeta(setting.key);
  const schema = useMemo(
    () => settingFormSchema({ type: setting.type, min: setting.min, max: setting.max, maxLength: setting.maxLength }),
    [setting.type, setting.min, setting.max, setting.maxLength],
  );
  const { control, handleSubmit, reset, formState: { isDirty } } = useForm<SettingFormValues>({
    resolver: zodResolver(schema),
    defaultValues: { value: setting.value },
  });
  const typed = useWatch({ control, name: 'value' });

  const [serverError, setServerError] = useState<string | null>(null);
  const [rowError, setRowError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const save = useAction((value: string) => updateSystemSetting(setting.key, value), {
    errorMessage: 'The setting could not be saved.',
  });
  const restore = useAction(() => restoreSystemSettingDefault(setting.key), {
    errorMessage: 'The default could not be restored.',
  });
  const busy = save.pending || restore.pending;

  // The value in force changed (saved here, restored, or reloaded from the server): the editor starts from it.
  useEffect(() => { reset({ value: setting.value }); }, [reset, setting.value]);
  // A server message belongs to the value that was sent; it goes away once the value is edited.
  useEffect(() => { setServerError(null); }, [typed]);

  const submit = handleSubmit(async ({ value }) => {
    setSaved(null);
    setRowError(null);
    const result = await save.run(value.trim());
    if (result.ok) {
      onChanged(result.data);
      reset({ value: result.data.value });
      setSaved(`Saved. ${label} is now ${formatSettingValue(result.data, result.data.value)}.`);
    } else if (result.error) {
      // Type checks answer "value: <reason>" without an errors list; the blank and length checks carry one.
      setServerError(asSentence(result.error.fieldErrors?.value ?? result.error.message.replace(/^value: /, '')));
    }
  });

  const onRestore = async () => {
    const confirmed = await confirmAction(
      `Restore the default for ${label}?`,
      `The custom value (${formatSettingValue(setting, setting.value)}) is removed and the default `
        + `(${formatSettingValue(setting, setting.defaultValue)}) applies again.`,
      'Restore default',
      true,
    );
    if (!confirmed) return;
    setSaved(null);
    setRowError(null);
    setServerError(null);
    const result = await restore.run();
    if (result.ok) {
      const next: SystemSetting = {
        ...setting, value: setting.defaultValue, overridden: false, updatedAt: null, updatedBySystemId: null,
      };
      onChanged(next);
      reset({ value: next.value });
      setSaved(`The default applies again: ${formatSettingValue(next, next.value)}.`);
    } else if (result.error) {
      setRowError(result.error.message);
    }
  };

  const percent = meta?.unit === 'fraction' ? fractionAsPercent(typed ?? '') : null;
  const maxLength = setting.type === 'INT' || setting.type === 'DECIMAL' ? 20 : setting.maxLength ?? undefined;

  return (
    <View style={[styles.setting, !first && styles.settingDivider]}>
      <View style={styles.head}>
        <View style={styles.titles}>
          <Text style={styles.title} accessibilityRole="header" {...ariaLevel(3)}>{label}</Text>
          <Text style={styles.key} selectable>{setting.key}</Text>
        </View>
        <StatusBadge
          label={setting.overridden ? 'Custom value' : 'Default'}
          tone={setting.overridden ? 'info' : 'neutral'}
          icon={setting.overridden ? 'create-outline' : 'ellipse-outline'}
        />
      </View>

      <Text style={styles.description}>{setting.description}</Text>
      {meta ? <Text style={styles.affects}>{meta.affects}</Text> : null}

      <View>
        <InfoRow label="Value in force" value={formatSettingValue(setting, setting.value)} />
        <InfoRow label="Default" value={formatSettingValue(setting, setting.defaultValue)} />
        {setting.overridden ? (
          <InfoRow
            label="Last changed"
            value={`${formatClinicDateTime(setting.updatedAt, 'At an unknown time')}${setting.updatedBySystemId ? ` by ${setting.updatedBySystemId}` : ''}`}
          />
        ) : null}
      </View>

      <View style={styles.editor}>
        <View style={styles.editorField}>
          <TextField
            control={control}
            name="value"
            label={`${label} value`}
            hint={settingInputHint(setting)}
            error={serverError}
            inputMode={INPUT_MODE[setting.type] ?? 'text'}
            autoCapitalize={setting.type === 'CURRENCY' ? 'characters' : 'none'}
            maxLength={maxLength}
            disabled={busy}
            onSubmit={() => { void submit(); }}
          />
          {percent ? <Text style={styles.preview}>{`That is ${percent}.`}</Text> : null}
        </View>
        <View style={styles.editorActions}>
          <Button
            label="Save" icon="save-outline" onPress={() => { void submit(); }}
            loading={save.pending} disabled={!isDirty || restore.pending}
            accessibilityLabel={`Save ${label}`}
          />
          {setting.overridden ? (
            <Button
              label="Restore default" icon="arrow-undo-outline" variant="secondary"
              onPress={() => { void onRestore(); }}
              loading={restore.pending} disabled={save.pending}
              accessibilityLabel={`Restore default for ${label}`}
            />
          ) : null}
        </View>
      </View>

      {saved ? <Banner tone="success" text={saved} onDismiss={() => setSaved(null)} /> : null}
      {rowError ? <Banner tone="danger" text={rowError} onDismiss={() => setRowError(null)} /> : null}
    </View>
  );
}

function groupSettings(settings: SystemSetting[]): { title: string; items: SystemSetting[] }[] {
  const groups: { title: string; items: SystemSetting[] }[] = [];
  for (const setting of settings) {
    const title = settingMeta(setting.key)?.group ?? OTHER_SETTINGS_GROUP;
    const group = groups.find(g => g.title === title);
    if (group) group.items.push(setting);
    else groups.push({ title, items: [setting] });
  }
  return groups;
}

export default function AdminSystemSettings({ theme }: Partial<SectionProps>) {
  const look = theme ?? ROLE_THEME.admin;
  const settings = useAdminSystemSettings();
  const groups = useMemo(() => groupSettings(settings.data ?? []), [settings.data]);

  const onChanged = (next: SystemSetting) => {
    settings.setData(prev => (prev ? prev.map(s => (s.key === next.key ? next : s)) : prev));
  };

  let body;
  if (settings.loading) {
    body = <LoadingView label="Loading system settings…" />;
  } else if (!settings.data) {
    body = settings.errorStatus === 403
      ? <NoAccessView message="Only administrators can change system settings." />
      : <ErrorView message={settings.error ?? 'The system settings could not be loaded.'} onRetry={settings.refetch} />;
  } else if (settings.data.length === 0) {
    body = <ErrorView message="The server returned no settings." onRetry={settings.refetch} />;
  } else {
    body = (
      <>
        {settings.error ? (
          <Banner tone="danger" text={settings.error} action={{ label: 'Retry', onPress: settings.refetch }} />
        ) : null}
        {groups.map(group => (
          <SectionCard key={group.title} title={group.title}>
            {group.items.map((setting, index) => (
              <SettingRow key={setting.key} setting={setting} first={index === 0} onChanged={onChanged} />
            ))}
          </SectionCard>
        ))}
      </>
    );
  }

  return (
    <Screen
      title="System settings"
      subtitle="Values the clinic can change without a new deployment. A saved value is used the next time it is read; on an installation with several servers that can take up to a minute."
      theme={look}
      actions={settings.data ? (
        <Button
          label="Refresh" icon="refresh-outline" variant="secondary" onPress={settings.refetch}
          loading={settings.refreshing}
        />
      ) : undefined}
    >
      {body}
    </Screen>
  );
}

const styles = StyleSheet.create({
  setting:        { gap: space.sm, minWidth: 0 },
  settingDivider: { borderTopWidth: 1, borderTopColor: colors.border, paddingTop: space.lg, marginTop: space.xs },

  head:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', gap: space.sm },
  titles: { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0, gap: 2 },
  title:  { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  key:    { fontSize: fontSize.xs, color: colors.textSubtle },

  description: { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  affects:     { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },

  editor:        { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: space.md },
  editorField:   { flexGrow: 1, flexShrink: 1, flexBasis: 240, minWidth: 0, maxWidth: 480, gap: space.xs },
  // Lines the buttons up with the input, under the field's label.
  editorActions: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, paddingTop: 22 },
  preview:       { fontSize: fontSize.sm, color: colors.textMuted },
});
