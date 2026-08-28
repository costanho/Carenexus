import { Ionicons } from '@expo/vector-icons';
import { ActivityIndicator, Image, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useCaregiverDependents, type CaregiverDependent } from '@/hooks/use-caregiver-dependents';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const PURPLE = '#7C3AED';
const PURPLE_BG = '#F5F3FF';
const GREEN  = '#10B981';
const ORANGE = '#F59E0B';
const RED    = '#EF4444';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

const AVATAR_COLORS = ['#4F46E5', '#0D9488', '#F59E0B', '#EF4444', '#8B5CF6', '#10B981', '#0EA5E9'];
function avatarColor(name: string) {
  let h = 0;
  for (let i = 0; i < (name?.length ?? 0); i++) h = (h * 31 + name.charCodeAt(i)) & 0xffff;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}
function initials(name: string) {
  return (name ?? '?').split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase();
}
function calcAge(dob: string | null) {
  if (!dob) return null;
  return Math.floor((Date.now() - new Date(dob).getTime()) / (365.25 * 24 * 3600 * 1000));
}
function healthColor(s: string | null) {
  if (s === 'STABLE')   return { color: GREEN,  bg: '#D1FAE5' };
  if (s === 'MONITOR')  return { color: ORANGE, bg: '#FEF3C7' };
  if (s === 'CRITICAL') return { color: RED,    bg: '#FEE2E2' };
  return { color: GRAY, bg: '#F3F4F6' };
}

// Static enrichment per patient (supplemental data not in dependent_access)
const PATIENT_EXTRAS: Record<number, {
  conditions: string[];
  vitals: { label: string; value: string; unit: string; icon: IoniconsName; alert?: boolean }[];
  allergies: string[];
  immunizations: { name: string; date: string; status: 'DONE' | 'DUE' }[];
  emergency: { name: string; relation: string; phone: string };
}> = {
  3: {
    conditions: ['Hypertension (Stage 2)', 'Type 2 Diabetes Mellitus'],
    vitals: [
      { label: 'Blood Pressure', value: '148/92', unit: 'mmHg', icon: 'pulse-outline',       alert: true  },
      { label: 'Heart Rate',     value: '78',     unit: 'bpm',  icon: 'heart-outline',        alert: false },
      { label: 'Blood Glucose',  value: '9.4',    unit: 'mmol/L', icon: 'water-outline',      alert: true  },
      { label: 'Weight',         value: '84',     unit: 'kg',   icon: 'scale-outline',        alert: false },
      { label: 'Temperature',    value: '36.8',   unit: '°C',   icon: 'thermometer-outline',  alert: false },
      { label: 'SpO₂',          value: '97',     unit: '%',    icon: 'medical-outline',      alert: false },
    ],
    allergies: ['Penicillin (rash)', 'Sulfonamides (angioedema)'],
    immunizations: [
      { name: 'Influenza (Flu)',    date: 'Mar 2026', status: 'DONE' },
      { name: 'COVID-19 Booster',  date: 'Jan 2026', status: 'DONE' },
      { name: 'Pneumococcal (PCV)',date: 'Due Aug 2026', status: 'DUE'  },
    ],
    emergency: { name: 'Sarah Dube (Daughter)', relation: 'Daughter', phone: '+263 773 456 789' },
  },
  4: {
    conditions: ['Chronic Kidney Disease (Stage 3b)', 'Osteoarthritis — Bilateral Knees'],
    vitals: [
      { label: 'Blood Pressure', value: '152/94', unit: 'mmHg', icon: 'pulse-outline',      alert: true  },
      { label: 'Heart Rate',     value: '82',     unit: 'bpm',  icon: 'heart-outline',      alert: false },
      { label: 'eGFR',          value: '32',     unit: 'mL/min/1.73m²', icon: 'flask-outline', alert: true },
      { label: 'Weight',        value: '61',     unit: 'kg',   icon: 'scale-outline',      alert: false },
      { label: 'Temperature',   value: '37.1',   unit: '°C',   icon: 'thermometer-outline',alert: false },
      { label: 'SpO₂',         value: '96',     unit: '%',    icon: 'medical-outline',    alert: false },
    ],
    allergies: ['NSAIDs (contraindicated — CKD)', 'Contrast dye (renal impairment)'],
    immunizations: [
      { name: 'Influenza (Flu)',   date: 'Apr 2026', status: 'DONE' },
      { name: 'Pneumococcal',     date: 'Feb 2026', status: 'DONE' },
      { name: 'Hepatitis B',      date: 'Due Sep 2026', status: 'DUE' },
    ],
    emergency: { name: 'Sarah Dube (Guardian)', relation: 'Guardian', phone: '+263 773 456 789' },
  },
};

function PatientHealthCard({ dep }: { dep: CaregiverDependent }) {
  const name   = dep.patient_name ?? 'Unknown';
  const age    = calcAge(dep.patient_date_of_birth);
  const { color, bg } = healthColor(dep.patient_health_status);
  const extras = PATIENT_EXTRAS[dep.patient_id];

  return (
    <View style={styles.card}>
      {/* Patient header */}
      <View style={styles.patHeader}>
        <View style={styles.avatarWrap}>
          {dep.patient_avatar ? (
            <Image source={{ uri: dep.patient_avatar }} style={styles.avatar} />
          ) : (
            <View style={[styles.avatar, { backgroundColor: avatarColor(name), alignItems: 'center', justifyContent: 'center' }]}>
              <Text style={{ color: WHITE, fontSize: 22, fontWeight: '700' }}>{initials(name)}</Text>
            </View>
          )}
          <View style={[styles.statusDot, { backgroundColor: color }]} />
        </View>
        <View style={{ flex: 1 }}>
          <Text style={styles.patName}>{name}</Text>
          <View style={styles.patMetaRow}>
            {age && <Text style={styles.patMeta}>{age} yrs</Text>}
            {dep.patient_gender && <Text style={styles.patMeta}>{dep.patient_gender.charAt(0) + dep.patient_gender.slice(1).toLowerCase()}</Text>}
            {dep.patient_blood_type && (
              <View style={styles.bloodType}>
                <Text style={styles.bloodText}>{dep.patient_blood_type}</Text>
              </View>
            )}
          </View>
          <View style={styles.condRow}>
            {extras?.conditions.map(c => (
              <View key={c} style={styles.condTag}><Text style={styles.condText}>{c}</Text></View>
            ))}
          </View>
        </View>
        <View style={[styles.statusBadge, { backgroundColor: bg }]}>
          <Text style={[styles.statusText, { color }]}>
            {dep.patient_health_status ?? 'Unknown'}
          </Text>
        </View>
      </View>

      {/* Vitals */}
      {extras && (
        <>
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>Latest Vitals</Text>
            <View style={styles.vitalsGrid}>
              {extras.vitals.map(v => (
                <View key={v.label} style={[styles.vitalCard, v.alert && styles.vitalAlert]}>
                  <Ionicons name={v.icon} size={16} color={v.alert ? RED : PURPLE} />
                  <Text style={styles.vitalLabel}>{v.label}</Text>
                  <Text style={[styles.vitalValue, v.alert && { color: RED }]}>
                    {v.value} <Text style={styles.vitalUnit}>{v.unit}</Text>
                  </Text>
                </View>
              ))}
            </View>
          </View>

          {/* Allergies */}
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>Allergies & Contraindications</Text>
            <View style={{ gap: 6 }}>
              {extras.allergies.map(a => (
                <View key={a} style={styles.allergyRow}>
                  <Ionicons name="warning-outline" size={14} color={RED} />
                  <Text style={styles.allergyText}>{a}</Text>
                </View>
              ))}
            </View>
          </View>

          {/* Immunizations */}
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>Immunizations</Text>
            <View style={{ gap: 8 }}>
              {extras.immunizations.map(im => (
                <View key={im.name} style={styles.immunRow}>
                  <Ionicons
                    name={im.status === 'DONE' ? 'checkmark-circle' : 'alert-circle-outline'}
                    size={16}
                    color={im.status === 'DONE' ? GREEN : ORANGE}
                  />
                  <Text style={styles.immunName}>{im.name}</Text>
                  <Text style={[styles.immunDate, im.status === 'DUE' && { color: ORANGE, fontWeight: '600' }]}>
                    {im.date}
                  </Text>
                </View>
              ))}
            </View>
          </View>

          {/* Emergency contact */}
          <View style={[styles.emergencyCard]}>
            <Ionicons name="call-outline" size={18} color={RED} />
            <View style={{ flex: 1 }}>
              <Text style={styles.emergencyTitle}>Emergency Contact</Text>
              <Text style={styles.emergencyName}>{extras.emergency.name}</Text>
              <Text style={styles.emergencyPhone}>{extras.emergency.phone}</Text>
            </View>
          </View>
        </>
      )}
    </View>
  );
}

export default function PatientProxyHealthSummary() {
  const { dependents, loading } = useCaregiverDependents();

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>
      <View style={styles.pageHeader}>
        <Text style={styles.pageTitle}>Health Summary</Text>
        <Text style={styles.pageSub}>Complete health overview for all linked patients</Text>
      </View>

      {loading ? (
        <ActivityIndicator size="large" color={PURPLE} style={{ marginTop: 60 }} />
      ) : dependents.length === 0 ? (
        <View style={styles.empty}>
          <Ionicons name="heart-outline" size={40} color={BORDER} />
          <Text style={styles.emptyText}>No linked patients</Text>
        </View>
      ) : (
        dependents.map(dep => <PatientHealthCard key={dep.access_id} dep={dep} />)
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll:     { flex: 1, backgroundColor: BG },
  container:  { padding: 24, paddingBottom: 48, gap: 20 },
  pageHeader: { marginBottom: 4 },
  pageTitle:  { fontSize: 22, fontWeight: '700', color: TEXT },
  pageSub:    { fontSize: 13, color: GRAY, marginTop: 3 },

  card:        { backgroundColor: WHITE, borderRadius: 14, borderWidth: 1, borderColor: BORDER, padding: 20, gap: 20 },
  patHeader:   { flexDirection: 'row', alignItems: 'flex-start', gap: 14 },
  avatarWrap:  { position: 'relative' },
  avatar:      { width: 64, height: 64, borderRadius: 32, backgroundColor: BORDER },
  statusDot:   { width: 14, height: 14, borderRadius: 7, borderWidth: 2, borderColor: WHITE, position: 'absolute', bottom: 2, right: 2 },
  patName:     { fontSize: 20, fontWeight: '700', color: TEXT },
  patMetaRow:  { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 4 },
  patMeta:     { fontSize: 13, color: GRAY },
  bloodType:   { backgroundColor: '#FEE2E2', borderRadius: 6, paddingHorizontal: 8, paddingVertical: 2 },
  bloodText:   { fontSize: 12, fontWeight: '700', color: RED },
  condRow:     { flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginTop: 8 },
  condTag:     { backgroundColor: PURPLE_BG, borderRadius: 20, paddingHorizontal: 10, paddingVertical: 3 },
  condText:    { fontSize: 11, color: PURPLE, fontWeight: '600' },
  statusBadge: { borderRadius: 20, paddingHorizontal: 12, paddingVertical: 4 },
  statusText:  { fontSize: 12, fontWeight: '700' },

  section:      { gap: 10 },
  sectionTitle: { fontSize: 14, fontWeight: '700', color: TEXT, borderBottomWidth: 1, borderBottomColor: BORDER, paddingBottom: 8 },

  vitalsGrid:  { flexDirection: 'row', flexWrap: 'wrap', gap: 10 },
  vitalCard:   { backgroundColor: PURPLE_BG, borderRadius: 10, padding: 12, minWidth: 120, flex: 1, gap: 4, alignItems: 'flex-start' },
  vitalAlert:  { backgroundColor: '#FEE2E2' },
  vitalLabel:  { fontSize: 11, color: GRAY },
  vitalValue:  { fontSize: 18, fontWeight: '700', color: TEXT },
  vitalUnit:   { fontSize: 11, fontWeight: '400', color: GRAY },

  allergyRow:  { flexDirection: 'row', alignItems: 'center', gap: 8, backgroundColor: '#FEF2F2', borderRadius: 8, padding: 10 },
  allergyText: { fontSize: 13, color: TEXT, flex: 1 },

  immunRow:   { flexDirection: 'row', alignItems: 'center', gap: 10 },
  immunName:  { fontSize: 13, color: TEXT, flex: 1 },
  immunDate:  { fontSize: 12, color: GRAY },

  emergencyCard: { flexDirection: 'row', alignItems: 'center', gap: 12, backgroundColor: '#FEF2F2', borderRadius: 10, borderWidth: 1, borderColor: '#FECACA', padding: 14 },
  emergencyTitle:{ fontSize: 11, fontWeight: '600', color: RED, marginBottom: 2 },
  emergencyName: { fontSize: 14, fontWeight: '700', color: TEXT },
  emergencyPhone:{ fontSize: 13, color: GRAY, marginTop: 2 },

  empty:     { alignItems: 'center', paddingVertical: 60, gap: 12 },
  emptyText: { fontSize: 14, color: GRAY },
});
