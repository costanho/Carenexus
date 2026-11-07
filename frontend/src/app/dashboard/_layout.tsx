import { Stack, useRouter, useSegments } from 'expo-router';
import { useEffect, useState, type ReactNode } from 'react';
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

    // Decode base64url payload
    const b64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    const decoded = JSON.parse(atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4)));
    return decoded.userId ?? null;
  } catch {
    return null;
  }
}

/** Role a dashboard route belongs to ('patient/index' -> patient). The call route is shared by every role. */
function routeRole(routeName: string): Role | null {
  const first = routeName.split('/')[0];
  return first === 'patient' || first === 'doctor' || first === 'proxy' ? first : null;
}

/** undefined while checking, null when there is no valid session. */
type Session = { role: Role } | null | undefined;

function Loader() {
  return (
    <View style={styles.loader}>
      <ActivityIndicator size="large" color="#0D9488" accessibilityLabel="Loading" />
    </View>
  );
}

/**
 * Wraps every dashboard screen. A screen only mounts (and fetches its data) once the session is verified and the
 * route belongs to the signed-in role; another role's dashboard URL shows the loader and is replaced by the
 * user's own dashboard, so its screens never mount and never call APIs that would answer 403.
 */
function RoleGate({ routeName, session, children }: { routeName: string; session: Session; children: ReactNode }) {
  const router   = useRouter();
  const required = routeRole(routeName);
  const wrongRole = !!session && required !== null && session.role !== required;

  useEffect(() => {
    if (session && wrongRole) router.replace(ROLE_HOME[session.role] as any);
  }, [session, wrongRole, router]);

  if (!session || wrongRole) return <Loader />;
  return <>{children}</>;
}

export default function DashboardLayout() {
  const router   = useRouter();
  const segments = useSegments();
  const [session, setSession] = useState<Session>(undefined);
  const path = segments.join('/');

  useEffect(() => {
    let cancelled = false;
    async function verify() {
      const [token, user] = await Promise.all([getAccessToken(), getUser()]);
      if (cancelled) return;

      // No token or user, or a token for a different account than the stored user -> back to login
      if (!token || !user || getUserIdFromToken(token) !== user.user_id || !ROLE_HOME[user.role]) {
        setSession(null);
        router.replace(LOGIN_ROUTE as any);
        return;
      }
      setSession(prev => (prev?.role === user.role ? prev : { role: user.role }));
    }
    verify();
    return () => { cancelled = true; };
  }, [path, router]);

  // The navigator always stays mounted (redirects need it; rendering a loader in its place made expo-router fall
  // back to the first route, /dashboard/call/undefined). Screens are gated individually by RoleGate instead.
  // The role homes are declared first so any fallback lands on a gated screen, never on the call route.
  return (
    <Stack
      screenOptions={{ headerShown: false }}
      screenLayout={({ route, children }) => (
        <RoleGate routeName={route.name} session={session}>{children}</RoleGate>
      )}
    >
      <Stack.Screen name="patient/index" />
      <Stack.Screen name="doctor/index" />
      <Stack.Screen name="proxy/index" />
      <Stack.Screen name="patient/appointments/book" />
      <Stack.Screen name="patient/appointments/status" />
      <Stack.Screen name="call/[appointmentId]" />
    </Stack>
  );
}

const styles = StyleSheet.create({
  loader: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: '#F9FAFB' },
});
