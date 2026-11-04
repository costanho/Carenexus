import { Ionicons } from '@expo/vector-icons';
import React from 'react';
import { ActivityIndicator, StyleSheet, Text, TouchableOpacity, View, type StyleProp, type ViewStyle } from 'react-native';

// Small shared building blocks for the call screens (no LiveKit imports here).

export const C = {
  indigo:   '#4F46E5',
  indigoBg: '#EEF2FF',
  red:      '#B91C1C',
  redBg:    '#FEF2F2',
  amber:    '#B45309',
  amberBg:  '#FFFBEB',
  green:    '#047857',
  greenBg:  '#ECFDF5',
  text:     '#111827',
  muted:    '#4B5563',
  faint:    '#5B6472',
  border:   '#E5E7EB',
  surface:  '#FFFFFF',
  page:     '#F3F4F6',
  dark:     '#0F172A',
  darkTile: '#1E293B',
};

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

export function CallButton(props: {
  label:    string;
  onPress:  () => void;
  variant?: 'primary' | 'secondary' | 'danger' | 'ghost';
  icon?:    IoniconsName;
  disabled?: boolean;
  busy?:    boolean;
  accessibilityLabel?: string;
  style?:   StyleProp<ViewStyle>;
}) {
  const v = props.variant ?? 'primary';
  const bg = v === 'primary' ? C.indigo : v === 'danger' ? C.red : v === 'secondary' ? C.surface : 'transparent';
  const fg = v === 'primary' || v === 'danger' ? '#FFFFFF' : C.indigo;
  const disabled = props.disabled || props.busy;
  return (
    <TouchableOpacity
      onPress={props.onPress}
      disabled={disabled}
      accessibilityRole="button"
      accessibilityLabel={props.accessibilityLabel ?? props.label}
      accessibilityState={{ disabled: !!disabled, busy: !!props.busy }}
      aria-busy={!!props.busy}
      style={[
        styles.btn,
        { backgroundColor: bg, borderColor: v === 'secondary' ? C.border : bg },
        disabled && styles.btnDisabled,
        props.style,
      ]}
    >
      {props.busy
        ? <ActivityIndicator size="small" color={fg} accessibilityLabel="Please wait" />
        : props.icon ? <Ionicons name={props.icon} size={16} color={fg} /> : null}
      <Text style={[styles.btnText, { color: fg }]}>{props.label}</Text>
    </TouchableOpacity>
  );
}

export function Notice(props: {
  tone?:   'info' | 'warning' | 'error' | 'success';
  title?:  string;
  children?: React.ReactNode;
  icon?:   IoniconsName;
}) {
  const tone = props.tone ?? 'info';
  const palette = {
    info:    { bg: C.indigoBg, fg: C.indigo, icon: 'information-circle-outline' as IoniconsName },
    warning: { bg: C.amberBg,  fg: C.amber,  icon: 'alert-circle-outline' as IoniconsName },
    error:   { bg: C.redBg,    fg: C.red,    icon: 'close-circle-outline' as IoniconsName },
    success: { bg: C.greenBg,  fg: C.green,  icon: 'checkmark-circle-outline' as IoniconsName },
  }[tone];
  return (
    <View
      style={[styles.notice, { backgroundColor: palette.bg }]}
      accessibilityRole={tone === 'error' ? 'alert' : undefined}
      accessibilityLiveRegion="polite"
    >
      <Ionicons name={props.icon ?? palette.icon} size={18} color={palette.fg} style={{ marginTop: 1 }} />
      <View style={{ flex: 1, gap: 2 }}>
        {props.title ? <Text style={[styles.noticeTitle, { color: palette.fg }]}>{props.title}</Text> : null}
        {typeof props.children === 'string'
          ? <Text style={styles.noticeBody}>{props.children}</Text>
          : props.children}
      </View>
    </View>
  );
}

export function Card(props: { children: React.ReactNode; style?: StyleProp<ViewStyle> }) {
  return <View style={[styles.card, props.style]}>{props.children}</View>;
}

export function CenteredMessage(props: {
  icon:     IoniconsName;
  title:    string;
  body?:    string;
  children?: React.ReactNode;
}) {
  return (
    <View style={styles.centered}>
      <Card style={styles.centeredCard}>
        <Ionicons name={props.icon} size={36} color={C.indigo} />
        <Text style={styles.centeredTitle} accessibilityRole="header">{props.title}</Text>
        {props.body ? <Text style={styles.centeredBody}>{props.body}</Text> : null}
        {props.children}
      </Card>
    </View>
  );
}

const styles = StyleSheet.create({
  btn:          { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8, borderWidth: 1, borderRadius: 10, paddingHorizontal: 16, paddingVertical: 10, minHeight: 44 },
  btnDisabled:  { opacity: 0.5 },
  btnText:      { fontSize: 14, fontWeight: '600' },
  notice:       { flexDirection: 'row', gap: 10, borderRadius: 10, padding: 12 },
  noticeTitle:  { fontSize: 14, fontWeight: '700' },
  noticeBody:   { fontSize: 13, color: C.muted, lineHeight: 19 },
  card:         { backgroundColor: C.surface, borderRadius: 14, borderWidth: 1, borderColor: C.border, padding: 16, gap: 12 },
  centered:     { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 16 },
  centeredCard: { width: '100%', maxWidth: 460, alignItems: 'center' },
  centeredTitle:{ fontSize: 18, fontWeight: '700', color: C.text, textAlign: 'center' },
  centeredBody: { fontSize: 14, color: C.muted, textAlign: 'center', lineHeight: 20 },
});
