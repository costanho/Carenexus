import { Ionicons } from '@expo/vector-icons';
import { createContext, useContext } from 'react';
import { ActivityIndicator, StyleSheet, Text, TouchableOpacity, View } from 'react-native';

// Small shared pieces for the messages + notifications screens.

export const TEXT    = '#111827';
export const GRAY    = '#5B6472';
export const LIGHT   = '#636A75';
export const BORDER  = '#E5E7EB';
export const BG      = '#F9FAFB';
export const WHITE   = '#FFFFFF';
export const RED     = '#B91C1C';
export const RED_BG  = '#FEF2F2';
export const AMBER   = '#B45309';
export const AMBER_BG = '#FFFBEB';

export interface MessagesTheme {
  accent:   string;
  accentBg: string;
}

export const DEFAULT_THEME: MessagesTheme = { accent: '#0F766E', accentBg: '#E8F5F4' };

export const ThemeContext = createContext<MessagesTheme>(DEFAULT_THEME);
export function useTheme(): MessagesTheme {
  return useContext(ThemeContext);
}

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

export function Loading({ label = 'Loading…', compact }: { label?: string; compact?: boolean }) {
  const { accent } = useTheme();
  return (
    <View style={[styles.center, compact && styles.compact]}>
      <ActivityIndicator size={compact ? 'small' : 'large'} color={accent} accessibilityLabel={label} />
    </View>
  );
}

export function ErrorBlock({ message, onRetry, compact }: { message: string; onRetry?: () => void; compact?: boolean }) {
  const { accent } = useTheme();
  return (
    <View style={[styles.center, compact && styles.compact]} accessibilityRole="alert">
      <Ionicons aria-hidden name="alert-circle-outline" size={compact ? 22 : 36} color={RED} importantForAccessibility="no" />
      <Text style={styles.msg}>{message}</Text>
      {onRetry && (
        <TouchableOpacity
          style={[styles.outlineBtn, { borderColor: accent }]}
          onPress={onRetry}
          activeOpacity={0.8}
          accessibilityRole="button"
          accessibilityLabel="Retry"
        >
          <Ionicons aria-hidden name="refresh-outline" size={14} color={accent} importantForAccessibility="no" />
          <Text style={[styles.outlineText, { color: accent }]}>Retry</Text>
        </TouchableOpacity>
      )}
    </View>
  );
}

export function Empty({ icon, title, sub, children }: { icon: IoniconsName; title: string; sub?: string; children?: React.ReactNode }) {
  return (
    <View style={styles.center}>
      <Ionicons aria-hidden name={icon} size={40} color={BORDER} importantForAccessibility="no" />
      <Text style={styles.emptyTitle}>{title}</Text>
      {sub ? <Text style={styles.sub}>{sub}</Text> : null}
      {children}
    </View>
  );
}

export function Banner({ tone, icon, text }: { tone: 'warning' | 'danger' | 'info'; icon: IoniconsName; text: string }) {
  const color = tone === 'danger' ? RED : tone === 'warning' ? AMBER : GRAY;
  const bg    = tone === 'danger' ? RED_BG : tone === 'warning' ? AMBER_BG : '#F3F4F6';
  return (
    <View style={[styles.banner, { backgroundColor: bg }]} accessibilityRole="alert" accessibilityLiveRegion="polite">
      <Ionicons aria-hidden name={icon} size={16} color={color} importantForAccessibility="no" />
      <Text style={[styles.bannerText, { color }]}>{text}</Text>
    </View>
  );
}

/**
 * The unread count shown in a conversation row. It is decorative: the row's accessible name already ends with
 * "N unread", and a labelled badge inside the row would make the row's visible text differ from its name.
 * `label` is accepted so existing callers keep compiling; the row's name carries that text.
 */
export function CountBadge({ count, color = RED }: { count: number; color?: string; label?: string }) {
  if (count <= 0) return null;
  return (
    <View
      style={[styles.badge, { backgroundColor: color }]}
      aria-hidden
      importantForAccessibility="no-hide-descendants"
      accessibilityElementsHidden
    >
      <Text style={styles.badgeText}>{count > 99 ? '99+' : count}</Text>
    </View>
  );
}

export function UrgentTag() {
  return (
    <View style={styles.urgentTag}>
      <Ionicons aria-hidden name="alert-circle" size={11} color={RED} importantForAccessibility="no" />
      <Text style={styles.urgentText}>Urgent{' '}</Text>
    </View>
  );
}

/** Round badge with initials, or with `icon` for a conversation that is not about one person (support). */
export function Avatar({ text, color, size = 36, icon }: { text: string; color: string; size?: number; icon?: IoniconsName }) {
  return (
    <View aria-hidden
      style={[styles.avatar, { width: size, height: size, borderRadius: size / 2, backgroundColor: color }]}
      importantForAccessibility="no-hide-descendants"
    >
      {icon
        ? <Ionicons aria-hidden name={icon} size={size > 32 ? 18 : 14} color={WHITE} importantForAccessibility="no" />
        : <Text style={[styles.avatarText, { fontSize: size > 32 ? 13 : 11 }]}>{text}</Text>}
    </View>
  );
}

// White initials need 4.5:1 against every avatar colour.
const AVATAR_COLORS = ['#4F46E5', '#0F766E', '#B45309', '#B91C1C', '#6D28D9', '#047857', '#0369A1'];
export function avatarColor(seed: string): string {
  let h = 0;
  for (let i = 0; i < (seed?.length ?? 0); i++) h = (h * 31 + seed.charCodeAt(i)) & 0xffff;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}

const styles = StyleSheet.create({
  center:      { alignItems: 'center', justifyContent: 'center', paddingVertical: 48, paddingHorizontal: 16, gap: 10, flexGrow: 1 },
  compact:     { paddingVertical: 16, flexGrow: 0 },
  msg:         { fontSize: 14, color: GRAY, textAlign: 'center', maxWidth: 420 },
  emptyTitle:  { fontSize: 15, fontWeight: '600', color: TEXT, textAlign: 'center' },
  sub:         { fontSize: 13, color: GRAY, textAlign: 'center', maxWidth: 420, lineHeight: 19 },
  outlineBtn:  { flexDirection: 'row', alignItems: 'center', gap: 6, borderWidth: 1, borderRadius: 8, paddingHorizontal: 14, paddingVertical: 7, marginTop: 4 },
  outlineText: { fontSize: 13, fontWeight: '600' },
  banner:      { flexDirection: 'row', alignItems: 'center', gap: 8, paddingHorizontal: 14, paddingVertical: 9 },
  bannerText:  { fontSize: 13, fontWeight: '500', flex: 1 },
  badge:       { minWidth: 18, height: 18, borderRadius: 9, paddingHorizontal: 5, alignItems: 'center', justifyContent: 'center' },
  badgeText:   { color: WHITE, fontSize: 10, fontWeight: '700' },
  urgentTag:   { flexDirection: 'row', alignItems: 'center', gap: 3, backgroundColor: RED_BG, borderRadius: 10, paddingHorizontal: 7, paddingVertical: 2, alignSelf: 'flex-start' },
  urgentText:  { fontSize: 10, fontWeight: '700', color: RED, letterSpacing: 0.3 },
  avatar:      { alignItems: 'center', justifyContent: 'center' },
  avatarText:  { color: WHITE, fontWeight: '700' },
});
