import { Ionicons } from '@expo/vector-icons';
import { StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { MessagesTabIcon, useTabLabel } from '../../messages/messages-tab-icon';

const PURPLE = '#7C3AED';
const GRAY   = '#6B7280';
const WHITE  = '#FFFFFF';
const BORDER = '#E5E7EB';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type TabItem = { key: string; label: string; icon: IoniconsName; activeIcon: IoniconsName; comingSoon?: boolean };

// Dashboard, Schedule, Messages and Profile have native screens (loved-one profiles and records open from the dashboard).
// A tab without a screen yet is shown disabled with a "Soon" label.
const tabs: TabItem[] = [
  { key: 'dashboard',  label: 'Dashboard',  icon: 'home-outline',        activeIcon: 'home' },
  { key: 'schedule',   label: 'Schedule',   icon: 'calendar-outline',    activeIcon: 'calendar' },
  { key: 'messages',   label: 'Messages',   icon: 'chatbubble-outline',  activeIcon: 'chatbubble' },
  { key: 'profile',    label: 'Profile',    icon: 'person-outline',      activeIcon: 'person' },
];

export function PatientProxyBottomNavbar({ activeKey, onNavigate }: { activeKey: string; onNavigate: (key: string) => void }) {
  const insets = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();
  // Screens reached from the dashboard (loved-one profile, add loved one) keep Dashboard highlighted.
  const active = tabs.some(t => t.key === activeKey) ? activeKey : 'dashboard';
  const tabLabel = useTabLabel();

  const s = (n: number) => Math.round(n * (width  / 390));
  const h = (n: number) => Math.round(n * (height / 844));

  const iconSize  = s(24);
  const fontSize  = s(10);
  const padTop    = h(12);
  const padBottom = insets.bottom || h(16);

  return (
    <View style={[styles.container, { paddingTop: padTop, paddingBottom: padBottom }]} accessibilityRole="tablist">
      {tabs.map((tab) => {
        const isActive = active === tab.key;
        const disabled = !!tab.comingSoon;
        return (
          <TouchableOpacity
            key={tab.key}
            style={[styles.tab, { gap: s(4) }, disabled && styles.tabDisabled]}
            activeOpacity={0.7}
            onPress={disabled ? undefined : () => onNavigate(tab.key)}
            disabled={disabled}
            accessibilityRole="tab"
            accessibilityLabel={disabled ? `${tab.label}, coming soon` : tabLabel(tab.key, tab.label)}
            accessibilityState={{ selected: isActive, disabled }}
          >
            {isActive && (
              <View style={[styles.activeIndicator, { height: s(3), top: -padTop }]} />
            )}
            <MessagesTabIcon show={tab.key === 'messages'}>
              <Ionicons
                name={isActive ? tab.activeIcon : tab.icon}
                size={iconSize}
                color={isActive ? PURPLE : GRAY}
                importantForAccessibility="no"
              />
            </MessagesTabIcon>
            <Text style={[styles.label, { fontSize }, isActive && styles.labelActive]}>
              {disabled ? `${tab.label} (Soon)` : tab.label}
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
    backgroundColor: PURPLE,
    borderRadius: 2,
  },
  tabDisabled: { opacity: 0.5 },
  label:       { color: GRAY, fontWeight: '500' },
  labelActive: { color: PURPLE, fontWeight: '700' },
});
