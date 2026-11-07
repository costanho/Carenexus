import { Ionicons } from '@expo/vector-icons';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { api } from '@/lib/auth/auth.interceptor';
import {
  caregiverErrorMessage,
  parseList,
  useCaregiverActivity,
  useCaregiverDependents,
  type CaregiverConsultation,
  type CaregiverDependent,
} from '@/hooks/use-caregiver-dependents';
import { ageFromDateOnly, formatDateOnly } from '@/lib/ui/date-only';
import { EmptyState, ErrorState, LoadingState } from '@/components/web/proxy/patient/proxy-states';
import {
  BG, BORDER, GRAY, GRAY2, GREEN, ORANGE, PURPLE, PURPLE_BG, PURPLE_LIGHT, RED, TEXT, WHITE,
  avatarColor, canView, capitalize, depFirstName, depName, DependentPicker, initials,
  ScreenHeader, SectionCard, useDoctorNames, usePickedDependent, useScale,
} from './patient-proxy-mobile-shared';

interface EmergencyContact {
  contactId:    number;
  name:         string;
  relationship: string | null;
  phone:        string | null;
  isPrimary:    boolean | null;
}

interface HealthProfile {
  dateOfBirth:       string | null;
  gender:            string | null;
  bloodType:         string | null;
  healthStatus:      string | null;
  allergies:         string[];
  conditions:        string[];
  emergencyContacts: EmergencyContact[];
}

function str(v: unknown): string | null {
  return typeof v === 'string' && v.trim() ? v.trim() : null;
}

function toContacts(raw: unknown): EmergencyContact[] {
  if (!Array.isArray(raw)) return [];
  return raw
    .filter(c => c && typeof c === 'object')
    .map((c: any, i) => ({
      contactId:    typeof c.contactId === 'number' ? c.contactId : i,
      name:         str(c.name) ?? 'Contact',
      relationship: str(c.relationship),
      phone:        str(c.phone),
      isPrimary:    c.isPrimary === true,
    }));
}

/** The loved one's profile (GET /api/profile/user/{id}). Health fields come back null when records are not shared. */
function useHealthProfile(patientSystemId: string) {
  const [profile, setProfile] = useState<HealthProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [error,   setError]   = useState<string | null>(null);
  const [tick,    setTick]    = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    api.get<any>(`/api/profile/user/${patientSystemId}`)
      .then(({ data }) => {
        if (cancelled) return;
        setProfile({
          dateOfBirth:       str(data?.dateOfBirth),
          gender:            str(data?.gender),
          bloodType:         str(data?.bloodType),
          healthStatus:      str(data?.healthStatus),
          allergies:         parseList(data?.allergies),
          conditions:        parseList(data?.chronicConditions),
          emergencyContacts: toContacts(data?.emergencyContacts),
        });
        setError(null);
      })
      .catch(err => { if (!cancelled) setError(caregiverErrorMessage(err, 'Could not load the health profile.')); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [patientSystemId, tick]);

  const refetch = useCallback(() => setTick(t => t + 1), []);
  return { profile, loading, error, refetch };
}

function healthColors(s: string | null) {
  if (s === 'STABLE')   return { color: GREEN,  bg: '#D1FAE5' };
  if (s === 'MONITOR')  return { color: ORANGE, bg: '#FEF3C7' };
  if (s === 'CRITICAL') return { color: RED,    bg: '#FEE2E2' };
  return { color: GRAY, bg: '#F3F4F6' };
}
function consultationTime(c: CaregiverConsultation) {
  return new Date(c.completedAt ?? c.startedAt ?? c.createdAt ?? 0).getTime() || 0;
}

const CONSULTATIONS_LIMIT = 5;

function NoneText({ text }: { text: string }) {
  const { s } = useScale();
  return <Text style={{ fontSize: s(12), color: GRAY2, fontStyle: 'italic' }}>{text}</Text>;
}

function HealthDetails({ dep, consultations, activityLoading, activityError, onRetryActivity }: {
  dep: CaregiverDependent;
  consultations: CaregiverConsultation[];
  activityLoading: boolean;
  activityError: string | null;
  onRetryActivity: () => void;
}) {
  const { s } = useScale();
  const doctorName = useDoctorNames();
  const { profile, loading, error, refetch } = useHealthProfile(dep.patientSystemId);

  const name          = depName(dep);
  const first         = depFirstName(dep);
  const recordsShared = canView(dep, 'canViewRecords');
  const consultsShared = canView(dep, 'canViewConsultations');

  const dob    = profile?.dateOfBirth ?? dep.patientDateOfBirth;
  const gender = profile?.gender ?? dep.patientGender;
  const status = recordsShared ? profile?.healthStatus ?? dep.patientHealthStatus : null;
  const blood  = recordsShared ? profile?.bloodType ?? dep.patientBloodType : null;
  const age    = ageFromDateOnly(dob);
  const hc     = healthColors(status);
  const statusLabel = !recordsShared ? 'Not shared' : status ? capitalize(status) : 'Not recorded';

  // Only finalised consultations (the backend already hides drafts and in-progress ones)
  const recent = useMemo(
    () => consultations
      .filter(c => c.patientSystemId === dep.patientSystemId && c.status === 'COMPLETED')
      .sort((a, b) => consultationTime(b) - consultationTime(a))
      .slice(0, CONSULTATIONS_LIMIT),
    [consultations, dep.patientSystemId],
  );

  return (
    <>
      {/* Header */}
      <SectionCard>
        <View style={[styles.row, { gap: s(12) }]}>
          <View
            style={{ width: s(56), height: s(56), borderRadius: s(28), backgroundColor: avatarColor(name), alignItems: 'center', justifyContent: 'center' }}
            importantForAccessibility="no-hide-descendants"
            accessibilityElementsHidden
          >
            <Text style={{ color: WHITE, fontSize: s(18), fontWeight: '700' }}>{initials(name)}</Text>
          </View>
          <View style={{ flex: 1, minWidth: 0, gap: s(3) }}>
            <Text style={{ fontSize: s(17), fontWeight: '700', color: TEXT }} numberOfLines={1} accessibilityRole="header">{name}</Text>
            <Text style={{ fontSize: s(12), color: PURPLE, fontWeight: '500' }} numberOfLines={1}>{capitalize(dep.relationship)}</Text>
          </View>
          <View
            style={{ backgroundColor: hc.bg, borderRadius: s(12), paddingHorizontal: s(8), paddingVertical: s(3), flexShrink: 0 }}
            accessible
            accessibilityLabel={`Health status: ${statusLabel}`}
          >
            <Text style={{ fontSize: s(11), fontWeight: '700', color: hc.color }} numberOfLines={1}>{statusLabel}</Text>
          </View>
        </View>
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: s(8) }}>
          {[
            { label: 'Age',           value: age === null ? '—' : `${age} yrs` },
            { label: 'Gender',        value: capitalize(gender) },
            { label: 'Blood type',    value: recordsShared ? blood ?? '—' : 'Not shared' },
            { label: 'Date of birth', value: formatDateOnly(dob) },
          ].map(t => (
            <View key={t.label} style={[styles.tile, { flexGrow: 1, flexBasis: '45%', borderRadius: s(10), padding: s(10) }]} accessible accessibilityLabel={`${t.label}: ${t.value}`}>
              <Text style={{ fontSize: s(10), color: GRAY2, fontWeight: '500' }} numberOfLines={1}>{t.label}</Text>
              <Text style={{ fontSize: s(14), color: TEXT, fontWeight: '700' }} numberOfLines={1}>{t.value}</Text>
            </View>
          ))}
        </View>
      </SectionCard>

      {/* Conditions + allergies */}
      <SectionCard title="Chronic conditions">
        {!recordsShared ? (
          <NoneText text={`${first}'s health details are not shared with you.`} />
        ) : loading && !profile ? (
          <LoadingState compact label="Loading health profile" />
        ) : error && !profile ? (
          <ErrorState compact message={error} onRetry={refetch} />
        ) : profile && profile.conditions.length > 0 ? (
          <View style={[styles.wrap, { gap: s(6) }]}>
            {profile.conditions.map(c => (
              <View key={c} style={{ backgroundColor: PURPLE_BG, borderRadius: s(12), paddingHorizontal: s(10), paddingVertical: s(4), maxWidth: '100%' }}>
                <Text style={{ fontSize: s(12), color: PURPLE, fontWeight: '600' }}>{c}</Text>
              </View>
            ))}
          </View>
        ) : (
          <NoneText text="None recorded" />
        )}
      </SectionCard>

      <SectionCard title="Allergies">
        {!recordsShared ? (
          <NoneText text={`${first}'s health details are not shared with you.`} />
        ) : loading && !profile ? (
          <LoadingState compact label="Loading allergies" />
        ) : error && !profile ? (
          <ErrorState compact message={error} onRetry={refetch} />
        ) : profile && profile.allergies.length > 0 ? (
          <View style={{ gap: s(6) }}>
            {profile.allergies.map(a => (
              <View key={a} style={[styles.row, styles.allergy, { gap: s(8), borderRadius: s(8), padding: s(10) }]}>
                <Ionicons name="warning-outline" size={s(14)} color={RED} importantForAccessibility="no" />
                <Text style={{ fontSize: s(13), color: TEXT, flex: 1 }}>{a}</Text>
              </View>
            ))}
          </View>
        ) : (
          <NoneText text="None recorded" />
        )}
      </SectionCard>

      {/* Emergency contacts are part of the basic profile, so they show for every active grant */}
      <SectionCard title="Emergency contacts">
        {loading && !profile ? (
          <LoadingState compact label="Loading emergency contacts" />
        ) : error && !profile ? (
          <ErrorState compact message={error} onRetry={refetch} />
        ) : profile && profile.emergencyContacts.length > 0 ? (
          <View style={{ gap: s(8) }}>
            {profile.emergencyContacts.map(ec => (
              <View key={ec.contactId} style={[styles.row, styles.contact, { gap: s(10), borderRadius: s(10), padding: s(12) }]}>
                <Ionicons name="call-outline" size={s(16)} color={PURPLE} importantForAccessibility="no" />
                <View style={{ flex: 1, minWidth: 0 }}>
                  <Text style={{ fontSize: s(11), color: PURPLE, fontWeight: '600' }}>{ec.isPrimary ? 'Primary contact' : 'Contact'}</Text>
                  <Text style={{ fontSize: s(14), color: TEXT, fontWeight: '700' }} numberOfLines={2}>
                    {ec.name}{ec.relationship ? ` (${ec.relationship})` : ''}
                  </Text>
                  {ec.phone ? <Text style={{ fontSize: s(13), color: GRAY, marginTop: s(2) }} selectable>{ec.phone}</Text> : null}
                </View>
              </View>
            ))}
          </View>
        ) : (
          <NoneText text="None recorded" />
        )}
      </SectionCard>

      {/* Recent consultations, from the caregiver activity feed */}
      <SectionCard title="Recent consultations">
        {!consultsShared ? (
          <NoneText text={`${first}'s consultations are not shared with you.`} />
        ) : activityLoading && consultations.length === 0 ? (
          <LoadingState compact label="Loading consultations" />
        ) : activityError && consultations.length === 0 ? (
          <ErrorState compact message={activityError} onRetry={onRetryActivity} />
        ) : recent.length === 0 ? (
          <NoneText text="No completed consultations yet" />
        ) : (
          recent.map((c, i) => {
            const t = consultationTime(c);
            // completedAt is a timestamp (not a date-only value), shown in the device's time zone
            const when = t ? new Date(t).toLocaleDateString('en-ZW', { day: 'numeric', month: 'short', year: 'numeric' }) : '';
            const doctor = doctorName(c.doctorSystemId);
            const title = c.chiefComplaint?.trim() || c.diagnosis?.trim() || 'Consultation';
            return (
              <View key={c.consultationId}>
                {i > 0 && <View style={[styles.sep, { marginBottom: s(10) }]} />}
                <View style={[styles.row, { gap: s(10) }]} accessible accessibilityLabel={[title, c.diagnosis && c.diagnosis !== title ? `Diagnosis: ${c.diagnosis}` : null, doctor, when].filter(Boolean).join(', ')}>
                  <View style={{ width: s(34), height: s(34), borderRadius: s(10), backgroundColor: PURPLE_LIGHT, alignItems: 'center', justifyContent: 'center' }}>
                    <Ionicons name="document-text-outline" size={s(16)} color={PURPLE} importantForAccessibility="no" />
                  </View>
                  <View style={{ flex: 1, minWidth: 0 }}>
                    <Text style={{ fontSize: s(13), fontWeight: '700', color: TEXT }} numberOfLines={2}>{title}</Text>
                    {c.diagnosis && c.diagnosis.trim() !== title ? (
                      <Text style={{ fontSize: s(12), color: GRAY }} numberOfLines={2}>Diagnosis: {c.diagnosis.trim()}</Text>
                    ) : null}
                    {doctor ? <Text style={{ fontSize: s(11), color: GRAY }} numberOfLines={1}>{doctor}</Text> : null}
                  </View>
                  {when ? <Text style={{ fontSize: s(11), color: GRAY, flexShrink: 0 }}>{when}</Text> : null}
                </View>
              </View>
            );
          })
        )}
      </SectionCard>
    </>
  );
}

/** Health summary: one loved one at a time (profile, conditions, allergies, emergency contacts, consultations). */
export default function PatientProxyMobileHealthSummary({ initialPatientId, onBack }: {
  initialPatientId?: string;
  onBack: () => void;
}) {
  const { s } = useScale();
  const { dependents, loading, error, refetch } = useCaregiverDependents();
  const activity = useCaregiverActivity();
  const { value, setValue, selected } = usePickedDependent(dependents, initialPatientId, 'first');

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={{ padding: s(14), paddingBottom: s(24) }} showsVerticalScrollIndicator={false}>
      <ScreenHeader title="Health Summary" sub="A health overview for each loved one" onBack={onBack} />
      <DependentPicker dependents={dependents} value={value} onChange={setValue} includeAll={false} />
      {loading && dependents.length === 0 ? (
        <LoadingState label="Loading loved ones" />
      ) : error && dependents.length === 0 ? (
        <ErrorState message={error} onRetry={refetch} />
      ) : dependents.length === 0 ? (
        <EmptyState icon="heart-outline" title="No loved ones linked yet" />
      ) : selected ? (
        <HealthDetails
          key={selected.patientSystemId}
          dep={selected}
          consultations={activity.consultations}
          activityLoading={activity.loading}
          activityError={activity.error}
          onRetryActivity={activity.refetch}
        />
      ) : (
        <LoadingState label="Loading health summary" />
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll:  { flex: 1, backgroundColor: BG },
  row:     { flexDirection: 'row', alignItems: 'center' },
  wrap:    { flexDirection: 'row', flexWrap: 'wrap' },
  tile:    { backgroundColor: BG, gap: 3 },
  allergy: { backgroundColor: '#FEF2F2' },
  contact: { backgroundColor: BG, borderWidth: 1, borderColor: BORDER },
  sep:     { height: StyleSheet.hairlineWidth, backgroundColor: BORDER },
});
