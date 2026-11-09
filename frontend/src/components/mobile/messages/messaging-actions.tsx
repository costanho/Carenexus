import { Ionicons } from '@expo/vector-icons';
import { createContext, type ReactNode, useCallback, useContext, useMemo, useRef, useState } from 'react';
import { ActivityIndicator, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { MessageCareTeamButton } from '@/components/web/proxy/patient/message-care-team-button';
import { apiErrorMessage } from '@/hooks/use-appointments';
import type { CaregiverDependent } from '@/hooks/use-caregiver-dependents';
import { useCareTeamConversation } from '@/hooks/use-caregiver-messaging';
import { useCurrentUser } from '@/hooks/use-current-user';
import { useDoctorMessagePatient } from '@/hooks/use-doctor-message-patient';
import { createThread } from '@/lib/communications/api';
import { showMessage } from '@/lib/ui/dialogs';

// Contextual "Message" buttons on the phone screens. The dashboard provides the actions once, so a card
// deep in a screen only renders a button (no props threaded through every screen). Each action uses the
// get-or-create thread POST, like web: pressing twice opens the same conversation, never a duplicate.

interface Actions {
  /** Switch the dashboard to Messages on this thread. */
  openThread:      (threadId: number) => void;
  /** Patient → doctor: the private conversation with that doctor. */
  messageDoctor:   ((doctorSystemId: string, doctorName: string) => void) | null;
  /** Doctor → patient: the private conversation with that patient. */
  messagePatient:  ((patientSystemId: string) => void) | null;
  /** Caregiver → care team for one loved one. */
  careTeam:        ReturnType<typeof useCareTeamConversation> | null;
  /** System id of the person whose conversation is being opened (spinner on that button). */
  pendingFor:      string | null;
  /** Patient: open the caregiver access screen (approve / deny / revoke caregivers). */
  openCaregiverAccess: (() => void) | null;
}

const Ctx = createContext<Actions | null>(null);

export function useMessagingActions(): Actions | null {
  return useContext(Ctx);
}

export function MobileMessagingProvider({ role, onOpenThread, onOpenMessages, onOpenCaregiverAccess, children }: {
  role:           'PATIENT' | 'DOCTOR' | 'CAREGIVER';
  onOpenThread:   (threadId: number) => void;
  /** Fallback when a conversation could not be opened (patient → doctor): show the inbox. */
  onOpenMessages: () => void;
  /** Patient only. */
  onOpenCaregiverAccess?: () => void;
  children:       ReactNode;
}) {
  const { user } = useCurrentUser();
  const doctor = useDoctorMessagePatient(role === 'DOCTOR' ? onOpenThread : undefined);
  const careTeam = useCareTeamConversation(role === 'CAREGIVER' ? onOpenThread : undefined);
  const [pendingDoctor, setPendingDoctor] = useState<string | null>(null);
  const busy = useRef(false);
  const mySystemId = user?.system_id ?? null;

  const messageDoctor = useCallback(async (doctorSystemId: string, doctorName: string) => {
    if (busy.current) return;
    if (!mySystemId) { onOpenMessages(); return; }
    busy.current = true;
    setPendingDoctor(doctorSystemId);
    try {
      const thread = await createThread({ patientSystemId: mySystemId, type: 'PRIVATE', memberSystemIds: [doctorSystemId] });
      onOpenThread(thread.threadId);
    } catch (e) {
      onOpenMessages();
      showMessage(
        'Could not open the conversation',
        apiErrorMessage(e, `We could not open a conversation with ${doctorName}. You can start one from Messages.`),
      );
    } finally {
      busy.current = false;
      setPendingDoctor(null);
    }
  }, [mySystemId, onOpenThread, onOpenMessages]);

  const value = useMemo<Actions>(() => ({
    openThread:     onOpenThread,
    messageDoctor:  role === 'PATIENT' ? messageDoctor : null,
    messagePatient: role === 'DOCTOR' ? doctor.messagePatient : null,
    careTeam:       role === 'CAREGIVER' ? careTeam : null,
    pendingFor:     role === 'PATIENT' ? pendingDoctor : role === 'DOCTOR' ? doctor.pendingPatient : null,
    openCaregiverAccess: role === 'PATIENT' ? (onOpenCaregiverAccess ?? null) : null,
  }), [role, onOpenThread, messageDoctor, doctor.messagePatient, doctor.pendingPatient, careTeam, pendingDoctor, onOpenCaregiverAccess]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

function PillButton({ label, a11yLabel, color, busy, onPress, compact }: {
  label: string; a11yLabel: string; color: string; busy: boolean; onPress: () => void; compact?: boolean;
}) {
  return (
    <TouchableOpacity
      style={[styles.pill, compact && styles.pillCompact, { borderColor: color + '55', backgroundColor: color + '12' }]}
      onPress={onPress}
      disabled={busy}
      activeOpacity={0.75}
      accessibilityRole="button"
      accessibilityLabel={a11yLabel}
      accessibilityState={{ busy, disabled: busy }}
    >
      {busy
        ? <ActivityIndicator size="small" color={color} />
        : <Ionicons name="chatbubble-ellipses-outline" size={15} color={color} importantForAccessibility="no" />}
      <Text style={[styles.pillText, { color }]}>{label}</Text>
    </TouchableOpacity>
  );
}

/** "Message Dr X" on a patient's appointment card. Renders nothing outside a patient dashboard. */
export function MessageDoctorButton({ doctorSystemId, doctorName, compact }: {
  doctorSystemId: string | null | undefined; doctorName: string; compact?: boolean;
}) {
  const actions = useMessagingActions();
  if (!actions?.messageDoctor || !doctorSystemId) return null;
  const go = actions.messageDoctor;
  return (
    <PillButton
      label="Message"
      a11yLabel={`Message ${doctorName}`}
      color="#0F766E"
      compact={compact}
      busy={actions.pendingFor === doctorSystemId}
      onPress={() => go(doctorSystemId, doctorName)}
    />
  );
}

/** "Message" on a doctor's patient or appointment card. Renders nothing outside a doctor dashboard. */
export function MessagePatientButton({ patientSystemId, patientName, compact }: {
  patientSystemId: string | null | undefined; patientName: string; compact?: boolean;
}) {
  const actions = useMessagingActions();
  if (!actions?.messagePatient || !patientSystemId) return null;
  const go = actions.messagePatient;
  return (
    <PillButton
      label="Message"
      a11yLabel={`Message ${patientName}`}
      color="#4F46E5"
      compact={compact}
      busy={actions.pendingFor === patientSystemId}
      onPress={() => go(patientSystemId)}
    />
  );
}

/**
 * "Message Care Team" for one loved one (caregiver). The shared button shows the messaging status and
 * reason ("Waiting for {name} to approve messaging", "not allowed") with the same wording as web.
 */
export function MobileMessageCareTeamButton({ dependent, compact }: { dependent: CaregiverDependent; compact?: boolean }) {
  const actions = useMessagingActions();
  const careTeam = actions?.careTeam;
  if (!careTeam) return null;
  return (
    <MessageCareTeamButton
      dependent={dependent}
      compact={compact}
      busy={careTeam.openingFor === dependent.accessId}
      error={careTeam.openingFor == null ? careTeam.error : null}
      onPress={careTeam.open}
    />
  );
}

/** Patient profile row: "Caregivers and access" (approve messaging and calls, revoke access). */
export function CaregiverAccessLink() {
  const open = useMessagingActions()?.openCaregiverAccess;
  if (!open) return null;
  return (
    <TouchableOpacity
      style={styles.link}
      onPress={open}
      activeOpacity={0.75}
      accessibilityRole="button"
      accessibilityLabel="Caregivers and access"
      accessibilityHint="Approve or remove what your caregivers can do"
    >
      <Ionicons name="people-outline" size={20} color="#0F766E" importantForAccessibility="no" />
      <View style={styles.linkBody}>
        <Text style={styles.linkTitle}>Caregivers and access</Text>
        <Text style={styles.linkSub}>Approve messaging and video calls, or remove access</Text>
      </View>
      <Ionicons name="chevron-forward" size={18} color="#6B7280" importantForAccessibility="no" />
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  link:        { flexDirection: 'row', alignItems: 'center', gap: 12, backgroundColor: '#FFFFFF', borderRadius: 14, borderWidth: 1, borderColor: '#E5E7EB', padding: 14, marginBottom: 14 },
  linkBody:    { flex: 1, gap: 2 },
  linkTitle:   { fontSize: 15, fontWeight: '700', color: '#111827' },
  linkSub:     { fontSize: 12, color: '#5B6472' },
  pill:        { flexDirection: 'row', alignItems: 'center', gap: 6, borderWidth: 1, borderRadius: 20, paddingHorizontal: 14, paddingVertical: 9, alignSelf: 'flex-start' },
  pillCompact: { paddingHorizontal: 11, paddingVertical: 6 },
  pillText:    { fontSize: 13, fontWeight: '600' },
});
