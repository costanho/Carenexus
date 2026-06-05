import { Ionicons } from '@expo/vector-icons';
import { Image, StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';

const TEXT  = '#111827';
const GRAY  = '#6B7280';
const WHITE = '#FFFFFF';
const RED   = '#EF4444';

interface Props {
  showMenuButton?: boolean;
  onMenuPress?: () => void;
}

function BellIcon({ count }: { count: number }) {
  return (
    <View style={styles.bellWrapper}>
      <Ionicons name="notifications-outline" size={22} color={GRAY} />
      <View style={styles.badge}>
        <Text style={styles.badgeText}>{count}</Text>
      </View>
    </View>
  );
}

export function PatientTopNavbar({ showMenuButton, onMenuPress }: Props) {
  const { width } = useWindowDimensions();
  const isSmall = width < 768;

  return (
    <View style={styles.navbar}>
      <View style={styles.left}>
        {showMenuButton && (
          <TouchableOpacity onPress={onMenuPress} style={styles.menuBtn} activeOpacity={0.7}>
            <Ionicons name="menu-outline" size={26} color={TEXT} />
          </TouchableOpacity>
        )}
        <View>
          <Text style={styles.title} numberOfLines={1}>Welcome back, Linda!</Text>
          {!isSmall && (
            <Text style={styles.subtitle}>Here's your health overview and latest updates.</Text>
          )}
        </View>
      </View>

      <View style={styles.right}>
        <BellIcon count={3} />
        {!isSmall && <BellIcon count={2} />}
        <View style={styles.divider} />
        <TouchableOpacity style={styles.userRow} activeOpacity={0.7}>
          <Image
            source={{ uri: 'https://i.pravatar.cc/150?img=47' }}
            style={styles.avatar}
          />
          {!isSmall && <Text style={styles.userName}>Linda Davis</Text>}
          <Ionicons name="chevron-down" size={16} color={GRAY} />
        </TouchableOpacity>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  navbar: {
    height: 96,
    backgroundColor: WHITE,
    borderBottomWidth: 1,
    borderBottomColor: '#E5E7EB',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 24,
  },
  left: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    minWidth: 0,
  },
  menuBtn: {
    padding: 4,
  },
  title: {
    fontSize: 16,
    fontWeight: '700',
    color: TEXT,
  },
  subtitle: {
    fontSize: 13,
    color: GRAY,
    marginTop: 2,
  },
  right: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 16,
  },
  bellWrapper: {
    position: 'relative',
    width: 32,
    height: 32,
    alignItems: 'center',
    justifyContent: 'center',
  },
  badge: {
    position: 'absolute',
    top: 0,
    right: 0,
    backgroundColor: RED,
    borderRadius: 8,
    minWidth: 16,
    height: 16,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 3,
  },
  badgeText: {
    color: WHITE,
    fontSize: 10,
    fontWeight: '700',
  },
  divider: {
    width: 1,
    height: 28,
    backgroundColor: '#E5E7EB',
  },
  userRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  avatar: {
    width: 34,
    height: 34,
    borderRadius: 17,
    backgroundColor: '#E5E7EB',
  },
  userName: {
    fontSize: 14,
    fontWeight: '500',
    color: TEXT,
  },
});
