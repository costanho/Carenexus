import { Stack, useRouter, useSegments } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Platform, StyleSheet, View } from 'react-native';
import { getAccessToken, getUser } from '@/lib/auth/auth.storage';
import { ROLE_HOME, type Role } from '@/lib/auth/auth.types';

const LOGIN_ROUTE = Platform.OS === 'web'
  ? '/auth/login/web/login-web'
  : '/auth/login/mobile/login-mobile';

// Extract user_id from token: "access-{role}-{user_id}-{timestamp}"
function getUserIdFromToken(token: string): number | null {
  const parts = token.split('-');
  const id    = parseInt(parts[2]);
  return isNaN(id) ? null : id;
}

export default function DashboardLayout() {
  const router   = useRouter();
  const segments = useSegments();
  const [checking, setChecking] = useState(true);
  const didRedirect = useRef(false);

  useEffect(() => {
    async function verify() {
      const [token, user] = await Promise.all([getAccessToken(), getUser()]);

      // No token or user → back to login
      if (!token || !user) {
        router.replace(LOGIN_ROUTE as any);
        return;
      }

      // Extract user_id from token and verify it matches stored user
      const tokenUserId = getUserIdFromToken(token);
      if (tokenUserId !== user.user_id) {
        router.replace(LOGIN_ROUTE as any);
        return;
      }

      // Check the current dashboard segment matches the user's role
      // segments = ['dashboard', 'patient'] for /dashboard/patient
      const dashboardSegment = segments[1] as string;

      // Map route segment → role (proxy route = caregiver role)
      const segmentToRole: Record<string, Role> = {
        patient:   'patient',
        doctor:    'doctor',
        proxy:     'proxy',
      };

      const expectedRole = segmentToRole[dashboardSegment];

      if (expectedRole && user.role !== expectedRole && !didRedirect.current) {
        didRedirect.current = true;
        console.warn(`[Guard] Role mismatch — user is "${user.role}" but tried to access "${dashboardSegment}" dashboard. Redirecting.`);
        router.replace(ROLE_HOME[user.role] as any);
        return;
      }

      setChecking(false);
    }

    verify();
  }, [segments]);

  if (checking) {
    return (
      <View style={styles.loader}>
        <ActivityIndicator size="large" color="#0D9488" />
      </View>
    );
  }

  return <Stack screenOptions={{ headerShown: false }} />;
}

const styles = StyleSheet.create({
  loader: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: '#F9FAFB' },
});
