import { Stack, useRouter, useSegments } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Platform, StyleSheet, View } from 'react-native';
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

  useEffect(() => {
    isAuthenticated().then(setAuthed);
  }, []);

  useEffect(() => {
    if (authed === null) return;
    if (didRedirect.current) return;

    const inAuthGroup      = segments[0] === 'auth';
    const inDashboardGroup = segments[0] === 'dashboard';

    if (!authed && !inAuthGroup) {
      didRedirect.current = true;
      router.replace(LOGIN_ROUTE as any);
    } else if (authed && (inAuthGroup || !inDashboardGroup)) {
      // Authenticated but not yet on a dashboard — send to role dashboard
      getUser().then(user => {
        if (!user) {
          router.replace(LOGIN_ROUTE as any);
          return;
        }
        didRedirect.current = true;
        router.replace(ROLE_HOME[user.role] as any);
      });
    }
  }, [authed, segments]);

  if (authed === null) {
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
