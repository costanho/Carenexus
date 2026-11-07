import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { MessagesTabIcon, useTabLabel } from '../../messages/messages-tab-icon';

const TEAL  = '#0D9488';
const GRAY  = '#9CA3AF';
const WHITE = '#FFFFFF';
const BORDER = '#E5E7EB';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

type TabItem = { key: string; label: string; icon: IoniconsName; activeIcon: IoniconsName };

const tabs: TabItem[] = [
  { key: 'dashboard',    label: 'Dashboard',    icon: 'home-outline',         activeIcon: 'home' },
  { key: 'appointments', label: 'Appointments', icon: 'calendar-outline',     activeIcon: 'calendar' },
  { key: 'messages',     label: 'Messages',     icon: 'chatbubbles-outline',  activeIcon: 'chatbubbles' },
  { key: 'records',      label: 'Records',      icon: 'folder-open-outline',  activeIcon: 'folder-open' },
  { key: 'prescriptions', label: 'Prescriptions', icon: 'medkit-outline',      activeIcon: 'medkit' },
  { key: 'profile',      label: 'Profile',      icon: 'person-outline',       activeIcon: 'person' },
];

const TAB_ROUTES: Record<string, string> = {
  appointments: '/dashboard/patient/appointments/status',
};

export function PatientBottomNavbar({
  activeKey,
  onNavigate,
}: {
  activeKey?: string;
  onNavigate?: (key: string) => void;
}) {
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();
  const [localActive, setLocalActive] = useState('dashboard');
  const active = activeKey ?? localActive;
  const tabLabel = useTabLabel();

  const s = (n: number) => Math.round(n * (width / 390));
  const h = (n: number) => Math.round(n * (height / 844));

  const iconSize  = s(24);
  const fontSize  = s(10);
  const padTop    = h(12);
  const padBottom = insets.bottom || h(16);

  function handlePress(tab: TabItem) {
    if (onNavigate) {
      onNavigate(tab.key);
    } else {
      setLocalActive(tab.key);
      const route = TAB_ROUTES[tab.key];
      if (route) router.push(route as any);
    }
  }

  return (
    <View style={[styles.container, { paddingTop: padTop, paddingBottom: padBottom }]}>
      {tabs.map((tab) => {
        const isActive = active === tab.key;
        return (
          <TouchableOpacity
            key={tab.key}
            style={[styles.tab, { gap: s(4) }]}
            activeOpacity={0.7}
            onPress={() => handlePress(tab)}
            accessibilityRole="tab"
            accessibilityLabel={tabLabel(tab.key, tab.label)}
            accessibilityState={{ selected: isActive }}
          >
            {isActive && (
              <View style={[styles.activeIndicator, { height: s(3), top: -padTop }]} />
            )}
            <MessagesTabIcon show={tab.key === 'messages'}>
              <Ionicons
                name={isActive ? tab.activeIcon : tab.icon}
                size={iconSize}
                color={isActive ? TEAL : GRAY}
              />
            </MessagesTabIcon>
            <Text style={[styles.label, { fontSize }, isActive && styles.labelActive]} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.8}>
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
