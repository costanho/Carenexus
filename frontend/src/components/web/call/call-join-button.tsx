import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import React from 'react';
import { Text, TouchableOpacity, type StyleProp, type TextStyle, type ViewStyle } from 'react-native';
import type { CallJoinInfo } from '@/lib/calls/join';

/**
 * Join control for appointment lists: opens the internal call screen (callJoinRoute).
 * Renders nothing when `info` is null. Styling comes from the host component.
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
  if (!info?.route) return null;
  const route = info.route;
  return (
    <TouchableOpacity
      style={props.style}
      activeOpacity={0.8}
      onPress={() => router.push(route as any)}
      accessibilityRole="button"
      accessibilityLabel={props.withName ? `${info.label}: video call with ${props.withName}` : `${info.label}: video call`}
    >
      <Ionicons name={props.icon ?? 'videocam-outline'} size={props.iconSize ?? 14} color={props.iconColor ?? '#4F46E5'} />
      <Text style={props.textStyle}>{info.label}</Text>
    </TouchableOpacity>
  );
}
