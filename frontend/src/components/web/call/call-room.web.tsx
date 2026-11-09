import { Ionicons } from '@expo/vector-icons';
import {
  RoomAudioRenderer,
  RoomContext,
  VideoTrack,
  useConnectionQualityIndicator,
  useIsMuted,
  useIsSpeaking,
  useTracks,
  type TrackReferenceOrPlaceholder,
} from '@livekit/components-react';
import {
  ConnectionQuality,
  DisconnectReason,
  Room,
  RoomEvent,
  Track,
  VideoPresets,
} from 'livekit-client';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { StyleSheet, Text, TouchableOpacity, View, useWindowDimensions } from 'react-native';
import { apiErrorMessage } from '@/hooks/use-appointments';
import { getCallToken } from '@/lib/calls/api';
import type { CallStatusResponse, CallTokenResponse } from '@/lib/calls/types';
import { CallAdmissionPanel } from './call-admission-panel';
import { DeviceSelect, type DeviceChoices } from './call-device-check.web';
import { ARM_DELAY_MS, C, useArmed } from './call-ui';

// The live call: LiveKit room, participant tiles, controls, reconnect and token refresh.
// No chat, no screen share, no recording (the token denies data publishing and screen share).

const REFRESH_MARGIN_MS = 60_000;     // refresh the stored token a minute before it expires
const MIN_REFRESH_MS    = 20_000;

export function CallRoom(props: {
  appointmentId: number;
  token:         CallTokenResponse;
  choices:       DeviceChoices;
  status:        CallStatusResponse;
  isDoctor:      boolean;
  onStatus:      (s: CallStatusResponse) => void;
  /** Connection could not be (re)established; `message` is shown to the user. */
  onConnectionLost: (message: string) => void;
  /** The server closed the connection (removed / room deleted); the parent re-checks status. */
  onServerClosed:   () => void;
  onLeave:       () => void;
  /** Doctor only. Must confirm before ending (CallScreen.end uses confirmAction). */
  onEnd?:        () => void;
  leaving:       boolean;
  ending:        boolean;
  /** The user's in-call camera/microphone toggles, remembered for the next lobby visit. */
  onDevicePrefs?: (prefs: { micOn: boolean; camOn: boolean }) => void;
}) {
  const { appointmentId, token, choices, status, isDoctor, onConnectionLost, onServerClosed } = props;
  const [room] = useState(() => new Room({
    adaptiveStream: true,
    dynacast:       true,
    audioCaptureDefaults: choices.audioDeviceId ? { deviceId: choices.audioDeviceId } : undefined,
    videoCaptureDefaults: {
      deviceId:   choices.videoDeviceId,
      resolution: VideoPresets.h540.resolution,
    },
  }));
  const [phase, setPhase]               = useState<'connecting' | 'connected' | 'reconnecting'>('connecting');
  const [micOn, setMicOn]               = useState(false);
  const [camOn, setCamOn]               = useState(false);
  const [deviceError, setDeviceError]   = useState<string | null>(null);
  const [audioBlocked, setAudioBlocked] = useState(false);
  const [showPeople, setShowPeople]     = useState(isDoctor);
  const [showDevices, setShowDevices]   = useState(false);
  const [mics, setMics]                 = useState<MediaDeviceInfo[]>([]);
  const [cams, setCams]                 = useState<MediaDeviceInfo[]>([]);
  const [activeMic, setActiveMic]       = useState<string | undefined>(choices.audioDeviceId);
  const [activeCam, setActiveCam]       = useState<string | undefined>(choices.videoDeviceId);

  const latestToken  = useRef<CallTokenResponse>(token);
  const intentional  = useRef(false);
  const retried      = useRef(false);
  const unmounted    = useRef(false);
  // What the user wants on: lobby choices first, then their in-call toggles (a manual reconnect republishes these).
  const wantMic      = useRef(choices.micEnabled);
  const wantCam      = useRef(choices.cameraEnabled);
  const { width }    = useWindowDimensions();
  const wide         = width >= 960;
  // End call ignores presses for a moment after the room appears (see useArmed).
  const endArmed     = useArmed(ARM_DELAY_MS);
  const { onDevicePrefs } = props;
  const reportPrefs  = useCallback(() => {
    onDevicePrefs?.({ micOn: wantMic.current, camOn: wantCam.current });
  }, [onDevicePrefs]);

  const publishInitialTracks = useCallback(async () => {
    const lp = room.localParticipant;
    if (wantMic.current) {
      try { await lp.setMicrophoneEnabled(true); } catch { setDeviceError('Your microphone could not be started. Others cannot hear you.'); }
    }
    if (wantCam.current) {
      try { await lp.setCameraEnabled(true); } catch { setDeviceError('Your camera could not be started. You are in the call with audio only.'); }
    }
    setMicOn(lp.isMicrophoneEnabled);
    setCamOn(lp.isCameraEnabled);
  }, [room]);

  const connect = useCallback(async (t: CallTokenResponse) => {
    await room.connect(t.serverUrl, t.token, { autoSubscribe: true });
    if (unmounted.current) { room.disconnect(); return; }
    setPhase('connected');
    setAudioBlocked(!room.canPlaybackAudio);
    await publishInitialTracks();
  }, [room, publishInitialTracks]);

  // Connect once; tear down on unmount.
  useEffect(() => {
    unmounted.current = false;
    connect(latestToken.current).catch(() => {
      if (!unmounted.current) onConnectionLost('Could not connect to the call. Check your connection and try again.');
    });
    return () => {
      unmounted.current = true;
      intentional.current = true;
      room.disconnect();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Room events: reconnect banner, unexpected disconnects, audio playback.
  useEffect(() => {
    const onReconnecting = () => setPhase('reconnecting');
    const onReconnected  = () => setPhase('connected');
    const onAudio        = () => setAudioBlocked(!room.canPlaybackAudio);
    const onMediaError   = () => setDeviceError('A camera or microphone stopped working. Check your devices.');
    const onDisconnected = async (reason?: DisconnectReason) => {
      if (intentional.current || unmounted.current) return;
      if (reason === DisconnectReason.PARTICIPANT_REMOVED
        || reason === DisconnectReason.ROOM_DELETED
        || reason === DisconnectReason.DUPLICATE_IDENTITY) {
        onServerClosed();
        return;
      }
      if (retried.current) {
        onConnectionLost('The connection to the call was lost.');
        return;
      }
      retried.current = true;
      setPhase('reconnecting');
      try {
        // A fresh token also re-checks that we are still admitted.
        const fresh = await getCallToken(appointmentId);
        latestToken.current = fresh;
        if (unmounted.current) return;
        await connect(fresh);
        retried.current = false;
      } catch (e: any) {
        if (unmounted.current) return;
        if (e?.response) onServerClosed();
        else onConnectionLost('The connection to the call was lost.');
      }
    };
    room
      .on(RoomEvent.Reconnecting, onReconnecting)
      .on(RoomEvent.SignalReconnecting, onReconnecting)
      .on(RoomEvent.Reconnected, onReconnected)
      .on(RoomEvent.Disconnected, onDisconnected)
      .on(RoomEvent.AudioPlaybackStatusChanged, onAudio)
      .on(RoomEvent.MediaDevicesError, onMediaError);
    return () => {
      room
        .off(RoomEvent.Reconnecting, onReconnecting)
        .off(RoomEvent.SignalReconnecting, onReconnecting)
        .off(RoomEvent.Reconnected, onReconnected)
        .off(RoomEvent.Disconnected, onDisconnected)
        .off(RoomEvent.AudioPlaybackStatusChanged, onAudio)
        .off(RoomEvent.MediaDevicesError, onMediaError);
    };
  }, [room, appointmentId, connect, onConnectionLost, onServerClosed]);

  // Keep a valid token ready (5-minute lifetime) for reconnects; a refused refresh means access changed.
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | null = null;
    let cancelled = false;
    const schedule = () => {
      const expires = new Date(latestToken.current.expiresAt).getTime();
      const wait = Number.isNaN(expires) ? 4 * 60_000 : Math.max(MIN_REFRESH_MS, expires - Date.now() - REFRESH_MARGIN_MS);
      timer = setTimeout(async () => {
        if (cancelled) return;
        try {
          latestToken.current = await getCallToken(appointmentId);
        } catch (e: any) {
          const code = e?.response?.status;
          if (code === 403 || code === 409) { if (!cancelled) onServerClosed(); return; }
        }
        if (!cancelled) schedule();
      }, wait);
    };
    schedule();
    return () => { cancelled = true; if (timer) clearTimeout(timer); };
  }, [appointmentId, onServerClosed]);

  const refreshDevices = useCallback(async () => {
    try {
      const list = await navigator.mediaDevices.enumerateDevices();
      setMics(list.filter(d => d.kind === 'audioinput' && d.deviceId));
      setCams(list.filter(d => d.kind === 'videoinput' && d.deviceId));
    } catch { /* selectors stay empty */ }
  }, []);

  useEffect(() => {
    if (showDevices) refreshDevices();
  }, [showDevices, refreshDevices]);

  async function toggleMic() {
    const next = !micOn;
    wantMic.current = next;
    try {
      await room.localParticipant.setMicrophoneEnabled(next);
      setDeviceError(null);
    } catch {
      setDeviceError('Your microphone could not be started. Allow microphone access in your browser.');
    }
    setMicOn(room.localParticipant.isMicrophoneEnabled);
    reportPrefs();
  }

  async function toggleCam() {
    const next = !camOn;
    wantCam.current = next;
    try {
      await room.localParticipant.setCameraEnabled(next);
      setDeviceError(null);
    } catch {
      setDeviceError('Your camera could not be started. Allow camera access in your browser or continue with audio only.');
    }
    setCamOn(room.localParticipant.isCameraEnabled);
    reportPrefs();
  }

  async function switchDevice(kind: 'audioinput' | 'videoinput', id: string) {
    try {
      await room.switchActiveDevice(kind, id);
      if (kind === 'audioinput') setActiveMic(id); else setActiveCam(id);
    } catch {
      setDeviceError('Could not switch to that device.');
    }
  }

  const leave = () => { intentional.current = true; props.onLeave(); };

  const waitingCount = status.participants.filter(p => !p.me && p.status === 'REQUESTED').length;

  return (
    <RoomContext.Provider value={room}>
      <View style={styles.root}>
        <View style={styles.header}>
          <View style={{ flex: 1, minWidth: 0 }}>
            <Text style={styles.title} numberOfLines={1} accessibilityRole="header">
              {isDoctor ? `Call with ${status.patientName ?? 'patient'}` : `Call with ${status.doctorName ?? 'your doctor'}`}
            </Text>
            <Text style={styles.sub}>Leaving does not end the appointment.</Text>
          </View>
          {phase !== 'connected' && (
            <View style={styles.pill} accessibilityLiveRegion="polite">
              <Text style={styles.pillText}>{phase === 'connecting' ? 'Connecting...' : 'Reconnecting...'}</Text>
            </View>
          )}
        </View>

        {phase === 'reconnecting' && (
          <View style={styles.banner} accessibilityRole="alert">
            <Ionicons name="cloud-offline-outline" size={16} color="#FDE68A" />
            <Text style={styles.bannerText}>Reconnecting... Your connection dropped. Stay on this page.</Text>
          </View>
        )}
        {audioBlocked && (
          <TouchableOpacity
            style={styles.banner}
            onPress={() => room.startAudio().then(() => setAudioBlocked(!room.canPlaybackAudio)).catch(() => {})}
            accessibilityRole="button"
            accessibilityLabel="Turn on call audio"
          >
            <Ionicons name="volume-high-outline" size={16} color="#FDE68A" />
            <Text style={styles.bannerText}>Your browser paused the call audio. Select here to hear others.</Text>
          </TouchableOpacity>
        )}
        {deviceError && (
          <View style={styles.banner} accessibilityRole="alert">
            <Ionicons name="alert-circle-outline" size={16} color="#FDE68A" />
            <Text style={styles.bannerText}>{deviceError}</Text>
          </View>
        )}

        <View style={[styles.body, wide ? styles.bodyWide : null]}>
          <View style={styles.stage}>
            <ParticipantGrid width={wide && showPeople ? width - 380 : width} />
          </View>
          {isDoctor && showPeople && (
            <View style={[styles.side, wide ? styles.sideWide : null]}>
              <CallAdmissionPanel appointmentId={appointmentId} status={status} onStatus={props.onStatus} dark />
            </View>
          )}
        </View>

        {showDevices && (
          <View style={styles.deviceBar}>
            {mics.length > 0 && <DeviceSelect dark label="Microphone" devices={mics} value={activeMic} onChange={id => switchDevice('audioinput', id)} />}
            {cams.length > 0 && <DeviceSelect dark label="Camera" devices={cams} value={activeCam} onChange={id => switchDevice('videoinput', id)} />}
            {mics.length === 0 && cams.length === 0 && <Text style={styles.sub}>No devices found.</Text>}
          </View>
        )}

        <View style={styles.controls}>
          <ControlButton
            icon={micOn ? 'mic' : 'mic-off'}
            label={micOn ? 'Mute' : 'Unmute'}
            active={micOn}
            onPress={toggleMic}
            disabled={phase === 'connecting'}
          />
          <ControlButton
            icon={camOn ? 'videocam' : 'videocam-off'}
            label={camOn ? 'Stop camera' : 'Start camera'}
            active={camOn}
            onPress={toggleCam}
            disabled={phase === 'connecting'}
          />
          <ControlButton
            icon="settings-outline"
            label="Devices"
            active={showDevices}
            onPress={() => setShowDevices(v => !v)}
          />
          {isDoctor && (
            <ControlButton
              icon="people-outline"
              label={waitingCount > 0 ? `People (${waitingCount} waiting)` : 'People'}
              active={showPeople}
              highlight={waitingCount > 0}
              onPress={() => setShowPeople(v => !v)}
            />
          )}
          <ControlButton icon="exit-outline" label={props.leaving ? 'Leaving...' : 'Leave'} danger onPress={leave} disabled={props.leaving || props.ending} />
          {isDoctor && props.onEnd && (
            // Kept apart from Leave so a press meant for one cannot land on the other.
            <View style={styles.endGroup}>
              <ControlButton
                icon="call-outline"
                label={props.ending ? 'Ending...' : 'End call'}
                danger
                solid
                onPress={props.onEnd}
                disabled={props.ending || props.leaving || !endArmed}
              />
            </View>
          )}
        </View>
        <RoomAudioRenderer />
      </View>
    </RoomContext.Provider>
  );
}

function ParticipantGrid(props: { width: number }) {
  const tracks = useTracks([{ source: Track.Source.Camera, withPlaceholder: true }], { onlySubscribed: false });
  const n = tracks.length;
  const cols = props.width < 640 ? (n > 2 ? 2 : 1) : n <= 1 ? 1 : n <= 4 ? 2 : 3;
  const basis = `${100 / cols}%` as const;
  if (n === 0) {
    return <View style={styles.emptyStage}><Text style={styles.sub}>Waiting for others to connect...</Text></View>;
  }
  return (
    <View style={styles.grid}>
      {tracks.map(t => (
        <View key={`${t.participant.identity}-${t.source}`} style={[styles.cell, { width: basis }]}>
          <ParticipantTile trackRef={t} />
        </View>
      ))}
    </View>
  );
}

function ParticipantTile({ trackRef }: { trackRef: TrackReferenceOrPlaceholder }) {
  const p = trackRef.participant;
  const camMuted = useIsMuted(trackRef);
  const micMuted = useIsMuted({ participant: p, source: Track.Source.Microphone });
  const speaking = useIsSpeaking(p);
  const { quality } = useConnectionQualityIndicator({ participant: p });
  const label = p.name || 'Participant';
  const shownLabel = p.isLocal ? `${label} (You)` : label;
  const hasVideo = !!trackRef.publication && !camMuted;

  const qualityText =
    quality === ConnectionQuality.Excellent ? 'Excellent connection'
    : quality === ConnectionQuality.Good ? 'Good connection'
    : quality === ConnectionQuality.Poor ? 'Poor connection'
    : quality === ConnectionQuality.Lost ? 'Connection lost'
    : 'Connection unknown';
  const qualityColor =
    quality === ConnectionQuality.Excellent || quality === ConnectionQuality.Good ? '#22C55E'
    : quality === ConnectionQuality.Poor ? '#B45309'
    : quality === ConnectionQuality.Lost ? '#B91C1C' : '#94A3B8';

  return (
    <View
      style={[styles.tile, speaking && styles.tileSpeaking]}
      accessibilityLabel={`${shownLabel}${micMuted ? ', muted' : ''}${hasVideo ? '' : ', camera off'}, ${qualityText}`}
    >
      {hasVideo && trackRef.publication ? (
        <VideoTrack
          trackRef={trackRef as any}
          style={{ width: '100%', height: '100%', objectFit: 'cover', transform: p.isLocal ? 'scaleX(-1)' : undefined }}
        />
      ) : (
        <View style={styles.avatarWrap}>
          <View style={styles.avatar}>
            <Text style={styles.avatarText}>{initials(label)}</Text>
          </View>
        </View>
      )}
      <View style={styles.labelBar}>
        <Ionicons name={micMuted ? 'mic-off' : 'mic'} size={13} color={micMuted ? '#FCA5A5' : '#F8FAFC'} />
        <Text style={styles.labelText} numberOfLines={2}>{shownLabel}</Text>
        <View style={[styles.qualityDot, { backgroundColor: qualityColor }]} />
      </View>
    </View>
  );
}

function initials(name: string): string {
  return name.replace(/\(.*\)/, '').trim().split(/\s+/).map(w => w[0] ?? '').join('').slice(0, 2).toUpperCase() || '?';
}

function ControlButton(props: {
  icon: React.ComponentProps<typeof Ionicons>['name'];
  label: string;
  onPress: () => void;
  active?: boolean;
  danger?: boolean;
  solid?: boolean;
  highlight?: boolean;
  disabled?: boolean;
}) {
  const neutralBg = props.solid ? '#DC2626' : props.danger ? '#7F1D1D' : props.active ? '#334155' : '#475569';
  return (
    <TouchableOpacity
      onPress={props.onPress}
      disabled={props.disabled}
      accessibilityRole="button"
      accessibilityLabel={props.label}
      accessibilityState={{ disabled: !!props.disabled, selected: !!props.active }}
      style={[
        styles.ctrl,
        { backgroundColor: neutralBg },
        props.highlight && { borderColor: '#B45309', borderWidth: 2 },
        props.disabled && { opacity: 0.5 },
      ]}
    >
      <Ionicons name={props.icon} size={18} color="#F8FAFC" />
      <Text style={styles.ctrlText}>{props.label}</Text>
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  root:        { flex: 1, backgroundColor: C.dark, minHeight: '100%' as any },
  header:      { flexDirection: 'row', alignItems: 'center', gap: 12, paddingHorizontal: 16, paddingVertical: 12 },
  title:       { color: '#F8FAFC', fontSize: 16, fontWeight: '700' },
  sub:         { color: '#CBD5E1', fontSize: 12 },
  pill:        { backgroundColor: '#334155', borderRadius: 999, paddingHorizontal: 10, paddingVertical: 4 },
  pillText:    { color: '#F8FAFC', fontSize: 12, fontWeight: '600' },
  banner:      { flexDirection: 'row', alignItems: 'center', gap: 8, marginHorizontal: 16, marginBottom: 8, padding: 10, borderRadius: 10, backgroundColor: '#422006' },
  bannerText:  { color: '#FEF3C7', fontSize: 13, flex: 1 },
  body:        { flex: 1, gap: 12, paddingHorizontal: 16 },
  bodyWide:    { flexDirection: 'row' },
  stage:       { flex: 1, minHeight: 240 },
  emptyStage:  { flex: 1, alignItems: 'center', justifyContent: 'center' },
  grid:        { flexDirection: 'row', flexWrap: 'wrap', margin: -4 },
  cell:        { padding: 4 },
  tile:        { width: '100%', aspectRatio: 16 / 9, backgroundColor: C.darkTile, borderRadius: 12, overflow: 'hidden', borderWidth: 2, borderColor: 'transparent' },
  tileSpeaking:{ borderColor: '#22C55E' },
  avatarWrap:  { flex: 1, alignItems: 'center', justifyContent: 'center' },
  avatar:      { width: 64, height: 64, borderRadius: 32, backgroundColor: C.indigo, alignItems: 'center', justifyContent: 'center' },
  avatarText:  { color: '#FFFFFF', fontSize: 22, fontWeight: '700' },
  labelBar:    { position: 'absolute', left: 8, right: 8, bottom: 8, flexDirection: 'row', alignItems: 'center', gap: 6, backgroundColor: 'rgba(15,23,42,0.75)', borderRadius: 8, paddingHorizontal: 8, paddingVertical: 4 },
  labelText:   { color: '#F8FAFC', fontSize: 12, fontWeight: '600', flex: 1 },
  qualityDot:  { width: 8, height: 8, borderRadius: 4 },
  side:        { paddingBottom: 8 },
  sideWide:    { width: 360 },
  deviceBar:   { flexDirection: 'row', flexWrap: 'wrap', gap: 10, paddingHorizontal: 16, paddingTop: 8 },
  controls:    { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'center', gap: 8, padding: 16 },
  // Fixed minimum width: a label change (Mute/Unmute, People (1 waiting)) does not shift the other buttons.
  ctrl:        { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 6, borderRadius: 999, paddingHorizontal: 14, paddingVertical: 10, minHeight: 44, minWidth: 136 },
  endGroup:    { marginLeft: 24 },
  ctrlText:    { color: '#F8FAFC', fontSize: 13, fontWeight: '600' },
});
