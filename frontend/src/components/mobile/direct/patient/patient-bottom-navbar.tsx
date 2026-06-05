import { Ionicons } from '@expo/vector-icons';
import { useState } from 'react';
import { StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

const TEAL  = '#0D9488';
const GRAY  = '#9CA3AF';
const WHITE = '#FFFFFF';
const BORDER = '#E5E7EB';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

type TabItem = { key: string; label: string; icon: IoniconsName; activeIcon: IoniconsName };

const tabs: TabItem[] = [
  { key: 'dashboard',    label: 'Dashboard',    icon: 'home-outline',         activeIcon: 'home' },
  { key: 'appointments', label: 'Appointments', icon: 'calendar-outline',     activeIcon: 'calendar' },
  { key: 'records',      label: 'Records',      icon: 'folder-open-outline',  activeIcon: 'folder-open' },
  { key: 'messages',     label: 'Messages',     icon: 'chatbubble-outline',   activeIcon: 'chatbubble' },
  { key: 'profile',      label: 'Profile',      icon: 'person-outline',       activeIcon: 'person' },
];

export function PatientBottomNavbar() {
  const insets = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();
  const [active, setActive] = useState('dashboard');

  const s = (n: number) => Math.round(n * (width / 390));
  const h = (n: number) => Math.round(n * (height / 844));

  const iconSize  = s(24);
  const fontSize  = s(10);
  const padTop    = h(12);
  const padBottom = insets.bottom || h(16);

  return (
    <View style={[styles.container, { paddingTop: padTop, paddingBottom: padBottom }]}>
      {tabs.map((tab) => {
        const isActive = active === tab.key;
        return (
          <TouchableOpacity
            key={tab.key}
            style={[styles.tab, { gap: s(4) }]}
            activeOpacity={0.7}
            onPress={() => setActive(tab.key)}
          >
            {isActive && (
              <View style={[styles.activeIndicator, { height: s(3), top: -padTop }]} />
            )}
            <Ionicons
              name={isActive ? tab.activeIcon : tab.icon}
              size={iconSize}
              color={isActive ? TEAL : GRAY}
            />
            <Text style={[styles.label, { fontSize }, isActive && styles.labelActive]}>
              {tab.label}
            </Text>
          </TouchableOpacity>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    backgroundColor: WHITE,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: BORDER,
  },
  tab: {
    flex: 1,
    alignItems: 'center',
    position: 'relative',
  },
  activeIndicator: {
    position: 'absolute',
    width: '50%',
    backgroundColor: TEAL,
    borderRadius: 2,
  },
  label:       { color: GRAY, fontWeight: '500' },
  labelActive: { color: TEAL, fontWeight: '700' },
});
