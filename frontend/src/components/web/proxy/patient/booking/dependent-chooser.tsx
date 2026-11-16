import { useRef } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import {
  colors, decorative, fontSize, Icon, radius, space, useUiTheme, webOnly, type WebKeyEvent,
} from '@/components/web/ui';
import type { CaregiverDependent } from '@/lib/caregiver/types';
import { dependentName, schedulingAccess } from './scheduling';

export interface DependentChooserProps {
  dependents: readonly CaregiverDependent[];
  /** PAT id of the chosen loved one, or '' for none. */
  value:      string;
  onChange:   (patientSystemId: string) => void;
  disabled?:  boolean;
}

function initials(name: string): string {
  const parts = name.split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase() || '?';
}

/**
 * The loved ones the caregiver can book for, as a radio group. Loved ones whose grant does not allow scheduling
 * are listed too, disabled, with the reason, so the caregiver understands why someone cannot be chosen.
 *
 * Keyboard (web, WAI-ARIA radio group): the group is one Tab stop, on the chosen person or else the first one who
 * can be chosen; Arrow keys move to the next or previous person who can be chosen and choose them; Space and
 * Enter choose the focused person.
 */
export function DependentChooser({ dependents, value, onChange, disabled = false }: DependentChooserProps) {
  const { accent, accentBg } = useUiTheme();
  const rows = dependents
    .map(d => ({ dependent: d, name: dependentName(d), access: schedulingAccess(d) }))
    // People the caregiver can book for first, then by name.
    .sort((a, b) => Number(b.access.allowed) - Number(a.access.allowed) || a.name.localeCompare(b.name));

  const choosable = disabled ? [] : rows.filter(r => r.access.allowed).map(r => r.dependent.patientSystemId);
  const tabStop = choosable.includes(value) ? value : choosable[0];
  const refs = useRef(new Map<string, unknown>());
  const move = (from: string, step: number) => {
    const at = choosable.indexOf(from);
    if (at < 0 || choosable.length === 0) return;
    const next = choosable[(at + step + choosable.length) % choosable.length];
    if (next !== value) onChange(next);
    (refs.current.get(next) as { focus?: () => void } | undefined)?.focus?.();
  };
  const onKeyDown = (id: string) => (event: WebKeyEvent) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowRight') { event.preventDefault(); move(id, 1); }
    else if (event.key === 'ArrowUp' || event.key === 'ArrowLeft') { event.preventDefault(); move(id, -1); }
    else if (event.key === ' ' || event.key === 'Spacebar') { event.preventDefault(); if (id !== value) onChange(id); }
  };

  return (
    <View style={styles.list} accessibilityRole="radiogroup" {...webOnly({ 'aria-label': 'Book for' })}>
      {rows.map(({ dependent, name, access }) => {
        const selected = value === dependent.patientSystemId;
        const unavailable = !access.allowed;
        const relationship = dependent.relationship?.trim();
        const reason = access.allowed ? null : access.reason;
        return (
          <Pressable
            key={dependent.accessId}
            ref={el => { refs.current.set(dependent.patientSystemId, el); }}
            onPress={() => onChange(dependent.patientSystemId)}
            disabled={disabled || unavailable}
            accessibilityRole="radio"
            accessibilityLabel={[name, relationship, reason].filter(Boolean).join(', ')}
            accessibilityState={{ checked: selected, disabled: disabled || unavailable }}
            {...webOnly({
              'aria-checked': selected,
              'aria-disabled': disabled || unavailable,
              tabIndex: dependent.patientSystemId === tabStop ? 0 : -1,
              onKeyDown: onKeyDown(dependent.patientSystemId),
            })}
            style={[
              styles.row,
              selected && { borderColor: accent, backgroundColor: accentBg },
              unavailable && styles.rowUnavailable,
            ]}
          >
            <View style={[styles.avatar, { backgroundColor: unavailable ? colors.bg : accentBg }]} {...decorative}>
              <Text style={[styles.avatarText, { color: unavailable ? colors.textMuted : accent }]}>{initials(name)}</Text>
            </View>
            <View style={styles.text} {...decorative}>
              <Text style={[styles.name, unavailable && styles.muted]}>{name}</Text>
              {relationship ? <Text style={styles.sub}>{relationship}</Text> : null}
              {reason ? (
                <View style={styles.reasonRow}>
                  <Icon name="lock-closed-outline" size={12} color={colors.textMuted} />
                  <Text style={styles.reason}>{reason}</Text>
                </View>
              ) : null}
            </View>
            <View
              style={[styles.radio, selected && { borderColor: accent }, unavailable && styles.radioUnavailable]}
              {...decorative}
            >
              {selected ? <View style={[styles.radioDot, { backgroundColor: accent }]} /> : null}
            </View>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  list: { gap: space.sm },
  row: {
    flexDirection: 'row', alignItems: 'center', gap: space.md, minHeight: 56, minWidth: 0,
    paddingVertical: space.sm, paddingHorizontal: space.md,
    borderWidth: 1, borderColor: colors.borderInput, borderRadius: radius.lg, backgroundColor: colors.surface,
  },
  rowUnavailable: { backgroundColor: colors.bg, borderColor: colors.border },
  avatar:     { width: 36, height: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center' },
  avatarText: { fontSize: fontSize.sm, fontWeight: '700' },
  text:       { flex: 1, minWidth: 0, gap: 2 },
  name:       { fontSize: fontSize.md, fontWeight: '600', color: colors.text },
  muted:      { color: colors.textMuted },
  sub:        { fontSize: fontSize.xs, color: colors.textMuted },
  reasonRow:  { flexDirection: 'row', alignItems: 'center', gap: 4, marginTop: 2 },
  reason:     { fontSize: fontSize.xs, color: colors.textMuted, flexShrink: 1 },
  radio: {
    width: 20, height: 20, borderRadius: 10, borderWidth: 2, borderColor: colors.borderInput,
    alignItems: 'center', justifyContent: 'center',
  },
  radioUnavailable: { opacity: 0.5 },
  radioDot: { width: 10, height: 10, borderRadius: 5 },
});
