import { Ionicons } from '@expo/vector-icons';
import { StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

const BLUE   = '#4F46E5';
const GRAY   = '#9CA3AF';
const WHITE  = '#FFFFFF';
const BORDER = '#E5E7EB';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type TabItem = { key: string; label: string; icon: IoniconsName; activeIcon: IoniconsName };

const tabs: TabItem[] = [
  { key: 'dashboard',     label: 'Home',     icon: 'home-outline',            activeIcon: 'home'            },
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
          >
            {isActive && (
              <View style={[styles.activeIndicator, { height: s(3), top: -padTop }]} />
            )}
            <Ionicons
              name={isActive ? tab.activeIcon : tab.icon}
              size={iconSize}
              color={isActive ? BLUE : GRAY}
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
    backgroundColor: BLUE,
    borderRadius: 2,
  },
  label:       { color: GRAY, fontWeight: '500' },
  labelActive: { color: BLUE, fontWeight: '700' },
});
