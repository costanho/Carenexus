import { Ionicons } from '@expo/vector-icons';
import { Image, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { usePatientProfile, type PatientProfile } from '@/hooks/use-patient-profile';

const TEAL   = '#0D9488';
const TEAL_L = '#E6F4F1';
const TEAL_M = '#CCEBE6';
const GREEN  = '#10B981';
const GREEN_L= '#ECFDF5';
const AMBER  = '#F59E0B';
const AMBER_L= '#FFFBEB';
const RED    = '#EF4444';
const RED_L  = '#FEF2F2';
const INDIGO = '#4F46E5';
const INDIGO_L='#EEF2FF';
const PURPLE = '#8B5CF6';
const PURPLE_L='#F5F3FF';
const BLUE   = '#3B82F6';
const BLUE_L = '#EFF6FF';
const GRAY   = '#6B7280';
const GRAY_L = '#F3F4F6';
const TEXT   = '#111827';
const TEXT_S = '#374151';
const BORDER = '#E5E7EB';
const WHITE  = '#FFFFFF';
const BG     = '#F9FAFB';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

const HEALTH_CONFIG: Record<'STABLE' | 'MONITOR' | 'CRITICAL', { label: string; color: string; bg: string; icon: IoniconsName }> = {
  STABLE:   { label: 'Stable',   color: GREEN, bg: GREEN_L,  icon: 'checkmark-circle-outline' },
  MONITOR:  { label: 'Monitor',  color: AMBER, bg: AMBER_L,  icon: 'alert-circle-outline'     },
  CRITICAL: { label: 'Critical', color: RED,   bg: RED_L,    icon: 'warning-outline'           },
};

const BLOOD_CONFIG: Record<string, { color: string; bg: string }> = {
  'A+': { color: RED,    bg: RED_L    },
  'A-': { color: RED,    bg: RED_L    },
  'B+': { color: PURPLE, bg: PURPLE_L },
  'B-': { color: PURPLE, bg: PURPLE_L },
  'AB+':{ color: TEAL,   bg: TEAL_L   },
  'AB-':{ color: TEAL,   bg: TEAL_L   },
  'O+': { color: GREEN,  bg: GREEN_L  },
  'O-': { color: GREEN,  bg: GREEN_L  },
};

const GENDER_LABELS: Record<string, string> = {
  MALE: 'Male', FEMALE: 'Female', OTHER: 'Other',
};

function formatDate(iso: string | null) {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric' });
}

function formatDateShort(iso: string | null) {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}

function calcAge(dob: string | null): string {
  if (!dob) return '—';
  const diff = Date.now() - new Date(dob).getTime();
  return `${Math.floor(diff / (365.25 * 24 * 60 * 60 * 1000))} years`;
}

function SectionCard({ title, icon, children }: { title: string; icon: IoniconsName; children: React.ReactNode }) {
  return (
    <View style={styles.card}>
      <View style={styles.cardHeader}>
        <View style={styles.cardIconBox}>
          <Ionicons name={icon} size={16} color={TEAL} />
        </View>
        <Text style={styles.cardTitle}>{title}</Text>
      </View>
      <View style={styles.divider} />
      {children}
    </View>
  );
}

function InfoRow({ label, value, valueColor }: { label: string; value: string; valueColor?: string }) {
  return (
    <View style={styles.infoRow}>
      <Text style={styles.infoLabel}>{label}</Text>
      <Text style={[styles.infoValue, valueColor ? { color: valueColor } : undefined]}>{value}</Text>
    </View>
  );
}

function TagChip({ label, color, bg }: { label: string; color: string; bg: string }) {
  return (
    <View style={[styles.chip, { backgroundColor: bg, borderColor: color + '40' }]}>
      <Text style={[styles.chipText, { color }]}>{label}</Text>
    </View>
  );
}

function EmptyTags({ label }: { label: string }) {
  return (
    <View style={styles.emptyTags}>
      <Ionicons name="checkmark-circle-outline" size={14} color={GREEN} />
      <Text style={styles.emptyTagsText}>{label}</Text>
    </View>
  );
}

function ProfileHero({ profile }: { profile: PatientProfile }) {
  const fullName    = `${profile.first_name} ${profile.last_name}`;
  const initials    = `${profile.first_name[0]}${profile.last_name[0]}`.toUpperCase();
  const healthCfg   = profile.health_status ? HEALTH_CONFIG[profile.health_status] : null;
  const bloodCfg    = profile.blood_type ? (BLOOD_CONFIG[profile.blood_type] ?? { color: TEAL, bg: TEAL_L }) : null;
  const memberSince = formatDateShort(profile.patient_created_at ?? profile.user_created_at);

  return (
    <View style={styles.hero}>
      <View style={styles.heroLeft}>
        {profile.avatar_url ? (
          <Image source={{ uri: profile.avatar_url }} style={styles.heroAvatar} />
        ) : (
          <View style={[styles.heroAvatar, styles.heroAvatarFallback]}>
            <Text style={styles.heroInitials}>{initials}</Text>
          </View>
        )}
        {profile.is_active === 1 && <View style={styles.activeIndicator} />}
      </View>

      <View style={styles.heroInfo}>
        <Text style={styles.heroName}>{fullName}</Text>

        <View style={styles.heroBadgeRow}>
          <View style={styles.roleBadge}>
            <Ionicons name="person-outline" size={12} color={TEAL} />
            <Text style={styles.roleBadgeText}>
              {profile.role.charAt(0).toUpperCase() + profile.role.slice(1).toLowerCase()}
            </Text>
          </View>
          {healthCfg && (
            <View style={[styles.statusBadge, { backgroundColor: healthCfg.bg }]}>
              <Ionicons name={healthCfg.icon} size={12} color={healthCfg.color} />
              <Text style={[styles.statusBadgeText, { color: healthCfg.color }]}>{healthCfg.label}</Text>
            </View>
          )}
          {bloodCfg && profile.blood_type && (
            <View style={[styles.bloodBadge, { backgroundColor: bloodCfg.bg, borderColor: bloodCfg.color + '50' }]}>
              <Text style={[styles.bloodBadgeText, { color: bloodCfg.color }]}>
                {profile.blood_type}
              </Text>
            </View>
          )}
        </View>

        <View style={styles.heroMeta}>
          <View style={styles.heroMetaItem}>
            <Ionicons name="mail-outline" size={13} color={GRAY} />
            <Text style={styles.heroMetaText}>{profile.email}</Text>
          </View>
          {profile.phone && (
            <View style={styles.heroMetaItem}>
              <Ionicons name="call-outline" size={13} color={GRAY} />
              <Text style={styles.heroMetaText}>{profile.phone}</Text>
            </View>
          )}
        </View>
      </View>

      <View style={styles.heroRight}>
        {profile.patient_id && (
          <View style={styles.heroStatBox}>
            <Text style={styles.heroStatLabel}>Patient ID</Text>
            <Text style={styles.heroStatValue}>#{profile.patient_id}</Text>
          </View>
        )}
        <View style={styles.heroStatBox}>
          <Text style={styles.heroStatLabel}>Member Since</Text>
          <Text style={styles.heroStatValue}>{memberSince}</Text>
        </View>
        <View style={styles.heroStatBox}>
          <Text style={styles.heroStatLabel}>Account</Text>
          <View style={[styles.activeTag, { backgroundColor: profile.is_active ? GREEN_L : RED_L }]}>
            <Text style={[styles.activeTagText, { color: profile.is_active ? GREEN : RED }]}>
              {profile.is_active ? 'Active' : 'Inactive'}
            </Text>
          </View>
        </View>
      </View>
    </View>
  );
}

export function PatientWebProfile() {
  const { profile, loading, error, refetch } = usePatientProfile();

  if (loading) {
    return (
      <View style={styles.center}>
        <View style={styles.loadingPulse} />
        <Text style={styles.loadingText}>Loading profile…</Text>
      </View>
    );
  }

  if (error || !profile) {
    return (
      <View style={styles.center}>
        <Ionicons name="alert-circle-outline" size={48} color={RED} />
        <Text style={styles.errorText}>{error ?? 'Profile not found.'}</Text>
        <TouchableOpacity style={styles.retryBtn} onPress={refetch}>
          <Text style={styles.retryText}>Try again</Text>
        </TouchableOpacity>
      </View>
    );
  }

  const healthCfg = profile.health_status ? HEALTH_CONFIG[profile.health_status] : null;

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
      {/* Page header */}
      <View style={styles.pageHeader}>
        <View>
          <Text style={styles.pageTitle}>Profile &amp; Settings</Text>
          <Text style={styles.pageSubtitle}>Your personal and health information</Text>
        </View>
        <TouchableOpacity style={styles.refreshBtn} onPress={refetch}>
          <Ionicons name="refresh-outline" size={16} color={TEAL} />
          <Text style={styles.refreshText}>Refresh</Text>
        </TouchableOpacity>
      </View>

      {/* Hero */}
      <ProfileHero profile={profile} />

      {/* Two-column grid */}
      <View style={styles.grid}>
        {/* Left column */}
        <View style={styles.col}>
          <SectionCard title="Personal Information" icon="person-circle-outline">
            <InfoRow label="First Name"    value={profile.first_name} />
            <InfoRow label="Last Name"     value={profile.last_name} />
            <InfoRow label="Date of Birth" value={formatDate(profile.date_of_birth)} />
            <InfoRow label="Age"           value={calcAge(profile.date_of_birth)} />
            <InfoRow label="Gender"        value={profile.gender ? GENDER_LABELS[profile.gender] : '—'} />
          </SectionCard>

          <SectionCard title="Contact Details" icon="call-outline">
            <InfoRow label="Email Address" value={profile.email} />
            <InfoRow label="Phone Number"  value={profile.phone ?? '—'} />
          </SectionCard>
        </View>

        {/* Right column */}
        <View style={styles.col}>
          <SectionCard title="Medical Profile" icon="medical-outline">
            {/* Blood type */}
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>Blood Type</Text>
              {profile.blood_type ? (() => {
                const bc = BLOOD_CONFIG[profile.blood_type] ?? { color: TEAL, bg: TEAL_L };
                return (
                  <View style={[styles.bloodPill, { backgroundColor: bc.bg, borderColor: bc.color + '50' }]}>
                    <Text style={[styles.bloodPillText, { color: bc.color }]}>{profile.blood_type}</Text>
                  </View>
                );
              })() : <Text style={styles.infoValue}>—</Text>}
            </View>

            {/* Health status */}
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>Health Status</Text>
              {healthCfg ? (
                <View style={[styles.statusPill, { backgroundColor: healthCfg.bg }]}>
                  <Ionicons name={healthCfg.icon} size={12} color={healthCfg.color} />
                  <Text style={[styles.statusPillText, { color: healthCfg.color }]}>{healthCfg.label}</Text>
                </View>
              ) : <Text style={styles.infoValue}>—</Text>}
            </View>

            <View style={styles.subDivider} />

            {/* Allergies */}
            <Text style={styles.tagSectionLabel}>Allergies</Text>
            {profile.allergies.length > 0 ? (
              <View style={styles.tagRow}>
                {profile.allergies.map((a, i) => (
                  <TagChip key={i} label={a} color={RED} bg={RED_L} />
                ))}
              </View>
            ) : (
              <EmptyTags label="No known allergies" />
            )}

            <View style={styles.subDivider} />

            {/* Chronic conditions */}
            <Text style={styles.tagSectionLabel}>Chronic Conditions</Text>
            {profile.chronic_conditions.length > 0 ? (
              <View style={styles.tagRow}>
                {profile.chronic_conditions.map((c, i) => (
                  <TagChip key={i} label={c} color={AMBER} bg={AMBER_L} />
                ))}
              </View>
            ) : (
              <EmptyTags label="No chronic conditions" />
            )}
          </SectionCard>
        </View>
      </View>

      {/* Account details — full width */}
      <SectionCard title="Account Details" icon="shield-checkmark-outline">
        <View style={styles.accountGrid}>
          <InfoRow label="User ID"          value={`#${profile.user_id}`} />
          <InfoRow label="Patient ID"       value={profile.patient_id ? `#${profile.patient_id}` : '—'} />
          <InfoRow label="Role"             value={profile.role.charAt(0).toUpperCase() + profile.role.slice(1).toLowerCase()} valueColor={TEAL} />
          <InfoRow label="Account Status"   value={profile.is_active ? 'Active' : 'Inactive'} valueColor={profile.is_active ? GREEN : RED} />
          <InfoRow label="Profile Created"  value={formatDate(profile.patient_created_at)} />
          <InfoRow label="User Registered"  value={formatDate(profile.user_created_at)} />
          <InfoRow label="Last Updated"     value={formatDate(profile.user_updated_at)} />
        </View>
      </SectionCard>
    </ScrollView>
  );
}

export default PatientWebProfile;

const styles = StyleSheet.create({
  scroll:        { flex: 1, backgroundColor: BG },
  scrollContent: { padding: 24, paddingBottom: 40 },

  center: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 12, padding: 24, backgroundColor: BG },
  loadingPulse: { width: 64, height: 64, borderRadius: 32, backgroundColor: TEAL_M },
  loadingText:  { fontSize: 14, color: GRAY },
  errorText:    { fontSize: 15, color: RED, textAlign: 'center' },
  retryBtn:     { marginTop: 8, paddingVertical: 10, paddingHorizontal: 24, backgroundColor: TEAL, borderRadius: 8 },
  retryText:    { color: WHITE, fontWeight: '600', fontSize: 14 },

  pageHeader: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    marginBottom: 20,
  },
  pageTitle:    { fontSize: 22, fontWeight: '700', color: TEXT },
  pageSubtitle: { fontSize: 14, color: GRAY, marginTop: 2 },
  refreshBtn:   { flexDirection: 'row', alignItems: 'center', gap: 6, paddingVertical: 8, paddingHorizontal: 14, borderRadius: 8, borderWidth: 1, borderColor: TEAL, backgroundColor: TEAL_L },
  refreshText:  { fontSize: 13, color: TEAL, fontWeight: '600' },

  // Hero
  hero: {
    flexDirection: 'row', alignItems: 'flex-start',
    backgroundColor: WHITE, borderRadius: 16, padding: 24,
    borderWidth: 1, borderColor: BORDER,
    marginBottom: 20, gap: 20,
    shadowColor: '#000', shadowOffset: { width: 0, height: 1 }, shadowOpacity: 0.04, shadowRadius: 4, elevation: 1,
  },
  heroLeft:          { position: 'relative' },
  heroAvatar:        { width: 88, height: 88, borderRadius: 44, borderWidth: 3, borderColor: TEAL },
  heroAvatarFallback:{ backgroundColor: TEAL, alignItems: 'center', justifyContent: 'center' },
  heroInitials:      { fontSize: 28, fontWeight: '700', color: WHITE },
  activeIndicator:   { position: 'absolute', bottom: 4, right: 4, width: 16, height: 16, borderRadius: 8, backgroundColor: GREEN, borderWidth: 2, borderColor: WHITE },

  heroInfo:       { flex: 1, gap: 8 },
  heroName:       { fontSize: 22, fontWeight: '700', color: TEXT },
  heroBadgeRow:   { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  roleBadge:      { flexDirection: 'row', alignItems: 'center', gap: 4, paddingVertical: 4, paddingHorizontal: 10, backgroundColor: TEAL_L, borderRadius: 20 },
  roleBadgeText:  { fontSize: 12, color: TEAL, fontWeight: '600' },
  statusBadge:    { flexDirection: 'row', alignItems: 'center', gap: 4, paddingVertical: 4, paddingHorizontal: 10, borderRadius: 20 },
  statusBadgeText:{ fontSize: 12, fontWeight: '600' },
  bloodBadge:     { paddingVertical: 4, paddingHorizontal: 10, borderRadius: 20, borderWidth: 1 },
  bloodBadgeText: { fontSize: 12, fontWeight: '700' },
  heroMeta:       { flexDirection: 'row', flexWrap: 'wrap', gap: 16 },
  heroMetaItem:   { flexDirection: 'row', alignItems: 'center', gap: 5 },
  heroMetaText:   { fontSize: 13, color: TEXT_S },

  heroRight:      { gap: 12, alignItems: 'flex-end' },
  heroStatBox:    { alignItems: 'flex-end', gap: 2 },
  heroStatLabel:  { fontSize: 11, color: GRAY, fontWeight: '500' },
  heroStatValue:  { fontSize: 14, color: TEXT, fontWeight: '700' },
  activeTag:      { paddingVertical: 3, paddingHorizontal: 10, borderRadius: 12 },
  activeTagText:  { fontSize: 12, fontWeight: '600' },

  // Grid
  grid: { flexDirection: 'row', gap: 16, marginBottom: 16 },
  col:  { flex: 1, gap: 16 },

  // Card
  card: {
    backgroundColor: WHITE, borderRadius: 14, borderWidth: 1, borderColor: BORDER,
    shadowColor: '#000', shadowOffset: { width: 0, height: 1 }, shadowOpacity: 0.04, shadowRadius: 4, elevation: 1,
    overflow: 'hidden',
  },
  cardHeader:  { flexDirection: 'row', alignItems: 'center', gap: 10, padding: 16 },
  cardIconBox: { width: 32, height: 32, borderRadius: 8, backgroundColor: TEAL_L, alignItems: 'center', justifyContent: 'center' },
  cardTitle:   { fontSize: 15, fontWeight: '700', color: TEXT },
  divider:     { height: 1, backgroundColor: BORDER },

  // Info rows
  infoRow:   { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 16, paddingVertical: 13, borderBottomWidth: 1, borderBottomColor: GRAY_L },
  infoLabel: { fontSize: 13, color: GRAY, fontWeight: '500', flex: 1 },
  infoValue: { fontSize: 13, color: TEXT, fontWeight: '600', textAlign: 'right', flexShrink: 1, marginLeft: 12 },

  subDivider:      { height: 1, backgroundColor: GRAY_L, marginHorizontal: 16, marginVertical: 4 },
  tagSectionLabel: { fontSize: 12, fontWeight: '600', color: GRAY, paddingHorizontal: 16, paddingTop: 10, paddingBottom: 6, textTransform: 'uppercase', letterSpacing: 0.5 },
  tagRow:          { flexDirection: 'row', flexWrap: 'wrap', gap: 8, paddingHorizontal: 16, paddingBottom: 12 },
  chip:            { paddingVertical: 5, paddingHorizontal: 12, borderRadius: 20, borderWidth: 1 },
  chipText:        { fontSize: 12, fontWeight: '600' },
  emptyTags:       { flexDirection: 'row', alignItems: 'center', gap: 6, paddingHorizontal: 16, paddingBottom: 12 },
  emptyTagsText:   { fontSize: 13, color: GREEN, fontWeight: '500' },

  bloodPill:     { paddingVertical: 4, paddingHorizontal: 14, borderRadius: 20, borderWidth: 1 },
  bloodPillText: { fontSize: 13, fontWeight: '700' },
  statusPill:    { flexDirection: 'row', alignItems: 'center', gap: 4, paddingVertical: 4, paddingHorizontal: 10, borderRadius: 20 },
  statusPillText:{ fontSize: 12, fontWeight: '600' },

  accountGrid: { paddingBottom: 4 },
});
