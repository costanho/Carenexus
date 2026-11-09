import { Ionicons } from '@expo/vector-icons';
import { ActivityIndicator, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { NO_ACCESS_MESSAGE } from '@/hooks/use-caregiver-dependents';

// Shared loading / error / empty / "coming soon" states for caregiver (proxy) screens.

const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const PURPLE = '#7C3AED';
const RED    = '#B91C1C';
const ORANGE = '#B45309';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

export function LoadingState({ label = 'Loading…', compact }: { label?: string; compact?: boolean }) {
  return (
    <View style={[styles.wrap, compact && styles.compact]} accessibilityRole="progressbar" accessibilityLabel={label}>
      <ActivityIndicator size={compact ? 'small' : 'large'} color={PURPLE} />
    </View>
  );
}

export function ErrorState({ message, onRetry, compact }: { message: string; onRetry?: () => void; compact?: boolean }) {
  const noAccess = message === NO_ACCESS_MESSAGE;
  return (
    <View style={[styles.wrap, compact && styles.compact]} accessibilityRole="alert">
      <Ionicons
        name={noAccess ? 'lock-closed-outline' : 'alert-circle-outline'}
        size={compact ? 24 : 40}
        color={noAccess ? ORANGE : RED}
        importantForAccessibility="no"
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
          <Ionicons name="refresh-outline" size={14} color={PURPLE} importantForAccessibility="no" />
          <Text style={styles.retryText}>Retry</Text>
        </TouchableOpacity>
      )}
    </View>
  );
}

export function EmptyState({ icon, title, sub, compact }: { icon: IoniconsName; title: string; sub?: string; compact?: boolean }) {
  return (
    <View style={[styles.wrap, compact && styles.compact]}>
      <Ionicons name={icon} size={compact ? 24 : 40} color={BORDER} importantForAccessibility="no" />
      <Text style={styles.msg}>{title}</Text>
      {sub ? <Text style={styles.sub}>{sub}</Text> : null}
    </View>
  );
}

export function ComingSoonState({ icon, title, sub }: { icon: IoniconsName; title: string; sub: string }) {
  return (
    <View style={styles.wrap}>
      <View style={styles.soonIcon}>
        <Ionicons name={icon} size={30} color={PURPLE} importantForAccessibility="no" />
      </View>
      <Text style={styles.soonTitle}>{title}</Text>
      <Text style={styles.sub}>{sub}</Text>
      <View style={styles.soonPill}>
        <Text style={styles.soonPillText}>Coming soon</Text>
      </View>
    </View>
  );
}

export function SoonPill() {
  return (
    <View style={styles.soonPill}>
      <Text style={styles.soonPillText}>Coming soon</Text>
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
