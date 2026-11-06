import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { ComingSoonState } from './proxy-states';

const TEXT = '#111827';
const GRAY = '#6B7280';
const BG   = '#F9FAFB';

// Secure messaging with the care team has no backend yet. Show an honest
// placeholder instead of sample conversations.
export default function PatientProxyMessages() {
  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>
      <View style={styles.pageHeader}>
        <Text style={styles.pageTitle} accessibilityRole="header">Messages</Text>
        <Text style={styles.pageSub}>Secure messaging with your loved ones' care teams</Text>
      </View>
      <ComingSoonState
        icon="chatbox-outline"
        title="Secure messaging is not available yet"
        sub="You can't send or receive messages from the care team in CareNexus yet. Contact the clinic directly for anything urgent."
      />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll:     { flex: 1, backgroundColor: BG },
  container:  { padding: 24, paddingBottom: 48, gap: 16 },
  pageHeader: { marginBottom: 4 },
  pageTitle:  { fontSize: 22, fontWeight: '700', color: TEXT },
  pageSub:    { fontSize: 13, color: GRAY, marginTop: 3 },
});
