import { Pressable, StyleSheet, Text, View } from 'react-native';
import { ariaPressed, colors, fontSize, Icon, radius, space, STATUS_TONES, useUiTheme } from '@/components/web/ui';
import type { DependentRecordsStatus } from '@/hooks/use-dependent-records';
import { dependentName } from './format';

// Small pieces shared by the caregiver record screens: filter chips, the loved-one filter and the note shown
// in place of data the caregiver's access does not include.

// ─── Filter chips ─────────────────────────────────────────────────────────────

export interface ChipOption<K extends string = string> {
  key:   K;
  label: string;
}

/**
 * A row of toggle buttons where exactly one is on (a filter). The row wraps, so it never widens the page on a
 * phone. Each chip is a button with a pressed state; `label` names the group for assistive technology.
 */
export function FilterChips<K extends string = string>({ label, options, value, onChange }: {
  label:    string;
  options:  readonly ChipOption<K>[];
  value:    K;
  onChange: (key: K) => void;
}) {
  const { accent, accentBg } = useUiTheme();
  return (
    <View style={styles.chips} role="group" aria-label={label}>
      {options.map(option => {
        const selected = option.key === value;
        return (
          <Pressable
            key={option.key}
            accessibilityRole="button"
            {...ariaPressed(selected)}
            onPress={() => { if (!selected) onChange(option.key); }}
            style={[styles.chip, selected && { borderColor: accent, backgroundColor: accentBg }]}
          >
            <Text style={[styles.chipText, selected && { color: accent, fontWeight: '700' }]} numberOfLines={1}>
              {option.label}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}

// ─── Loved-one filter (the `patient` URL param) ───────────────────────────────

const ALL = '__all__';

export interface DependentScope<D> {
  /** The loved ones to show: all of them, or the one the `patient` URL param names. */
  dependents: D[];
  /** The `patient` param when it names a linked loved one, else null. */
  selectedId: string | null;
  /** True when the `patient` param names nobody in the caregiver's list (a stale or mistyped link). */
  unknown:    boolean;
}

/** Applies the `patient` URL param to the caregiver's list of loved ones. */
export function scopeDependents<D extends { patientSystemId: string }>(
  dependents: readonly D[],
  patientParam: string | null | undefined,
): DependentScope<D> {
  const wanted = (patientParam ?? '').trim();
  if (!wanted) return { dependents: [...dependents], selectedId: null, unknown: false };
  const match = dependents.filter(d => d.patientSystemId === wanted);
  return match.length > 0
    ? { dependents: match, selectedId: wanted, unknown: false }
    : { dependents: [], selectedId: null, unknown: true };
}

/**
 * Chooses whose records are shown: everyone or one loved one. The choice lives in the `patient` URL param, so a
 * link from a loved one's profile opens that person only and a refresh keeps the choice. Renders nothing when
 * there is only one loved one and no filter to clear.
 */
export function DependentFilter<D extends { patientSystemId: string; patientName?: string | null }>({
  dependents, selectedId, onChange, label = 'Show records for',
}: {
  dependents: readonly D[];
  selectedId: string | null;
  /** Called with a PAT id, or null for everyone. */
  onChange:   (patientSystemId: string | null) => void;
  /** Names the group of chips for assistive technology. */
  label?:     string;
}) {
  if (dependents.length < 2 && !selectedId) return null;
  const options: ChipOption[] = [
    { key: ALL, label: 'All loved ones' },
    ...dependents.map(d => ({ key: d.patientSystemId, label: dependentName(d) })),
  ];
  return (
    <FilterChips
      label={label}
      options={options}
      value={selectedId ?? ALL}
      onChange={key => onChange(key === ALL ? null : key)}
    />
  );
}

// ─── "Not shared with you" note ───────────────────────────────────────────────

/** True for the statuses where the caregiver's access, not a failure, is why nothing is shown. */
export function isAccessStatus(status: DependentRecordsStatus): status is 'not-shared' | 'denied' | 'ended' {
  return status === 'not-shared' || status === 'denied' || status === 'ended';
}

/** The sentence shown in place of records the caregiver's access does not include. */
export function accessNoteText(status: 'not-shared' | 'denied' | 'ended', what: string, name: string): string {
  if (status === 'ended') return `Your access to ${name}'s information has ended.`;
  return `${what} for ${name} are not shared with you.`;
}

/**
 * Shown in place of a loved one's records when the caregiver's access does not include them. It says what is
 * withheld and who can change it; it is information, not an error, so there is no Retry.
 */
export function AccessNote({ status, what, name, compact = false }: {
  status:   'not-shared' | 'denied' | 'ended';
  /** What is withheld, capitalised and plural: "Lab results". */
  what:     string;
  /** The loved one's name. */
  name:     string;
  compact?: boolean;
}) {
  const tone = STATUS_TONES.warning;
  return (
    <View style={[styles.note, compact && styles.noteCompact, { backgroundColor: tone.bg, borderColor: tone.border }]} role="status">
      <Icon name="lock-closed-outline" size={compact ? 16 : 18} color={tone.fg} style={styles.noteIcon} />
      <View style={styles.noteTexts}>
        <Text style={[styles.noteTitle, { color: tone.fg }]}>{accessNoteText(status, what, name)}</Text>
        <Text style={[styles.noteSub, { color: tone.fg }]}>
          {status === 'ended'
            ? `Ask ${name} to grant you access again if you still help with their care.`
            : `Ask ${name} to update your caregiver access if you need to see them.`}
        </Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  chips:    { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, maxWidth: '100%' },
  chip: {
    minHeight: 36, maxWidth: '100%', justifyContent: 'center', paddingHorizontal: space.md, paddingVertical: space.xs,
    borderWidth: 1, borderColor: colors.borderInput, borderRadius: radius.pill, backgroundColor: colors.surface,
  },
  chipText: { fontSize: fontSize.sm, fontWeight: '500', color: colors.textMuted },

  note: {
    flexDirection: 'row', alignItems: 'flex-start', gap: space.sm,
    padding: space.md, borderWidth: 1, borderRadius: radius.md, minWidth: 0,
  },
  noteCompact: { padding: space.sm },
  noteIcon:    { marginTop: 1, flexShrink: 0 },
  noteTexts:   { flex: 1, minWidth: 0, gap: 2 },
  noteTitle:   { fontSize: fontSize.sm, fontWeight: '700', lineHeight: 19 },
  noteSub:     { fontSize: fontSize.sm, lineHeight: 19 },
});
