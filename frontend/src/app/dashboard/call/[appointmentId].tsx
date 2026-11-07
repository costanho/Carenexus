import { useLocalSearchParams, useRouter } from 'expo-router';
import Head from 'expo-router/head';
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { CallScreen } from '@/components/web/call/call-screen';
import { getUser } from '@/lib/auth/auth.storage';
import { ROLE_HOME } from '@/lib/auth/auth.types';
import { parseAppointmentId } from '@/lib/calls/join';

export default function CallRoute() {
  const router = useRouter();
  const { appointmentId } = useLocalSearchParams<{ appointmentId: string }>();
  const id = parseAppointmentId(appointmentId);

  // A missing or malformed id ("undefined", "abc", "0") never reaches the call screen or the API.
  if (id === null) {
    const backHome = async () => {
      const user = await getUser();
      router.replace((user && ROLE_HOME[user.role] ? ROLE_HOME[user.role] : '/') as any);
    };
    return (
      <View style={styles.root}>
        <Head><title>Call not available · CareNexus</title></Head>
        <Text style={styles.title} accessibilityRole="header">Call not available</Text>
        <Text style={styles.body}>This call link is not valid.</Text>
        <TouchableOpacity
          style={styles.btn}
          onPress={backHome}
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
