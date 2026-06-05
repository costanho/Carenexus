import { Ionicons } from '@expo/vector-icons';
import { Image, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';

const BG      = '#FFFFFF';
const BORDER  = '#E5E7EB';
const TEXT    = '#111827';
const GRAY    = '#6B7280';
const INPUT_BG= '#F3F4F6';
const RED     = '#EF4444';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

function IconBtn({ name, badge }: { name: IoniconsName; badge?: string }) {
  return (
    <View style={styles.iconBtn}>
      <Ionicons name={name} size={22} color={GRAY} />
      {badge && (
        <View style={styles.badge}>
          <Text style={styles.badgeText}>{badge}</Text>
        </View>
      )}
    </View>
  );
}

export interface DoctorTopNavbarProps {
  onMenuPress?: () => void;
}

export function DoctorTopNavbar({ onMenuPress }: DoctorTopNavbarProps) {
  return (
    <View style={styles.navbar}>
      {/* Left: hamburger */}
      <View style={styles.left}>
        <TouchableOpacity onPress={onMenuPress} style={styles.hamburger} activeOpacity={0.7}>
          <Ionicons name="menu-outline" size={26} color={TEXT} />
        </TouchableOpacity>
      </View>

      {/* Center: search — absolutely centered */}
      <View style={styles.center}>
        <View style={styles.searchWrapper}>
          <Ionicons name="search-outline" size={18} color={GRAY} />
          <TextInput
            style={styles.searchInput}
            placeholder="Search patients, appointments..."
            placeholderTextColor={GRAY}
          />
        </View>
      </View>

      {/* Right: icons + profile */}
      <View style={styles.right}>
        <TouchableOpacity activeOpacity={0.7}>
          <IconBtn name="chatbubble-outline" badge="6" />
        </TouchableOpacity>
        <TouchableOpacity activeOpacity={0.7}>
          <IconBtn name="notifications-outline" badge="12" />
        </TouchableOpacity>

        <View style={styles.divider} />

        <TouchableOpacity style={styles.profile} activeOpacity={0.8}>
          <Image
            source={{ uri: 'https://i.pravatar.cc/150?img=12' }}
            style={styles.avatar}
          />
          <View>
            <Text style={styles.doctorName}>Dr. Matt</Text>
            <Text style={styles.doctorRole}>Physician</Text>
          </View>
          <Ionicons name="chevron-down" size={16} color={GRAY} />
        </TouchableOpacity>
      </View>
    </View>
  );
}



const styles = StyleSheet.create({
  navbar: {
    height: 88,
    backgroundColor: BG,
    borderBottomWidth: 1,
    borderBottomColor: BORDER,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 24,
    gap: 16,
  },
  left: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
  },
  center: {
    flex: 2,
    alignItems: 'center',
  },
  hamburger: {
    padding: 6,
  },
  searchWrapper: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: INPUT_BG,
    borderRadius: 10,
    paddingHorizontal: 14,
    paddingVertical: 10,
    gap: 8,
    width: '100%',
    maxWidth: 480,
  },
  searchInput: {
    flex: 1,
    fontSize: 14,
    color: TEXT,
    outlineStyle: 'none',
  } as any,
  right: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'flex-end',
    gap: 4,
  },
  iconBtn: {
    width: 40,
    height: 40,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center',
  },
  badge: {
    position: 'absolute',
    top: 4,
    right: 4,
    backgroundColor: RED,
    borderRadius: 8,
    minWidth: 16,
    height: 16,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 3,
  },
  badgeText: {
    fontSize: 9,
    fontWeight: '700',
    color: '#FFFFFF',
  },
  divider: {
    width: 1,
    height: 32,
    backgroundColor: BORDER,
    marginHorizontal: 8,
  },
  profile: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  avatar: {
    width: 38,
    height: 38,
    borderRadius: 19,
    backgroundColor: '#E5E7EB',
  },
  doctorName: {
    fontSize: 14,
    fontWeight: '700',
    color: TEXT,
  },
  doctorRole: {
    fontSize: 12,
    color: GRAY,
  },
});
