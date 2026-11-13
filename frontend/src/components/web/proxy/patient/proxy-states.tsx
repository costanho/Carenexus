import { ActivityIndicator, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { decorative } from '@/components/web/ui/a11y';
import { Icon, type IoniconsName } from '@/components/web/ui/icon';
import { NO_ACCESS_MESSAGE } from '@/hooks/use-caregiver-dependents';

// Shared loading / error / empty states for the older caregiver (proxy) screens, web and native. New screens use
// the primitives in @/components/web/ui instead. Icons are decorative (`Icon` hides them from assistive
// technology on web and native); the words next to them carry the meaning.

const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const PURPLE = '#7C3AED';
const RED    = '#B91C1C';
const ORANGE = '#B45309';

// The wrapper is a polite live region named by `label` (same pattern as LoadingView in @/components/web/ui).
// The spinner is decorative: on web react-native-web renders it as its own unnamed role="progressbar", which
// axe reports as aria-progressbar-name.
export function LoadingState({ label = 'Loading…', compact }: { label?: string; compact?: boolean }) {
  return (
    <View style={[styles.wrap, compact && styles.compact]} role="status" aria-live="polite" accessibilityLabel={label}>
      <View {...decorative}>
        <ActivityIndicator size={compact ? 'small' : 'large'} color={PURPLE} />
      </View>
    </View>
  );
}

export function ErrorState({ message, onRetry, compact }: { message: string; onRetry?: () => void; compact?: boolean }) {
  const noAccess = message === NO_ACCESS_MESSAGE;
  return (
    <View style={[styles.wrap, compact && styles.compact]} accessibilityRole="alert">
      <Icon
        name={noAccess ? 'lock-closed-outline' : 'alert-circle-outline'}
        size={compact ? 24 : 40}
        color={noAccess ? ORANGE : RED}
      />
      <Text style={styles.msg}>{message}</Text>
      {noAccess && (
        <Text style={styles.sub}>Ask your loved one to update the permissions on your caregiver access.</Text>
      )}
      {onRetry && !noAccess && (
        <TouchableOpacity
          style={styles.retryBtn}
          onPress={onRetry}
          activeOpacity={0.8}
          accessibilityRole="button"
          accessibilityLabel="Retry"
        >
          <Icon name="refresh-outline" size={14} color={PURPLE} />
          <Text style={styles.retryText}>Retry</Text>
        </TouchableOpacity>
      )}
    </View>
  );
}

export function EmptyState({ icon, title, sub, compact }: { icon: IoniconsName; title: string; sub?: string; compact?: boolean }) {
  return (
    <View style={[styles.wrap, compact && styles.compact]}>
      <Icon name={icon} size={compact ? 24 : 40} color={BORDER} />
      <Text style={styles.msg}>{title}</Text>
      {sub ? <Text style={styles.sub}>{sub}</Text> : null}
    </View>
  );
}

/**
 * No web screen uses this any more (every caregiver section is real). It stays only because the native care
 * plans placeholder (components/mobile/proxy/patient/patient-proxy-mobile-care-plans.tsx) still imports it.
 */
export function ComingSoonState({ icon, title, sub }: { icon: IoniconsName; title: string; sub: string }) {
  return (
    <View style={styles.wrap}>
      <View style={styles.soonIcon}>
        <Icon name={icon} size={30} color={PURPLE} />
      </View>
      <Text style={styles.soonTitle}>{title}</Text>
      <Text style={styles.sub}>{sub}</Text>
      <View style={styles.soonPill}>
        <Text style={styles.soonPillText}>Coming soon</Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap:      { alignItems: 'center', justifyContent: 'center', paddingVertical: 60, paddingHorizontal: 16, gap: 10 },
  compact:   { paddingVertical: 16 },
  msg:       { fontSize: 14, color: GRAY, textAlign: 'center' },
  sub:       { fontSize: 12, color: GRAY, textAlign: 'center', maxWidth: 420, lineHeight: 18 },
  retryBtn:  { flexDirection: 'row', alignItems: 'center', gap: 6, borderWidth: 1, borderColor: PURPLE, borderRadius: 8, paddingHorizontal: 14, paddingVertical: 7, marginTop: 4 },
  retryText: { fontSize: 13, color: PURPLE, fontWeight: '600' },

  soonIcon:     { width: 64, height: 64, borderRadius: 32, backgroundColor: '#F5F3FF', alignItems: 'center', justifyContent: 'center' },
  soonTitle:    { fontSize: 16, fontWeight: '700', color: TEXT, textAlign: 'center' },
  soonPill:     { backgroundColor: '#EDE9FE', borderRadius: 20, paddingHorizontal: 8, paddingVertical: 2 },
  soonPillText: { fontSize: 10, fontWeight: '700', color: PURPLE },
});
