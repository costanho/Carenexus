import { Ionicons } from '@expo/vector-icons';
import { Image, StyleSheet, Text, TouchableOpacity, View } from 'react-native';

const PURPLE = '#7C3AED';
const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

function IconBadge({ name, badge }: { name: IoniconsName; badge: string }) {
  return (
    <View style={styles.iconWrapper}>
      <Ionicons name={name} size={24} color={TEXT} />
      <View style={styles.badge}>
        <Text style={styles.badgeText}>{badge}</Text>
      </View>
    </View>
  );
}

export function PatientProxyTopNavbar() {
  return (
    <View style={styles.navbar}>
      {/* Left: greeting */}
      <View style={styles.left}>
        <Text style={styles.greeting}>Good morning, Sarah! 👋</Text>
        <Text style={styles.subtitle}>Here's how your loved ones are doing today.</Text>
      </View>

      {/* Right: icons + profile + switch */}
      <View style={styles.right}>
        <View style={styles.iconsRow}>
          <TouchableOpacity activeOpacity={0.7}>
            <IconBadge name="notifications-outline" badge="5" />
          </TouchableOpacity>
          <TouchableOpacity activeOpacity={0.7}>
            <IconBadge name="mail-outline" badge="3" />
          </TouchableOpacity>
        </View>

        <View style={styles.divider} />

        {/* Profile + switch stacked */}
        <View style={styles.profileBlock}>
          <TouchableOpacity style={styles.profileRow} activeOpacity={0.8}>
            <Image
              source={{ uri: 'https://i.pravatar.cc/150?img=47' }}
              style={styles.avatar}
            />
            <View>
              <Text style={styles.profileName}>Sarah Davis</Text>
              <Text style={styles.profileRole}>Proxy (Daughter)</Text>
            </View>
            <Ionicons name="chevron-down" size={16} color={GRAY} />
          </TouchableOpacity>

          <TouchableOpacity style={styles.switchBtn} activeOpacity={0.8}>
            <Ionicons name="people-outline" size={15} color={PURPLE} />
            <Text style={styles.switchText}>Switch to another</Text>
            <Ionicons name="chevron-down" size={14} color={PURPLE} />
          </TouchableOpacity>
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  navbar: {
    height: 92,
    backgroundColor: WHITE,
    borderBottomWidth: 1,
    borderBottomColor: BORDER,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 28,
    gap: 20,
  },

  // Left
  left: {
    flex: 1,
  },
  greeting: {
    fontSize: 20,
    fontWeight: '700',
    color: TEXT,
    marginBottom: 4,
  },
  subtitle: {
    fontSize: 13,
    color: GRAY,
  },

  // Right
  right: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 16,
  },
  iconsRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 20,
  },
  iconWrapper: {
    width: 40,
    height: 40,
    alignItems: 'center',
    justifyContent: 'center',
  },
  badge: {
    position: 'absolute',
    top: 2,
    right: 2,
    backgroundColor: PURPLE,
    borderRadius: 8,
    minWidth: 18,
    height: 18,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 4,
  },
  badgeText: {
    fontSize: 10,
    fontWeight: '700',
    color: WHITE,
  },
  divider: {
    width: 1,
    height: 36,
    backgroundColor: BORDER,
  },

  // Profile block
  profileBlock: {
    alignItems: 'flex-end',
    gap: 6,
  },
  profileRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  avatar: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: BORDER,
  },
  profileName: {
    fontSize: 14,
    fontWeight: '700',
    color: TEXT,
  },
  profileRole: {
    fontSize: 12,
    color: GRAY,
    marginTop: 1,
  },
  switchBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
    backgroundColor: BG,
    borderWidth: 1,
    borderColor: BORDER,
    borderRadius: 8,
    paddingHorizontal: 10,
    paddingVertical: 5,
  },
  switchText: {
    fontSize: 12,
    color: PURPLE,
    fontWeight: '600',
  },
});
