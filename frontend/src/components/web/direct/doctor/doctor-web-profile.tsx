import { Ionicons } from '@expo/vector-icons';
import { Image, ActivityIndicator, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useDoctorProfile } from '@/hooks/use-doctor-profile';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const LGRAY  = '#9CA3AF';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const BLUE   = '#4F46E5';
const GREEN  = '#10B981';
const AMBER  = '#F59E0B';
const TEAL   = '#0D9488';
const NAVY   = '#0D1B2E';
const PURPLE = '#8B5CF6';
const RED    = '#EF4444';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

const AVATAR_COLORS = ['#4F46E5', '#0D9488', '#F59E0B', '#EF4444', '#8B5CF6', '#10B981', '#0EA5E9'];
function avatarColor(name: string) {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) & 0xffff;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}
function initials(name: string) {
  return name.split(' ').filter(Boolean).map(w => w[0]).join('').slice(0, 2).toUpperCase();
}
function formatDate(iso: string): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric' });
}
function memberSince(iso: string): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-GB', { month: 'long', year: 'numeric' });
}

// ─── Info Row ─────────────────────────────────────────────────────────────────

function InfoRow({ icon, label, value, mono }: { icon: IoniconsName; label: string; value: string; mono?: boolean }) {
  return (
    <View style={styles.infoRow}>
      <View style={styles.infoIconWrap}>
        <Ionicons name={icon} size={15} color={BLUE} />
      </View>
      <View style={{ flex: 1 }}>
        <Text style={styles.infoLabel}>{label}</Text>
        <Text style={[styles.infoValue, mono && { fontFamily: 'monospace' as any }]}>{value}</Text>
      </View>
    </View>
  );
}

// ─── Stat Card ────────────────────────────────────────────────────────────────

function StatCard({ icon, iconBg, iconColor, label, value }: {
  icon: IoniconsName; iconBg: string; iconColor: string; label: string; value: number;
}) {
  return (
    <View style={styles.statCard}>
      <View style={[styles.statIconBg, { backgroundColor: iconBg }]}>
        <Ionicons name={icon} size={22} color={iconColor} />
      </View>
      <Text style={styles.statValue}>{value}</Text>
      <Text style={styles.statLabel}>{label}</Text>
    </View>
  );
}

// ─── Main ─────────────────────────────────────────────────────────────────────

export default function DoctorWebProfile() {
  const { profile, stats, loading, error, refetch } = useDoctorProfile();

  if (loading) return <View style={styles.center}><ActivityIndicator size="large" color={BLUE} /></View>;

  if (error || !profile) return (
    <View style={styles.center}>
      <Ionicons name="alert-circle-outline" size={52} color={RED} />
      <Text style={styles.errorText}>{error ?? 'Profile not found'}</Text>
      <TouchableOpacity style={styles.retryBtn} onPress={refetch} activeOpacity={0.8}>
        <Text style={styles.retryBtnText}>Retry</Text>
      </TouchableOpacity>
    </View>
  );

  const ac = avatarColor(profile.full_name);

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>

      {/* Hero banner */}
      <View style={styles.heroBanner}>
        <View style={styles.heroOverlay} />
        <View style={styles.heroContent}>
          {/* Avatar */}
          <View style={styles.avatarRing}>
            {profile.avatar_url ? (
              <Image source={{ uri: profile.avatar_url }} style={styles.avatarImg} />
            ) : (
              <View style={[styles.avatarFallback, { backgroundColor: ac }]}>
                <Text style={styles.avatarInitials}>{initials(profile.full_name)}</Text>
              </View>
            )}
          </View>

          {/* Name + spec */}
          <View style={{ flex: 1, gap: 6 }}>
            <View style={styles.nameRow}>
              <Text style={styles.heroName}>{profile.full_name}</Text>
              {!!profile.is_active && (
                <View style={styles.activeBadge}>
                  <View style={styles.activeDot} />
                  <Text style={styles.activeText}>Active</Text>
                </View>
              )}
            </View>
            <Text style={styles.heroSpec}>{profile.specialization}</Text>
            <View style={styles.heroMeta}>
              <Ionicons name="shield-checkmark-outline" size={13} color="rgba(255,255,255,0.7)" />
              <Text style={styles.heroMetaText}>{profile.license_no}</Text>
              <Text style={styles.heroMetaDivider}>·</Text>
              <Ionicons name="calendar-outline" size={13} color="rgba(255,255,255,0.7)" />
              <Text style={styles.heroMetaText}>Since {memberSince(profile.created_at)}</Text>
            </View>
          </View>

          {/* Refresh */}
          <TouchableOpacity style={styles.refreshBtn} onPress={refetch} activeOpacity={0.8}>
            <Ionicons name="refresh-outline" size={15} color={WHITE} />
          </TouchableOpacity>
        </View>
      </View>

      {/* Stats row */}
      <View style={styles.statsRow}>
        <StatCard icon="calendar-number-outline" iconBg="#EEF2FF" iconColor={BLUE}   label="Appointments"  value={stats.appointments}  />
        <StatCard icon="people-outline"           iconBg="#D1FAE5" iconColor={GREEN}  label="Patients"      value={stats.patients}      />
        <StatCard icon="videocam-outline"         iconBg="#F5F3FF" iconColor={PURPLE} label="Consultations" value={stats.consultations} />
        <StatCard icon="medkit-outline"           iconBg="#FEF3C7" iconColor={AMBER}  label="Prescriptions" value={stats.prescriptions} />
      </View>

      {/* Two-column layout */}
      <View style={styles.twoCol}>

        {/* Left: About + Contact */}
        <View style={styles.leftCol}>

          {/* About */}
          {!!profile.bio && (
            <View style={styles.card}>
              <View style={styles.cardHeader}>
                <View style={[styles.cardIconWrap, { backgroundColor: '#EEF2FF' }]}>
                  <Ionicons name="person-outline" size={16} color={BLUE} />
                </View>
                <Text style={styles.cardTitle}>About</Text>
              </View>
              <Text style={styles.bioText}>{profile.bio}</Text>
            </View>
          )}

          {/* Contact Information */}
          <View style={styles.card}>
            <View style={styles.cardHeader}>
              <View style={[styles.cardIconWrap, { backgroundColor: '#D1FAE5' }]}>
                <Ionicons name="call-outline" size={16} color={GREEN} />
              </View>
              <Text style={styles.cardTitle}>Contact Information</Text>
            </View>
            <View style={{ gap: 4 }}>
              <InfoRow icon="mail-outline"      label="Email Address" value={profile.email}            />
              <InfoRow icon="call-outline"      label="Phone Number"  value={profile.phone ?? 'Not provided'} />
            </View>
          </View>

        </View>

        {/* Right: Professional Details */}
        <View style={styles.rightCol}>

          {/* Professional Details */}
          <View style={styles.card}>
            <View style={styles.cardHeader}>
              <View style={[styles.cardIconWrap, { backgroundColor: '#F5F3FF' }]}>
                <Ionicons name="ribbon-outline" size={16} color={PURPLE} />
              </View>
              <Text style={styles.cardTitle}>Professional Details</Text>
            </View>
            <View style={{ gap: 4 }}>
              <InfoRow icon="briefcase-outline"        label="Specialization"  value={profile.specialization}          />
              <InfoRow icon="shield-checkmark-outline" label="License Number"  value={profile.license_no} mono         />
              <InfoRow icon="calendar-outline"         label="Member Since"    value={memberSince(profile.created_at)} />
              <InfoRow icon="checkmark-circle-outline" label="Account Status"  value={profile.is_active ? 'Active' : 'Inactive'} />
            </View>
          </View>

          {/* Account Details */}
          <View style={styles.card}>
            <View style={styles.cardHeader}>
              <View style={[styles.cardIconWrap, { backgroundColor: '#FEF3C7' }]}>
                <Ionicons name="information-circle-outline" size={16} color={AMBER} />
              </View>
              <Text style={styles.cardTitle}>Account Details</Text>
            </View>
            <View style={{ gap: 4 }}>
              <InfoRow icon="finger-print-outline" label="Doctor ID"  value={`#${profile.doctor_id}`} mono />
              <InfoRow icon="person-outline"       label="User ID"    value={`#${profile.user_id}`}   mono />
              <InfoRow icon="time-outline"         label="Joined"     value={formatDate(profile.created_at)} />
            </View>
          </View>

        </View>
      </View>

    </ScrollView>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  scroll:    { flex: 1, backgroundColor: BG },
  container: { paddingBottom: 48 },
  center:    { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 12 },

  // Hero
  heroBanner: {
    backgroundColor: NAVY,
    paddingTop: 32,
    paddingBottom: 28,
    paddingHorizontal: 28,
    overflow: 'hidden',
  },
  heroOverlay: {
    position: 'absolute',
    top: -40, right: -40,
    width: 220, height: 220,
    borderRadius: 110,
    backgroundColor: 'rgba(79,110,247,0.12)',
  },
  heroContent: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 22,
  },
  avatarRing: {
    width: 96,
    height: 96,
    borderRadius: 48,
    borderWidth: 3,
    borderColor: 'rgba(45,212,191,0.6)',
    overflow: 'hidden',
  },
  avatarImg:      { width: '100%', height: '100%' },
  avatarFallback: { width: '100%', height: '100%', alignItems: 'center', justifyContent: 'center' },
  avatarInitials: { color: WHITE, fontSize: 30, fontWeight: '700' },

  nameRow:     { flexDirection: 'row', alignItems: 'center', gap: 10, flexWrap: 'wrap' },
  heroName:    { fontSize: 24, fontWeight: '700', color: WHITE },
  activeBadge: { flexDirection: 'row', alignItems: 'center', gap: 5, backgroundColor: 'rgba(16,185,129,0.2)', borderRadius: 20, paddingHorizontal: 10, paddingVertical: 3, borderWidth: 1, borderColor: 'rgba(16,185,129,0.4)' },
  activeDot:   { width: 6, height: 6, borderRadius: 3, backgroundColor: GREEN },
  activeText:  { fontSize: 11, fontWeight: '600', color: GREEN },

  heroSpec:        { fontSize: 15, color: '#8BA3BE', fontWeight: '500' },
  heroMeta:        { flexDirection: 'row', alignItems: 'center', gap: 6, flexWrap: 'wrap' },
  heroMetaText:    { fontSize: 12, color: 'rgba(255,255,255,0.55)' },
  heroMetaDivider: { fontSize: 12, color: 'rgba(255,255,255,0.3)' },

  refreshBtn: {
    width: 36, height: 36, borderRadius: 18,
    backgroundColor: 'rgba(255,255,255,0.1)',
    alignItems: 'center', justifyContent: 'center',
    alignSelf: 'flex-start',
  },

  // Stats
  statsRow: {
    flexDirection: 'row',
    gap: 12,
    paddingHorizontal: 24,
    paddingTop: 24,
    paddingBottom: 8,
    flexWrap: 'wrap',
  },
  statCard: {
    flex: 1, minWidth: 100,
    backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER,
    borderRadius: 14, padding: 16, gap: 6, alignItems: 'flex-start',
  },
  statIconBg: { width: 40, height: 40, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  statValue:  { fontSize: 28, fontWeight: '700', color: TEXT },
  statLabel:  { fontSize: 12, color: GRAY },

  // Layout
  twoCol:   { flexDirection: 'row', gap: 16, paddingHorizontal: 24, paddingTop: 16, flexWrap: 'wrap' },
  leftCol:  { flex: 2, minWidth: 280, gap: 16 },
  rightCol: { flex: 1, minWidth: 240, gap: 16 },

  // Card
  card: {
    backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER,
    borderRadius: 16, padding: 20, gap: 16,
  },
  cardHeader: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  cardIconWrap: { width: 34, height: 34, borderRadius: 9, alignItems: 'center', justifyContent: 'center' },
  cardTitle: { fontSize: 15, fontWeight: '700', color: TEXT },

  bioText: { fontSize: 14, color: GRAY, lineHeight: 22 },

  // Info row
  infoRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: 12,
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: BG,
  },
  infoIconWrap: {
    width: 32, height: 32, borderRadius: 8,
    backgroundColor: '#EEF2FF',
    alignItems: 'center', justifyContent: 'center',
    marginTop: 2,
  },
  infoLabel: { fontSize: 11, color: LGRAY, textTransform: 'uppercase', letterSpacing: 0.5, marginBottom: 2 },
  infoValue: { fontSize: 14, color: TEXT, fontWeight: '500' },

  errorText:    { fontSize: 15, color: GRAY, textAlign: 'center' },
  retryBtn:     { backgroundColor: BLUE, borderRadius: 8, paddingHorizontal: 20, paddingVertical: 10 },
  retryBtnText: { color: WHITE, fontWeight: '600', fontSize: 14 },
});
