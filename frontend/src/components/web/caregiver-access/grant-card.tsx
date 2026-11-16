import { useEffect, useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Banner, Button, Card, colors, DateField, fontSize, FormActions, Icon, radius, space,
  StatusBadge, SwitchField, useUiTheme,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import {
  isStaleAccessError,
  revokeCaregiverAccess,
  updateCaregiverAccess,
} from '@/hooks/use-caregiver-access';
import { caregiverDisplayName, formatAccessDate } from '@/lib/caregiver-access/format';
import {
  COMMUNICATION_FLAG_OPTIONS, dataFlagsToSend, dataFlagsUnderLevel, levelLabel,
} from '@/lib/caregiver-access/permissions';
import type {
  AccessLevel, CaregiverAccess, CommunicationFlag, DataFlags, UpdateCaregiverAccessBody,
} from '@/lib/caregiver-access/types';
import { expiryDateError } from '@/lib/caregiver-access/validation';
import { isGrantActive } from '@/lib/caregiver/permissions';
import { clinicToday } from '@/lib/time/clinic';
import { confirmAction } from '@/lib/ui/dialogs';
import { AccessSummary } from './access-summary';
import { ActivityPanel } from './activity-panel';
import { ConsentHistoryPanel } from './consent-history-panel';
import { Disclosure } from './disclosure';
import { Notice, scrollIntoView } from './notice';
import { PermissionEditor } from './permission-editor';

/** The date part of a grant's end ("YYYY-MM-DD"), or '' when it has none. */
function expiryDay(grant: CaregiverAccess): string {
  return grant.expiresAt ? grant.expiresAt.slice(0, 10) : '';
}

/** Whether a communication permission is in effect right now (requested, approved and the grant active). */
function inEffect(grant: CaregiverAccess, flag: CommunicationFlag): boolean {
  return flag === 'canMessageCareTeam' ? grant.messagingAllowed === true : grant.joinCallsAllowed === true;
}

function sentence(parts: string[]): string {
  return parts.join(' and ');
}

export interface GrantCardProps {
  grant:     CaregiverAccess;
  online:    boolean;
  /** Changes when something outside the card asks to edit this grant (a request from the same caregiver). */
  editToken?: number;
  onChanged: (grant: CaregiverAccess) => void;
  onRemoved: (accessId: number, name: string) => void;
  /** A change was refused because the grant is gone or in another state: the screen reloads its lists. */
  onStale:   (message: string) => void;
}

/**
 * One caregiver: what they can see and do right now (with the Full access / View only rule applied), requests
 * that wait for the patient (approve or decline), messaging and video calls, the permission editor with the
 * billing switches, removal, and the Activity and Consent history panels.
 */
export function GrantCard({ grant, online, editToken, onChanged, onRemoved, onStale }: GrantCardProps) {
  const { accent, accentBg } = useUiTheme();
  const [editing, setEditing] = useState(false);
  const [level,   setLevel]   = useState<AccessLevel>(grant.accessLevel);
  const [flags,   setFlags]   = useState<DataFlags>(() => dataFlagsUnderLevel(grant.accessLevel, grant));
  const [expires, setExpires] = useState(expiryDay(grant));
  const [expiresError, setExpiresError] = useState<string | null>(null);
  const [error,   setError]   = useState<string | null>(null);
  const [showActivity, setShowActivity] = useState(false);
  const [showHistory,  setShowHistory]  = useState(false);
  // Bumped after each saved change so an open consent history shows the step that was just recorded.
  const [historyVersion, setHistoryVersion] = useState(0);
  const cardRef = useRef<View>(null);

  const patch  = useAction((body: UpdateCaregiverAccessBody) => updateCaregiverAccess(grant.accessId, body));
  const revoke = useAction(() => revokeCaregiverAccess(grant.accessId));

  const name     = caregiverDisplayName(grant);
  // The list never contains revoked grants, so a grant that is not active has passed its end date.
  const expired  = grant.accessStatus === 'EXPIRED' || !isGrantActive(grant);
  const busy     = patch.pending || revoke.pending;
  const disabled = busy || !online;
  const ends     = formatAccessDate(grant.expiresAt);
  const given    = formatAccessDate(grant.authorizedDate);

  // Communication permissions the caregiver asked for that the patient has not answered yet.
  const waiting = grant.pendingApproval
    ? COMMUNICATION_FLAG_OPTIONS.filter(o => grant[o.key] && !inEffect(grant, o.key))
    : [];

  function startEdit() {
    setLevel(grant.accessLevel);
    // Seeded with what the level really gives, so saving never narrows a Full access grant by accident.
    setFlags(dataFlagsUnderLevel(grant.accessLevel, grant));
    setExpires(expiryDay(grant));
    setExpiresError(null);
    setError(null);
    setEditing(true);
  }

  const startEditRef = useRef(startEdit);
  startEditRef.current = startEdit;
  useEffect(() => {
    if (editToken === undefined) return;
    startEditRef.current();
    scrollIntoView(cardRef);
  }, [editToken]);

  async function save(body: UpdateCaregiverAccessBody, fallback: string): Promise<boolean> {
    setError(null);
    const result = await patch.run(body);
    if (result.ok) {
      onChanged(result.data);
      setHistoryVersion(v => v + 1);
      return true;
    }
    if (!result.error) return false;
    if (isStaleAccessError(result.error.status)) onStale(result.error.message);
    else setError(result.error.status >= 500 ? fallback : result.error.message);
    return false;
  }

  /** Approve sends `true`, Decline sends `false`, for every communication permission that is waiting. */
  function answerWaiting(approve: boolean) {
    const body: UpdateCaregiverAccessBody = {};
    for (const option of waiting) body[option.key] = approve;
    save(body, approve ? 'Could not approve. Please try again.' : 'Could not decline. Please try again.');
  }

  /** The switch of one communication permission: on approves it, off withdraws the request and the approval. */
  function setCommunication(flag: CommunicationFlag, value: boolean) {
    const body: UpdateCaregiverAccessBody = {};
    body[flag] = value;
    save(body, 'Could not save the change. Please try again.');
  }

  async function saveEdit() {
    const day = expires.trim();
    const dayChanged = day !== expiryDay(grant);
    const problem = dayChanged ? expiryDateError(day) : null;
    setExpiresError(problem);
    if (problem) return;
    const body: UpdateCaregiverAccessBody = { accessLevel: level, ...dataFlagsToSend(level, flags) };
    if (dayChanged) body.expiresAt = day; // "" removes the end date
    if (await save(body, 'Could not save changes. Please try again.')) setEditing(false);
  }

  async function remove() {
    const confirmed = await confirmAction(
      `Remove ${name}'s access?`,
      `${name} will no longer see your information. They are also removed from conversations and from any call in progress.`,
      'Remove access',
      true,
    );
    if (!confirmed) return;
    setError(null);
    const result = await revoke.run();
    if (result.ok) { onRemoved(grant.accessId, name); return; }
    if (!result.error) return;
    if (isStaleAccessError(result.error.status)) onStale(result.error.message);
    else setError(result.error.status >= 500 ? 'Could not remove access. Please try again.' : result.error.message);
  }

  return (
    <Card style={styles.card}>
      <View ref={cardRef} style={styles.header}>
        <View style={[styles.avatar, { backgroundColor: accentBg }]}>
          <Icon name="person-outline" size={18} color={accent} />
        </View>
        <View style={styles.who}>
          <Text style={styles.name} accessibilityRole="header" {...ariaLevel(3)}>{name}</Text>
          {grant.caregiverEmail && grant.caregiverEmail !== name
            ? <Text style={styles.meta}>{grant.caregiverEmail}</Text>
            : null}
          <Text style={styles.meta}>{`Relationship: ${grant.relationship}`}</Text>
        </View>
        <View style={styles.badges}>
          <StatusBadge label={levelLabel(grant.accessLevel)} tone="info" icon="key-outline" />
          {expired
            ? <StatusBadge label="Ended" tone="warning" icon="time-outline" />
            : <StatusBadge label="Active" tone="success" />}
        </View>
      </View>

      <Text style={styles.meta}>
        {[
          given ? `Access given ${given}.` : null,
          grant.expiresAt ? `${expired ? 'Ended' : 'Ends'} ${ends || 'on a set date'}.` : 'No end date.',
          grant.guardianAttestedAt ? `${name} confirmed they are your parent or legal guardian.` : null,
        ].filter(Boolean).join(' ')}
      </Text>

      {waiting.length > 0 ? (
        <Notice
          tone="warning"
          icon="hand-left-outline"
          title="Needs your answer"
          text={`${name} asked to ${sentence(waiting.map(o => o.verb))}. Nothing is allowed until you approve.`}
        >
          <Button
            label="Decline" variant="secondary" size="sm"
            accessibilityLabel={`Decline, ${name}'s request to ${sentence(waiting.map(o => o.verb))}`}
            onPress={() => answerWaiting(false)} disabled={disabled}
          />
          <Button
            label="Approve" size="sm"
            accessibilityLabel={`Approve, ${name}'s request to ${sentence(waiting.map(o => o.verb))}`}
            onPress={() => answerWaiting(true)} disabled={disabled}
          />
        </Notice>
      ) : null}

      {expired ? (
        <Notice
          tone="warning"
          icon="time-outline"
          title="This access has ended"
          text={`${name} cannot see or do anything now. Set a later end date, or clear it, to give the access below again.`}
        >
          {!editing ? (
            <Button
              label="Change end date" variant="secondary" size="sm"
              accessibilityLabel={`Change end date, ${name}`}
              onPress={startEdit} disabled={disabled}
            />
          ) : null}
        </Notice>
      ) : null}

      {!editing ? (
        <View style={styles.block}>
          <Text style={styles.blockTitle} accessibilityRole="header" {...ariaLevel(4)}>
            {expired ? 'What they could see and do' : 'What they can see and do'}
          </Text>
          <AccessSummary level={grant.accessLevel} flags={grant} label={`Permissions of ${name}`} />
        </View>
      ) : null}

      <View style={styles.box}>
        <Text style={styles.blockTitle} accessibilityRole="header" {...ariaLevel(4)}>Messaging and video calls</Text>
        {COMMUNICATION_FLAG_OPTIONS.map(option => {
          const on = inEffect(grant, option.key);
          return (
            <SwitchField
              key={option.key}
              label={option.label}
              description={on ? 'Allowed.' : grant[option.key] && !expired ? 'Asked for, waiting for your answer.' : 'Not allowed.'}
              value={on}
              onChange={value => setCommunication(option.key, value)}
              disabled={disabled || expired}
            />
          );
        })}
      </View>

      {editing ? (
        <View style={styles.block}>
          <PermissionEditor
            level={level}
            flags={flags}
            onChange={(nextLevel, nextFlags) => { setLevel(nextLevel); setFlags(nextFlags); }}
            disabled={busy}
          />
          <DateField
            label="Access ends on (optional)"
            value={expires}
            onChange={value => { setExpires(value); setExpiresError(null); }}
            error={expiresError}
            hint="Access ends at the end of that day. Leave empty for no end date."
            min={clinicToday()}
            disabled={busy}
          />
        </View>
      ) : null}

      {error ? <Banner tone="danger" text={error} onDismiss={() => setError(null)} /> : null}

      {editing ? (
        <FormActions
          submitLabel="Save changes"
          onSubmit={saveEdit}
          onCancel={() => { setEditing(false); setError(null); }}
          submitting={patch.pending}
          disabled={!online}
        />
      ) : (
        <View style={styles.actions}>
          <Button
            label="Remove access" variant="danger" icon="trash-outline"
            accessibilityLabel={`Remove access, ${name}`}
            onPress={remove} loading={revoke.pending} disabled={disabled && !revoke.pending}
          />
          <Button
            label="Change permissions" variant="secondary" icon="create-outline"
            accessibilityLabel={`Change permissions, ${name}`}
            onPress={startEdit} disabled={disabled}
          />
        </View>
      )}

      <View>
        <Disclosure
          label="Activity" context={name} icon="eye-outline"
          open={showActivity} onToggle={() => setShowActivity(open => !open)}
        >
          <ActivityPanel accessId={grant.accessId} caregiverName={name} onStale={onStale} />
        </Disclosure>
        <Disclosure
          label="Consent history" context={name} icon="document-text-outline"
          open={showHistory} onToggle={() => setShowHistory(open => !open)}
        >
          <ConsentHistoryPanel accessId={grant.accessId} caregiverName={name} refreshKey={historyVersion} />
        </Disclosure>
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  card:   { gap: space.md },
  header: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: space.md },
  avatar: { width: 40, height: 40, borderRadius: radius.pill, alignItems: 'center', justifyContent: 'center', flexShrink: 0 },
  who:    { flexGrow: 1, flexShrink: 1, flexBasis: 160, minWidth: 0, gap: 2 },
  name:   { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  meta:   { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  badges: { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs },

  block:      { gap: space.sm, minWidth: 0 },
  blockTitle: { fontSize: fontSize.sm, fontWeight: '700', color: colors.text },
  box: {
    gap: 2, paddingHorizontal: space.md, paddingVertical: space.sm,
    borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, backgroundColor: colors.bg,
  },
  actions: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'flex-end', alignItems: 'center', gap: space.sm },
});
