import { useEffect, useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Banner, Button, Card, colors, DateField, fontSize, FormActions, Icon, radius, space,
  StatusBadge, TextField, useUiTheme,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import {
  approveAccessRequest,
  declineAccessRequest,
  isStaleAccessError,
} from '@/hooks/use-caregiver-access';
import { caregiverDisplayName, formatAccessDate, formatAccessDateTime } from '@/lib/caregiver-access/format';
import {
  COMMUNICATION_FLAG_OPTIONS, dataFlagsToSend, dataFlagsUnderLevel, describeDataAccess, levelLabel,
} from '@/lib/caregiver-access/permissions';
import type {
  AccessLevel, ApproveAccessRequestBody, CaregiverAccess, CaregiverAccessRequest, CommunicationFlags,
  DataFlags, DeclineAccessRequestBody,
} from '@/lib/caregiver-access/types';
import { DECLINE_REASON_MAX, declineReasonError, expiryDateError } from '@/lib/caregiver-access/validation';
import { clinicToday } from '@/lib/time/clinic';
import { Notice, scrollIntoView } from './notice';
import { CommunicationEditor, PermissionEditor } from './permission-editor';

export interface AccessRequestCardProps {
  request:        CaregiverAccessRequest;
  /** The grant this caregiver already holds (active or ended), when there is one: it is edited instead. */
  existingGrant?: CaregiverAccess | null;
  online:         boolean;
  /** The request a notification link pointed at: it is outlined and scrolled into view. */
  highlighted?:   boolean;
  onApproved:     (request: CaregiverAccessRequest) => void;
  onDeclined:     (request: CaregiverAccessRequest) => void;
  /** The request was answered, cancelled or expired meanwhile, or cannot be approved: the screen reloads. */
  onStale:        (message: string) => void;
  /** Opens the permission editor of the caregiver's existing grant. */
  onEditGrant:    (accessId: number) => void;
}

/**
 * One pending access request: who asks, their relationship and message, what they asked for, and the two
 * answers. Approving opens the permission editor pre-filled with the request; the patient decides the final
 * level, every permission (billing included), messaging, video calls and an optional end date. Declining takes
 * an optional reason that the caregiver will see.
 */
export function AccessRequestCard({
  request, existingGrant, online, highlighted = false, onApproved, onDeclined, onStale, onEditGrant,
}: AccessRequestCardProps) {
  const { accent, accentBg } = useUiTheme();
  const [mode, setMode] = useState<'idle' | 'approve' | 'decline'>('idle');
  const [level, setLevel] = useState<AccessLevel>(request.accessLevel);
  const [flags, setFlags] = useState<DataFlags>(() => dataFlagsUnderLevel(request.accessLevel, request));
  const [communication, setCommunication] = useState<CommunicationFlags>({
    canMessageCareTeam: request.canMessageCareTeam === true,
    canJoinCalls:       request.canJoinCalls === true,
  });
  const [expires, setExpires] = useState('');
  const [expiresError, setExpiresError] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const [reasonError, setReasonError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const cardRef = useRef<View>(null);

  const approve = useAction((body: ApproveAccessRequestBody) => approveAccessRequest(request.requestId, body));
  const decline = useAction((body: DeclineAccessRequestBody) => declineAccessRequest(request.requestId, body));

  const name = caregiverDisplayName(request);
  const busy = approve.pending || decline.pending;
  const asked = formatAccessDateTime(request.createdAt);
  const lapses = formatAccessDate(request.requestExpiresAt);
  const askedCommunication = COMMUNICATION_FLAG_OPTIONS.filter(o => request[o.key]).map(o => o.verb);

  useEffect(() => {
    if (highlighted) scrollIntoView(cardRef);
  }, [highlighted]);

  function open(next: 'approve' | 'decline') {
    setError(null);
    setExpiresError(null);
    setReasonError(null);
    setMode(next);
  }

  async function submitApproval() {
    const problem = expiryDateError(expires);
    setExpiresError(problem);
    if (problem) return;
    setError(null);
    // The body is authoritative on the server: the level and all twelve flags are sent, never left to defaults.
    const result = await approve.run({
      accessLevel: level,
      ...dataFlagsToSend(level, flags),
      ...communication,
      expiresAt: expires.trim() || null,
    });
    if (result.ok) { onApproved(result.data); return; }
    if (!result.error) return;
    if (isStaleAccessError(result.error.status)) { setMode('idle'); onStale(result.error.message); return; }
    setError(result.error.status >= 500 ? 'Could not approve the request. Please try again.' : result.error.message);
  }

  async function submitDecline() {
    const problem = declineReasonError(reason);
    setReasonError(problem);
    if (problem) return;
    setError(null);
    const text = reason.trim();
    const result = await decline.run(text ? { reason: text } : {});
    if (result.ok) { onDeclined(result.data); return; }
    if (!result.error) return;
    if (isStaleAccessError(result.error.status)) { setMode('idle'); onStale(result.error.message); return; }
    const fieldMessage = result.error.fieldErrors?.reason;
    if (fieldMessage) { setReasonError(fieldMessage); return; }
    setError(result.error.status >= 500 ? 'Could not decline the request. Please try again.' : result.error.message);
  }

  return (
    <Card style={[styles.card, highlighted && { borderColor: accent, borderWidth: 2 }]}>
      <View ref={cardRef} style={styles.header}>
        <View style={[styles.avatar, { backgroundColor: accentBg }]}>
          <Icon name="person-add-outline" size={18} color={accent} />
        </View>
        <View style={styles.who}>
          <Text style={styles.name} accessibilityRole="header" {...ariaLevel(3)}>{name}</Text>
          {request.caregiverEmail && request.caregiverEmail !== name
            ? <Text style={styles.meta}>{request.caregiverEmail}</Text>
            : null}
          <Text style={styles.meta}>{`Relationship: ${request.relationship}`}</Text>
        </View>
        <StatusBadge label="Waiting for your answer" tone="warning" icon="hourglass-outline" />
      </View>

      <Text style={styles.meta}>
        {[
          asked ? `Asked ${asked}.` : null,
          lapses ? `If you do not answer, the request lapses after ${lapses}.` : null,
        ].filter(Boolean).join(' ')}
      </Text>

      {request.message?.trim() ? (
        <View style={styles.message}>
          <Text style={styles.blockTitle}>{`Message from ${name}`}</Text>
          <Text style={styles.messageText}>{request.message.trim()}</Text>
        </View>
      ) : null}

      <View style={styles.block}>
        <Text style={styles.blockTitle} accessibilityRole="header" {...ariaLevel(4)}>What they asked for</Text>
        <Text style={styles.body}>
          {`${levelLabel(request.accessLevel)}. ${describeDataAccess(request.accessLevel, request)}`}
        </Text>
        <Text style={styles.body}>
          {askedCommunication.length > 0
            ? `Also asked to ${askedCommunication.join(' and ')}.`
            : 'Did not ask for messaging or video calls.'}
        </Text>
      </View>

      {existingGrant ? (
        <Notice
          tone="info"
          icon="information-circle-outline"
          title={`${name} already has access`}
          text={`They hold ${levelLabel(existingGrant.accessLevel)} access${existingGrant.accessStatus === 'EXPIRED' ? ' that has ended' : ''}. Change their permissions instead, then decline this request.`}
        >
          <Button
            label="Change their permissions" variant="secondary" size="sm"
            accessibilityLabel={`Change their permissions, ${name}`}
            onPress={() => onEditGrant(existingGrant.accessId)} disabled={busy}
          />
        </Notice>
      ) : null}

      {mode === 'approve' ? (
        <View style={styles.block}>
          <Text style={styles.body}>
            {`Choose what ${name} gets. It starts from what they asked for; nothing is shared until you approve.`}
          </Text>
          <PermissionEditor
            level={level}
            flags={flags}
            onChange={(nextLevel, nextFlags) => { setLevel(nextLevel); setFlags(nextFlags); }}
            disabled={busy}
          />
          <CommunicationEditor value={communication} onChange={setCommunication} disabled={busy} />
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

      {mode === 'decline' ? (
        <TextField
          label="Reason (optional)"
          value={reason}
          onChange={value => { setReason(value); setReasonError(null); }}
          error={reasonError}
          hint={`${name} will see this reason.`}
          multiline rows={3} maxLength={DECLINE_REASON_MAX} showCount
          disabled={busy}
        />
      ) : null}

      {error ? <Banner tone="danger" text={error} onDismiss={() => setError(null)} /> : null}

      {mode === 'approve' ? (
        <FormActions
          submitLabel="Approve access"
          submitIcon="checkmark-outline"
          onSubmit={submitApproval}
          onCancel={() => { setMode('idle'); setError(null); }}
          submitting={approve.pending}
          disabled={!online}
        />
      ) : mode === 'decline' ? (
        <FormActions
          submitLabel="Decline request"
          onSubmit={submitDecline}
          onCancel={() => { setMode('idle'); setError(null); }}
          submitting={decline.pending}
          disabled={!online}
          destructive
        />
      ) : (
        <View style={styles.actions}>
          <Button
            label="Decline" variant="secondary" icon="close-outline"
            accessibilityLabel={`Decline, request from ${name}`}
            onPress={() => open('decline')} disabled={!online}
          />
          {!existingGrant ? (
            <Button
              label="Review and approve" icon="checkmark-outline"
              accessibilityLabel={`Review and approve, request from ${name}`}
              onPress={() => open('approve')} disabled={!online}
            />
          ) : null}
        </View>
      )}
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

  block:      { gap: space.sm, minWidth: 0 },
  blockTitle: { fontSize: fontSize.sm, fontWeight: '700', color: colors.text },
  body:       { fontSize: fontSize.sm, color: colors.text, lineHeight: 19 },
  message: {
    gap: space.xs, paddingHorizontal: space.md, paddingVertical: space.sm,
    borderLeftWidth: 3, borderLeftColor: colors.borderInput, backgroundColor: colors.bg, borderRadius: radius.sm,
  },
  messageText: { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  actions: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'flex-end', alignItems: 'center', gap: space.sm },
});
