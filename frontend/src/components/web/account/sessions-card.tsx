import { useState } from 'react';
import { View } from 'react-native';
import {
  Banner, type BannerTone, Button, colors, EmptyView, ErrorView, FormActions, Icon, type IoniconsName, LoadingView,
  Modal, SectionCard, StatusBadge,
} from '@/components/web/ui';
import { useSessions } from '@/hooks/use-account-sessions';
import { formatInstant, sessionDeviceLabel, sessionDeviceType, SIGN_OUT_DELAY_NOTE } from '@/lib/account/format';
import type { SessionResponse } from '@/lib/account/types';
import { accountStyles, ListRow, ListRowText, Para } from './account-ui';

interface Notice { tone: BannerTone; text: string }

function deviceIcon(session: SessionResponse): IoniconsName {
  const type = (session.deviceType ?? '').trim().toLowerCase();
  if (type === 'ios' || type === 'android') return 'phone-portrait-outline';
  if (type === 'web') return 'desktop-outline';
  return 'hardware-chip-outline';
}

/**
 * Active sign-in sessions of the account. The row of this device is marked from the stored session id (stable
 * across token refreshes) and has no sign-out control here: signing out of this device is the normal logout.
 */
export function SessionsCard() {
  const s = useSessions();
  const [confirmOthers, setConfirmOthers] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);

  const others = s.sessions.filter(x => !x.current);
  const canRevokeOthers = s.hasCurrent && others.length > 0;
  const busy = s.revoke.pending || s.revokeOthers.pending;

  const onRevoke = async (session: SessionResponse) => {
    setNotice(null);
    const result = await s.revoke.run(session.sessionId);
    if (result.ok) {
      setNotice({ tone: 'success', text: `${sessionDeviceLabel(session)} was signed out. ${SIGN_OUT_DELAY_NOTE}` });
    } else if (result.error) {
      setNotice({ tone: 'danger', text: result.error.message });
    }
  };

  const onRevokeOthers = async () => {
    setNotice(null);
    const result = await s.revokeOthers.run();
    if (result.ok) {
      setConfirmOthers(false);
      const count = result.data;
      setNotice({
        tone: 'success',
        text: count === 0
          ? 'No other device was signed in.'
          : `${count} other ${count === 1 ? 'device was' : 'devices were'} signed out. ${SIGN_OUT_DELAY_NOTE}`,
      });
    } else if (result.error) {
      setConfirmOthers(false);
      setNotice({
        tone: 'danger',
        // 404: the session of this device is no longer active, so nothing was signed out.
        text: result.error.status === 404
          ? 'The session on this device has ended, so nothing was signed out. You will be asked to sign in again shortly.'
          : result.error.message,
      });
    }
  };

  return (
    <SectionCard
      title="Active sessions"
      subtitle="Devices that are signed in to your account."
      action={canRevokeOthers ? (
        <Button
          label="Sign out everywhere else" variant="secondary" size="sm" icon="log-out-outline"
          onPress={() => { setNotice(null); setConfirmOthers(true); }} disabled={busy}
        />
      ) : undefined}
    >
      {notice ? <Banner tone={notice.tone} text={notice.text} onDismiss={() => setNotice(null)} /> : null}

      {s.loading ? (
        <LoadingView compact label="Loading your sessions" />
      ) : s.error && s.sessions.length === 0 ? (
        <ErrorView compact message={s.error} onRetry={s.refetch} />
      ) : s.sessions.length === 0 ? (
        <EmptyView
          compact icon="phone-portrait-outline" title="No active sessions"
          sub="The session on this device was ended from somewhere else. You will be asked to sign in again shortly."
        />
      ) : (
        <View style={accountStyles.stack}>
          {s.error ? <Banner tone="danger" text={s.error} action={{ label: 'Retry', onPress: s.refetch }} /> : null}
          <View style={accountStyles.list} accessibilityRole="list" aria-label="Active sessions">
            {s.sessions.map(session => {
              const label = sessionDeviceLabel(session);
              return (
                <View key={session.sessionId} role="listitem">
                  <ListRow>
                    <Icon name={deviceIcon(session)} size={22} color={colors.textMuted} />
                    <ListRowText
                      title={label}
                      lines={[
                        sessionDeviceType(session),
                        `Signed in ${formatInstant(session.createdAt)}`,
                        `Last active ${formatInstant(session.lastUsedAt)}`,
                      ]}
                    />
                    {session.current ? (
                      <StatusBadge label="This device" tone="success" icon="checkmark-circle-outline" />
                    ) : (
                      <Button
                        label="Sign out" variant="secondary" size="sm"
                        accessibilityLabel={`Sign out ${label}, last active ${formatInstant(session.lastUsedAt)}`}
                        onPress={() => onRevoke(session)}
                        loading={s.revokingId === session.sessionId}
                        disabled={busy && s.revokingId !== session.sessionId}
                      />
                    )}
                  </ListRow>
                </View>
              );
            })}
          </View>
          {!s.hasCurrent ? (
            <Para muted>
              This device could not be matched to a session in the list, so signing out everywhere else is not
              offered here. Sign out and sign in again to see it marked.
            </Para>
          ) : null}
          <Para muted>{`"Last active" is updated when a device renews its sign-in, so it can be up to 15 minutes behind. ${SIGN_OUT_DELAY_NOTE}`}</Para>
        </View>
      )}

      <Modal
        open={confirmOthers} title="Sign out everywhere else?" size="sm"
        onClose={() => setConfirmOthers(false)} busy={s.revokeOthers.pending}
        footer={(
          <FormActions
            submitLabel="Sign out other devices" destructive onSubmit={onRevokeOthers}
            submitting={s.revokeOthers.pending} onCancel={() => setConfirmOthers(false)}
          />
        )}
      >
        <View style={accountStyles.stack}>
          <Para>
            {`${others.length === 1 ? 'The other device' : `All ${others.length} other devices`} will be signed out of your account. You stay signed in on this device.`}
          </Para>
          <Para muted>{SIGN_OUT_DELAY_NOTE}</Para>
        </View>
      </Modal>
    </SectionCard>
  );
}
