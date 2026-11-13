import { type ErrorBoundaryProps, Stack, useRouter, useSegments } from 'expo-router';
import Head from 'expo-router/head';
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Platform, StyleSheet, View } from 'react-native';
import { RouteErrorFallback } from '@/components/web/layout/error-boundary';
import { onSessionExpired } from '@/lib/auth/auth.events';
import { getUser, isAuthenticated } from '@/lib/auth/auth.storage';
import { ROLE_HOME } from '@/lib/auth/auth.types';
import { rememberReturnTo } from '@/lib/nav/return-to';

const LOGIN_ROUTE = Platform.OS === 'web'
  ? '/auth/login/web/login-web'
  : '/auth/login/mobile/login-mobile';

/**
 * Route groups that can be opened without a session: everything under /auth (sign in, register and the links
 * sent by email: forgot-password, reset-password, verify-email, confirm-email-change, change-password),
 * the legal pages (/legal/terms, /legal/privacy) and the not-found page.
 */
const PUBLIC_SEGMENTS = new Set(['auth', 'legal', '+not-found']);

/** Sign in and registration: the only public screens a signed-in person is sent away from. */
function isSignInRoute(segments: readonly string[]): boolean {
  return segments[0] === 'auth' && (segments[1] === 'login' || segments[1] === 'register');
}

/**
 * Shown by expo-router in place of the app when a screen throws while rendering and nothing closer caught it
 * (dashboard sections have their own boundary inside the shell), instead of a blank page.
 */
export function ErrorBoundary({ retry }: ErrorBoundaryProps) {
  return <RouteErrorFallback retry={retry} />;
}

export default function RootLayout() {
  const router   = useRouter();
  const segments = useSegments() as readonly string[];
  const [authed, setAuthed] = useState<boolean | null>(null);
  const didRedirect = useRef(false);

  // When the API layer can no longer refresh the session, go back to login.
  useEffect(() => {
    return onSessionExpired(() => {
      setAuthed(false);
      didRedirect.current = true;
      rememberReturnTo(); // signing in again returns to the section that was open
      router.replace(LOGIN_ROUTE as any);
    });
  }, [router]);

  useEffect(() => {
    isAuthenticated().then(setAuthed);
  }, [segments]);

  useEffect(() => {
    if (authed === null) return;

    const isPublic         = PUBLIC_SEGMENTS.has(segments[0]);
    const inDashboardGroup = segments[0] === 'dashboard';

    // Once a signed-out user is on a public screen (login, register, an emailed link, a legal page),
    // allow the guard to run again.
    if (isPublic && !authed) {
      didRedirect.current = false;
      return;
    }
    if (didRedirect.current) return;

    if (!authed) {
      // `authed` is refreshed asynchronously when the segments change, so it can still hold the value from before
      // sign-in when the sign-in page navigates to the dashboard (or to a remembered section). Check the stored
      // session again before sending anyone to sign in, and drop the result if the route changed meanwhile.
      let cancelled = false;
      isAuthenticated().then(ok => {
        if (cancelled || didRedirect.current) return;
        if (ok) {
          setAuthed(true);
          return;
        }
        didRedirect.current = true;
        // A link to a dashboard section opened while signed out: open it after sign-in.
        if (inDashboardGroup) rememberReturnTo();
        router.replace(LOGIN_ROUTE as any);
      });
      return () => { cancelled = true; };
    } else if (isSignInRoute(segments) || (!inDashboardGroup && !isPublic)) {
      // Authenticated but on sign in / register or the index route: send to the role dashboard.
      // Other public screens stay open for a signed-in person (changing a password, reading the terms).
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
        <ActivityIndicator size="large" color="#0F766E" accessibilityLabel="Loading" />
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
