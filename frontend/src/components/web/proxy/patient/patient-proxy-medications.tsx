import { type ReactNode, useCallback } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, ariaPressed, Banner, Card, colors, CONTROL_HEIGHT, EmptyView, ErrorView, fontSize, LoadingView,
  NoAccessView, PagedList, radius, ROLE_THEME, Screen, SectionCard, space, Tabs, useUiTheme,
} from '@/components/web/ui';
import {
  DateSwitcher, DoseList, LinkedPrescription, parsePrescriptionFilter, PrescriptionCard, type PrescriptionFilter,
  prescriptionFilterLabel, PrescriptionStatusFilter,
} from '@/components/web/medication';
import { RemindersPanel } from '@/components/web/reminders';
import { type CaregiverDependent, useCaregiverDependents } from '@/hooks/use-caregiver-dependents';
import { useCurrentUser } from '@/hooks/use-current-user';
import { usePatientPrescriptions } from '@/hooks/use-prescriptions';
import { useSectionNav } from '@/hooks/use-section-nav';
import { effectivePermission, isGrantActive } from '@/lib/caregiver/permissions';
import { normaliseScheduleDate } from '@/lib/medication/dates';
import type { SectionProps } from '@/lib/nav/sections';
import { clinicToday } from '@/lib/time/clinic';

// Caregiver "Medications" section: for each linked patient the caregiver may see medications for, the
// prescriptions and the daily dose schedule.
//
// URL params (CONTRACTS 7.1 and 12.1):
//   patient       the linked patient to show (a PAT-... id); absent = everyone
//   tab=schedule  the dose schedule; absent = prescriptions. With tab=schedule, `id` names a dose of the day
//   tab=reminders the reminders of the loved one (manual, follow-up and care-plan reminders)
//   date          the day the schedule opens on ("yyyy-MM-dd"); absent = the clinic's today
//   id            without `tab`: a prescription to show above the lists
//   status        the status filter of the prescription lists
//
// What is shown comes only from effectivePermission / isGrantActive: the schedule and the prescriptions need
// the medications permission, and marking doses also needs the permission to manage the care plan. Reminders
// can be read with any active grant and managed with the permission to manage the care plan. The server checks
// every request again; a refusal is shown for that patient only.

type TabKey = 'prescriptions' | 'schedule' | 'reminders';

const TABS: readonly { key: TabKey; label: string }[] = [
  { key: 'prescriptions', label: 'Prescriptions' },
  { key: 'schedule',      label: 'Schedule' },
  { key: 'reminders',     label: 'Reminders' },
];

function toTab(value: string | undefined): TabKey {
  return value === 'schedule' || value === 'reminders' ? value : 'prescriptions';
}

const PAGE_SIZE = 10;

function positiveInt(value: string | undefined): number | null {
  if (!value || !/^\d+$/.test(value)) return null;
  const n = Number(value);
  return Number.isSafeInteger(n) && n > 0 ? n : null;
}

function nameOf(dependent: CaregiverDependent): string {
  return dependent.patientName?.trim() || 'Name not set';
}

function listNames(dependents: CaregiverDependent[]): string {
  return dependents.map(nameOf).join(', ');
}

/** The prescriptions of one linked patient, with their own loading, failed (Retry), no-access and empty states. */
function DependentPrescriptions({ dependent, filter }: { dependent: CaregiverDependent; filter: PrescriptionFilter }) {
  const name = nameOf(dependent);
  const list = usePatientPrescriptions(dependent.patientSystemId, {
    status:      filter === 'ALL' ? null : filter,
    pageSize:    PAGE_SIZE,
    patientName: name,
  });
  return (
    <SectionCard title={name} subtitle={dependent.relationship?.trim() || undefined}>
      <PagedList
        state={list}
        label={`Prescriptions of ${name}`}
        keyOf={item => item.prescription_id}
        renderItem={item => <PrescriptionCard item={item} />}
        noAccessMessage={`You don't have access to the medications of ${name}.`}
        empty={filter === 'ALL'
          ? { icon: 'medkit-outline', title: 'No prescriptions yet' }
          : { icon: 'medkit-outline', title: `No ${prescriptionFilterLabel(filter).toLowerCase()} prescriptions` }}
      />
    </SectionCard>
  );
}

/** Chooses whose medications are shown: everyone, or one linked patient (kept in the `patient` URL param). */
function DependentChooser({ dependents, selected, onSelect }: {
  dependents: CaregiverDependent[];
  selected:   string | null;
  onSelect:   (patientSystemId: string | null) => void;
}) {
  const { accent, accentBg } = useUiTheme();
  const options: { id: string | null; label: string }[] = [
    { id: null, label: 'Everyone' },
    ...dependents.map(d => ({ id: d.patientSystemId, label: nameOf(d) })),
  ];
  return (
    <View style={styles.chooser} role="group" aria-label="Show medications for">
      {options.map(option => {
        const on = option.id === selected;
        return (
          <Pressable
            key={option.id ?? 'everyone'}
            accessibilityRole="button"
            accessibilityState={{ selected: on }}
            {...ariaPressed(on)}
            onPress={() => { if (!on) onSelect(option.id); }}
            style={[styles.chip, on && { borderColor: accent, backgroundColor: accentBg }]}
          >
            <Text style={[styles.chipText, on && { color: accent, fontWeight: '700' }]}>{option.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

/**
 * The Reminders tab: one reminders list per loved one in view. Any active grant may read a loved one's
 * reminders; adding, pausing, editing and deleting need the permission to manage the care plan. Caregivers are
 * not notified by reminders (they reach the patient), so there is no link to notification settings here.
 */
function RemindersBody({ dependents, theme }: { dependents: CaregiverDependent[]; theme?: SectionProps['theme'] }) {
  const several = dependents.length > 1;
  return (
    <>
      {dependents.map(dependent => {
        const name = nameOf(dependent);
        const active = isGrantActive(dependent);
        return (
          <View key={dependent.patientSystemId} style={styles.person}>
            {several ? (
              <Text style={styles.personName} accessibilityRole="header" {...ariaLevel(2)}>{name}</Text>
            ) : null}
            {active ? (
              <RemindersPanel
                patientSystemId={dependent.patientSystemId}
                canManage={effectivePermission(dependent, 'canManageCarePlan')}
                theme={theme ?? ROLE_THEME.proxy}
              />
            ) : (
              <NoAccessView
                compact
                message={`Your access to ${name} has ended.`}
                sub="Ask your loved one to renew your caregiver access if you still help with their care."
              />
            )}
          </View>
        );
      })}
    </>
  );
}

export default function PatientProxyMedications({ params: paramsProp, theme }: Partial<SectionProps> = {}) {
  const nav = useSectionNav();
  // The registry may render this screen without passing the section params; the URL is the source either way.
  const params = paramsProp ?? nav.params;
  const { setParams } = nav;
  const { user } = useCurrentUser();
  const { dependents, loading, error, refetch } = useCaregiverDependents();

  const today = clinicToday();
  const tab = toTab(params.tab);
  const date = normaliseScheduleDate(params.date, today);
  const filter = parsePrescriptionFilter(params.status);
  const highlightedDose = tab === 'schedule' ? positiveInt(params.id) : null;
  const linkedPrescription = params.tab ? null : positiveInt(params.id);
  const selectedId = params.patient?.trim() || null;

  const selectPatient = useCallback((patientSystemId: string | null) => {
    // An opened prescription or a pointed-out dose belongs to the patient it was linked with.
    setParams({ patient: patientSystemId ?? undefined, id: undefined });
  }, [setParams]);
  const selectTab = useCallback((key: TabKey) => {
    // `id` means a prescription on one tab and a dose on the other, so it does not travel between them.
    setParams({ tab: key === 'prescriptions' ? undefined : key, id: undefined });
  }, [setParams]);
  const setDate = useCallback((next: string) => {
    setParams({ date: next === clinicToday() ? undefined : next, id: undefined });
  }, [setParams]);
  const setFilter = useCallback((next: PrescriptionFilter) => {
    setParams({ status: next === 'ALL' ? undefined : next });
  }, [setParams]);
  const closeLinked = useCallback(() => setParams({ id: undefined }), [setParams]);

  // Who the caregiver may see medications for right now. An ended grant (expired, revoked) shows nothing.
  const viewable = dependents.filter(d => effectivePermission(d, 'canViewMedications'));
  const ended    = dependents.filter(d => !isGrantActive(d));
  const denied   = dependents.filter(d => isGrantActive(d) && !effectivePermission(d, 'canViewMedications'));

  const selected = selectedId ? dependents.find(d => d.patientSystemId === selectedId) ?? null : null;
  const shown = selectedId ? viewable.filter(d => d.patientSystemId === selectedId) : viewable;
  const patientNameOf = (patientSystemId: string) => {
    const dependent = dependents.find(d => d.patientSystemId === patientSystemId);
    return dependent ? nameOf(dependent) : null;
  };

  let content: ReactNode;
  if (loading && dependents.length === 0) {
    content = <LoadingView label="Loading linked patients" />;
  } else if (error && dependents.length === 0) {
    content = <ErrorView message={error} onRetry={refetch} />;
  } else if (dependents.length === 0) {
    content = <EmptyView icon="people-outline" title="No linked patients yet" sub="Medications appear here once you are linked to a loved one." />;
  } else {
    let body: ReactNode;
    if (selectedId && !selected) {
      body = (
        <EmptyView
          icon="person-outline"
          title="Patient not found"
          sub="This person is not one of your linked patients, or your access to them has ended."
          action={{ label: 'Show everyone', onPress: () => selectPatient(null) }}
        />
      );
    } else if (tab === 'reminders') {
      body = <RemindersBody dependents={selected ? [selected] : dependents} theme={theme} />;
    } else if (viewable.length === 0) {
      body = (
        <NoAccessView
          message="You don't have access to medications for your linked patients."
          sub="Ask your loved one to update the permissions on your caregiver access."
        />
      );
    } else if (selected && shown.length === 0) {
      body = (
        <NoAccessView
          message={isGrantActive(selected)
            ? `You don't have access to the medications of ${nameOf(selected)}.`
            : `Your access to ${nameOf(selected)} has ended.`}
          sub="Ask your loved one to update the permissions on your caregiver access."
        />
      );
    } else if (tab === 'schedule') {
      body = (
        <>
          <Card>
            <DateSwitcher date={date} onChange={setDate} />
          </Card>
          {shown.map(dependent => {
            const name = nameOf(dependent);
            // Marking needs both permissions, exactly as the server checks them.
            const canMark = effectivePermission(dependent, 'canViewMedications')
              && effectivePermission(dependent, 'canManageCarePlan');
            return (
              <DoseList
                // A new instance per patient: nothing of one person's schedule is carried over to another's.
                key={dependent.patientSystemId}
                patientSystemId={dependent.patientSystemId}
                personName={name}
                viewerSystemId={user?.system_id}
                canMark={canMark}
                readOnlyNote={`You can see this schedule but not mark doses. Marking needs permission to manage the care plan of ${name}.`}
                date={date}
                onDateChange={setDate}
                showDateSwitcher={false}
                highlightScheduleId={highlightedDose}
                // The grant changed since the page was loaded: read it again so the controls match it.
                onMarkDenied={refetch}
              />
            );
          })}
        </>
      );
    } else {
      body = (
        <>
          {linkedPrescription !== null ? (
            <Card>
              <LinkedPrescription prescriptionId={linkedPrescription} patientNameOf={patientNameOf} onClose={closeLinked} />
            </Card>
          ) : null}
          <PrescriptionStatusFilter value={filter} onChange={setFilter} />
          {shown.map(dependent => (
            <DependentPrescriptions key={dependent.patientSystemId} dependent={dependent} filter={filter} />
          ))}
        </>
      );
    }

    // The access banners are about medications; reminders state access per person in their own block.
    const medicationsTab = tab !== 'reminders';
    content = (
      <>
        {/* A reload of the list of linked patients failed: what is on screen stays, with a way to try again. */}
        {error ? <Banner tone="danger" text={error} action={{ label: 'Retry', onPress: refetch }} /> : null}
        {dependents.length > 1 || selectedId ? (
          <DependentChooser dependents={dependents} selected={selectedId} onSelect={selectPatient} />
        ) : null}
        <Tabs tabs={TABS} active={tab} onChange={selectTab} label="Medication views" />
        {medicationsTab && viewable.length > 0 && !selectedId && denied.length > 0 ? (
          <Banner tone="warning" icon="lock-closed-outline" text={`You don't have access to medications for ${listNames(denied)}.`} />
        ) : null}
        {medicationsTab && viewable.length > 0 && !selectedId && ended.length > 0 ? (
          <Banner tone="info" text={`Your access to ${listNames(ended)} has ended.`} />
        ) : null}
        <View key={tab} style={styles.panel} role="tabpanel" aria-label={TABS.find(t => t.key === tab)?.label}>
          {body}
        </View>
      </>
    );
  }

  return (
    <Screen
      title="Medications"
      // The e2e smoke suite finds this section by its subtitle.
      subtitle="Prescriptions for your linked patients"
      theme={theme ?? ROLE_THEME.proxy}
    >
      {content}
    </Screen>
  );
}

const styles = StyleSheet.create({
  panel:      { gap: space.lg, minWidth: 0 },
  person:     { gap: space.sm, minWidth: 0 },
  personName: { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  chooser:  { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  chip: {
    minHeight: CONTROL_HEIGHT, justifyContent: 'center', paddingHorizontal: space.md, paddingVertical: space.xs,
    borderWidth: 1, borderColor: colors.borderInput, borderRadius: radius.pill, backgroundColor: colors.surface,
    maxWidth: '100%',
  },
  chipText: { fontSize: fontSize.sm, fontWeight: '500', color: colors.textMuted },
});
