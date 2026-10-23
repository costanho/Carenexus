import { Stack, useRouter, useSegments } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Platform, StyleSheet, View } from 'react-native';
import { getAccessToken, getUser } from '@/lib/auth/auth.storage';
import { ROLE_HOME, type Role } from '@/lib/auth/auth.types';

const LOGIN_ROUTE = Platform.OS === 'web'
  ? '/auth/login/web/login-web'
  : '/auth/login/mobile/login-mobile';

// Extract userId from JWT payload
function getUserIdFromToken(token: string): number | null {
  try {
    // JWT format: header.payload.signature
    const payload = token.split('.')[1];
    if (!payload) return null;

    // Decode base64 payload
    const decoded = JSON.parse(atob(payload));
    return decoded.userId ?? null;
  } catch {
    return null;
  }
}

export default function DashboardLayout() {
  const router   = useRouter();
  const segments = useSegments();
  const [checking, setChecking] = useState(true);
  const didRedirect = useRef(false);

  useEffect(() => {
    async function verify() {
      console.log('🔐 Dashboard guard: verifying access');
      const [token, user] = await Promise.all([getAccessToken(), getUser()]);
      console.log('📦 Dashboard guard: token:', token ? 'exists' : 'missing', 'user:', user);

      // No token or user → back to login
      if (!token || !user) {
        console.log('❌ No token or user, redirecting to login');
        router.replace(LOGIN_ROUTE as any);
        return;
      }

      // Extract user_id from token and verify it matches stored user
      const tokenUserId = getUserIdFromToken(token);
      console.log('🔍 Token userId:', tokenUserId, 'Stored user_id:', user.user_id, 'Match:', tokenUserId === user.user_id);
      if (tokenUserId !== user.user_id) {
        console.log('❌ UserId mismatch, redirecting to login');
        router.replace(LOGIN_ROUTE as any);
        return;
      }

      // Check the current dashboard segment matches the user's role
      // segments = ['dashboard', 'patient'] for /dashboard/patient
      const dashboardSegment = segments[1] as string;
      console.log('📍 Dashboard segment:', dashboardSegment, 'User role:', user.role);

      // Map route segment → role (proxy route = caregiver role)
      const segmentToRole: Record<string, Role> = {
        patient:   'patient',
        doctor:    'doctor',
        proxy:     'proxy',
      };

      const expectedRole = segmentToRole[dashboardSegment];
      console.log('✔️ Expected role:', expectedRole);

      if (expectedRole && user.role !== expectedRole && !didRedirect.current) {
        didRedirect.current = true;
        console.warn(`[Guard] Role mismatch — user is "${user.role}" but tried to access "${dashboardSegment}" dashboard. Redirecting.`);
        router.replace(ROLE_HOME[user.role] as any);
        return;
      }

      console.log('✅ Dashboard guard passed!');
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
