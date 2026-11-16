import { useEffect, useRef } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  Button, colors, fontSize, Icon, IS_WEB, radius, space, StatusBadge, useUiTheme,
} from '@/components/web/ui';
import {
  careTeamAffiliation, careTeamMemberName, careTeamRoleIcon, careTeamRoleLine, isAccountProvider, isAssignedMember,
} from '@/lib/careteam/format';
import type { CareTeamMember } from '@/lib/careteam/types';
import { formatClinicDate } from '@/lib/time/clinic';

/** The row actions; `busy` names the one that is running. */
export type CareTeamRowAction = 'message' | 'primary' | 'role' | 'remove' | 'assign';

export interface CareTeamMemberRowProps {
  member: CareTeamMember;
  /** The list belongs to the person looking at it (wording: "your appointments"). */
  own: boolean;
  /** Draws the row as the one a link or notification pointed at, and scrolls it into view. */
  highlighted?: boolean;
  /** The action running on this row, if any. */
  busy?: CareTeamRowAction | null;
  /** Another row or dialog is busy: every control on this row ignores presses. */
  disabled?: boolean;
  /** Each handler is passed only when the viewer may use that control on this row. */
  onMessage?:       (member: CareTeamMember) => void;
  onTogglePrimary?: (member: CareTeamMember) => void;
  onChangeRole?:    (member: CareTeamMember) => void;
  onRemove?:        (member: CareTeamMember) => void;
  /** For a row derived from appointments: turn it into a stored membership. */
  onAssign?:        (member: CareTeamMember) => void;
}

/**
 * One care-team member: name, role and specialization, where they work, the Primary badge, the
 * "from appointments" label of a derived row, and the controls the viewer may use.
 */
export function CareTeamMemberRow({
  member, own, highlighted = false, busy = null, disabled = false,
  onMessage, onTogglePrimary, onChangeRole, onRemove, onAssign,
}: CareTeamMemberRowProps) {
  const { accent, accentBg } = useUiTheme();
  const rowRef = useRef<View>(null);

  const name = careTeamMemberName(member);
  const affiliation = careTeamAffiliation(member);
  const phone = (member.providerPhone ?? '').trim();
  const assigned = isAssignedMember(member);
  const added = assigned ? formatClinicDate(member.assignedAt) : '';
  const off = disabled || busy !== null;

  useEffect(() => {
    if (!highlighted || !IS_WEB) return;
    const node = rowRef.current as unknown as { scrollIntoView?: (options: object) => void } | null;
    node?.scrollIntoView?.({ block: 'nearest' });
  }, [highlighted]);

  const hasActions = !!(onMessage || onTogglePrimary || onChangeRole || onRemove || onAssign);

  return (
    <View
      ref={rowRef}
      role="listitem"
      style={[styles.row, highlighted && { borderColor: accent, backgroundColor: accentBg }]}
    >
      <View style={styles.top}>
        <View style={[styles.avatar, { backgroundColor: accentBg }]}>
          <Icon name={careTeamRoleIcon(member.role)} size={20} color={accent} />
        </View>

        <View style={styles.details}>
          <View style={styles.nameLine}>
            <Text style={styles.name}>{name}</Text>
            {member.isPrimary ? <StatusBadge label="Primary" tone="success" icon="star" /> : null}
          </View>
          <Text style={styles.role}>{careTeamRoleLine(member)}</Text>

          {affiliation ? (
            <View style={styles.meta}>
              <Icon name="business-outline" size={14} color={colors.textMuted} style={styles.metaIcon} />
              <Text style={styles.metaText}>{affiliation}</Text>
            </View>
          ) : null}
          {phone ? (
            <View style={styles.meta}>
              <Icon name="call-outline" size={14} color={colors.textMuted} style={styles.metaIcon} />
              <Text style={styles.metaText} selectable>{`Phone: ${phone}`}</Text>
            </View>
          ) : null}

          {member.source === 'DERIVED' ? (
            <StatusBadge
              label={own ? 'From your appointments' : 'From appointments with this patient'}
              tone="neutral"
              icon="calendar-outline"
            />
          ) : null}
          {assigned && !isAccountProvider(member) ? (
            <Text style={styles.note}>Not on CareNexus: listed for reference, with no access to records.</Text>
          ) : null}
          {added ? <Text style={styles.note}>{`Added ${added}`}</Text> : null}
        </View>
      </View>

      {hasActions ? (
        <View style={styles.actions}>
          {onMessage ? (
            <Button
              label="Message"
              accessibilityLabel={`Message ${name}`}
              icon="chatbubble-outline"
              variant="secondary"
              size="sm"
              onPress={() => onMessage(member)}
              loading={busy === 'message'}
              disabled={off && busy !== 'message'}
            />
          ) : null}
          {onTogglePrimary ? (
            <Button
              label={member.isPrimary ? 'Remove primary' : 'Make primary'}
              accessibilityLabel={member.isPrimary ? `Remove primary status from ${name}` : `Make primary: ${name}`}
              icon={member.isPrimary ? 'star-outline' : 'star'}
              variant="secondary"
              size="sm"
              onPress={() => onTogglePrimary(member)}
              loading={busy === 'primary'}
              disabled={off && busy !== 'primary'}
            />
          ) : null}
          {onChangeRole ? (
            <Button
              label="Change role"
              accessibilityLabel={`Change role of ${name}`}
              icon="swap-horizontal-outline"
              variant="secondary"
              size="sm"
              onPress={() => onChangeRole(member)}
              loading={busy === 'role'}
              disabled={off && busy !== 'role'}
            />
          ) : null}
          {onAssign ? (
            <Button
              label="Add to care team"
              accessibilityLabel={`Add to care team: ${name}`}
              icon="person-add-outline"
              variant="secondary"
              size="sm"
              onPress={() => onAssign(member)}
              loading={busy === 'assign'}
              disabled={off && busy !== 'assign'}
            />
          ) : null}
          {onRemove ? (
            <Button
              label="Remove"
              accessibilityLabel={`Remove ${name} from the care team`}
              icon="trash-outline"
              variant="ghost"
              size="sm"
              onPress={() => onRemove(member)}
              loading={busy === 'remove'}
              disabled={off && busy !== 'remove'}
            />
          ) : null}
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    gap: space.md, padding: space.md, minWidth: 0, maxWidth: '100%',
    borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, backgroundColor: colors.surface,
  },
  top:      { flexDirection: 'row', alignItems: 'flex-start', gap: space.md, minWidth: 0 },
  avatar:   { width: 40, height: 40, borderRadius: radius.pill, alignItems: 'center', justifyContent: 'center', flexShrink: 0 },
  details:  { flex: 1, minWidth: 0, gap: space.xs },
  nameLine: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', columnGap: space.sm, rowGap: space.xs },
  name:     { flexShrink: 1, minWidth: 0, fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  role:     { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  meta:     { flexDirection: 'row', alignItems: 'flex-start', gap: space.xs, minWidth: 0 },
  metaIcon: { flexShrink: 0, marginTop: 2 },
  metaText: { flex: 1, minWidth: 0, fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  note:     { fontSize: fontSize.xs, color: colors.textSubtle, lineHeight: 17 },
  actions:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
});
