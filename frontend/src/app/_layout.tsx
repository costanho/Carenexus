import { Stack, useRouter, useSegments } from 'expo-router';
import Head from 'expo-router/head';
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Platform, StyleSheet, View } from 'react-native';
import { onSessionExpired } from '@/lib/auth/auth.events';
import { getUser, isAuthenticated } from '@/lib/auth/auth.storage';
import { ROLE_HOME } from '@/lib/auth/auth.types';

const LOGIN_ROUTE = Platform.OS === 'web'
  ? '/auth/login/web/login-web'
  : '/auth/login/mobile/login-mobile';

export default function RootLayout() {
  const router   = useRouter();
  const segments = useSegments();
  const [authed, setAuthed] = useState<boolean | null>(null);
  const didRedirect = useRef(false);

  // When the API layer can no longer refresh the session, go back to login.
  useEffect(() => {
    return onSessionExpired(() => {
      setAuthed(false);
      didRedirect.current = true;
      router.replace(LOGIN_ROUTE as any);
    });
  }, [router]);

  useEffect(() => {
    isAuthenticated().then(setAuthed);
  }, [segments]);

  useEffect(() => {
    if (authed === null) return;

    const inAuthGroup      = segments[0] === 'auth';
    const inDashboardGroup = segments[0] === 'dashboard';

    // Once the user is back on the login/register screens, allow the guard to run again.
    if (inAuthGroup && !authed) {
      didRedirect.current = false;
      return;
    }
    if (didRedirect.current) return;

    if (!authed && !inAuthGroup) {
      didRedirect.current = true;
      router.replace(LOGIN_ROUTE as any);
    } else if (authed && (inAuthGroup || !inDashboardGroup)) {
      // Authenticated but not yet on a dashboard — send to role dashboard
      getUser().then(user => {
        const targetRoute = user ? ROLE_HOME[user.role] : undefined;
        didRedirect.current = true;
        router.replace((targetRoute ?? LOGIN_ROUTE) as any);
      });
    }
  }, [authed, segments]);

  // Every page needs a non-empty document title (screens may set a more specific one).
  const head = <Head><title>CareNexus</title></Head>;

  if (authed === null) {
    return (
      <View style={styles.loader}>
        {head}
        <ActivityIndicator size="large" color="#0D9488" accessibilityLabel="Loading" />
      </View>
    );
  }

  return (
    <>
      {head}
      <Stack screenOptions={{ headerShown: false }} />
    </>
  );
}

const styles = StyleSheet.create({
  loader: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: '#F9FAFB' },
});
