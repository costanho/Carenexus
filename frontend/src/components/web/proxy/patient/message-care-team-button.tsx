import { Ionicons } from '@expo/vector-icons';
import { ActivityIndicator, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import type { CaregiverDependent } from '@/hooks/use-caregiver-dependents';
import { caregiverMessagingState } from '@/hooks/use-caregiver-messaging';

const PURPLE       = '#7C3AED';
const PURPLE_BG    = '#F5F3FF';
const PURPLE_LIGHT = '#EDE9FE';
const GRAY         = '#5B6472';
const GRAY_BG      = '#F3F4F6';
const BORDER       = '#E5E7EB';
const ORANGE_TEXT  = '#92400E';
const RED          = '#B91C1C';

/**
 * "Message Care Team" for one loved one. Enabled only when messaging is in effect (`messagingAllowed`); otherwise
 * disabled with the reason next to it ("Waiting for {name} to approve messaging" or "not allowed").
 * The parent owns the open action (see useCareTeamConversation) so one in-flight request covers every button.
 */
export function MessageCareTeamButton({ dependent, onPress, busy, error, compact }: {
  dependent: CaregiverDependent;
  onPress:   (dep: CaregiverDependent) => void;
  busy?:     boolean;
  error?:    string | null;
  compact?:  boolean;
}) {
  const { status, reason } = caregiverMessagingState(dependent);
  const disabled = status !== 'ALLOWED' || !!busy;
  const name = dependent.patientName ?? 'your loved one';
  // Starts with the visible text (WCAG 2.5.3); the reason is part of the name so it is announced with the button.
  const label = status === 'ALLOWED'
    ? `Message Care Team about ${name}`
    : `Message Care Team about ${name}. ${reason}`;

  return (
    <View style={[styles.wrap, compact && styles.wrapCompact]}>
      <TouchableOpacity
        style={[styles.btn, compact && styles.btnCompact, status !== 'ALLOWED' && styles.btnDisabled]}
        activeOpacity={0.7}
        onPress={() => { if (!disabled) onPress(dependent); }}
        disabled={disabled}
        accessibilityRole="button"
        accessibilityLabel={label}
        accessibilityState={{ disabled, busy: !!busy }}
      >
        {busy
          ? <ActivityIndicator size="small" color={PURPLE} />
          : <Ionicons name="chatbox-ellipses-outline" size={15} color={status === 'ALLOWED' ? PURPLE : GRAY} importantForAccessibility="no" />}
        <Text style={[styles.btnText, status !== 'ALLOWED' && styles.btnTextDisabled]}>Message Care Team</Text>
      </TouchableOpacity>
      {reason ? (
        <Text style={[styles.reason, status === 'WAITING' && styles.reasonWaiting]} importantForAccessibility="no">
          {reason}
        </Text>
      ) : null}
      {error ? <Text style={styles.error} accessibilityLiveRegion="polite" role="alert">{error}</Text> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  // Shrinks with its row so the reason wraps under the button on a phone instead of running off-screen.
  wrap:            { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: 8, minWidth: 0, flexShrink: 1, flexGrow: 1, maxWidth: '100%' },
  wrapCompact:     { gap: 6 },
  btn:             { flexDirection: 'row', alignItems: 'center', gap: 6, backgroundColor: PURPLE_BG, borderWidth: 1, borderColor: PURPLE_LIGHT, borderRadius: 20, paddingHorizontal: 16, paddingVertical: 10, flexShrink: 0 },
  btnCompact:      { paddingHorizontal: 12, paddingVertical: 7 },
  btnDisabled:     { backgroundColor: GRAY_BG, borderColor: BORDER },
  btnText:         { fontSize: 13, color: PURPLE, fontWeight: '600' },
  btnTextDisabled: { color: GRAY },
  reason:          { fontSize: 12, color: GRAY, flexShrink: 1, minWidth: 0 },
  reasonWaiting:   { color: ORANGE_TEXT },
  error:           { fontSize: 12, color: RED, flexBasis: '100%' },
});
