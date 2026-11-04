import { Ionicons } from '@expo/vector-icons';
import { StyleSheet, Text, View } from 'react-native';
import { useCaregiverDependents } from '@/hooks/use-caregiver-dependents';
import { caregiverMessagingState } from '@/hooks/use-caregiver-messaging';
import { AMBER, BORDER, GRAY, TEXT } from './ui';

type Status = 'allowed' | 'pending' | 'off' | 'inactive';

function statusText(s: Status, name: string): string {
  switch (s) {
    case 'allowed':  return `You can message ${name}'s care team.`;
    case 'pending':  return `Waiting for ${name} to approve messaging.`;
    case 'inactive': return `Your access for ${name} is no longer active. Ask ${name} to renew it.`;
    default:         return `Messaging is not allowed for your access to ${name}. ${name} can turn it on in their caregiver settings.`;
  }
}

/**
 * Shown in a caregiver's empty inbox: per loved one, whether messaging is allowed, waiting for the
 * patient's approval or not allowed. Informational, not an error.
 */
export function CaregiverMessagingStatus({ patientSystemId }: { patientSystemId?: string | null }) {
  const { dependents, loading, error } = useCaregiverDependents();
  if (loading || error) return null;
  const active = dependents
    .filter(d => d.isActive && d.accessStatus !== 'REVOKED')
    .filter(d => !patientSystemId || d.patientSystemId === patientSystemId);
  if (active.length === 0) {
    return patientSystemId ? null : <Text style={styles.note}>You are not linked to anyone yet.</Text>;
  }
  return (
    <View style={styles.list} accessibilityRole="list" accessibilityLabel="Messaging access">
      {active.map(d => {
        const name = d.patientName ?? 'Your loved one';
        // Same rule as the caregiver dashboard (effective server values, not the request flag alone).
        const state = caregiverMessagingState(d);
        const s: Status = state.status === 'ALLOWED' ? 'allowed'
          : state.status === 'WAITING' ? 'pending'
          : d.accessStatus && d.accessStatus !== 'ACTIVE' ? 'inactive' : 'off';
        return (
          <View key={d.accessId} role="listitem" style={styles.row}>
            <Ionicons
              name={s === 'allowed' ? 'chatbubbles-outline' : s === 'pending' ? 'time-outline' : 'lock-closed-outline'}
              size={15}
              color={s === 'allowed' ? GRAY : AMBER}
              importantForAccessibility="no"
            />
            <Text style={styles.text}>{statusText(s, name)}</Text>
          </View>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  list: { alignSelf: 'stretch', gap: 6, marginTop: 4, maxWidth: 420 },
  row:  { flexDirection: 'row', alignItems: 'flex-start', gap: 8, borderWidth: 1, borderColor: BORDER, borderRadius: 8, paddingHorizontal: 10, paddingVertical: 8 },
  text: { flex: 1, fontSize: 12, color: TEXT, lineHeight: 17 },
  note: { fontSize: 12, color: GRAY },
});
