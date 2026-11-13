import { StyleSheet, Text, View } from 'react-native';
import { ariaLevel, colors, fontSize, radius, SelectField, space, SwitchField } from '@/components/web/ui';
import {
  ACCESS_LEVEL_OPTIONS, COMM_FLAGS, COMM_LABELS, lockedBy, normaliseDataFlags, PERMISSION_OPTIONS,
} from './access';
import type { AccessLevel, CommFlag, CommFlags, DataFlag, DataFlags } from './types';

// The access-level and permission controls shared by the caregiver forms: adding a loved one, asking an existing
// patient for access, and a caregiver reducing their own access. Lives beside the permission model it draws.

export interface PermissionFieldsProps {
  level:         AccessLevel;
  flags:         DataFlags;
  onLevelChange: (level: AccessLevel) => void;
  onFlagChange:  (flag: DataFlag, value: boolean) => void;
  /** Levels offered, in this order. Default: all four. */
  levels?:       readonly AccessLevel[];
  /**
   * Set when a caregiver edits a grant they already hold: the permissions they hold now. A permission that is
   * not held cannot be switched on, because only the patient can add to a caregiver's access.
   */
  held?:         DataFlags;
  /** Who can add permissions, for the note under a permission that is not held ("Thomas"). */
  grantorName?:  string;
  disabled?:     boolean;
  levelError?:   string | null;
}

function noteFor(level: AccessLevel, flag: DataFlag, held: DataFlags | undefined, grantorName: string): string | undefined {
  const lock = lockedBy(level, flag);
  if (lock === 'on') return 'Included in Full access.';
  if (held && !held[flag]) return `Only ${grantorName} can give you this.`;
  if (lock === 'off') return 'Not available with View only.';
  return undefined;
}

/**
 * An access-level select followed by one switch per data permission. The switches always show what will be
 * stored: Full access shows everything on and View only shows the three "do" permissions off, both locked.
 */
export function PermissionFields({
  level, flags, onLevelChange, onFlagChange, levels, held, grantorName = 'the patient', disabled = false, levelError,
}: PermissionFieldsProps) {
  const shown = normaliseDataFlags(level, flags);
  const options = ACCESS_LEVEL_OPTIONS.filter(o => !levels || levels.includes(o.value));
  const description = ACCESS_LEVEL_OPTIONS.find(o => o.value === level)?.description;

  const group = (title: string, write: boolean) => (
    <View style={styles.group} role="group" aria-label={title}>
      <Text style={styles.groupTitle} accessibilityRole="header" {...ariaLevel(3)}>{title}</Text>
      {PERMISSION_OPTIONS.filter(o => o.write === write).map(option => {
        const lock = lockedBy(level, option.flag);
        // Under Full access the level itself grants the permission, whatever is held flag by flag.
        const notHeld = lock !== 'on' && !!held && !held[option.flag];
        return (
          <SwitchField
            key={option.flag}
            label={option.label}
            value={notHeld ? false : shown[option.flag]}
            onChange={value => onFlagChange(option.flag, value)}
            description={noteFor(level, option.flag, held, grantorName)}
            disabled={disabled || notHeld || lock !== null}
          />
        );
      })}
    </View>
  );

  return (
    <View style={styles.wrap}>
      <SelectField<AccessLevel>
        label="Access level"
        value={level}
        onChange={value => { if (value) onLevelChange(value); }}
        options={options.map(o => ({ value: o.value, label: o.label }))}
        hint={description}
        error={levelError}
        disabled={disabled || options.length < 2}
        required
      />
      <View style={styles.box}>
        {group('What you can see', false)}
        {group('What you can do', true)}
      </View>
    </View>
  );
}

export interface CommunicationFieldsProps {
  value:     CommFlags;
  onChange:  (flag: CommFlag, value: boolean) => void;
  /** Explains who approves and when, under the switches. */
  note:      string;
  /** Label of each switch; defaults to "Ask to ..." wording for a new request. */
  labels?:   Partial<Record<CommFlag, string>>;
  /** A caregiver editing their own grant can switch a request off, never on: pass what is on now. */
  held?:     CommFlags;
  disabled?: boolean;
}

const REQUEST_LABELS: Record<CommFlag, string> = {
  canMessageCareTeam: 'Ask to message the care team',
  canJoinCalls:       'Ask to join video calls',
};

/** The two communication switches. They are requests: nothing takes effect until the patient approves. */
export function CommunicationFields({ value, onChange, note, labels, held, disabled = false }: CommunicationFieldsProps) {
  return (
    <View style={styles.box}>
      <View style={styles.group} role="group" aria-label="Messaging and video calls">
        <Text style={styles.groupTitle} accessibilityRole="header" {...ariaLevel(3)}>Messaging and video calls</Text>
        {COMM_FLAGS.map(flag => (
          <SwitchField
            key={flag}
            label={labels?.[flag] ?? (held ? COMM_LABELS[flag] : REQUEST_LABELS[flag])}
            value={value[flag]}
            onChange={next => onChange(flag, next)}
            disabled={disabled || (!!held && !held[flag])}
          />
        ))}
        <Text style={styles.note}>{note}</Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap:       { gap: space.md, minWidth: 0 },
  box:        { gap: space.lg, padding: space.md, borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, backgroundColor: colors.bg, minWidth: 0 },
  group:      { gap: space.sm, minWidth: 0 },
  groupTitle: { fontSize: fontSize.sm, fontWeight: '700', color: colors.text },
  note:       { fontSize: fontSize.xs, color: colors.textMuted, lineHeight: 17 },
});
