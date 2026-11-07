import { KeyboardAvoidingView, Platform, StyleSheet } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import PatientWebCaregiverAccess from '@/components/web/direct/patient/patient-web-caregiver-access';

/**
 * The patient's caregiver access on a phone: approve, deny or revoke a caregiver's messaging and video-call
 * permissions (and the pending-approval state). The screen is built from plain React Native views, so the
 * same component (copy, rules and API calls) serves web and native; this wrapper adds the safe area and
 * keeps the invite form above the keyboard.
 */
export default function PatientMobileCaregiverAccess({ onBack }: { onBack?: () => void }) {
  const insets = useSafeAreaInsets();
  return (
    <KeyboardAvoidingView
      style={[styles.wrap, { paddingTop: insets.top }]}
      behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
    >
      <PatientWebCaregiverAccess onBack={onBack} />
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  wrap: { flex: 1, backgroundColor: '#F9FAFB' },
});
