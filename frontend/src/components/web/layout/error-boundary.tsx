import { Ionicons } from '@expo/vector-icons';
import { type Href, router } from 'expo-router';
import { Component, type ErrorInfo, type ReactNode, useEffect, useState } from 'react';
import { Platform, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { getUser, isAuthenticated } from '@/lib/auth/auth.storage';
import { ROLE_HOME, isAppRole } from '@/lib/auth/auth.types';

const TEXT   = '#111827';
const GRAY   = '#4B5563';
const WHITE  = '#FFFFFF';
const BORDER = '#D1D5DB';
const RED    = '#B91C1C';
const RED_BG = '#FEF2F2';

const HIDDEN = { 'aria-hidden': true } as object;
const H1 = { 'aria-level': 1 } as object;

export interface ErrorFallbackProps {
  /** Tries again: reloads the page on web, re-renders the failed part elsewhere. */
  onReload: () => void;
  /** Leaves the failed screen. Omit when there is nowhere better to go (the failure is on the home screen). */
  onHome?: () => void;
  homeLabel?: string;
  /** Colour of the primary button (the role accent). */
  accent?: string;
  /** Fills the whole window (route-level failures) instead of the section area. */
  fullScreen?: boolean;
}

/** "Something went wrong" state shown in place of a screen that failed to render. */
export function ErrorFallback({ onReload, onHome, homeLabel = 'Back to dashboard', accent = '#0F766E', fullScreen }: ErrorFallbackProps) {
  return (
    <View style={[styles.wrap, fullScreen && styles.fullScreen]} accessibilityRole="alert" aria-live="assertive">
      <View style={styles.card}>
        <View style={styles.iconWrap} importantForAccessibility="no-hide-descendants" {...HIDDEN}>
          <Ionicons aria-hidden name="alert-circle-outline" size={28} color={RED} />
        </View>
        {/* Shown in place of the screen, so it is the page's main heading. */}
        <Text style={styles.title} accessibilityRole="header" {...H1}>Something went wrong</Text>
        <Text style={styles.message}>
          This screen could not be shown. Your information is safe. Reload to try again{onHome ? ', or go back to your dashboard' : ''}.
        </Text>
        <View style={styles.actions}>
          <TouchableOpacity
            style={[styles.btn, { backgroundColor: accent, borderColor: accent }]}
            onPress={onReload}
            activeOpacity={0.8}
            accessibilityRole="button"
          >
            <Ionicons name="refresh" size={16} color={WHITE} importantForAccessibility="no" {...HIDDEN} />
            <Text style={[styles.btnText, { color: WHITE }]}>Reload</Text>
          </TouchableOpacity>
          {onHome && (
            <TouchableOpacity
              style={[styles.btn, styles.btnSecondary]}
              onPress={onHome}
              activeOpacity={0.8}
              accessibilityRole="button"
            >
              <Text style={[styles.btnText, { color: TEXT }]}>{homeLabel}</Text>
            </TouchableOpacity>
          )}
        </View>
      </View>
    </View>
  );
}

interface BoundaryProps {
  children: ReactNode;
  /** The boundary clears its error when this value changes (for example the active section key). */
  resetKey?: string;
  onHome?: () => void;
  homeLabel?: string;
  accent?: string;
}

interface BoundaryState {
  failed:   boolean;
  resetKey: string | undefined;
}

/**
 * Keeps a render error inside one section: the sidebar and top bar stay usable and the person can reload or
 * leave, instead of the whole dashboard going blank.
 */
export class ErrorBoundary extends Component<BoundaryProps, BoundaryState> {
  state: BoundaryState = { failed: false, resetKey: this.props.resetKey };

  static getDerivedStateFromError(): Partial<BoundaryState> {
    return { failed: true };
  }

  static getDerivedStateFromProps(props: BoundaryProps, state: BoundaryState): Partial<BoundaryState> | null {
    return props.resetKey !== state.resetKey ? { failed: false, resetKey: props.resetKey } : null;
  }

  componentDidCatch(error: unknown, info: ErrorInfo): void {
    // Only the error type and where it happened: messages and props can contain personal or clinical data.
    const name  = error instanceof Error ? error.name : typeof error;
    const where = (info.componentStack ?? '').split('\n').map(line => line.trim()).filter(Boolean)[0] ?? 'unknown component';
    console.error(`[ErrorBoundary] ${name} while rendering (${where})`);
  }

  private reload = () => {
    // A full reload also recovers from a failed code download; sections are URL-addressable, so it lands here again.
    if (Platform.OS === 'web' && typeof window !== 'undefined') {
      window.location.reload();
      return;
    }
    this.setState({ failed: false });
  };

  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <ErrorFallback
        onReload={this.reload}
        onHome={this.props.onHome}
        homeLabel={this.props.homeLabel}
        accent={this.props.accent}
      />
    );
  }
}

/**
 * Fallback for the `ErrorBoundary` a route layout exports (expo-router renders it in place of the layout when
 * anything below it throws while rendering). Offers Reload and, for a signed-in person, their dashboard.
 */
export function RouteErrorFallback({ retry }: { retry: () => Promise<void> | void }) {
  const [home, setHome] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([isAuthenticated(), getUser()])
      .then(([authed, user]) => {
        if (!cancelled && authed && user && isAppRole(user.role)) setHome(ROLE_HOME[user.role]);
      })
      .catch(() => { /* no stored session: only Reload is offered */ });
    return () => { cancelled = true; };
  }, []);

  const onWeb = Platform.OS === 'web' && typeof window !== 'undefined';

  function reload() {
    if (onWeb) window.location.reload();
    else void retry();
  }

  function goHome() {
    if (!home) return;
    // On web a full load of the dashboard also clears whatever state caused the failure.
    if (onWeb) {
      window.location.assign(home);
      return;
    }
    router.replace(home as Href);
    void retry();
  }

  return <ErrorFallback fullScreen onReload={reload} onHome={home ? goHome : undefined} />;
}

const styles = StyleSheet.create({
  wrap:       { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24, minWidth: 0 },
  fullScreen: { backgroundColor: '#F9FAFB' },
  card: {
    width: '100%', maxWidth: 440, alignItems: 'center',
    backgroundColor: WHITE, borderRadius: 14, borderWidth: 1, borderColor: '#E5E7EB',
    paddingVertical: 28, paddingHorizontal: 24,
  },
  iconWrap: {
    width: 52, height: 52, borderRadius: 26, backgroundColor: RED_BG,
    alignItems: 'center', justifyContent: 'center', marginBottom: 14,
  },
  title:   { fontSize: 18, fontWeight: '700', color: TEXT, textAlign: 'center' },
  message: { fontSize: 14, lineHeight: 20, color: GRAY, textAlign: 'center', marginTop: 8 },
  actions: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'center', gap: 10, marginTop: 20 },
  btn: {
    flexDirection: 'row', alignItems: 'center', gap: 8,
    paddingVertical: 10, paddingHorizontal: 18, borderRadius: 8, borderWidth: 1, minHeight: 40,
  },
  btnSecondary: { backgroundColor: WHITE, borderColor: BORDER },
  btnText:      { fontSize: 14, fontWeight: '600' },
});
