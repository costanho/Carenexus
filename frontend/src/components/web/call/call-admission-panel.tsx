import { Ionicons } from '@expo/vector-icons';
import React, { useEffect, useRef, useState } from 'react';
import { Platform, StyleSheet, Text, View } from 'react-native';
import { apiErrorMessage } from '@/hooks/use-appointments';
import { admitParticipant, denyParticipant, removeParticipant } from '@/lib/calls/api';
import type { CallParticipant, CallStatusResponse } from '@/lib/calls/types';
import { confirmAction } from '@/lib/ui/dialogs';
import { C, CallButton, Notice } from './call-ui';

// Doctor-only admission panel: waiting requests (Admit / Deny) and admitted people (Remove).

function playChime() {
  if (Platform.OS !== 'web' || typeof window === 'undefined') return;
  try {
    const Ctx: typeof AudioContext | undefined = (window as any).AudioContext ?? (window as any).webkitAudioContext;
    if (!Ctx) return;
    const ctx = new Ctx();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = 'sine';
    osc.frequency.value = 880;
    gain.gain.setValueAtTime(0.0001, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.15, ctx.currentTime + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.4);
    osc.connect(gain).connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.45);
    osc.onended = () => { ctx.close().catch(() => {}); };
  } catch {
    // Sound is only a cue; the visual highlight still shows.
  }
}

export function CallAdmissionPanel(props: {
  appointmentId: number;
  status:        CallStatusResponse;
  onStatus:      (s: CallStatusResponse) => void;
  dark?:         boolean;
}) {
  const { appointmentId, status, onStatus, dark } = props;
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError]   = useState<string | null>(null);
  const [fresh, setFresh]   = useState<Set<number>>(new Set());
  const seen = useRef<Set<number> | null>(null);

  const others    = status.participants.filter(p => !p.me);
  const requested = others.filter(p => p.status === 'REQUESTED');
  const admitted  = others.filter(p => p.status === 'ADMITTED');
  const pendingRemoval = others.filter(p => p.status === 'REMOVED' && p.removalPending);

  // Cue for new join requests (not for those already waiting when the panel opened).
  useEffect(() => {
    const ids = new Set(requested.map(p => p.participantId));
    if (seen.current === null) {
      seen.current = ids;
      return;
    }
    const added = [...ids].filter(id => !seen.current!.has(id));
    seen.current = ids;
    if (added.length > 0) {
      playChime();
      setFresh(prev => new Set([...prev, ...added]));
      const t = setTimeout(() => setFresh(prev => {
        const next = new Set(prev);
        added.forEach(id => next.delete(id));
        return next;
      }), 8000);
      return () => clearTimeout(t);
    }
  }, [requested.map(p => p.participantId).join(',')]);

  async function act(p: CallParticipant, kind: 'admit' | 'deny' | 'remove') {
    if (busyId !== null) return;
    if (kind === 'remove') {
      const ok = await confirmAction(
        'Remove from call?',
        `${p.label} will be disconnected from this call.`,
        'Remove',
        true,
      );
      if (!ok) return;
    }
    setBusyId(p.participantId);
    setError(null);
    try {
      const fn = kind === 'admit' ? admitParticipant : kind === 'deny' ? denyParticipant : removeParticipant;
      onStatus(await fn(appointmentId, p.participantId));
    } catch (e) {
      setError(apiErrorMessage(e, 'Could not update this person. Please try again.'));
    } finally {
      setBusyId(null);
    }
  }

  const text  = dark ? '#F8FAFC' : C.text;
  const muted = dark ? '#CBD5E1' : C.muted;

  return (
    <View style={[styles.panel, dark && styles.panelDark]} accessibilityLabel="People in this call">
      <Text style={[styles.heading, { color: text }]} accessibilityRole="header">
        Waiting to join {requested.length > 0 ? `(${requested.length})` : ''}
      </Text>
      <View accessibilityLiveRegion="polite">
        {requested.length === 0 ? (
          <Text style={[styles.empty, { color: muted }]}>No one is waiting.</Text>
        ) : requested.map(p => (
          <View
            key={p.participantId}
            style={[styles.person, dark && styles.personDark, fresh.has(p.participantId) && styles.personFresh]}
          >
            <View style={styles.personInfo}>
              <Ionicons name="person-circle-outline" size={20} color={muted} />
              <View style={{ flex: 1, minWidth: 0 }}>
                <Text style={[styles.label, { color: text }]}>{p.label}</Text>
                {fresh.has(p.participantId) && <Text style={styles.newTag}>New request</Text>}
              </View>
            </View>
            <View style={styles.actions}>
              <CallButton
                label="Admit"
                icon="checkmark-outline"
                onPress={() => act(p, 'admit')}
                busy={busyId === p.participantId}
                disabled={busyId !== null && busyId !== p.participantId}
                accessibilityLabel={`Admit ${p.label}`}
                style={styles.smallBtn}
              />
              <CallButton
                label="Deny"
                variant="secondary"
                onPress={() => act(p, 'deny')}
                disabled={busyId !== null}
                accessibilityLabel={`Deny ${p.label}`}
                style={styles.smallBtn}
              />
            </View>
          </View>
        ))}
      </View>

      <Text style={[styles.heading, { color: text, marginTop: 8 }]} accessibilityRole="header">In the call</Text>
      {admitted.length === 0 ? (
        <Text style={[styles.empty, { color: muted }]}>No one else has been admitted yet.</Text>
      ) : admitted.map(p => (
        <View key={p.participantId} style={[styles.person, dark && styles.personDark]}>
          <View style={styles.personInfo}>
            <View style={[styles.dot, { backgroundColor: p.connected ? '#22C55E' : '#94A3B8' }]} />
            <View style={{ flex: 1, minWidth: 0 }}>
              <Text style={[styles.label, { color: text }]}>{p.label}</Text>
              <Text style={[styles.sub, { color: muted }]}>{p.connected ? 'Connected' : 'Admitted, not connected'}</Text>
            </View>
          </View>
          <CallButton
            label="Remove"
            variant="secondary"
            onPress={() => act(p, 'remove')}
            busy={busyId === p.participantId}
            disabled={busyId !== null && busyId !== p.participantId}
            accessibilityLabel={`Remove ${p.label} from the call`}
            style={styles.smallBtn}
          />
        </View>
      ))}

      {pendingRemoval.map(p => (
        <View key={p.participantId} style={[styles.person, dark && styles.personDark]}>
          <View style={styles.personInfo}>
            <Ionicons name="time-outline" size={18} color={C.amber} />
            <View style={{ flex: 1, minWidth: 0 }}>
              <Text style={[styles.label, { color: text }]}>{p.label}</Text>
              <Text style={[styles.sub, { color: C.amber }]}>Removal pending</Text>
            </View>
          </View>
        </View>
      ))}

      {status.removalPending && pendingRemoval.length === 0 && (
        <Text style={[styles.sub, { color: C.amber }]}>Removal pending: the video service has not confirmed it yet.</Text>
      )}

      {error && <Notice tone="error">{error}</Notice>}
    </View>
  );
}

const styles = StyleSheet.create({
  panel:       { gap: 8, padding: 14, borderRadius: 12, backgroundColor: C.surface, borderWidth: 1, borderColor: C.border },
  panelDark:   { backgroundColor: '#111827', borderColor: '#1F2937' },
  heading:     { fontSize: 14, fontWeight: '700' },
  empty:       { fontSize: 13 },
  person:      { gap: 8, padding: 10, borderRadius: 10, borderWidth: 1, borderColor: C.border, backgroundColor: '#F9FAFB' },
  personDark:  { borderColor: '#334155', backgroundColor: '#1E293B' },
  personFresh: { borderColor: '#B45309', borderWidth: 2 },
  personInfo:  { flexDirection: 'row', alignItems: 'center', gap: 8 },
  label:       { fontSize: 13, fontWeight: '600' },
  sub:         { fontSize: 12 },
  newTag:      { fontSize: 11, fontWeight: '700', color: '#B45309', marginTop: 2 },
  actions:     { flexDirection: 'row', gap: 8, flexWrap: 'wrap' },
  smallBtn:    { paddingVertical: 6, paddingHorizontal: 12, minHeight: 36 },
  dot:         { width: 10, height: 10, borderRadius: 5 },
});
