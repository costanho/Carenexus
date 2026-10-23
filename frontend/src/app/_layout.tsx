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
    console.log('🔄 Checking auth status due to segments change:', segments);
    isAuthenticated().then(auth => {
      console.log('✅ isAuthenticated returned:', auth);
      setAuthed(auth);
    });
  }, [segments]);

  useEffect(() => {
    console.log('🛡️ Auth guard effect running:', { authed, segments, didRedirect: didRedirect.current });
    if (authed === null) {
      console.log('⏳ authed is null, waiting...');
      return;
    }
    if (didRedirect.current) {
      console.log('⏭️ Already redirected, skipping');
      return;
    }

    const inAuthGroup      = segments[0] === 'auth';
    const inDashboardGroup = segments[0] === 'dashboard';
    console.log('📍 Route check:', { inAuthGroup, inDashboardGroup, currentSegments: segments });

    if (!authed && !inAuthGroup) {
      console.log('🔴 Not authenticated and not in auth group → going to login');
      didRedirect.current = true;
      router.replace(LOGIN_ROUTE as any);
    } else if (authed && (inAuthGroup || !inDashboardGroup)) {
      console.log('🟡 Authenticated but in auth group or not on dashboard → fetching user...');
      // Authenticated but not yet on a dashboard — send to role dashboard
      getUser().then(user => {
        console.log('👤 User from storage:', user);
        if (!user) {
          console.log('❌ No user found → going to login');
          router.replace(LOGIN_ROUTE as any);
          return;
        }
        const targetRoute = ROLE_HOME[user.role];
        console.log('🟢 Redirecting to role dashboard:', targetRoute);
        didRedirect.current = true;
        router.replace(targetRoute as any);
      });
    } else {
      console.log('✅ No redirect needed');
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
