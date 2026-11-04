import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, Text, TouchableOpacity, View, useWindowDimensions } from 'react-native';
import { apiErrorMessage } from '@/hooks/use-appointments';
import { useCallStatus } from '@/hooks/use-call-status';
import { useCurrentUser } from '@/hooks/use-current-user';
import { ROLE_HOME, type Role } from '@/lib/auth/auth.types';
import {
  confirmCallTime, endCall, getCallToken, leaveCall, requestToJoin, startCall,
} from '@/lib/calls/api';
import { formatClinicDateTime, formatClinicTime, formatClinicWallClock } from '@/lib/calls/format';
import type { CallStatusResponse, CallTokenResponse } from '@/lib/calls/types';
import { confirmAction } from '@/lib/ui/dialogs';
import { CallAdmissionPanel } from './call-admission-panel';
import { CallDeviceCheck, type DeviceChoices } from './call-device-check.web';
import { CallRoom } from './call-room.web';
import { C, CallButton, Card, CenteredMessage, Notice } from './call-ui';

// Web call screen: lobby (status, device check, waiting area, doctor admission) and the live room.

type Mode = 'lobby' | 'joining' | 'in-call';

const NO_DEVICES: DeviceChoices = { micEnabled: false, cameraEnabled: false, audioDeviceId: undefined, videoDeviceId: undefined };

function windowState(s: CallStatusResponse): 'open' | 'before' | 'after' {
  if (s.joinWindowOpen) return 'open';
  const now   = new Date(s.serverTime).getTime();
  const opens = s.joinWindowOpensAt ? new Date(s.joinWindowOpensAt).getTime() : NaN;
  if (!Number.isNaN(opens) && !Number.isNaN(now) && now < opens) return 'before';
  return 'after';
}

export function CallScreen({ appointmentId }: { appointmentId: number }): React.JSX.Element {
  const router = useRouter();
  const { user } = useCurrentUser();
  const { width } = useWindowDimensions();
  const wide = width >= 900;

  const [mode, setMode]               = useState<Mode>('lobby');
  const [token, setToken]             = useState<CallTokenResponse | null>(null);
  const [choices, setChoices]         = useState<DeviceChoices>(NO_DEVICES);
  const [busy, setBusy]               = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [note, setNote]               = useState<string | null>(null);
  const [actingForPatient, setActing] = useState(false);
  const [leaving, setLeaving]         = useState(false);
  const [ending, setEnding]           = useState(false);
  const joinRequested = useRef(false);

  // Poll interval depends on what the user is waiting for (CONTRACT.md 11.3).
  const [interval, setIntervalMs] = useState<number | null>(15_000);
  const { status, loading, error, errorCode, offline, refetch, setStatus } = useCallStatus(appointmentId, interval);

  // A 403 after the call had loaded means our permission was withdrawn (e.g. the patient revoked
  // a caregiver's call access, or a doctor was unassigned). The last good status is stale then.
  const accessLost  = !!status && errorCode === 403;

  const isDoctor    = status?.myRole === 'DOCTOR';
  const isAdmin     = status?.myRole === 'ADMIN';
  const isCaregiver = status?.myRole === 'CAREGIVER';

  useEffect(() => {
    let next: number | null = 15_000;
    if (accessLost) next = 15_000;
    else if (mode === 'in-call') next = isDoctor ? 3_000 : 5_000;
    else if (mode === 'joining') next = null;
    else if (status) {
      const w = windowState(status);
      switch (status.callStatus) {
        case 'ENDED':
        case 'CANCELLED':
          next = null; break;
        case 'NEEDS_TIME_CONFIRMATION':
        case 'FAILED':
          next = 15_000; break;
        case 'PREPARING':
          next = w === 'open' ? 3_000 : 15_000; break;
        default:
          if (w !== 'open') next = 15_000;
          else if (isDoctor || status.myParticipant?.status === 'REQUESTED') next = 3_000;
          else next = 5_000;
      }
    }
    setIntervalMs(next);
  }, [mode, isDoctor, accessLost, status?.callStatus, status?.joinWindowOpen, status?.myParticipant?.status, status?.serverTime]);

  const goHome = useCallback(() => {
    const role = user?.role as Role | undefined;
    router.replace((role && ROLE_HOME[role] ? ROLE_HOME[role] : '/') as any);
  }, [router, user?.role]);

  const connectWithToken = useCallback(async () => {
    setMode('joining');
    setActionError(null);
    setNote(null);
    try {
      const t = await getCallToken(appointmentId);
      setToken(t);
      setMode('in-call');
    } catch (e) {
      setMode('lobby');
      setActionError(apiErrorMessage(e, 'Could not join the call. Please try again.'));
      refetch();
    }
  }, [appointmentId, refetch]);

  // Patient/caregiver: once admitted after asking, join automatically.
  useEffect(() => {
    if (!status || mode !== 'lobby' || !joinRequested.current) return;
    if (status.callStatus === 'LIVE' && status.myParticipant?.status === 'ADMITTED') {
      joinRequested.current = false;
      connectWithToken();
    } else if (status.myParticipant && status.myParticipant.status !== 'REQUESTED') {
      joinRequested.current = false;
    }
  }, [status, mode, connectWithToken]);

  // While in the call: leave the room when the call ends or our access changes.
  useEffect(() => {
    if (mode !== 'in-call' || !status) return;
    const ended = status.callStatus === 'ENDED' || status.callStatus === 'CANCELLED';
    if (ended || accessLost || status.myParticipant?.status !== 'ADMITTED') {
      setToken(null);
      setMode('lobby');
    }
  }, [mode, status, accessLost]);

  // Access withdrawn: forget any pending join request (the page keeps polling and recovers if it returns).
  useEffect(() => {
    if (accessLost) joinRequested.current = false;
  }, [accessLost]);

  const onConnectionLost = useCallback((message: string) => {
    setToken(null);
    setMode('lobby');
    setNote(message);
    refetch();
  }, [refetch]);

  const onServerClosed = useCallback(() => {
    setToken(null);
    setMode('lobby');
    setNote('You were disconnected from the call.');
    refetch();
  }, [refetch]);

  async function run(key: string, fn: () => Promise<CallStatusResponse>, fallback: string): Promise<CallStatusResponse | null> {
    if (busy) return null;
    setBusy(key);
    setActionError(null);
    try {
      const s = await fn();
      setStatus(s);
      return s;
    } catch (e) {
      setActionError(apiErrorMessage(e, fallback));
      refetch();
      return null;
    } finally {
      setBusy(null);
    }
  }

  async function doctorStart() {
    const s = await run('start', () => startCall(appointmentId), 'Could not start the call. Please try again.');
    if (s && s.callStatus === 'LIVE') await connectWithToken();
  }

  async function askToJoin() {
    const s = await run('join', () => requestToJoin(appointmentId, isCaregiver ? actingForPatient : false), 'Could not ask to join. Please try again.');
    if (s) {
      joinRequested.current = true;
      setNote(null);
    }
  }

  async function cancelRequest() {
    joinRequested.current = false;
    await run('cancel', () => leaveCall(appointmentId), 'Could not cancel the request.');
  }

  async function confirmTime() {
    if (!status?.scheduledAtLocal) return;
    const local = status.scheduledAtLocal;
    await run('confirm', () => confirmCallTime(appointmentId, local), 'Could not confirm the time. Please try again.');
  }

  async function leave() {
    if (leaving) return;
    setLeaving(true);
    try { await leaveCall(appointmentId); } catch { /* leaving locally still disconnects */ }
    setToken(null);
    goHome();
  }

  async function end() {
    if (ending) return;
    const ok = await confirmAction('End call for everyone?', 'Everyone will be disconnected. Your consultation notes are not changed.', 'End call', true);
    if (!ok) return;
    setEnding(true);
    try {
      setStatus(await endCall(appointmentId));
    } catch (e) {
      setActionError(apiErrorMessage(e, 'Could not end the call. Please try again.'));
      refetch();
    } finally {
      setEnding(false);
    }
  }

  // ── Render ────────────────────────────────────────────────────────────────

  if (loading && !status) {
    return (
      <View style={styles.center}>
        <ActivityIndicator size="large" color={C.indigo} accessibilityLabel="Loading call" />
        <Text style={styles.muted}>Loading call...</Text>
      </View>
    );
  }

  if (!status) {
    const final = errorCode === 403 || errorCode === 404;
    return (
      <View style={styles.page}>
        <CenteredMessage
          icon={final ? 'lock-closed-outline' : 'cloud-offline-outline'}
          title={final ? 'Call not available' : 'Could not load the call'}
          body={offline ? "You're offline. Reconnect to load the call." : error ?? undefined}
        >
          <View style={styles.rowWrap}>
            {!final && <CallButton label="Try again" icon="refresh-outline" onPress={() => { refetch(); }} />}
            <CallButton label="Back to dashboard" variant="secondary" onPress={goHome} />
          </View>
        </CenteredMessage>
      </View>
    );
  }

  if (accessLost && mode !== 'in-call') {
    return (
      <View style={styles.page}>
        <CenteredMessage
          icon="lock-closed-outline"
          title="Your access to this call was removed."
          body="You can no longer join this call. If you think this is a mistake, ask the patient to check your permissions."
        >
          <CallButton label="Back to dashboard" onPress={goHome} />
        </CenteredMessage>
      </View>
    );
  }

  if (mode === 'in-call' && token) {
    return (
      <CallRoom
        appointmentId={appointmentId}
        token={token}
        choices={choices}
        status={status}
        isDoctor={isDoctor}
        onStatus={setStatus}
        onConnectionLost={onConnectionLost}
        onServerClosed={onServerClosed}
        onLeave={leave}
        onEnd={isDoctor ? end : undefined}
        leaving={leaving}
        ending={ending}
      />
    );
  }

  if (status.callStatus === 'ENDED' || status.callStatus === 'CANCELLED') {
    const cancelled = status.callStatus === 'CANCELLED';
    return (
      <View style={styles.page}>
        <CenteredMessage
          icon={cancelled ? 'calendar-clear-outline' : 'checkmark-done-outline'}
          title={cancelled ? 'This call is closed' : 'The call has ended'}
          body={cancelled
            ? 'This appointment was cancelled or changed, so its video call is closed.'
            : 'Thank you. You can close this page or go back to your dashboard.'}
        >
          <CallButton label="Back to dashboard" onPress={goHome} />
        </CenteredMessage>
      </View>
    );
  }

  const w = windowState(status);
  const showDevices = !isAdmin && status.callStatus !== 'NEEDS_TIME_CONFIRMATION';
  const autoStartDevices = w === 'open';

  return (
    <View style={styles.page}>
      <ScrollView contentContainerStyle={styles.scroll}>
        <View style={styles.topBar}>
          <TouchableOpacity
            onPress={goHome}
            accessibilityRole="button"
            accessibilityLabel="Back to dashboard"
            style={styles.backBtn}
          >
            <Ionicons name="arrow-back" size={18} color={C.indigo} />
            <Text style={styles.backText}>Back to dashboard</Text>
          </TouchableOpacity>
        </View>

        {offline && (
          <Notice tone="warning" title="You're offline">
            The call status will update when your connection returns.
          </Notice>
        )}

        <View style={[styles.columns, wide && styles.columnsWide]}>
          <View style={[styles.col, wide && styles.colMain]}>
            <Card>
              <Text style={styles.h1} accessibilityRole="header">Video consultation</Text>
              <InfoRow icon="medkit-outline" label="Doctor" value={status.doctorName ?? '—'} />
              <InfoRow icon="person-outline" label="Patient" value={status.patientName ?? '—'} />
              <InfoRow
                icon="calendar-outline"
                label="Time"
                value={status.scheduledStartUtc
                  ? formatClinicDateTime(status.scheduledStartUtc)
                  : status.scheduledAtLocal ? `${formatClinicWallClock(status.scheduledAtLocal)} (to be confirmed)` : '—'}
              />
              {status.joinWindowOpensAt && status.joinWindowClosesAt && (
                <InfoRow
                  icon="time-outline"
                  label="Call open"
                  value={`${formatClinicTime(status.joinWindowOpensAt)} to ${formatClinicTime(status.joinWindowClosesAt)}`}
                />
              )}
            </Card>

            <Card>
              {note && <Notice tone="warning">{note}</Notice>}
              <PrimaryArea
                status={status}
                window={w}
                isDoctor={isDoctor}
                isAdmin={isAdmin}
                isCaregiver={isCaregiver}
                busy={busy}
                joining={mode === 'joining'}
                actingForPatient={actingForPatient}
                setActing={setActing}
                onStart={doctorStart}
                onAsk={askToJoin}
                onCancelRequest={cancelRequest}
                onJoin={connectWithToken}
                onConfirmTime={confirmTime}
                onReschedule={goHome}
              />
              {actionError && <Notice tone="error">{actionError}</Notice>}
            </Card>

            {isDoctor && status.callStatus === 'LIVE' && (
              <CallAdmissionPanel appointmentId={appointmentId} status={status} onStatus={setStatus} />
            )}
          </View>

          {showDevices && (
            <View style={[styles.col, wide && styles.colSide]}>
              <Card>
                <CallDeviceCheck autoStart={autoStartDevices} released={mode !== 'lobby'} onChange={setChoices} />
              </Card>
            </View>
          )}
        </View>
      </ScrollView>
    </View>
  );
}

function PrimaryArea(props: {
  status:           CallStatusResponse;
  window:           'open' | 'before' | 'after';
  isDoctor:         boolean;
  isAdmin:          boolean;
  isCaregiver:      boolean;
  busy:             string | null;
  joining:          boolean;
  actingForPatient: boolean;
  setActing:        (v: boolean) => void;
  onStart:          () => void;
  onAsk:            () => void;
  onCancelRequest:  () => void;
  onJoin:           () => void;
  onConfirmTime:    () => void;
  onReschedule:     () => void;
}) {
  const s = props.status;
  const opensText = `This call opens at ${formatClinicTime(s.joinWindowOpensAt)}.`;
  const windowNotice = props.window === 'before'
    ? <Notice title={opensText}>You can check your camera and microphone now. This page updates on its own.</Notice>
    : <Notice tone="warning" title="This call has closed">The time for this video call has passed.</Notice>;

  if (props.joining) {
    return <Notice title="Joining the call...">Connecting your camera and microphone.</Notice>;
  }

  if (props.isAdmin) {
    return <Notice title={`Call status: ${s.callStatus.replace(/_/g, ' ').toLowerCase()}`}>Admins can see the call status but cannot join calls.</Notice>;
  }

  if (s.callStatus === 'NEEDS_TIME_CONFIRMATION') {
    if (!props.isDoctor) {
      return <Notice title="Waiting for the doctor to confirm the appointment time." />;
    }
    return (
      <View style={styles.stack}>
        <Text style={styles.h2} accessibilityRole="header">Confirm appointment time</Text>
        <Text style={styles.body}>
          This appointment was booked before video calls were set up here. Confirm the time so the call can open.
        </Text>
        <Text style={styles.strong}>{formatClinicWallClock(s.scheduledAtLocal) || 'Time unavailable'}</Text>
        <View style={styles.rowWrap}>
          {s.canConfirmTime && s.scheduledAtLocal && (
            <CallButton label="Confirm this time" icon="checkmark-outline" onPress={props.onConfirmTime} busy={props.busy === 'confirm'} />
          )}
          <CallButton label="Reschedule instead" variant="secondary" onPress={props.onReschedule} accessibilityLabel="Reschedule instead from your schedule" />
        </View>
        <Text style={styles.muted}>To pick a different time, reschedule the appointment from your schedule.</Text>
      </View>
    );
  }

  if (s.callStatus === 'FAILED') {
    return (
      <View style={styles.stack}>
        <Notice tone="error" title="The call room could not be prepared.">
          {props.isDoctor ? 'Try again in a moment.' : 'The doctor can try again. This page updates on its own.'}
        </Notice>
        {props.isDoctor && props.window === 'open' && (
          <CallButton label="Retry" icon="refresh-outline" onPress={props.onStart} busy={props.busy === 'start'} />
        )}
      </View>
    );
  }

  if (s.callStatus === 'PREPARING') {
    if (props.window !== 'open') return windowNotice;
    return (
      <View style={[styles.rowWrap, { alignItems: 'center' }]} accessibilityLiveRegion="polite">
        <ActivityIndicator color={C.indigo} accessibilityLabel="Preparing the call room" />
        <Text style={styles.body}>Preparing the call room...</Text>
      </View>
    );
  }

  if (props.window !== 'open') return windowNotice;

  if (props.isDoctor) {
    if (!s.canStart) {
      return <Notice tone="warning" title="The call cannot be started right now.">Check the appointment status in your schedule.</Notice>;
    }
    const live = s.callStatus === 'LIVE';
    return (
      <View style={styles.stack}>
        <Text style={styles.body}>
          {live ? 'The call is in progress. Rejoin to continue.' : 'When you start the call, the patient and approved caregivers can ask to join. You admit each person.'}
        </Text>
        <CallButton
          label={live ? 'Rejoin call' : 'Start call'}
          icon="videocam-outline"
          onPress={props.onStart}
          busy={props.busy === 'start'}
        />
      </View>
    );
  }

  // Patient or caregiver.
  const me = s.myParticipant;
  const patient = s.patientName ?? 'the patient';

  if (s.callStatus === 'READY') {
    return <Notice title="Waiting for the doctor to start the call.">This page updates on its own. You can check your camera and microphone meanwhile.</Notice>;
  }

  if (me?.status === 'REQUESTED') {
    return (
      <View style={styles.stack}>
        <View style={[styles.rowWrap, { alignItems: 'center' }]} accessibilityLiveRegion="polite">
          <ActivityIndicator color={C.indigo} accessibilityLabel="Waiting for the doctor" />
          <Text style={styles.body}>Waiting for the doctor to let you in...</Text>
        </View>
        {me.label ? <Text style={styles.muted}>You will appear as: {me.label}</Text> : null}
        <CallButton label="Cancel request" variant="secondary" onPress={props.onCancelRequest} busy={props.busy === 'cancel'} />
      </View>
    );
  }

  if (me?.status === 'ADMITTED') {
    return (
      <View style={styles.stack}>
        <Notice tone="success" title="The doctor has admitted you." />
        <CallButton label="Join call" icon="videocam-outline" onPress={props.onJoin} />
      </View>
    );
  }

  const outcome =
    me?.status === 'DENIED' ? <Notice tone="warning">The doctor did not admit you to this call.</Notice>
    : me?.status === 'REMOVED' ? <Notice tone="warning">Your access to this call was removed.</Notice>
    : me?.status === 'LEFT' ? <Notice>You left the call. Leaving does not end the appointment.</Notice>
    : null;

  if (!s.canRequestJoin) {
    return (
      <View style={styles.stack}>
        {outcome}
        {!outcome && <Notice title="You cannot join this call right now." />}
      </View>
    );
  }

  return (
    <View style={styles.stack}>
      {outcome}
      <Text style={styles.body}>The doctor has started the call. Ask to join and the doctor will let you in.</Text>
      {props.isCaregiver && (
        <View style={styles.stack} accessibilityRole="radiogroup" accessibilityLabel="How you are joining">
          <Choice
            selected={!props.actingForPatient}
            onPress={() => props.setActing(false)}
            title={`Attending with ${patient}`}
            sub={`${patient} is with you or joining too.`}
          />
          <Choice
            selected={props.actingForPatient}
            onPress={() => props.setActing(true)}
            title={`Speaking for ${patient}`}
            sub={`You are joining on behalf of ${patient}.`}
          />
        </View>
      )}
      <CallButton label="Ask to join" icon="hand-left-outline" onPress={props.onAsk} busy={props.busy === 'join'} />
    </View>
  );
}

function Choice(props: { selected: boolean; onPress: () => void; title: string; sub: string }) {
  return (
    <TouchableOpacity
      onPress={props.onPress}
      accessibilityRole="radio"
      accessibilityState={{ checked: props.selected }}
      aria-checked={props.selected}
      accessibilityLabel={props.title}
      style={[styles.choice, props.selected && styles.choiceOn]}
    >
      <Ionicons name={props.selected ? 'radio-button-on' : 'radio-button-off'} size={18} color={props.selected ? C.indigo : C.faint} />
      <View style={{ flex: 1 }}>
        <Text style={styles.strong}>{props.title}</Text>
        <Text style={styles.muted}>{props.sub}</Text>
      </View>
    </TouchableOpacity>
  );
}

function InfoRow(props: { icon: React.ComponentProps<typeof Ionicons>['name']; label: string; value: string }) {
  return (
    <View style={styles.infoRow}>
      <Ionicons name={props.icon} size={16} color={C.faint} />
      <Text style={styles.infoLabel}>{props.label}</Text>
      <Text style={styles.infoValue}>{props.value}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  page:        { flex: 1, backgroundColor: C.page },
  center:      { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 10, backgroundColor: C.page },
  scroll:      { padding: 16, gap: 12, width: '100%', maxWidth: 1100, alignSelf: 'center' },
  topBar:      { flexDirection: 'row', alignItems: 'center' },
  backBtn:     { flexDirection: 'row', alignItems: 'center', gap: 6, paddingVertical: 8, minHeight: 44 },
  backText:    { color: C.indigo, fontSize: 14, fontWeight: '600' },
  columns:     { gap: 12 },
  columnsWide: { flexDirection: 'row', alignItems: 'flex-start' },
  col:         { gap: 12 },
  colMain:     { flex: 3 },
  colSide:     { flex: 2 },
  h1:          { fontSize: 18, fontWeight: '700', color: C.text },
  h2:          { fontSize: 15, fontWeight: '700', color: C.text },
  body:        { fontSize: 14, color: C.muted, lineHeight: 20, flexShrink: 1 },
  strong:      { fontSize: 14, fontWeight: '600', color: C.text },
  muted:       { fontSize: 13, color: C.faint },
  stack:       { gap: 10 },
  rowWrap:     { flexDirection: 'row', flexWrap: 'wrap', gap: 10 },
  infoRow:     { flexDirection: 'row', alignItems: 'center', gap: 8, flexWrap: 'wrap' },
  infoLabel:   { fontSize: 13, color: C.faint, width: 72 },
  infoValue:   { fontSize: 14, color: C.text, fontWeight: '500', flexShrink: 1 },
  choice:      { flexDirection: 'row', alignItems: 'center', gap: 10, padding: 12, borderRadius: 10, borderWidth: 1, borderColor: C.border, backgroundColor: C.surface, minHeight: 44 },
  choiceOn:    { borderColor: C.indigo, backgroundColor: C.indigoBg },
});
