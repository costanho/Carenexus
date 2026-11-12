import { Ionicons } from '@expo/vector-icons';
import { type Href, Link, usePathname } from 'expo-router';
import Head from 'expo-router/head';
import { useEffect, useState } from 'react';
import { ActivityIndicator, Platform, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useLogout } from '@/hooks/use-logout';
import { getSessionFlags, getUser, isAuthenticated } from '@/lib/auth/auth.storage';
import { ROLE_HOME, isAppRole, requiredAccountStep } from '@/lib/auth/auth.types';

const LOGIN_ROUTE = Platform.OS === 'web'
  ? '/auth/login/web/login-web'
  : '/auth/login/mobile/login-mobile';

/** Where the page can lead: `href` is null when no link would get the person anywhere but back here. */
interface Destination { href: string | null; label: string; signedIn: boolean }

const SIGN_IN: Destination = { href: LOGIN_ROUTE, label: 'Go to sign in', signedIn: false };

/**
 * Shown for any URL that matches no route: says so and links to the person's dashboard (or to sign in).
 * Someone who is signed in can also sign out from here, so the page is never a dead end.
 */
export default function NotFoundScreen() {
  const pathname = usePathname();
  const { handleLogout, loading: signingOut } = useLogout();
  const [destination, setDestination] = useState<Destination | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([isAuthenticated(), getUser(), getSessionFlags()])
      .then(([authed, user, flags]) => {
        if (cancelled) return;
        if (!authed || !user || !isAppRole(user.role)) { setDestination(SIGN_IN); return; }
        // The dashboard sends a session with a pending account step (password change, updated terms) to that
        // step. If that step is the page that was not found, the dashboard link would only lead back here,
        // so it is left out and signing out is the way on.
        const loops = requiredAccountStep(flags) === pathname;
        setDestination({ href: loops ? null : ROLE_HOME[user.role], label: 'Back to dashboard', signedIn: true });
      })
      .catch(() => { if (!cancelled) setDestination(SIGN_IN); });
    return () => { cancelled = true; };
  }, [pathname]);

  return (
    <View style={styles.screen}>
      <Head><title>Page not found | CareNexus</title></Head>
      <View style={styles.card}>
        <View style={styles.iconWrap} importantForAccessibility="no-hide-descendants" {...({ 'aria-hidden': true } as object)}>
          <Ionicons name="compass-outline" size={30} color="#0F766E" />
        </View>
        <Text style={styles.title} accessibilityRole="header">Page not found</Text>
        <Text style={styles.message}>
          The page you are looking for does not exist or has moved. Check the address, or go back to CareNexus.
        </Text>
        {destination ? (
          <>
            {destination.href !== null && (
              <Link href={destination.href as Href} replace style={styles.link}>
                {destination.label}
              </Link>
            )}
            {destination.signedIn && (
              <TouchableOpacity
                style={styles.signOut}
                onPress={handleLogout}
                disabled={signingOut}
                activeOpacity={0.7}
                accessibilityRole="button"
                accessibilityState={{ disabled: signingOut, busy: signingOut }}
                aria-busy={signingOut}
              >
                <Text style={styles.signOutText}>{signingOut ? 'Signing out…' : 'Sign out'}</Text>
              </TouchableOpacity>
            )}
          </>
        ) : (
          <ActivityIndicator size="small" color="#0F766E" accessibilityLabel="Loading" style={styles.spinner} />
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24, backgroundColor: '#F9FAFB' },
  card: {
    width: '100%', maxWidth: 440, alignItems: 'center',
    backgroundColor: '#FFFFFF', borderRadius: 14, borderWidth: 1, borderColor: '#E5E7EB',
    paddingVertical: 32, paddingHorizontal: 24,
  },
  iconWrap: {
    width: 56, height: 56, borderRadius: 28, backgroundColor: '#E8F5F4',
    alignItems: 'center', justifyContent: 'center', marginBottom: 14,
  },
  title:   { fontSize: 20, fontWeight: '700', color: '#111827', textAlign: 'center' },
  message: { fontSize: 14, lineHeight: 21, color: '#4B5563', textAlign: 'center', marginTop: 8 },
  link: {
    marginTop: 20, paddingVertical: 11, paddingHorizontal: 20, borderRadius: 8, overflow: 'hidden',
    backgroundColor: '#0F766E', color: '#FFFFFF', fontSize: 14, fontWeight: '600', textAlign: 'center',
  },
  signOut:     { marginTop: 14, paddingVertical: 8, paddingHorizontal: 12, borderRadius: 8 },
  signOutText: { fontSize: 14, fontWeight: '600', color: '#0F766E', textAlign: 'center' },
  spinner: { marginTop: 24 },
});
