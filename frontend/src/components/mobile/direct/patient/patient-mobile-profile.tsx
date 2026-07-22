import { Ionicons } from '@expo/vector-icons';
import { Image, ScrollView, StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { usePatientProfile, type PatientProfile } from '@/hooks/use-patient-profile';

const TEAL   = '#0D9488';
const TEAL_L = '#E6F4F1';
const TEAL_D = '#0A7B70';
const GREEN  = '#10B981';
const GREEN_L= '#ECFDF5';
const AMBER  = '#F59E0B';
const AMBER_L= '#FFFBEB';
const RED    = '#EF4444';
const RED_L  = '#FEF2F2';
const PURPLE = '#8B5CF6';
const PURPLE_L='#F5F3FF';
const GRAY   = '#6B7280';
const GRAY_L = '#F3F4F6';
const TEXT   = '#111827';
const TEXT_S = '#374151';
const BORDER = '#E5E7EB';
const WHITE  = '#FFFFFF';
const BG     = '#F9FAFB';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

const HEALTH_CONFIG: Record<'STABLE' | 'MONITOR' | 'CRITICAL', { label: string; color: string; bg: string; icon: IoniconsName }> = {
  STABLE:   { label: 'Stable',   color: GREEN, bg: GREEN_L, icon: 'checkmark-circle-outline' },
  MONITOR:  { label: 'Monitor',  color: AMBER, bg: AMBER_L, icon: 'alert-circle-outline'     },
  CRITICAL: { label: 'Critical', color: RED,   bg: RED_L,   icon: 'warning-outline'           },
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
  return `${Math.floor(diff / (365.25 * 24 * 60 * 60 * 1000))} yrs`;
}

export function PatientMobileProfile() {
  const { width, height } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const { profile, loading, error, refetch } = usePatientProfile();

  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));

  if (loading) {
    return (
      <View style={[styles.center, { paddingTop: insets.top }]}>
        <View style={[styles.loadingPulse, { width: s(56), height: s(56), borderRadius: s(28) }]} />
        <Text style={[styles.loadingText, { fontSize: s(14) }]}>Loading profile…</Text>
      </View>
    );
  }

  if (error || !profile) {
    return (
      <View style={[styles.center, { paddingTop: insets.top }]}>
        <Ionicons name="alert-circle-outline" size={s(44)} color={RED} />
        <Text style={[styles.errorText, { fontSize: s(14) }]}>{error ?? 'Profile not found.'}</Text>
        <TouchableOpacity
          style={[styles.retryBtn, { paddingVertical: sh(10), paddingHorizontal: s(28), borderRadius: s(8) }]}
          onPress={refetch}
        >
          <Text style={[styles.retryBtnText, { fontSize: s(14) }]}>Try again</Text>
        </TouchableOpacity>
      </View>
    );
  }

  const fullName   = `${profile.first_name} ${profile.last_name}`;
  const initials   = `${profile.first_name[0]}${profile.last_name[0]}`.toUpperCase();
  const healthCfg  = profile.health_status ? HEALTH_CONFIG[profile.health_status] : null;
  const bloodCfg   = profile.blood_type ? (BLOOD_CONFIG[profile.blood_type] ?? { color: TEAL, bg: TEAL_L }) : null;

  const avatarSize     = s(84);
  const avatarRadius   = s(42);
  const headerPadTop   = insets.top + sh(16);

  return (
    <View style={styles.root}>
      {/* Teal header */}
      <View style={[styles.header, { paddingTop: headerPadTop, paddingBottom: sh(80), paddingHorizontal: s(20) }]}>
        <Text style={[styles.headerTitle, { fontSize: s(18) }]}>Profile &amp; Settings</Text>
        <TouchableOpacity onPress={refetch} style={[styles.headerRefresh, { padding: s(8) }]}>
          <Ionicons name="refresh-outline" size={s(18)} color={WHITE} />
        </TouchableOpacity>
      </View>

      {/* Avatar card overlapping header */}
      <View style={[styles.avatarCard, {
        marginHorizontal: s(16),
        marginTop: -(sh(60)),
        borderRadius: s(20),
        padding: s(20),
        gap: s(10),
      }]}>
        <View style={styles.avatarRow}>
          <View style={{ position: 'relative' }}>
            {profile.avatar_url ? (
              <Image
                source={{ uri: profile.avatar_url }}
                style={{ width: avatarSize, height: avatarSize, borderRadius: avatarRadius, borderWidth: s(3), borderColor: TEAL }}
              />
            ) : (
              <View style={[{ width: avatarSize, height: avatarSize, borderRadius: avatarRadius, borderWidth: s(3), borderColor: TEAL }, styles.avatarFallback]}>
                <Text style={{ fontSize: s(26), fontWeight: '700', color: WHITE }}>{initials}</Text>
              </View>
            )}
            {profile.is_active === 1 && (
              <View style={[styles.onlineDot, { width: s(14), height: s(14), borderRadius: s(7), bottom: s(2), right: s(2) }]} />
            )}
          </View>

          <View style={{ flex: 1, marginLeft: s(14), gap: s(6) }}>
            <Text style={[styles.profileName, { fontSize: s(18) }]}>{fullName}</Text>

            <View style={[styles.badgeRow, { gap: s(6) }]}>
              <View style={[styles.roleBadge, { paddingVertical: sh(3), paddingHorizontal: s(10), borderRadius: s(16) }]}>
                <Text style={[styles.roleBadgeText, { fontSize: s(11) }]}>
                  {profile.role.charAt(0).toUpperCase() + profile.role.slice(1).toLowerCase()}
                </Text>
              </View>

              {healthCfg && (
                <View style={[styles.healthBadge, { backgroundColor: healthCfg.bg, paddingVertical: sh(3), paddingHorizontal: s(10), borderRadius: s(16), gap: s(4) }]}>
                  <Ionicons name={healthCfg.icon} size={s(11)} color={healthCfg.color} />
                  <Text style={[styles.healthBadgeText, { fontSize: s(11), color: healthCfg.color }]}>{healthCfg.label}</Text>
                </View>
              )}

              {bloodCfg && profile.blood_type && (
                <View style={[styles.bloodBadge, { backgroundColor: bloodCfg.bg, borderColor: bloodCfg.color + '50', paddingVertical: sh(3), paddingHorizontal: s(10), borderRadius: s(16) }]}>
                  <Text style={[styles.bloodBadgeText, { fontSize: s(11), color: bloodCfg.color }]}>{profile.blood_type}</Text>
                </View>
              )}
            </View>
          </View>
        </View>

        {/* Quick meta row */}
        <View style={[styles.metaRow, { gap: s(16), paddingTop: sh(8), borderTopWidth: 1, borderTopColor: BORDER }]}>
          <View style={styles.metaItem}>
            <Ionicons name="mail-outline" size={s(13)} color={GRAY} />
            <Text style={[styles.metaText, { fontSize: s(12) }]} numberOfLines={1}>{profile.email}</Text>
          </View>
          {profile.phone && (
            <View style={styles.metaItem}>
              <Ionicons name="call-outline" size={s(13)} color={GRAY} />
              <Text style={[styles.metaText, { fontSize: s(12) }]}>{profile.phone}</Text>
            </View>
          )}
        </View>
      </View>

      {/* Scrollable sections */}
      <ScrollView
        style={styles.scroll}
        contentContainerStyle={[styles.scrollContent, { padding: s(16), paddingBottom: sh(32) }]}
        showsVerticalScrollIndicator={false}
      >
        {/* Personal Information */}
        <SectionCard title="Personal Information" icon="person-circle-outline" s={s} sh={sh}>
          <InfoRow label="First Name"    value={profile.first_name}                                             s={s} sh={sh} />
          <InfoRow label="Last Name"     value={profile.last_name}                                              s={s} sh={sh} />
          <InfoRow label="Date of Birth" value={formatDate(profile.date_of_birth)}                             s={s} sh={sh} />
          <InfoRow label="Age"           value={calcAge(profile.date_of_birth)}                                s={s} sh={sh} />
          <InfoRow label="Gender"        value={profile.gender ? GENDER_LABELS[profile.gender] : '—'}          s={s} sh={sh} last />
        </SectionCard>

        {/* Contact Details */}
        <SectionCard title="Contact Details" icon="call-outline" s={s} sh={sh}>
          <InfoRow label="Email"  value={profile.email}        s={s} sh={sh} />
          <InfoRow label="Phone"  value={profile.phone ?? '—'} s={s} sh={sh} last />
        </SectionCard>

        {/* Medical Profile */}
        <SectionCard title="Medical Profile" icon="medical-outline" s={s} sh={sh}>
          {/* Blood type row */}
          <View style={[styles.infoRow, { paddingHorizontal: s(16), paddingVertical: sh(12), borderBottomWidth: 1, borderBottomColor: GRAY_L }]}>
            <Text style={[styles.infoLabel, { fontSize: s(13) }]}>Blood Type</Text>
            {bloodCfg && profile.blood_type ? (
              <View style={[styles.bloodBadge, { backgroundColor: bloodCfg.bg, borderColor: bloodCfg.color + '50', paddingVertical: sh(3), paddingHorizontal: s(14), borderRadius: s(20) }]}>
                <Text style={[styles.bloodBadgeText, { fontSize: s(13), color: bloodCfg.color }]}>{profile.blood_type}</Text>
              </View>
            ) : (
              <Text style={[styles.infoValue, { fontSize: s(13) }]}>—</Text>
            )}
          </View>

          {/* Health status row */}
          <View style={[styles.infoRow, { paddingHorizontal: s(16), paddingVertical: sh(12), borderBottomWidth: 1, borderBottomColor: GRAY_L }]}>
            <Text style={[styles.infoLabel, { fontSize: s(13) }]}>Health Status</Text>
            {healthCfg ? (
              <View style={[styles.healthBadge, { backgroundColor: healthCfg.bg, paddingVertical: sh(3), paddingHorizontal: s(12), borderRadius: s(20), gap: s(4) }]}>
                <Ionicons name={healthCfg.icon} size={s(12)} color={healthCfg.color} />
                <Text style={[styles.healthBadgeText, { fontSize: s(12), color: healthCfg.color }]}>{healthCfg.label}</Text>
              </View>
            ) : (
              <Text style={[styles.infoValue, { fontSize: s(13) }]}>—</Text>
            )}
          </View>

          {/* Allergies */}
          <View style={[{ paddingHorizontal: s(16), paddingTop: sh(12), paddingBottom: sh(8), borderBottomWidth: 1, borderBottomColor: GRAY_L }]}>
            <Text style={[styles.tagSectionLabel, { fontSize: s(11), marginBottom: sh(8) }]}>ALLERGIES</Text>
            {profile.allergies.length > 0 ? (
              <View style={[styles.tagRow, { gap: s(6) }]}>
                {profile.allergies.map((a, i) => (
                  <TagChip key={i} label={a} color={RED} bg={RED_L} s={s} sh={sh} />
                ))}
              </View>
            ) : (
              <View style={[styles.emptyRow, { gap: s(6) }]}>
                <Ionicons name="checkmark-circle-outline" size={s(14)} color={GREEN} />
                <Text style={[styles.emptyText, { fontSize: s(13) }]}>No known allergies</Text>
              </View>
            )}
          </View>

          {/* Chronic conditions */}
          <View style={[{ paddingHorizontal: s(16), paddingTop: sh(12), paddingBottom: sh(12) }]}>
            <Text style={[styles.tagSectionLabel, { fontSize: s(11), marginBottom: sh(8) }]}>CHRONIC CONDITIONS</Text>
            {profile.chronic_conditions.length > 0 ? (
              <View style={[styles.tagRow, { gap: s(6) }]}>
                {profile.chronic_conditions.map((c, i) => (
                  <TagChip key={i} label={c} color={AMBER} bg={AMBER_L} s={s} sh={sh} />
                ))}
              </View>
            ) : (
              <View style={[styles.emptyRow, { gap: s(6) }]}>
                <Ionicons name="checkmark-circle-outline" size={s(14)} color={GREEN} />
                <Text style={[styles.emptyText, { fontSize: s(13) }]}>No chronic conditions</Text>
              </View>
            )}
          </View>
        </SectionCard>

        {/* Account Details */}
        <SectionCard title="Account Details" icon="shield-checkmark-outline" s={s} sh={sh}>
          <InfoRow label="User ID"         value={`#${profile.user_id}`}                                          s={s} sh={sh} />
          <InfoRow label="Patient ID"      value={profile.patient_id ? `#${profile.patient_id}` : '—'}            s={s} sh={sh} />
          <InfoRow label="Role"            value={profile.role.charAt(0).toUpperCase() + profile.role.slice(1).toLowerCase()} valueColor={TEAL} s={s} sh={sh} />
          <InfoRow label="Account Status"  value={profile.is_active ? 'Active' : 'Inactive'} valueColor={profile.is_active ? GREEN : RED} s={s} sh={sh} />
          <InfoRow label="Member Since"    value={formatDateShort(profile.patient_created_at ?? profile.user_created_at)} s={s} sh={sh} />
          <InfoRow label="Last Updated"    value={formatDateShort(profile.user_updated_at)}                        s={s} sh={sh} last />
        </SectionCard>
      </ScrollView>
    </View>
  );
}

function SectionCard({
  title, icon, children, s, sh,
}: {
  title: string; icon: IoniconsName; children: React.ReactNode;
  s: (n: number) => number; sh: (n: number) => number;
}) {
  return (
    <View style={[styles.card, { borderRadius: s(14), marginBottom: sh(14) }]}>
      <View style={[styles.cardHeader, { paddingHorizontal: s(16), paddingVertical: sh(14), gap: s(10) }]}>
        <View style={[styles.cardIconBox, { width: s(30), height: s(30), borderRadius: s(8) }]}>
          <Ionicons name={icon} size={s(15)} color={TEAL} />
        </View>
        <Text style={[styles.cardTitle, { fontSize: s(14) }]}>{title}</Text>
      </View>
      <View style={styles.cardDivider} />
      {children}
    </View>
  );
}

function InfoRow({
  label, value, valueColor, last, s, sh,
}: {
  label: string; value: string; valueColor?: string; last?: boolean;
  s: (n: number) => number; sh: (n: number) => number;
}) {
  return (
    <View style={[
      styles.infoRow,
      { paddingHorizontal: s(16), paddingVertical: sh(12) },
      !last && { borderBottomWidth: 1, borderBottomColor: GRAY_L },
    ]}>
      <Text style={[styles.infoLabel, { fontSize: s(13) }]}>{label}</Text>
      <Text style={[styles.infoValue, { fontSize: s(13) }, valueColor ? { color: valueColor } : undefined]}>{value}</Text>
    </View>
  );
}

function TagChip({
  label, color, bg, s, sh,
}: {
  label: string; color: string; bg: string;
  s: (n: number) => number; sh: (n: number) => number;
}) {
  return (
    <View style={[styles.chip, {
      backgroundColor: bg, borderColor: color + '40',
      paddingVertical: sh(4), paddingHorizontal: s(12), borderRadius: s(20),
    }]}>
      <Text style={[styles.chipText, { fontSize: s(12), color }]}>{label}</Text>
    </View>
  );
}

export default PatientMobileProfile;

const styles = StyleSheet.create({
  root:   { flex: 1, backgroundColor: BG },
  scroll: { flex: 1 },
  scrollContent: {},

  center: { flex: 1, backgroundColor: BG, alignItems: 'center', justifyContent: 'center', gap: 12, padding: 24 },
  loadingPulse: { backgroundColor: TEAL_L },
  loadingText:  { color: GRAY },
  errorText:    { color: RED, textAlign: 'center' },
  retryBtn:     { backgroundColor: TEAL, marginTop: 8 },
  retryBtnText: { color: WHITE, fontWeight: '700' },

  header: {
    backgroundColor: TEAL,
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
  },
  headerTitle:   { color: WHITE, fontWeight: '700' },
  headerRefresh: {},

  avatarCard: {
    backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER,
    shadowColor: '#000', shadowOffset: { width: 0, height: 2 }, shadowOpacity: 0.06, shadowRadius: 8, elevation: 3,
  },
  avatarRow:      { flexDirection: 'row', alignItems: 'center' },
  avatarFallback: { backgroundColor: TEAL, alignItems: 'center', justifyContent: 'center' },
  onlineDot:      { position: 'absolute', backgroundColor: GREEN, borderWidth: 2, borderColor: WHITE },

  profileName:    { fontWeight: '700', color: TEXT },
  badgeRow:       { flexDirection: 'row', flexWrap: 'wrap' },
  roleBadge:      { backgroundColor: TEAL_L, flexDirection: 'row', alignItems: 'center' },
  roleBadgeText:  { color: TEAL, fontWeight: '600' },
  healthBadge:    { flexDirection: 'row', alignItems: 'center' },
  healthBadgeText:{ fontWeight: '600' },
  bloodBadge:     { borderWidth: 1 },
  bloodBadgeText: { fontWeight: '700' },

  metaRow:  { flexDirection: 'row', flexWrap: 'wrap' },
  metaItem: { flexDirection: 'row', alignItems: 'center', gap: 4, flex: 1 },
  metaText: { color: TEXT_S, flex: 1 },

  card:       { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER, overflow: 'hidden' },
  cardHeader: { flexDirection: 'row', alignItems: 'center' },
  cardIconBox:{ backgroundColor: TEAL_L, alignItems: 'center', justifyContent: 'center' },
  cardTitle:  { fontWeight: '700', color: TEXT },
  cardDivider:{ height: 1, backgroundColor: BORDER },

  infoRow:   { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  infoLabel: { color: GRAY, fontWeight: '500', flex: 1 },
  infoValue: { color: TEXT, fontWeight: '600', textAlign: 'right', flexShrink: 1, marginLeft: 8 },

  tagSectionLabel: { fontWeight: '600', color: GRAY, letterSpacing: 0.5 },
  tagRow:          { flexDirection: 'row', flexWrap: 'wrap' },
  chip:            { borderWidth: 1 },
  chipText:        { fontWeight: '600' },
  emptyRow:        { flexDirection: 'row', alignItems: 'center' },
  emptyText:       { color: GREEN, fontWeight: '500' },
});
