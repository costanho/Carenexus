import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import React from 'react';
import { StyleSheet, Text, TouchableOpacity, View, type StyleProp, type TextStyle, type ViewStyle } from 'react-native';
import { isCallRoute, type CallJoinInfo } from '@/lib/calls/join';

/**
 * Join control for appointment lists: opens the internal call screen (callJoinRoute).
 * Renders nothing when `info` is null. Styling comes from the host component.
 * An informational state (e.g. "Time to be confirmed" for patients) renders as a neutral status label, never as
 * a button, and the host's button style is not applied to it.
 */
export function CallJoinButton(props: {
  info:       CallJoinInfo | null;
  /** Who/what the call is with, for the accessibility label. */
  withName?:  string | null;
  style?:     StyleProp<ViewStyle>;
  textStyle?: StyleProp<TextStyle>;
  iconColor?: string;
  iconSize?:  number;
  icon?:      React.ComponentProps<typeof Ionicons>['name'];
}) {
  const router = useRouter();
  const { info } = props;
  if (!info) return null;

  if (info.informational) {
    return (
      <View
        style={styles.status}
        accessibilityRole="text"
        accessibilityLabel={props.withName ? `Video call with ${props.withName}: ${info.label}` : `Video call: ${info.label}`}
      >
        <Ionicons aria-hidden name="time-outline" size={props.iconSize ?? 14} color={STATUS_FG} />
        <Text style={styles.statusText}>{info.label}</Text>
      </View>
    );
  }

  if (!isCallRoute(info.route)) return null;
  const route = info.route;
  return (
    <TouchableOpacity
      style={props.style}
      activeOpacity={0.8}
      onPress={() => router.push(route as any)}
      accessibilityRole="button"
      accessibilityLabel={props.withName ? `${info.label}: video call with ${props.withName}` : `${info.label}: video call`}
    >
      <Ionicons aria-hidden name={props.icon ?? 'videocam-outline'} size={props.iconSize ?? 14} color={props.iconColor ?? '#4F46E5'} />
      <Text style={props.textStyle}>{info.label}</Text>
    </TouchableOpacity>
  );
}

const STATUS_FG = '#4B5563';

const styles = StyleSheet.create({
  status:     { flexDirection: 'row', alignItems: 'center', gap: 6, alignSelf: 'flex-start', paddingHorizontal: 10, paddingVertical: 6, borderRadius: 8, backgroundColor: '#F3F4F6' },
  statusText: { fontSize: 13, fontWeight: '600', color: STATUS_FG },
});
