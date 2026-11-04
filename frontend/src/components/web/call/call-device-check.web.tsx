import { Ionicons } from '@expo/vector-icons';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { C, CallButton, Notice } from './call-ui';

// Camera/microphone check shown before joining. Web only (uses navigator.mediaDevices).

export type DeviceState = 'idle' | 'pending' | 'ok' | 'denied' | 'missing' | 'in-use' | 'unsupported' | 'error';

export interface DeviceChoices {
  micEnabled:    boolean;
  cameraEnabled: boolean;
  audioDeviceId: string | undefined;
  videoDeviceId: string | undefined;
}

function classify(e: any): DeviceState {
  const name = e?.name ?? '';
  if (name === 'NotAllowedError' || name === 'SecurityError' || name === 'PermissionDeniedError') return 'denied';
  if (name === 'NotFoundError' || name === 'OverconstrainedError' || name === 'DevicesNotFoundError') return 'missing';
  if (name === 'NotReadableError' || name === 'AbortError' || name === 'TrackStartError') return 'in-use';
  return 'error';
}

function hasMediaDevices(): boolean {
  return typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia;
}

const STATE_TEXT: Record<DeviceState, string> = {
  'idle':        'Not checked yet',
  'pending':     'Checking...',
  'ok':          'Working',
  'denied':      'Blocked by the browser',
  'missing':     'Not found',
  'in-use':      'In use by another app',
  'unsupported': 'Not supported in this browser',
  'error':       'Could not start',
};

export function CallDeviceCheck(props: {
  /** Start the check immediately (otherwise show a "Check" button). */
  autoStart:  boolean;
  /** When true the preview is released (e.g. while connecting to the room). */
  released:   boolean;
  onChange:   (choices: DeviceChoices) => void;
}) {
  const { autoStart, released, onChange } = props;
  const [started, setStarted]   = useState(autoStart);
  const [micState, setMicState] = useState<DeviceState>('idle');
  const [camState, setCamState] = useState<DeviceState>('idle');
  const [micOn, setMicOn]       = useState(true);
  const [camOn, setCamOn]       = useState(true);
  const [mics, setMics]         = useState<MediaDeviceInfo[]>([]);
  const [cams, setCams]         = useState<MediaDeviceInfo[]>([]);
  const [micId, setMicId]       = useState<string | undefined>(undefined);
  const [camId, setCamId]       = useState<string | undefined>(undefined);
  const [level, setLevel]       = useState(0);

  const videoRef    = useRef<HTMLVideoElement | null>(null);
  const audioStream = useRef<MediaStream | null>(null);
  const videoStream = useRef<MediaStream | null>(null);
  const audioCtx    = useRef<AudioContext | null>(null);
  const rafId       = useRef<number | null>(null);

  useEffect(() => { if (autoStart) setStarted(true); }, [autoStart]);

  const stopAudio = useCallback(() => {
    if (rafId.current != null) cancelAnimationFrame(rafId.current);
    rafId.current = null;
    audioCtx.current?.close().catch(() => {});
    audioCtx.current = null;
    audioStream.current?.getTracks().forEach(t => t.stop());
    audioStream.current = null;
    setLevel(0);
  }, []);

  const stopVideo = useCallback(() => {
    videoStream.current?.getTracks().forEach(t => t.stop());
    videoStream.current = null;
    if (videoRef.current) videoRef.current.srcObject = null;
  }, []);

  const refreshDeviceList = useCallback(async () => {
    try {
      const list = await navigator.mediaDevices.enumerateDevices();
      setMics(list.filter(d => d.kind === 'audioinput' && d.deviceId));
      setCams(list.filter(d => d.kind === 'videoinput' && d.deviceId));
    } catch {
      // Device labels are optional; selectors simply stay empty.
    }
  }, []);

  const startAudio = useCallback(async (deviceId?: string) => {
    stopAudio();
    setMicState('pending');
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: deviceId ? { deviceId: { exact: deviceId } } : true,
      });
      audioStream.current = stream;
      setMicState('ok');
      const track = stream.getAudioTracks()[0];
      const settingsId = track?.getSettings().deviceId;
      if (settingsId) setMicId(settingsId);

      const Ctx: typeof AudioContext | undefined = (window as any).AudioContext ?? (window as any).webkitAudioContext;
      if (Ctx) {
        const ctx = new Ctx();
        audioCtx.current = ctx;
        const analyser = ctx.createAnalyser();
        analyser.fftSize = 256;
        ctx.createMediaStreamSource(stream).connect(analyser);
        const data = new Uint8Array(analyser.frequencyBinCount);
        let last = 0;
        const tick = (ts: number) => {
          if (ts - last > 100) {
            last = ts;
            analyser.getByteTimeDomainData(data);
            let peak = 0;
            for (let i = 0; i < data.length; i++) peak = Math.max(peak, Math.abs(data[i] - 128));
            setLevel(Math.min(1, peak / 64));
          }
          rafId.current = requestAnimationFrame(tick);
        };
        rafId.current = requestAnimationFrame(tick);
      }
    } catch (e) {
      setMicState(classify(e));
    }
  }, [stopAudio]);

  const startVideo = useCallback(async (deviceId?: string) => {
    stopVideo();
    setCamState('pending');
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: deviceId ? { deviceId: { exact: deviceId } } : { width: { ideal: 960 }, height: { ideal: 540 } },
      });
      videoStream.current = stream;
      setCamState('ok');
      const settingsId = stream.getVideoTracks()[0]?.getSettings().deviceId;
      if (settingsId) setCamId(settingsId);
      if (videoRef.current) {
        videoRef.current.srcObject = stream;
        videoRef.current.play().catch(() => {});
      }
    } catch (e) {
      setCamState(classify(e));
    }
  }, [stopVideo]);

  const runCheck = useCallback(async () => {
    if (!hasMediaDevices()) {
      setMicState('unsupported');
      setCamState('unsupported');
      return;
    }
    // Ask for the microphone first so a camera problem never hides a working mic.
    await startAudio(micId);
    if (camOn) await startVideo(camId);
    else setCamState(s => (s === 'pending' ? 'idle' : s));
    await refreshDeviceList();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [startAudio, startVideo, refreshDeviceList]);

  // Start / release previews.
  useEffect(() => {
    if (!started || released) {
      stopAudio();
      stopVideo();
      return;
    }
    runCheck();
    return () => { stopAudio(); stopVideo(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [started, released]);

  useEffect(() => {
    if (!hasMediaDevices()) return;
    const onDeviceChange = () => { refreshDeviceList(); };
    navigator.mediaDevices.addEventListener?.('devicechange', onDeviceChange);
    return () => navigator.mediaDevices.removeEventListener?.('devicechange', onDeviceChange);
  }, [refreshDeviceList]);

  // Report choices upward.
  useEffect(() => {
    onChange({
      micEnabled:    micOn && micState === 'ok',
      cameraEnabled: camOn && camState === 'ok',
      audioDeviceId: micId,
      videoDeviceId: camId,
    });
  }, [micOn, camOn, micState, camState, micId, camId, onChange]);

  // Re-attach the preview stream if the <video> element remounts.
  useEffect(() => {
    if (videoRef.current && videoStream.current && videoRef.current.srcObject !== videoStream.current) {
      videoRef.current.srcObject = videoStream.current;
      videoRef.current.play().catch(() => {});
    }
  });

  function toggleCamera() {
    const next = !camOn;
    setCamOn(next);
    if (!started || released) return;
    if (next) startVideo(camId);
    else { stopVideo(); }
  }

  function toggleMic() {
    setMicOn(on => !on);
  }

  if (!started) {
    return (
      <View style={styles.wrap}>
        <Text style={styles.heading} accessibilityRole="header">Camera and microphone</Text>
        <Text style={styles.body}>Check that your camera and microphone work before the call opens.</Text>
        <CallButton
          label="Check camera and microphone"
          icon="videocam-outline"
          variant="secondary"
          onPress={() => setStarted(true)}
        />
      </View>
    );
  }

  const showPreview = camOn && camState === 'ok' && !released;
  const blocked = micState === 'denied' || camState === 'denied';
  const missing = micState === 'missing' || camState === 'missing';
  const inUse   = micState === 'in-use' || camState === 'in-use';

  return (
    <View style={styles.wrap}>
      <Text style={styles.heading} accessibilityRole="header">Camera and microphone</Text>

      <View style={styles.preview} accessibilityRole="image" accessibilityLabel={showPreview ? 'Your camera preview' : 'Camera preview is off'}>
        {showPreview ? (
          <video
            ref={videoRef}
            muted
            playsInline
            autoPlay
            style={{ width: '100%', height: '100%', objectFit: 'cover', transform: 'scaleX(-1)', borderRadius: 12 }}
          />
        ) : (
          <View style={styles.previewOff}>
            <Ionicons name="videocam-off-outline" size={28} color="#CBD5E1" />
            <Text style={styles.previewOffText}>
              {released ? 'Camera handed to the call' : camOn ? STATE_TEXT[camState] : 'Camera off'}
            </Text>
          </View>
        )}
      </View>

      <View style={styles.row}>
        <View style={styles.meterWrap} accessibilityLabel={`Microphone level ${Math.round(level * 100)} percent`}>
          <Ionicons name={micOn && micState === 'ok' ? 'mic-outline' : 'mic-off-outline'} size={16} color={C.muted} />
          <View style={styles.meterTrack}>
            <View style={[styles.meterFill, { width: `${Math.round((micOn ? level : 0) * 100)}%` }]} />
          </View>
        </View>
      </View>

      <View style={styles.statusList}>
        <StatusLine icon="mic-outline" label="Microphone" state={micState} />
        <StatusLine icon="videocam-outline" label="Camera" state={camOn ? camState : 'idle'} offText={camOn ? undefined : 'Turned off'} />
      </View>

      <View style={styles.toggles}>
        <ToggleChip
          on={micOn}
          disabled={micState !== 'ok'}
          onPress={toggleMic}
          onLabel="Microphone on"
          offLabel="Join muted"
          icon={micOn ? 'mic-outline' : 'mic-off-outline'}
        />
        <ToggleChip
          on={camOn}
          disabled={released}
          onPress={toggleCamera}
          onLabel="Camera on"
          offLabel="Join without camera"
          icon={camOn ? 'videocam-outline' : 'videocam-off-outline'}
        />
      </View>

      {(mics.length > 1 || cams.length > 1) && !released && (
        <View style={styles.selectors}>
          {mics.length > 1 && (
            <DeviceSelect
              label="Microphone"
              devices={mics}
              value={micId}
              onChange={id => { setMicId(id); startAudio(id); }}
            />
          )}
          {cams.length > 1 && camOn && (
            <DeviceSelect
              label="Camera"
              devices={cams}
              value={camId}
              onChange={id => { setCamId(id); startVideo(id); }}
            />
          )}
        </View>
      )}

      {blocked && (
        <Notice tone="warning" title="Your browser is blocking the camera or microphone">
          <Text style={styles.noticeText}>
            Select the camera icon or padlock in the address bar, allow camera and microphone for this site, then choose "Check again".
            You can also join with audio only or without a camera.
          </Text>
        </Notice>
      )}
      {missing && (
        <Notice tone="warning" title="No camera or microphone found">
          <Text style={styles.noticeText}>
            {micState === 'missing'
              ? 'Without a microphone you can still join and listen, but others will not hear you.'
              : 'You can join with audio only.'}
          </Text>
        </Notice>
      )}
      {inUse && (
        <Notice tone="warning" title="A device is in use">
          <Text style={styles.noticeText}>Close other apps or tabs that use the camera or microphone, then choose "Check again".</Text>
        </Notice>
      )}
      {micState === 'unsupported' && (
        <Notice tone="error" title="This browser cannot use a camera or microphone">
          <Text style={styles.noticeText}>Open CareNexus in a current version of Chrome, Edge, Firefox or Safari.</Text>
        </Notice>
      )}

      {!released && micState !== 'pending' && camState !== 'pending' && micState !== 'unsupported' && (
        <TouchableOpacity
          onPress={() => runCheck()}
          accessibilityRole="button"
          accessibilityLabel="Check again: camera and microphone"
          style={styles.linkBtn}
        >
          <Ionicons name="refresh-outline" size={14} color={C.indigo} />
          <Text style={styles.linkText}>Check again</Text>
        </TouchableOpacity>
      )}
    </View>
  );
}

function StatusLine(props: { icon: React.ComponentProps<typeof Ionicons>['name']; label: string; state: DeviceState; offText?: string }) {
  const good = props.state === 'ok';
  const text = props.offText ?? STATE_TEXT[props.state];
  return (
    <View style={styles.statusLine}>
      <Ionicons name={props.icon} size={15} color={C.muted} />
      <Text style={styles.statusLabel}>{props.label}</Text>
      <Text style={[styles.statusValue, { color: good ? C.green : props.state === 'idle' || props.state === 'pending' ? C.faint : C.amber }]}>
        {text}
      </Text>
    </View>
  );
}

function ToggleChip(props: {
  on: boolean; disabled?: boolean; onPress: () => void; onLabel: string; offLabel: string;
  icon: React.ComponentProps<typeof Ionicons>['name'];
}) {
  return (
    <TouchableOpacity
      onPress={props.onPress}
      disabled={props.disabled}
      accessibilityRole="switch"
      accessibilityState={{ checked: props.on, disabled: !!props.disabled }}
      aria-checked={props.on}
      accessibilityLabel={props.on ? props.onLabel : props.offLabel}
      style={[styles.chip, props.on ? styles.chipOn : styles.chipOff, props.disabled && { opacity: 0.5 }]}
    >
      <Ionicons name={props.icon} size={15} color={props.on ? C.indigo : C.muted} />
      <Text style={[styles.chipText, { color: props.on ? C.indigo : C.muted }]}>{props.on ? props.onLabel : props.offLabel}</Text>
    </TouchableOpacity>
  );
}

export function DeviceSelect(props: {
  label: string; devices: MediaDeviceInfo[]; value: string | undefined; onChange: (id: string) => void; dark?: boolean;
}) {
  return (
    <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12, color: props.dark ? '#E2E8F0' : C.muted, minWidth: 0, flex: 1 }}>
      {props.label}
      <select
        value={props.value ?? ''}
        onChange={e => props.onChange(e.target.value)}
        aria-label={props.label}
        style={{
          padding: '8px 10px', borderRadius: 8, border: `1px solid ${props.dark ? '#334155' : C.border}`,
          background: props.dark ? '#1E293B' : '#FFFFFF', color: props.dark ? '#F8FAFC' : C.text,
          fontSize: 13, maxWidth: '100%', minHeight: 40,
        }}
      >
        {props.devices.map((d, i) => (
          <option key={d.deviceId} value={d.deviceId}>{d.label || `${props.label} ${i + 1}`}</option>
        ))}
      </select>
    </label>
  );
}

const styles = StyleSheet.create({
  wrap:           { gap: 12 },
  heading:        { fontSize: 15, fontWeight: '700', color: C.text },
  body:           { fontSize: 13, color: C.muted },
  preview:        { width: '100%', aspectRatio: 16 / 9, backgroundColor: C.dark, borderRadius: 12, overflow: 'hidden' },
  previewOff:     { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 6 },
  previewOffText: { color: '#CBD5E1', fontSize: 13 },
  row:            { flexDirection: 'row', alignItems: 'center' },
  meterWrap:      { flex: 1, flexDirection: 'row', alignItems: 'center', gap: 8 },
  meterTrack:     { flex: 1, height: 6, borderRadius: 3, backgroundColor: C.border, overflow: 'hidden' },
  meterFill:      { height: 6, backgroundColor: C.green },
  statusList:     { gap: 6 },
  statusLine:     { flexDirection: 'row', alignItems: 'center', gap: 6, flexWrap: 'wrap' },
  statusLabel:    { fontSize: 13, color: C.text, fontWeight: '600' },
  statusValue:    { fontSize: 13 },
  toggles:        { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  chip:           { flexDirection: 'row', alignItems: 'center', gap: 6, borderRadius: 999, borderWidth: 1, paddingHorizontal: 12, paddingVertical: 8, minHeight: 40 },
  chipOn:         { borderColor: C.indigo, backgroundColor: C.indigoBg },
  chipOff:        { borderColor: C.border, backgroundColor: C.surface },
  chipText:       { fontSize: 13, fontWeight: '600' },
  selectors:      { flexDirection: 'row', flexWrap: 'wrap', gap: 10 },
  noticeText:     { fontSize: 13, color: C.muted, lineHeight: 19 },
  linkBtn:        { flexDirection: 'row', alignItems: 'center', gap: 4, alignSelf: 'flex-start', paddingVertical: 6 },
  linkText:       { fontSize: 13, color: C.indigo, fontWeight: '600' },
});
