import { type ReactNode, useEffect, useMemo, useState } from 'react';
import { ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { api } from '@/lib/auth/auth.interceptor';
import {
  caregiverErrorMessage,
  parseList,
  useCaregiverDependents,
  type CaregiverDependent,
} from '@/hooks/use-caregiver-dependents';
import { useSectionNav } from '@/hooks/use-section-nav';
import type { SectionProps } from '@/lib/nav/sections';
import { ariaLevel, decorative, Icon, NoAccessView, UiThemeProvider } from '@/components/web/ui';
import { effectivePermission } from '@/lib/caregiver/permissions';
import { DependentFilter, scopeDependents } from './dependent-records';
import { EmptyState, ErrorState, LoadingState } from './proxy-states';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const GRAY_DARK = '#4B5563';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const PURPLE = '#7C3AED';
const PURPLE_BG = '#F5F3FF';
const GREEN  = '#047857';
const ORANGE = '#B45309';
const RED    = '#B91C1C';

const AVATAR_COLORS = ['#4F46E5', '#0F766E', '#B45309', '#B91C1C', '#6D28D9', '#047857', '#0369A1'];
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
  // Unknown: a darker gray, 6.9:1 on its fill (the lighter one was 4.4:1).
  return { color: GRAY_DARK, bg: '#F3F4F6' };
}

/** Shown for a list the response did not carry: never "None recorded", which would claim there is nothing. */
const NOT_AVAILABLE = 'Not available: it may not be recorded, or not shared with you';

interface ProfileExtras {
  /**
   * null when the response carries no value. The server sends these as null to a caller without "View medical
   * records", so a null is never shown as "None recorded" (an empty list is only "[]" from the server).
   */
  allergies:         string[] | null;
  conditions:        string[] | null;
  emergencyContacts: { contactId: number; name: string; relationship: string | null; phone: string | null; isPrimary: boolean | null }[];
}

/**
 * A stored list (a JSON array string), or null when the field is absent (redacted or never recorded) or cannot be
 * read. Only a real "[]" gives an empty list, so nothing but a recorded empty list reads as "None recorded".
 */
function parseNullableList(raw: unknown): string[] | null {
  if (typeof raw !== 'string' || raw.trim() === '') return null;
  try {
    return Array.isArray(JSON.parse(raw)) ? parseList(raw) : null;
  } catch {
    return null;
  }
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
          allergies:         parseNullableList(data?.allergies),
          conditions:        parseNullableList(data?.chronicConditions),
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

/**
 * A titled block of a loved one's health card (same heading as Chronic Conditions, Allergies and Emergency
 * Contacts). A `vitalsSlot` renders its content inside one, so its heading matches the rest of the card.
 * The block titles are level 3, nested under the loved one's level-2 name, so on a page with several loved
 * ones a screen-reader user moving by heading can tell whose block they are in.
 */
export function HealthSummarySection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <View style={styles.section}>
      <Text style={styles.sectionTitle} accessibilityRole="header" {...ariaLevel(3)}>{title}</Text>
      {children}
    </View>
  );
}

/**
 * Renders the vitals of one loved one inside their health card, between the header and the health profile.
 * It owns the whole block, heading included (use `HealthSummarySection`), and returns null to show nothing,
 * for example when health measurements are not shared with the caregiver.
 */
export type HealthSummaryVitalsSlot = (dependent: CaregiverDependent) => ReactNode;

export interface PatientProxyHealthSummaryProps extends Partial<SectionProps> {
  /** Latest vitals per loved one. Without it the card has no vitals block. */
  vitalsSlot?: HealthSummaryVitalsSlot;
}

function PatientHealthCard({ dep, vitalsSlot }: { dep: CaregiverDependent; vitalsSlot?: HealthSummaryVitalsSlot }) {
  const name   = dep.patientName ?? 'Unknown';
  const age    = calcAge(dep.patientDateOfBirth);
  const { color, bg } = healthColor(dep.patientHealthStatus);
  const { extras, loading, error, refetch } = usePatientExtras(dep.patientSystemId);
  // Conditions and allergies need "View medical records" (the server leaves them out otherwise). Without it the
  // card says they are not shared, as the profile screen does, instead of reading the missing values as "none".
  const canViewRecords = effectivePermission(dep, 'canViewRecords');
  const firstName = name.split(' ')[0] || name;

  return (
    <View style={styles.card}>
      {/* Patient header */}
      <View style={styles.patHeader}>
        {/* The initials and the status dot repeat the name and the status word: hidden from assistive technology. */}
        <View style={styles.avatarWrap} {...decorative}>
          <View style={[styles.avatar, { backgroundColor: avatarColor(name), alignItems: 'center', justifyContent: 'center' }]}>
            <Text style={{ color: WHITE, fontSize: 22, fontWeight: '700' }}>{initials(name)}</Text>
          </View>
          <View style={[styles.statusDot, { backgroundColor: color }]} />
        </View>
        <View style={{ flex: 1 }}>
          <Text style={styles.patName} accessibilityRole="header" {...ariaLevel(2)}>{name}</Text>
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
            {dep.patientHealthStatus ?? (canViewRecords ? 'Unknown' : 'Not shared')}
          </Text>
        </View>
      </View>

      {/* Vitals come from the screen that owns health measurements; nothing is shown without it. */}
      {vitalsSlot ? vitalsSlot(dep) : null}

      {loading ? (
        <LoadingState compact label="Loading health profile" />
      ) : error ? (
        <ErrorState compact message={error} onRetry={refetch} />
      ) : extras ? (
        <>
          {!canViewRecords ? (
            <HealthSummarySection title="Chronic Conditions and Allergies">
              <NoAccessView
                compact
                message="Health details are not shared with you"
                sub={`Allergies and chronic conditions need "View medical records". Only ${firstName} can add it to your access.`}
              />
            </HealthSummarySection>
          ) : (
          <>
          {/* Conditions */}
          <View style={styles.section}>
            <Text style={styles.sectionTitle} accessibilityRole="header" {...ariaLevel(3)}>Chronic Conditions</Text>
            {extras.conditions === null ? (
              <Text style={styles.noneText}>{NOT_AVAILABLE}</Text>
            ) : extras.conditions.length === 0 ? (
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
            <Text style={styles.sectionTitle} accessibilityRole="header" {...ariaLevel(3)}>Allergies & Contraindications</Text>
            {extras.allergies === null ? (
              <Text style={styles.noneText}>{NOT_AVAILABLE}</Text>
            ) : extras.allergies.length === 0 ? (
              <Text style={styles.noneText}>None recorded</Text>
            ) : (
              <View style={{ gap: 6 }}>
                {extras.allergies.map(a => (
                  <View key={a} style={styles.allergyRow}>
                    <Icon name="warning-outline" size={14} color={RED} />
                    <Text style={styles.allergyText}>{a}</Text>
                  </View>
                ))}
              </View>
            )}
          </View>
          </>
          )}

          {/* Emergency contacts */}
          <View style={styles.section}>
            <Text style={styles.sectionTitle} accessibilityRole="header" {...ariaLevel(3)}>Emergency Contacts</Text>
            {extras.emergencyContacts.length === 0 ? (
              <Text style={styles.noneText}>None recorded</Text>
            ) : (
              extras.emergencyContacts.map(ec => (
                <View key={ec.contactId} style={styles.emergencyCard}>
                  <Icon name="call-outline" size={18} color={RED} />
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

/**
 * Caregiver "Health Summary": one health card per loved one. The `patient` URL param
 * (`?section=health-summary&patient=<PAT id>`) shows one loved one only, so a link from their profile opens
 * their card.
 */
export default function PatientProxyHealthSummary({ params: paramsProp, vitalsSlot }: PatientProxyHealthSummaryProps = {}) {
  // The registry may render this screen without props; the dashboard's navigation is then read from context.
  const nav = useSectionNav();
  const params = paramsProp ?? nav.params;
  const { setParams } = nav;
  const { dependents, loading, error, refetch } = useCaregiverDependents();
  const scope = useMemo(() => scopeDependents(dependents, params.patient), [dependents, params.patient]);
  const selectPatient = (patientSystemId: string | null) => setParams({ patient: patientSystemId ?? undefined });

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} showsVerticalScrollIndicator={false} focusable>
      <View style={styles.pageHeader}>
        <Text style={styles.pageTitle} accessibilityRole="header" {...ariaLevel(1)}>Health Summary</Text>
        <Text style={styles.pageSub}>Health overview for your linked patients</Text>
      </View>

      {loading ? (
        <LoadingState label="Loading linked patients" />
      ) : error ? (
        <ErrorState message={error} onRetry={refetch} />
      ) : dependents.length === 0 ? (
        <EmptyState icon="heart-outline" title="No linked patients" />
      ) : scope.unknown ? (
        <View style={styles.unknown}>
          <EmptyState
            icon="person-outline"
            title="This person is not one of your linked patients"
            sub="The link may be out of date, or your access to them has ended."
          />
          <TouchableOpacity
            style={styles.showAllBtn}
            onPress={() => selectPatient(null)}
            activeOpacity={0.8}
            accessibilityRole="button"
          >
            <Text style={styles.showAllText}>Show all linked patients</Text>
          </TouchableOpacity>
        </View>
      ) : (
        <>
          <UiThemeProvider role="proxy">
            <DependentFilter
              label="Show health summary for"
              dependents={dependents}
              selectedId={scope.selectedId}
              onChange={selectPatient}
            />
          </UiThemeProvider>
          {scope.dependents.map(dep => <PatientHealthCard key={dep.accessId} dep={dep} vitalsSlot={vitalsSlot} />)}
        </>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll:     { flex: 1, backgroundColor: BG },
  container:  { padding: 24, paddingBottom: 48, gap: 20 },
  pageHeader: { marginBottom: 4, flexWrap: 'wrap', rowGap: 12, columnGap: 12 },
  pageTitle:  { fontSize: 22, fontWeight: '700', color: TEXT },
  pageSub:    { fontSize: 13, color: GRAY, marginTop: 3 },

  unknown:     { alignItems: 'center' },
  showAllBtn:  { borderWidth: 1, borderColor: PURPLE, borderRadius: 8, paddingHorizontal: 14, paddingVertical: 9, backgroundColor: WHITE },
  showAllText: { fontSize: 13, color: PURPLE, fontWeight: '600' },

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
  // Darker than GRAY: 4.5:1 or more on the pale red card (GRAY was 4.4:1).
  emergencyPhone:{ fontSize: 13, color: GRAY_DARK, marginTop: 2 },

});
