import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  Banner, Button, colors, EmptyView, ErrorView, fontSize, InfoRow, LoadingView, PagedList, Screen, SectionCard,
  ariaLevel, space, StatusBadge,
} from '@/components/web/ui';
import {
  useAdminUser, useAdminUserActions, useAdminUserAuditLogs, useAdminUserLegalAcceptances, useAdminUserMfa,
} from '@/hooks/use-admin-user';
import { useCurrentUser } from '@/hooks/use-current-user';
import {
  accountActions, accountState, type AccountState, type AdminUserActionResult, type AdminUserDetail, type AuditEntry,
  fullName, type LegalAcceptance, legalDocLabel, roleLabel,
} from '@/lib/admin/types';
import type { SectionProps } from '@/lib/nav/sections';
import { formatClinicDateTime } from '@/lib/time/clinic';
import {
  AuditDetailPanel, auditActorName, auditResourceText, auditRowName, AuditStatusBadge, auditSubjectText,
} from './admin-audit-log';
import { ClinicianFacts, VerificationBadge } from './admin-clinician-queue';
import {
  accountActionNotice, AccountBadges, type ActionNotice, ActionNoticeBanner, ActivateAccountDialog, ConfirmDialog,
  DeactivateAccountDialog, useAdminNav,
} from './admin-users';

// Administrator screen "Account" (section `user-detail`, URL param `id` = the account's system id): profile,
// status, clinician verification, sign-in and security actions, legal acceptances and the account's recent
// audit rows. After every account action the screen shows the account as the server returned it.

function stateSentence(state: AccountState, user: AdminUserDetail): string {
  switch (state) {
    case 'ACTIVE':      return 'This account can sign in.';
    case 'DEACTIVATED': return 'This account was deactivated and cannot sign in.';
    case 'CLOSED':      return `The owner closed this account on ${formatClinicDateTime(user.closedAt, 'an earlier date')}. It cannot sign in until an administrator re-opens it.`;
    case 'DELETED':     return 'The personal data of this account was removed on request. It can never be reactivated.';
    case 'PENDING':     return 'This clinician is waiting for licence verification and cannot sign in yet.';
    case 'REJECTED':    return 'This clinician registration was rejected. The clinician cannot sign in.';
    case 'SUSPENDED':   return 'This clinician was suspended when the account was deactivated and cannot sign in.';
  }
}

type Dialog = 'deactivate' | 'activate' | 'sessions' | 'password' | 'mfa';

export default function AdminUserDetail(props: Partial<SectionProps>) {
  const { params, theme, openUsers, openUser, openQueue, openAudit } = useAdminNav(props);
  const systemId = (params.id ?? '').trim();

  const account = useAdminUser(systemId);
  const user = account.data;
  const { user: me } = useCurrentUser();
  const selfId = me?.system_id ?? null;

  // The related lists are asked for once the account is known to exist.
  const found = user !== null;
  const mfa = useAdminUserMfa(systemId, { enabled: found });
  const legal = useAdminUserLegalAcceptances(systemId, { enabled: found });
  const audit = useAdminUserAuditLogs(systemId, { enabled: found });
  const actions = useAdminUserActions();

  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [notice, setNotice] = useState<ActionNotice | null>(null);
  const [auditEntry, setAuditEntry] = useState<AuditEntry | null>(null);

  const backToUsers = openUsers
    ? <Button label="All users" variant="secondary" icon="arrow-back-outline" onPress={openUsers} />
    : undefined;

  if (!systemId) {
    return (
      <Screen title="Account" theme={theme} actions={backToUsers}>
        <EmptyView
          icon="person-outline" title="No account selected" sub="Open an account from the Users list."
          action={openUsers ? { label: 'Go to Users', onPress: openUsers } : undefined}
        />
      </Screen>
    );
  }
  if (account.loading) {
    return (
      <Screen title="Account" subtitle={systemId} theme={theme} actions={backToUsers}>
        <LoadingView label="Loading account…" />
      </Screen>
    );
  }
  if (!user) {
    return (
      <Screen title="Account" subtitle={systemId} theme={theme} actions={backToUsers}>
        {account.errorStatus === 404 ? (
          <EmptyView
            icon="person-outline" title="Account not found" sub={`No account has the ID ${systemId}.`}
            action={openUsers ? { label: 'Go to Users', onPress: openUsers } : undefined}
          />
        ) : (
          <ErrorView message={account.error ?? 'The account could not be loaded.'} onRetry={account.refetch} />
        )}
      </Screen>
    );
  }

  const name = fullName(user, user.systemId);
  const isSelf = user.systemId === selfId;
  const state = accountState(user);
  const can = accountActions(user, selfId);
  const showAppointments = user.role === 'DOCTOR' || user.role === 'PATIENT';

  const open = (next: Dialog) => {
    actions.activate.reset();
    actions.deactivate.reset();
    actions.revokeSessions.reset();
    actions.sendPasswordReset.reset();
    mfa.reset.reset();
    setDialog(next);
  };

  /** The response of an account action is the account after it: it replaces what is shown. */
  const applyResult = (result: AdminUserActionResult, deactivation = false) => {
    setDialog(null);
    account.setData(result);
    setNotice(accountActionNotice(result, deactivation));
    audit.refetch();
  };

  const runAction = async (run: (id: string) => Promise<{ ok: true; data: AdminUserActionResult } | { ok: false }>) => {
    const result = await run(user.systemId);
    if (result.ok) applyResult(result.data);
  };

  const confirmMfaReset = async () => {
    const result = await mfa.reset.run();
    if (!result.ok) return;
    setDialog(null);
    mfa.setData(result.data);
    setNotice({
      tone: 'success',
      text: `Two-factor authentication was reset for ${name} (${user.systemId}).`,
      detail: result.data.required
        ? 'Two-factor authentication is required for this role: it must be set up again at the next sign-in.'
        : null,
    });
    audit.refetch();
  };

  const renderAcceptance = (row: LegalAcceptance) => (
    <View style={styles.row}>
      <View style={styles.rowHead}>
        <Text style={styles.rowTitle}>{legalDocLabel(row.docType)}, version {row.version}</Text>
        <StatusBadge label={row.current ? 'Current version' : 'Earlier version'} tone={row.current ? 'success' : 'neutral'} />
      </View>
      <Text style={styles.muted}>Accepted {formatClinicDateTime(row.acceptedAt, 'at an unknown time')}</Text>
      {row.ipAddress ? <Text style={styles.muted} selectable>IP address {row.ipAddress}</Text> : null}
      {row.userAgent ? <Text style={styles.muted} selectable>{row.userAgent}</Text> : null}
    </View>
  );

  const renderAudit = (row: AuditEntry) => {
    const about = [auditResourceText(row), auditSubjectText(row)].filter(Boolean).join(' · ');
    return (
      <View style={styles.row}>
        <View style={styles.rowHead}>
          <Text style={styles.rowTitle} selectable>{row.action}</Text>
          <AuditStatusBadge status={row.status} />
        </View>
        <Text style={styles.muted}>
          {formatClinicDateTime(row.timestamp, 'Time unknown')} · by {auditActorName(row)}
        </Text>
        {about ? <Text style={styles.muted} selectable>{about}</Text> : null}
        <Button
          label="Details" variant="ghost" size="sm" icon="open-outline"
          accessibilityLabel={`Details, ${auditRowName(row)}`}
          onPress={() => setAuditEntry(row)}
        />
      </View>
    );
  };

  const passwordLabel = user.hasPassword ? 'Send password reset link' : 'Send invitation';

  return (
    <Screen title={name} subtitle={`${user.systemId} · ${roleLabel(user.role)}`} theme={theme} actions={backToUsers}>
      <ActionNoticeBanner notice={notice} onDismiss={() => setNotice(null)} />
      {account.error ? (
        <Banner tone="danger" text={account.error} action={{ label: 'Retry', onPress: account.refetch }} />
      ) : null}

      <View style={styles.grid}>
        <SectionCard title="Profile" style={styles.col}>
          <View>
            <InfoRow label="Name" value={name} />
            <InfoRow label="Account ID" value={user.systemId} />
            <InfoRow label="Role" value={roleLabel(user.role)} />
            <InfoRow label="Email" emptyText="No email address">
              {user.email ? (
                <View style={styles.inline}>
                  <Text style={styles.value} selectable>{user.email}</Text>
                  <StatusBadge
                    label={user.emailVerified ? 'Confirmed' : 'Not confirmed'}
                    tone={user.emailVerified ? 'success' : 'warning'}
                  />
                </View>
              ) : undefined}
            </InfoRow>
            {user.emailVerified ? (
              <InfoRow label="Email confirmed" value={formatClinicDateTime(user.emailVerifiedAt)} emptyText="Date not recorded" />
            ) : null}
            <InfoRow label="Phone" value={user.phone} />
            <InfoRow label="Created" value={formatClinicDateTime(user.createdAt)} emptyText="Unknown" />
            <InfoRow label="Last updated" value={formatClinicDateTime(user.updatedAt)} emptyText="Unknown" />
          </View>
        </SectionCard>

        <SectionCard title="Account status" style={styles.col}>
          <AccountBadges user={user} isSelf={isSelf} />
          <Text style={styles.value}>{stateSentence(state, user)}</Text>
          <View>
            {user.deactivationReason ? <InfoRow label="Deactivation reason" value={user.deactivationReason} /> : null}
            {user.closedAt && !user.anonymised ? (
              <InfoRow label="Closure reason" value={user.closureReason} emptyText="No reason given" />
            ) : null}
            {showAppointments ? (
              <InfoRow label="Upcoming appointments" value={user.futureActiveAppointments} />
            ) : null}
          </View>
          {can.canDeactivate || can.canActivate || (can.needsVerification && openQueue) ? (
            <View style={styles.actions}>
              {can.canDeactivate ? (
                <Button label="Deactivate account" variant="danger" icon="ban-outline" onPress={() => open('deactivate')} />
              ) : null}
              {can.canActivate ? (
                <Button
                  label={can.isReopen ? 'Re-open account' : 'Reactivate account'} icon="refresh-outline"
                  onPress={() => open('activate')}
                />
              ) : null}
              {can.needsVerification && openQueue ? (
                <Button
                  label="Open verification queue" variant="secondary" icon="shield-checkmark-outline"
                  onPress={() => openQueue(user.verificationStatus)}
                />
              ) : null}
            </View>
          ) : null}
          {isSelf && user.isActive ? (
            <Text style={styles.muted}>This is your own account. Another administrator has to deactivate it.</Text>
          ) : null}
        </SectionCard>

        {user.role === 'DOCTOR' ? (
          <SectionCard
            title="Clinician verification"
            style={styles.col}
            action={user.doctor && openQueue ? (
              <Button
                label="Open in verification queue" variant="secondary" size="sm" icon="shield-checkmark-outline"
                onPress={() => openQueue(user.doctor?.verificationStatus)}
              />
            ) : undefined}
          >
            {user.doctor ? (
              <>
                <VerificationBadge status={user.doctor.verificationStatus} />
                <ClinicianFacts doctor={user.doctor} />
              </>
            ) : (
              <Text style={styles.value}>
                This doctor account has no clinician record, so it cannot be verified or activated.
              </Text>
            )}
          </SectionCard>
        ) : null}

        <SectionCard title="Sign-in and security" style={styles.col}>
          <View>
            <InfoRow
              label="Password"
              value={!user.hasPassword
                ? 'No password yet'
                : user.mustChangePassword ? 'Temporary: must be changed at the next sign-in' : 'Set'}
            />
            <InfoRow
              label="Password changed" value={formatClinicDateTime(user.passwordChangedAt)}
              emptyText="Not since registration"
            />
            <InfoRow label="Active sessions" value={user.activeSessions} />
          </View>
          {can.canManageSecurity ? (
            <View style={styles.actions}>
              <Button
                label="Sign out everywhere" variant="secondary" icon="log-out-outline"
                onPress={() => open('sessions')}
              />
              {can.canSendPasswordLink ? (
                <Button label={passwordLabel} variant="secondary" icon="mail-outline" onPress={() => open('password')} />
              ) : null}
            </View>
          ) : null}
          {can.canManageSecurity && !user.email ? (
            <Text style={styles.muted}>This account has no email address, so a password link cannot be sent.</Text>
          ) : null}

          <View style={styles.divider} />
          <Text style={styles.subheading} accessibilityRole="header" {...ariaLevel(3)}>Two-factor authentication</Text>
          {mfa.loading ? (
            <LoadingView compact label="Loading two-factor authentication status…" />
          ) : mfa.data ? (
            <>
              <View>
                <InfoRow label="Status">
                  <View style={styles.inline}>
                    <StatusBadge label={mfa.data.enabled ? 'Turned on' : 'Not turned on'} tone={mfa.data.enabled ? 'success' : 'neutral'} />
                    {mfa.data.required ? <StatusBadge label="Required for this role" tone="info" icon="lock-closed-outline" /> : null}
                  </View>
                </InfoRow>
                {mfa.data.enabled ? (
                  <>
                    <InfoRow label="Turned on" value={formatClinicDateTime(mfa.data.enabledAt)} emptyText="Date not recorded" />
                    <InfoRow label="Recovery codes left" value={mfa.data.recoveryCodesRemaining} />
                  </>
                ) : null}
              </View>
              {mfa.error ? <Banner tone="danger" text={mfa.error} action={{ label: 'Retry', onPress: mfa.refetch }} /> : null}
              {mfa.data.enabled && can.canManageSecurity ? (
                <View style={styles.actions}>
                  <Button
                    label="Reset two-factor authentication" variant="secondary" icon="key-outline"
                    onPress={() => open('mfa')}
                  />
                </View>
              ) : null}
            </>
          ) : (
            <ErrorView
              message={mfa.error ?? 'The two-factor authentication status could not be loaded.'}
              onRetry={mfa.refetch} compact
            />
          )}
        </SectionCard>

        <SectionCard
          title="Legal acceptances"
          subtitle="Terms of Service and Privacy Policy versions this account accepted."
          style={styles.col}
        >
          <PagedList<LegalAcceptance>
            state={legal}
            keyOf={row => row.acceptanceId}
            renderItem={renderAcceptance}
            label="Legal acceptances"
            empty={{ icon: 'document-text-outline', title: 'Nothing accepted yet', sub: 'This account has not accepted the terms or the privacy policy.' }}
          />
        </SectionCard>

        <SectionCard
          title="Recent activity"
          subtitle="Audit entries where this account acted, was the target, or is the patient concerned."
          style={styles.col}
          action={openAudit ? (
            <Button
              label="Open audit log" variant="secondary" size="sm" icon="list-outline"
              accessibilityLabel={`Open audit log for actions by ${name}`}
              onPress={() => openAudit({ actor: user.systemId })}
            />
          ) : undefined}
        >
          <PagedList<AuditEntry>
            state={audit}
            keyOf={row => row.auditId}
            renderItem={renderAudit}
            label="Recent activity"
            empty={{ icon: 'time-outline', title: 'No activity recorded' }}
          />
        </SectionCard>
      </View>

      {dialog === 'deactivate' ? (
        <DeactivateAccountDialog
          user={user} action={actions.deactivate}
          onDone={result => applyResult(result, true)} onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog === 'activate' ? (
        <ActivateAccountDialog
          user={user} action={actions.activate}
          onDone={result => applyResult(result)} onClose={() => setDialog(null)}
          onOpenQueue={openQueue ? () => { setDialog(null); openQueue(user.verificationStatus); } : undefined}
        />
      ) : null}
      {dialog === 'sessions' ? (
        <ConfirmDialog
          title="Sign out everywhere"
          message={`${name} (${user.systemId}) will be signed out on every device. A device that is signed in now can keep working for up to 15 minutes.`}
          note={isSelf
            ? 'This is your own account: you will have to sign in again too.'
            : 'The account stays active and can sign in again.'}
          confirmLabel="Sign out everywhere"
          destructive
          pending={actions.revokeSessions.pending}
          error={actions.revokeSessions.error}
          onConfirm={() => { void runAction(actions.revokeSessions.run); }}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog === 'password' ? (
        <ConfirmDialog
          title={passwordLabel}
          message={user.hasPassword
            ? `A link to set a new password will be emailed to ${user.email ?? 'the account'}. Any earlier link stops working.`
            : `An invitation to set a password will be emailed to ${user.email ?? 'the account'}. Any earlier link stops working.`}
          note={user.hasPassword
            ? 'The current password keeps working, and nobody is signed out, until the new password is set.'
            : null}
          confirmLabel={user.hasPassword ? 'Send link' : 'Send invitation'}
          pending={actions.sendPasswordReset.pending}
          error={actions.sendPasswordReset.error}
          onConfirm={() => { void runAction(actions.sendPasswordReset.run); }}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog === 'mfa' ? (
        <ConfirmDialog
          title="Reset two-factor authentication"
          message={mfa.data?.required
            ? `The authenticator app and recovery codes of ${name} (${user.systemId}) will stop working. Two-factor authentication is required for this role, so it must be set up again at the next sign-in.`
            : `The authenticator app and recovery codes of ${name} (${user.systemId}) will stop working. The account then signs in with the password only.`}
          note="Devices that are signed in now stay signed in. Use Sign out everywhere to end them."
          confirmLabel="Reset"
          destructive
          pending={mfa.reset.pending}
          error={mfa.reset.error}
          onConfirm={() => { void confirmMfaReset(); }}
          onClose={() => setDialog(null)}
        />
      ) : null}

      <AuditDetailPanel
        open={auditEntry !== null}
        entry={auditEntry}
        onClose={() => setAuditEntry(null)}
        onOpenUser={openUser ? id => { setAuditEntry(null); if (id !== user.systemId) openUser(id); } : undefined}
      />
    </Screen>
  );
}

const styles = StyleSheet.create({
  grid: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: space.lg },
  // Two columns when both fit, one column on a phone (the basis shrinks to the window).
  col:  { flexGrow: 1, flexShrink: 1, flexBasis: 420, minWidth: 0 },

  value:      { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  muted:      { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  inline:     { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', columnGap: space.sm, rowGap: 2 },
  actions:    { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  divider:    { height: 1, backgroundColor: colors.border },
  subheading: { fontSize: fontSize.md, fontWeight: '700', color: colors.text },

  row:      { gap: 2, minWidth: 0, paddingVertical: space.sm, borderTopWidth: 1, borderTopColor: colors.border },
  rowHead:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  rowTitle: { flexShrink: 1, minWidth: 0, fontSize: fontSize.md, fontWeight: '600', color: colors.text },
});
