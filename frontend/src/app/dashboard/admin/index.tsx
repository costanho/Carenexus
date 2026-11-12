import { Ionicons } from '@expo/vector-icons';
import { Platform, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { AdminDashboardWeb } from '@/components/web/admin/admin-dashboard-web';
import { useLogout } from '@/hooks/use-logout';

/** The administrator area exists on the web only; the native app says so and offers to sign out. */
function AdminOnWebOnly() {
  const { handleLogout, loading } = useLogout();
  return (
    <View style={styles.wrap}>
      <Ionicons name="desktop-outline" size={44} color="#334155" importantForAccessibility="no" />
      <Text style={styles.title} accessibilityRole="header">Administration is available on the web</Text>
      <Text style={styles.sub}>Sign in to CareNexus from a web browser to use the administrator tools.</Text>
      <TouchableOpacity
        style={styles.btn}
        onPress={handleLogout}
        disabled={loading}
        activeOpacity={0.8}
        accessibilityRole="button"
        accessibilityState={{ disabled: loading, busy: loading }}
      >
        <Text style={styles.btnText}>{loading ? 'Signing out…' : 'Sign out'}</Text>
      </TouchableOpacity>
    </View>
  );
}

export default function AdminDashboard() {
  return Platform.OS === 'web' ? <AdminDashboardWeb /> : <AdminOnWebOnly />;
}

const styles = StyleSheet.create({
  wrap:    { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 32, gap: 12, backgroundColor: '#F8FAFC' },
  title:   { fontSize: 18, fontWeight: '700', color: '#0F172A', textAlign: 'center' },
  sub:     { fontSize: 14, lineHeight: 20, color: '#475569', textAlign: 'center', maxWidth: 360 },
  btn:     { marginTop: 8, backgroundColor: '#334155', borderRadius: 8, paddingVertical: 11, paddingHorizontal: 22 },
  btnText: { color: '#FFFFFF', fontSize: 14, fontWeight: '600' },
});
