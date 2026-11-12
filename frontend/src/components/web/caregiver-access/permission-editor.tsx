import { Pressable, StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, colors, fontSize, Icon, radius, space, SwitchField, useUiTheme, webOnly, type WebKeyEvent,
} from '@/components/web/ui';
import {
  ACCESS_LEVELS,
  applyLevelPreset,
  COMMUNICATION_FLAG_OPTIONS,
  DATA_FLAG_OPTIONS,
  isFlagFixedByLevel,
  withDataFlag,
  type DataFlagOption,
} from '@/lib/caregiver-access/permissions';
import type { AccessLevel, CommunicationFlags, DataFlags } from '@/lib/caregiver-access/types';

// The patient's permission editor: an access level (a preset) and the ten data permissions it leaves open.
// Used when granting access, changing a grant and approving an access request.

function focusRadio(event: WebKeyEvent, index: number) {
  const current = event.currentTarget as { parentElement?: { querySelectorAll?: (s: string) => ArrayLike<{ focus?: () => void }> } } | null;
  current?.parentElement?.querySelectorAll?.('[role="radio"]')?.[index]?.focus?.();
}

/**
 * The four access levels as a radio group. Keyboard: Tab reaches the selected level; the arrow keys move
 * between levels and select them; Space selects the focused one.
 */
export function LevelPicker({ value, onChange, disabled = false }: {
  value:     AccessLevel;
  onChange:  (level: AccessLevel) => void;
  disabled?: boolean;
}) {
  const { accent, accentBg } = useUiTheme();
  const count = ACCESS_LEVELS.length;
  const focusKey = ACCESS_LEVELS.some(l => l.value === value) ? value : ACCESS_LEVELS[0].value;

  const onKeyDown = (index: number) => (event: WebKeyEvent) => {
    let next = -1;
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') next = (index + 1) % count;
    else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') next = (index - 1 + count) % count;
    else if (event.key === ' ' || event.key === 'Spacebar') next = index;
    if (next < 0) return;
    event.preventDefault();
    if (disabled) return;
    if (ACCESS_LEVELS[next].value !== value) onChange(ACCESS_LEVELS[next].value);
    focusRadio(event, next);
  };

  return (
    <View style={styles.levelGrid} role="radiogroup" aria-label="Access level" accessibilityLabel="Access level">
      {ACCESS_LEVELS.map((option, index) => {
        const selected = option.value === value;
        return (
          <Pressable
            key={option.value}
            role="radio"
            aria-checked={selected}
            accessibilityState={{ checked: selected, disabled }}
            accessibilityLabel={`${option.label}. ${option.sub}`}
            disabled={disabled}
            tabIndex={option.value === focusKey ? 0 : -1}
            onPress={() => { if (!selected) onChange(option.value); }}
            style={[
              styles.levelCard,
              selected && { borderColor: accent, backgroundColor: accentBg },
              disabled && styles.dimmed,
            ]}
            {...webOnly({ onKeyDown: onKeyDown(index) })}
          >
            <Icon
              name={selected ? 'radio-button-on' : 'radio-button-off'}
              size={18}
              color={selected ? accent : colors.textMuted}
              style={styles.levelIcon}
            />
            <View style={styles.levelText}>
              <Text style={[styles.levelLabel, selected && { color: accent }]}>{option.label}</Text>
              <Text style={styles.levelSub}>{option.sub}</Text>
            </View>
          </Pressable>
        );
      })}
    </View>
  );
}

function fixedNote(level: AccessLevel, option: DataFlagOption): string {
  if (level === 'FULL_ACCESS') return `${option.sub} Always on with Full access.`;
  return `${option.sub} Not available with View only.`;
}

function FlagGroup({ title, options, level, flags, onChange, disabled }: {
  title:    string;
  options:  readonly DataFlagOption[];
  level:    AccessLevel;
  flags:    DataFlags;
  onChange: (flags: DataFlags) => void;
  disabled: boolean;
}) {
  return (
    <View style={styles.group}>
      <Text style={styles.groupTitle} accessibilityRole="header" {...ariaLevel(4)}>{title}</Text>
      {options.map(option => {
        const fixed = isFlagFixedByLevel(level, option.key);
        return (
          <SwitchField
            key={option.key}
            label={option.label}
            description={fixed ? fixedNote(level, option) : option.sub}
            value={flags[option.key]}
            onChange={value => onChange(withDataFlag(flags, option.key, value))}
            disabled={disabled || fixed}
          />
        );
      })}
    </View>
  );
}

export interface PermissionEditorProps {
  level:     AccessLevel;
  /** The data permissions as they will be saved for `level` (see `applyLevelPreset`). */
  flags:     DataFlags;
  /** Called with the new level and flags; picking a level applies its preset to the flags. */
  onChange:  (level: AccessLevel, flags: DataFlags) => void;
  disabled?: boolean;
}

/**
 * Access level plus one switch per data permission, billing included. Switches the level decides are shown in
 * their fixed state (on for Full access, off for the write permissions under View only) and cannot be changed,
 * so what is on screen is always what the caregiver will get.
 */
export function PermissionEditor({ level, flags, onChange, disabled = false }: PermissionEditorProps) {
  const reads  = DATA_FLAG_OPTIONS.filter(o => !o.write);
  const writes = DATA_FLAG_OPTIONS.filter(o => o.write);
  return (
    <View style={styles.editor}>
      <Text style={styles.heading} accessibilityRole="header" {...ariaLevel(3)}>Access level</Text>
      <LevelPicker
        value={level}
        onChange={next => onChange(next, applyLevelPreset(next, flags))}
        disabled={disabled}
      />
      <View style={styles.box}>
        <FlagGroup
          title="What they can see" options={reads} level={level} flags={flags}
          onChange={next => onChange(level, next)} disabled={disabled}
        />
        <FlagGroup
          title="What they can do" options={writes} level={level} flags={flags}
          onChange={next => onChange(level, next)} disabled={disabled}
        />
      </View>
      <Text style={styles.note}>
        No access level includes messaging your care team or joining your video calls. You approve those separately.
      </Text>
    </View>
  );
}

/**
 * The two communication permissions as switches, for a form that is saved as a whole (granting access,
 * approving a request). Switching one on approves it when the form is saved.
 */
export function CommunicationEditor({ value, onChange, disabled = false }: {
  value:     CommunicationFlags;
  onChange:  (value: CommunicationFlags) => void;
  disabled?: boolean;
}) {
  return (
    <View style={styles.box}>
      <View style={styles.group}>
        <Text style={styles.groupTitle} accessibilityRole="header" {...ariaLevel(4)}>Messaging and video calls</Text>
        {COMMUNICATION_FLAG_OPTIONS.map(option => (
          <SwitchField
            key={option.key}
            label={option.label}
            description="Switching this on approves it."
            value={value[option.key]}
            onChange={next => onChange({ ...value, [option.key]: next })}
            disabled={disabled}
          />
        ))}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  editor:  { gap: space.md, minWidth: 0 },
  heading: { fontSize: fontSize.md, fontWeight: '700', color: colors.text },

  levelGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  levelCard: {
    flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0, flexDirection: 'row', alignItems: 'flex-start', gap: space.sm,
    padding: space.md, borderWidth: 1, borderColor: colors.borderInput, borderRadius: radius.md,
    backgroundColor: colors.surface,
  },
  levelIcon:  { marginTop: 1, flexShrink: 0 },
  levelText:  { flex: 1, minWidth: 0, gap: 2 },
  levelLabel: { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  levelSub:   { fontSize: fontSize.xs, color: colors.textMuted, lineHeight: 17 },
  dimmed:     { opacity: 0.6 },

  box: {
    gap: space.md, paddingHorizontal: space.md, paddingVertical: space.sm,
    borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, backgroundColor: colors.bg,
  },
  group:      { gap: 2, minWidth: 0 },
  groupTitle: { fontSize: fontSize.sm, fontWeight: '700', color: colors.text, paddingVertical: space.xs },
  note:       { fontSize: fontSize.xs, color: colors.textMuted, lineHeight: 17 },
});
