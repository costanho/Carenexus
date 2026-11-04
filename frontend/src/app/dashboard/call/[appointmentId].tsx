import { useLocalSearchParams, useRouter } from 'expo-router';
import Head from 'expo-router/head';
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { CallScreen } from '@/components/web/call/call-screen';

export default function CallRoute() {
  const router = useRouter();
  const { appointmentId } = useLocalSearchParams<{ appointmentId: string }>();
  const raw = Array.isArray(appointmentId) ? appointmentId[0] : appointmentId;
  const id  = raw && /^\d+$/.test(raw) ? Number(raw) : NaN;

  if (!Number.isSafeInteger(id) || id <= 0) {
    return (
      <View style={styles.root}>
        <Text style={styles.title} accessibilityRole="header">Call not available</Text>
        <Text style={styles.body}>This call link is not valid.</Text>
        <TouchableOpacity
          style={styles.btn}
          onPress={() => router.replace('/' as any)}
          accessibilityRole="button"
          accessibilityLabel="Back to dashboard"
        >
          <Text style={styles.btnText}>Back to dashboard</Text>
        </TouchableOpacity>
      </View>
    );
  }

  return (
    <>
      <Head><title>Video consultation · CareNexus</title></Head>
      <CallScreen key={id} appointmentId={id} />
    </>
  );
}

const styles = StyleSheet.create({
  root:    { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24, gap: 12, backgroundColor: '#F9FAFB' },
  title:   { fontSize: 18, fontWeight: '700', color: '#111827' },
  body:    { fontSize: 14, color: '#4B5563', textAlign: 'center' },
  btn:     { marginTop: 8, backgroundColor: '#4F46E5', borderRadius: 10, paddingHorizontal: 18, paddingVertical: 10 },
  btnText: { color: '#FFFFFF', fontWeight: '600' },
});
