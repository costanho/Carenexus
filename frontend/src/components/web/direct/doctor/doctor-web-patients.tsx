import { useCallback, useState } from 'react';
import { StyleSheet, Text, useWindowDimensions, View } from 'react-native';
import {
  ariaLevel, Button, Card, colors, decorative, fontSize, Icon, type IoniconsName, PagedList, PHONE_BREAKPOINT, radius,
  ROLE_THEME, Screen, SelectField, type SelectOption, space, STATUS_TONES, StatusBadge, statusTone, TextField, webOnly,
} from '@/components/web/ui';
import { useDoctorMessagePatient } from '@/hooks/use-doctor-message-patient';
import type { HealthStatusResult } from '@/hooks/use-doctor-patient-detail';
import {
  type DoctorPatientDto, type DoctorPatientSummary, HEALTH_STATUSES, type HealthStatus, PATIENT_GENDERS,
  PATIENT_QUERY_MAX, PATIENT_SORTS, type PatientGender, type PatientSort, patientFullName, toStringList,
  useDoctorPatientSearch,
} from '@/hooks/use-doctor-patients';
import { useSectionNav } from '@/hooks/use-section-nav';
import type { SectionProps } from '@/lib/nav/sections';
import { DoctorPatientPanel, focusSymptomParam, type PatientDetailTab } from './patient-detail';
import {
  ageText, GENDER_LABEL, genderLabel, HEALTH_STATUS_LABEL, healthStatusLabel, initialsOf, lastVisitText,
  nextAppointmentText, PATIENT_SORT_LABEL, plural,
} from './patient-detail/format';

// Doctor > Patients. The list is searched, filtered, sorted and paged by the server
// (GET /api/doctors/patients/search); a card opens the patient detail panel, which is bound to the `patient`
// URL param so a refresh, the Back button and notification links keep it open.
//
// URL params of this section: `patient` (PAT id of the open panel), `tab` (panel tab), `id` (on tab=vitals: the
// symptom a HEALTH_TRACKING notification points at), `status`, `gender` and `sort` (list filters). The search
// text stays out of the URL: it is typed patient data.

type HealthFilter = HealthStatus | 'ALL';
type GenderFilter = PatientGender | 'ALL';

const HEALTH_OPTIONS: readonly SelectOption<HealthFilter>[] = [
  { value: 'ALL', label: 'All statuses' },
  ...HEALTH_STATUSES.map(s => ({ value: s, label: HEALTH_STATUS_LABEL[s] })),
];
const GENDER_OPTIONS: readonly SelectOption<GenderFilter>[] = [
  { value: 'ALL', label: 'All genders' },
  ...PATIENT_GENDERS.map(g => ({ value: g, label: GENDER_LABEL[g] })),
];
const SORT_OPTIONS: readonly SelectOption<PatientSort>[] = PATIENT_SORTS.map(s => ({ value: s, label: PATIENT_SORT_LABEL[s] }));

const DEFAULT_SORT: PatientSort = 'name';

function oneOf<T extends string>(value: string | undefined, allowed: readonly T[]): T | null {
  const wanted = (value ?? '').trim();
  return allowed.find(a => a === wanted || a.toUpperCase() === wanted.toUpperCase()) ?? null;
}

export type DoctorWebPatientsProps = Partial<SectionProps> & {
  /** Switch to Messages with this thread open. Default: the dashboard's own `openThread`. */
  onOpenThread?: (threadId: number) => void;
  /**
   * Tabs added to the patient detail panel after the dashboard's own ones (History, Care team, Vitals, Alerts,
   * Care plans, Documents, Referrals, Reminders).
   */
  extraTabs?: readonly PatientDetailTab[];
};

export default function DoctorWebPatients(props: DoctorWebPatientsProps = {}) {
  const nav = useSectionNav();
  const params     = props.params ?? nav.params;
  const openThread = props.onOpenThread ?? props.openThread ?? nav.openThread;
  const { setParams } = nav;
  const { width } = useWindowDimensions();
  const phone = width < PHONE_BREAKPOINT;

  const health: HealthFilter = oneOf(params.status, HEALTH_STATUSES) ?? 'ALL';
  const gender: GenderFilter = oneOf(params.gender, PATIENT_GENDERS) ?? 'ALL';
  const sort:   PatientSort  = oneOf(params.sort, PATIENT_SORTS) ?? DEFAULT_SORT;
  const [search, setSearch] = useState('');

  const list = useDoctorPatientSearch({ query: search, healthStatus: health, gender, sort });
  const { refetch, setItems } = list;
  const { messagePatient, pendingPatient } = useDoctorMessagePatient(openThread);

  const filtered = search.trim() !== '' || health !== 'ALL' || gender !== 'ALL';
  const clearFilters = useCallback(() => {
    setSearch('');
    setParams({ status: undefined, gender: undefined });
  }, [setParams]);

  const selectedPatient = (params.patient ?? '').trim() || null;
  const openPatient = useCallback(
    (patientSystemId: string) => setParams({ patient: patientSystemId, tab: undefined, id: undefined }),
    [setParams],
  );
  const closePatient = useCallback(
    () => setParams({ patient: undefined, tab: undefined, id: undefined }),
    [setParams],
  );

  const onHealthStatusChanged = useCallback((result: HealthStatusResult) => {
    if (!result.changed) return;
    // Show the new status on the card at once, then reload: the summary counts changed, and with a status
    // filter or the status sort the row may no longer belong where it is.
    setItems(rows => rows.map(r => (
      r.patientSystemId === result.patientSystemId ? { ...r, healthStatus: result.healthStatus } : r
    )));
    refetch();
  }, [refetch, setItems]);

  // The doctor removed themselves from the patient's care team and can no longer see them.
  const onAccessLost = useCallback(() => {
    closePatient();
    refetch();
  }, [closePatient, refetch]);

  const filters = (
    <View style={styles.filters}>
      <View style={styles.searchField}>
        <TextField
          label="Search patients"
          value={search}
          onChange={setSearch}
          placeholder="Name, email, phone or patient ID"
          maxLength={PATIENT_QUERY_MAX}
          autoCapitalize="none"
          autoComplete="off"
          inputMode="search"
        />
      </View>
      <View style={styles.selectField}>
        <SelectField<HealthFilter>
          label="Health status"
          value={health}
          onChange={value => setParams({ status: value === 'ALL' ? undefined : value })}
          options={HEALTH_OPTIONS}
        />
      </View>
      <View style={styles.selectField}>
        <SelectField<GenderFilter>
          label="Gender"
          value={gender}
          onChange={value => setParams({ gender: value === 'ALL' ? undefined : value })}
          options={GENDER_OPTIONS}
        />
      </View>
      <View style={styles.selectField}>
        <SelectField<PatientSort>
          label="Sort by"
          value={sort}
          onChange={value => setParams({ sort: value === DEFAULT_SORT ? undefined : value })}
          options={SORT_OPTIONS}
        />
      </View>
      {filtered ? (
        <Button label="Clear filters" variant="ghost" icon="close-circle-outline" onPress={clearFilters} style={styles.clear} />
      ) : null}
    </View>
  );

  return (
    <Screen
      title="My Patients"
      subtitle="Patients on your care team, or with an appointment or consultation with you"
      theme={props.theme ?? ROLE_THEME.doctor}
      actions={(
        <Button
          label="Refresh"
          variant="secondary"
          icon="refresh-outline"
          onPress={refetch}
          loading={list.refreshing}
          accessibilityLabel="Refresh the patient list"
        />
      )}
    >
      <SummaryRow summary={list.summary} />

      <PagedList
        state={list}
        label="Patients"
        header={filters}
        keyOf={p => p.patientSystemId}
        noAccessMessage="Your account cannot view a patient list."
        empty={filtered
          ? {
              icon: 'search-outline',
              title: 'No patients match',
              sub: 'Try a different search, or clear the filters to see all your patients.',
              action: { label: 'Clear filters', onPress: clearFilters },
            }
          : {
              icon: 'people-outline',
              title: 'No patients yet',
              sub: 'Patients appear here once they are on your care team or have an appointment or consultation with you.',
            }}
        renderItem={patient => (
          <PatientCard
            patient={patient}
            stacked={phone}
            onOpen={() => openPatient(patient.patientSystemId)}
            onMessage={() => { void messagePatient(patient.patientSystemId); }}
            messaging={pendingPatient === patient.patientSystemId}
          />
        )}
      />

      <DoctorPatientPanel
        patientSystemId={selectedPatient}
        onClose={closePatient}
        extraTabs={props.extraTabs}
        onOpenThread={openThread}
        onHealthStatusChanged={onHealthStatusChanged}
        focusSymptomId={focusSymptomParam(params.tab, params.id)}
        onAccessLost={onAccessLost}
        // A new appointment changes the card's next appointment.
        onBooked={refetch}
      />
    </Screen>
  );
}

// ─── Summary counts ───────────────────────────────────────────────────────────

function SummaryRow({ summary }: { summary: DoctorPatientSummary | null }) {
  const cards: { label: string; value: number | null; icon: IoniconsName; fg: string; bg: string }[] = [
    { label: 'Total',    value: summary?.total ?? null,    icon: 'people-outline',           fg: ROLE_THEME.doctor.accent,   bg: ROLE_THEME.doctor.accentBg },
    { label: 'Stable',   value: summary?.stable ?? null,   icon: 'checkmark-circle-outline', fg: STATUS_TONES.success.fg,    bg: STATUS_TONES.success.bg },
    { label: 'Monitor',  value: summary?.monitor ?? null,  icon: 'alert-circle-outline',     fg: STATUS_TONES.warning.fg,    bg: STATUS_TONES.warning.bg },
    { label: 'Critical', value: summary?.critical ?? null, icon: 'warning',                  fg: STATUS_TONES.danger.fg,     bg: STATUS_TONES.danger.bg },
  ];
  return (
    <View style={styles.stats} role="group" aria-label="Patients by health status">
      {cards.map(card => (
        <View key={card.label} style={styles.stat}>
          <View style={[styles.statIcon, { backgroundColor: card.bg }]}>
            <Icon name={card.icon} size={20} color={card.fg} />
          </View>
          <Text style={styles.statValue}>{card.value === null ? '—' : card.value}</Text>
          <Text style={styles.statLabel}>{card.label}</Text>
        </View>
      ))}
    </View>
  );
}

// ─── Patient card ─────────────────────────────────────────────────────────────

/** Elements inside a card that handle their own clicks; a click on them does not also open the detail panel. */
const INTERACTIVE = 'button, a, input, select, textarea, [role="button"], [role="link"]';

function PatientCard({ patient, stacked, onOpen, onMessage, messaging }: {
  patient:   DoctorPatientDto;
  /** Phone layout: the appointment facts go under the identity instead of beside it. */
  stacked:   boolean;
  onOpen:    () => void;
  onMessage: () => void;
  messaging: boolean;
}) {
  const name       = patientFullName(patient);
  const facts      = [ageText(patient.dateOfBirth), genderLabel(patient.gender), patient.bloodType ? `Blood type ${patient.bloodType}` : null].filter(Boolean);
  const allergies  = toStringList(patient.allergies);
  const conditions = toStringList(patient.chronicConditions);
  const next       = nextAppointmentText(patient.nextAppointmentAt, patient.nextAppointmentType);
  const lastVisit  = lastVisitText(patient.lastVisitAt);
  const visits     = patient.completedVisits ?? 0;

  // Pointer convenience only: anywhere on the card opens the panel. Keyboard and screen-reader users use the
  // "View details" button, so the card itself is not a control and holds no nested buttons.
  const onCardClick = (event: { target?: unknown }) => {
    const target = event.target as { closest?: (selector: string) => unknown } | null | undefined;
    if (target?.closest?.(INTERACTIVE)) return;
    if (typeof window !== 'undefined' && window.getSelection?.()?.toString()) return; // selecting the id or email
    onOpen();
  };

  return (
    <View style={styles.cardHit} {...webOnly({ onClick: onCardClick })}>
      <Card style={[styles.card, stacked && styles.cardStacked]}>
        <View style={styles.cardMain}>
          <View style={styles.identity}>
            <View style={styles.avatar} {...decorative}>
              <Text style={styles.avatarText}>{initialsOf(name)}</Text>
            </View>
            <View style={styles.identityText}>
              <Text style={styles.name} accessibilityRole="header" {...ariaLevel(2)}>{name}</Text>
              {facts.length > 0 ? <Text style={styles.meta}>{facts.join(' · ')}</Text> : null}
              <Text style={styles.patientId} selectable accessibilityLabel={`Patient ID ${patient.patientSystemId}`}>
                {patient.patientSystemId}
              </Text>
            </View>
            <StatusBadge label={healthStatusLabel(patient.healthStatus)} tone={statusTone('health', patient.healthStatus)} />
          </View>

          {patient.email || patient.phone ? (
            <View style={styles.contactRow}>
              {patient.email ? <IconLine icon="mail-outline" text={patient.email} selectable /> : null}
              {patient.phone ? <IconLine icon="call-outline" text={patient.phone} selectable /> : null}
            </View>
          ) : null}

          {allergies.length > 0 ? (
            <IconLine icon="warning-outline" tone="danger" text={`Allergies: ${allergies.join(', ')}`} />
          ) : null}
          {conditions.length > 0 ? (
            <IconLine icon="heart-outline" tone="warning" text={`Chronic conditions: ${conditions.join(', ')}`} />
          ) : null}
        </View>

        <View style={[styles.cardSide, stacked && styles.cardSideStacked]}>
          <Fact label="Next appointment" value={next} emptyText="No upcoming appointment" />
          <Fact label="Last visit" value={lastVisit} emptyText="No completed visits yet" />
          <Fact label="Completed visits" value={plural(visits, 'visit')} />
          <View style={styles.cardActions}>
            <Button
              label="View details"
              variant="secondary"
              size="sm"
              icon="person-circle-outline"
              onPress={onOpen}
              accessibilityLabel={`View details for ${name}`}
            />
            <Button
              label="Message"
              variant="ghost"
              size="sm"
              icon="chatbubble-ellipses-outline"
              onPress={onMessage}
              loading={messaging}
              accessibilityLabel={`Message ${name}`}
            />
          </View>
        </View>
      </Card>
    </View>
  );
}

function IconLine({ icon, text, tone, selectable = false }: {
  icon: IoniconsName;
  text: string;
  /** Colours the icon and the text; the words themselves say what the line is about. */
  tone?: 'danger' | 'warning';
  selectable?: boolean;
}) {
  const fg = tone ? STATUS_TONES[tone].fg : null;
  return (
    <View style={styles.iconLine}>
      <Icon name={icon} size={14} color={fg ?? colors.textMuted} style={styles.iconLineIcon} />
      <Text style={[styles.iconLineText, fg ? { color: fg, fontWeight: '600' } : null]} selectable={selectable}>{text}</Text>
    </View>
  );
}

function Fact({ label, value, emptyText }: { label: string; value: string | null; emptyText?: string }) {
  return (
    <View style={styles.fact}>
      <Text style={styles.factLabel}>{label}</Text>
      <Text style={[styles.factValue, !value && styles.factEmpty]}>{value ?? emptyText ?? 'Not provided'}</Text>
    </View>
  );
}

// ─── Styles ──────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  stats: { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  stat: {
    flexGrow: 1, flexShrink: 1, flexBasis: 130, minWidth: 0, gap: space.xs, padding: space.md,
    backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderRadius: radius.lg,
  },
  statIcon:  { width: 38, height: 38, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  statValue: { fontSize: 26, fontWeight: '700', color: colors.text },
  statLabel: { fontSize: fontSize.xs, color: colors.textMuted },

  filters: {
    flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-end', gap: space.md, padding: space.lg,
    backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderRadius: radius.lg,
  },
  searchField: { flexGrow: 2, flexShrink: 1, flexBasis: 240, minWidth: 0 },
  // Two selects share a row at 375 px (311 px inside the card): 2 x 140 + the gap.
  selectField: { flexGrow: 1, flexShrink: 1, flexBasis: 140, minWidth: 0 },
  clear:       { alignSelf: 'flex-end' },

  cardHit:     { minWidth: 0, cursor: 'pointer' } as never,
  card:        { flexDirection: 'row', flexWrap: 'wrap', gap: space.lg },
  cardStacked: { flexDirection: 'column', flexWrap: 'nowrap', gap: space.md },
  cardMain:    { flexGrow: 3, flexShrink: 1, flexBasis: 320, minWidth: 0, gap: space.sm },
  cardSide:    { flexGrow: 2, flexShrink: 1, flexBasis: 260, minWidth: 0, gap: space.sm },
  cardSideStacked: { flexGrow: 0, flexBasis: 'auto', paddingTop: space.md, borderTopWidth: 1, borderTopColor: colors.border },

  identity:     { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: space.md },
  avatar: {
    width: 44, height: 44, borderRadius: 22, alignItems: 'center', justifyContent: 'center', flexShrink: 0,
    backgroundColor: ROLE_THEME.doctor.accentBg,
  },
  avatarText:   { color: ROLE_THEME.doctor.accent, fontSize: fontSize.lg, fontWeight: '700' },
  identityText: { flexGrow: 1, flexShrink: 1, flexBasis: 140, minWidth: 0, gap: 2 },
  name:         { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  meta:         { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  patientId:    { fontSize: fontSize.xs, fontWeight: '600', color: colors.textMuted, alignSelf: 'flex-start' },

  contactRow:   { flexDirection: 'row', flexWrap: 'wrap', columnGap: space.lg, rowGap: space.xs },
  iconLine:     { flexDirection: 'row', alignItems: 'flex-start', gap: space.xs, minWidth: 0, maxWidth: '100%' },
  iconLineIcon: { marginTop: 2, flexShrink: 0 },
  iconLineText: { flexShrink: 1, minWidth: 0, fontSize: fontSize.sm, lineHeight: 19, color: colors.text },

  fact:        { flexDirection: 'row', flexWrap: 'wrap', columnGap: space.sm, rowGap: 2 },
  factLabel:   { width: 130, maxWidth: '100%', fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  factValue:   { flexGrow: 1, flexShrink: 1, flexBasis: 120, minWidth: 0, fontSize: fontSize.sm, fontWeight: '600', color: colors.text, lineHeight: 19 },
  factEmpty:   { fontWeight: '400', color: colors.textSubtle },
  cardActions: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, marginTop: space.xs },
});
