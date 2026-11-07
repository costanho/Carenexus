import { Ionicons } from '@expo/vector-icons';
import { useEffect, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { api } from '@/lib/auth/auth.interceptor';
import {
  caregiverErrorMessage,
  parseList,
  useCaregiverDependents,
  type CaregiverDependent,
} from '@/hooks/use-caregiver-dependents';
import { EmptyState, ErrorState, LoadingState } from './proxy-states';
import { ariaLevel } from '@/components/web/direct/patient/a11y';

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

interface ProfileExtras {
  allergies:         string[];
  conditions:        string[];
  emergencyContacts: { contactId: number; name: string; relationship: string | null; phone: string | null; isPrimary: boolean | null }[];
}

function usePatientExtras(patientSystemId: string) {
  const [extras,  setExtras]  = useState<ProfileExtras | null>(null);
  const [loading, setLoading] = useState(true);
  const [error,   setError]   = useState<string | null>(null);
  const [tick,    setTick]    = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    api.get<any>(`/api/profile/user/${patientSystemId}`)
      .then(({ data }) => {
        if (cancelled) return;
        setExtras({
          allergies:         parseList(data?.allergies),
          conditions:        parseList(data?.chronicConditions),
          emergencyContacts: Array.isArray(data?.emergencyContacts) ? data.emergencyContacts : [],
        });
        setError(null);
      })
      .catch(err => { if (!cancelled) setError(caregiverErrorMessage(err, 'Could not load the health profile.')); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [patientSystemId, tick]);

  return { extras, loading, error, refetch: () => setTick(t => t + 1) };
}

function PatientHealthCard({ dep }: { dep: CaregiverDependent }) {
  const name   = dep.patientName ?? 'Unknown';
  const age    = calcAge(dep.patientDateOfBirth);
  const { color, bg } = healthColor(dep.patientHealthStatus);
  const { extras, loading, error, refetch } = usePatientExtras(dep.patientSystemId);

  return (
    <View style={styles.card}>
      {/* Patient header */}
      <View style={styles.patHeader}>
        <View style={styles.avatarWrap}>
          <View style={[styles.avatar, { backgroundColor: avatarColor(name), alignItems: 'center', justifyContent: 'center' }]}>
            <Text style={{ color: WHITE, fontSize: 22, fontWeight: '700' }}>{initials(name)}</Text>
          </View>
          <View style={[styles.statusDot, { backgroundColor: color }]} />
        </View>
        <View style={{ flex: 1 }}>
          <Text style={styles.patName}>{name}</Text>
          <View style={styles.patMetaRow}>
            {age !== null && <Text style={styles.patMeta}>{age} yrs</Text>}
            {dep.patientGender && <Text style={styles.patMeta}>{dep.patientGender.charAt(0) + dep.patientGender.slice(1).toLowerCase()}</Text>}
            {dep.patientBloodType && (
              <View style={styles.bloodType}>
                <Text style={styles.bloodText}>{dep.patientBloodType}</Text>
              </View>
            )}
          </View>
        </View>
        <View style={[styles.statusBadge, { backgroundColor: bg }]}>
          <Text style={[styles.statusText, { color }]}>
            {dep.patientHealthStatus ?? 'Unknown'}
          </Text>
        </View>
      </View>

      {/* Vitals: no vitals store exists yet */}
      <View style={styles.section}>
        <Text style={styles.sectionTitle} accessibilityRole="header" {...ariaLevel(2)}>Latest Vitals</Text>
        <Text style={styles.noneText}>No vitals recorded</Text>
      </View>

      {loading ? (
        <LoadingState compact label="Loading health profile" />
      ) : error ? (
        <ErrorState compact message={error} onRetry={refetch} />
      ) : extras ? (
        <>
          {/* Conditions */}
          <View style={styles.section}>
            <Text style={styles.sectionTitle} accessibilityRole="header" {...ariaLevel(2)}>Chronic Conditions</Text>
            {extras.conditions.length === 0 ? (
              <Text style={styles.noneText}>None recorded</Text>
            ) : (
              <View style={styles.condRow}>
                {extras.conditions.map(c => (
                  <View key={c} style={styles.condTag}><Text style={styles.condText}>{c}</Text></View>
                ))}
              </View>
            )}
          </View>

          {/* Allergies */}
          <View style={styles.section}>
            <Text style={styles.sectionTitle} accessibilityRole="header" {...ariaLevel(2)}>Allergies & Contraindications</Text>
            {extras.allergies.length === 0 ? (
              <Text style={styles.noneText}>None recorded</Text>
            ) : (
              <View style={{ gap: 6 }}>
                {extras.allergies.map(a => (
                  <View key={a} style={styles.allergyRow}>
                    <Ionicons name="warning-outline" size={14} color={RED} importantForAccessibility="no" />
                    <Text style={styles.allergyText}>{a}</Text>
                  </View>
                ))}
              </View>
            )}
          </View>

          {/* Emergency contacts */}
          <View style={styles.section}>
            <Text style={styles.sectionTitle} accessibilityRole="header" {...ariaLevel(2)}>Emergency Contacts</Text>
            {extras.emergencyContacts.length === 0 ? (
              <Text style={styles.noneText}>None recorded</Text>
            ) : (
              extras.emergencyContacts.map(ec => (
                <View key={ec.contactId} style={styles.emergencyCard}>
                  <Ionicons name="call-outline" size={18} color={RED} importantForAccessibility="no" />
                  <View style={{ flex: 1 }}>
                    <Text style={styles.emergencyTitle}>{ec.isPrimary ? 'Primary contact' : 'Contact'}</Text>
                    <Text style={styles.emergencyName}>
                      {ec.name}{ec.relationship ? ` (${ec.relationship})` : ''}
                    </Text>
                    {ec.phone ? <Text style={styles.emergencyPhone}>{ec.phone}</Text> : null}
                  </View>
                </View>
              ))
            )}
          </View>
        </>
      ) : null}
    </View>
  );
}

export default function PatientProxyHealthSummary() {
  const { dependents, loading, error, refetch } = useCaregiverDependents();

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>
      <View style={styles.pageHeader}>
        <Text style={styles.pageTitle} accessibilityRole="header">Health Summary</Text>
        <Text style={styles.pageSub}>Health overview for your linked patients</Text>
      </View>

      {loading ? (
        <LoadingState label="Loading linked patients" />
      ) : error ? (
        <ErrorState message={error} onRetry={refetch} />
      ) : dependents.length === 0 ? (
        <EmptyState icon="heart-outline" title="No linked patients" />
      ) : (
        dependents.map(dep => <PatientHealthCard key={dep.accessId} dep={dep} />)
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
  noneText:     { fontSize: 13, color: GRAY },
  sectionTitle: { fontSize: 14, fontWeight: '700', color: TEXT, borderBottomWidth: 1, borderBottomColor: BORDER, paddingBottom: 8 },


  allergyRow:  { flexDirection: 'row', alignItems: 'center', gap: 8, backgroundColor: '#FEF2F2', borderRadius: 8, padding: 10 },
  allergyText: { fontSize: 13, color: TEXT, flex: 1 },


  emergencyCard: { flexDirection: 'row', alignItems: 'center', gap: 12, backgroundColor: '#FEF2F2', borderRadius: 10, borderWidth: 1, borderColor: '#FECACA', padding: 14 },
  emergencyTitle:{ fontSize: 11, fontWeight: '600', color: RED, marginBottom: 2 },
  emergencyName: { fontSize: 14, fontWeight: '700', color: TEXT },
  emergencyPhone:{ fontSize: 13, color: GRAY, marginTop: 2 },

});
