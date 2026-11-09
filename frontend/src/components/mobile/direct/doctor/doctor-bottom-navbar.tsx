import { Ionicons } from '@expo/vector-icons';
import { StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { MessagesTabIcon, useTabLabel } from '../../messages/messages-tab-icon';

const BLUE   = '#4F46E5';
const GRAY   = '#6B7280';
const WHITE  = '#FFFFFF';
const BORDER = '#E5E7EB';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type TabItem = { key: string; label: string; icon: IoniconsName; activeIcon: IoniconsName };

const tabs: TabItem[] = [
  { key: 'dashboard',     label: 'Home',     icon: 'home-outline',            activeIcon: 'home'            },
  { key: 'messages',      label: 'Messages', icon: 'chatbubbles-outline',     activeIcon: 'chatbubbles'     },
  { key: 'schedule',      label: 'Schedule', icon: 'calendar-outline',        activeIcon: 'calendar'        },
  { key: 'appointments',  label: 'Appts',    icon: 'calendar-number-outline', activeIcon: 'calendar-number' },
  { key: 'patients',      label: 'Patients', icon: 'people-outline',          activeIcon: 'people'          },
  { key: 'consultations', label: 'Consults', icon: 'document-text-outline',   activeIcon: 'document-text'   },
  { key: 'profile',       label: 'Profile',  icon: 'person-outline',          activeIcon: 'person'          },
];

export interface DoctorBottomNavbarProps {
  activeKey?:  string;
  onNavigate?: (key: string) => void;
}

export function DoctorBottomNavbar({ activeKey = 'dashboard', onNavigate }: DoctorBottomNavbarProps) {
  const insets = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();
  const tabLabel = useTabLabel();

  const s = (n: number) => Math.round(n * (width / 390));
  const h = (n: number) => Math.round(n * (height / 844));

  const iconSize  = s(21);
  const fontSize  = s(9);
  const padTop    = h(12);
  const padBottom = insets.bottom || h(16);

  return (
    <View style={[styles.container, { paddingTop: padTop, paddingBottom: padBottom }]}>
      {tabs.map((tab) => {
        const isActive = activeKey === tab.key;
        return (
          <TouchableOpacity
            key={tab.key}
            style={[styles.tab, { gap: s(4) }]}
            activeOpacity={0.7}
            onPress={() => onNavigate?.(tab.key)}
            accessibilityRole="tab"
            accessibilityState={{ selected: isActive }}
            accessibilityLabel={tabLabel(tab.key, ({ appointments: 'Appointments', consultations: 'Consultations' } as Record<string, string>)[tab.key] ?? tab.label)}
          >
            {isActive && (
              <View style={[styles.activeIndicator, { height: s(3), top: -padTop }]} />
            )}
            <MessagesTabIcon show={tab.key === 'messages'}>
              <Ionicons
                name={isActive ? tab.activeIcon : tab.icon}
                size={iconSize}
                color={isActive ? BLUE : GRAY}
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
    backgroundColor: BLUE,
    borderRadius: 2,
  },
  label:       { color: GRAY, fontWeight: '500' },
  labelActive: { color: BLUE, fontWeight: '700' },
});
