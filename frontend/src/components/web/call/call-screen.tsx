import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import React from 'react';
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native';

// Native fallback. Metro resolves call-screen.web.tsx on web, so LiveKit never reaches native bundles.
export function CallScreen(_props: { appointmentId: number }): React.JSX.Element {
  const router = useRouter();
  return (
    <View style={styles.root}>
      <Ionicons aria-hidden name="videocam-outline" size={40} color="#4F46E5" />
      <Text style={styles.title} accessibilityRole="header">Video consultation</Text>
      <Text style={styles.body}>Video consultations are available in the CareNexus web app.</Text>
      <TouchableOpacity
        style={styles.btn}
        onPress={() => (router.canGoBack() ? router.back() : router.replace('/' as any))}
        accessibilityRole="button"
        accessibilityLabel="Go back"
      >
        <Text style={styles.btnText}>Go back</Text>
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  root:    { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24, gap: 12, backgroundColor: '#F9FAFB' },
  title:   { fontSize: 18, fontWeight: '700', color: '#111827' },
  body:    { fontSize: 14, color: '#4B5563', textAlign: 'center' },
  btn:     { marginTop: 8, backgroundColor: '#4F46E5', borderRadius: 10, paddingHorizontal: 18, paddingVertical: 10 },
  btnText: { color: '#FFFFFF', fontWeight: '600' },
});
