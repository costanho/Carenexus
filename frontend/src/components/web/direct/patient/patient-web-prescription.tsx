import { useCallback } from 'react';
import { StyleSheet, View } from 'react-native';
import { RequestCorrectionButton } from '@/components/web/corrections';
import {
  DoseList, LinkedPrescription, parsePrescriptionFilter, PrescriptionCard, type PrescriptionFilter,
  prescriptionFilterLabel, PrescriptionStatusFilter,
} from '@/components/web/medication';
import { RemindersPanel } from '@/components/web/reminders';
import {
  Card, ErrorView, IconButton, LoadingView, PagedList, ROLE_THEME, Screen, SectionCard, space, Tabs,
} from '@/components/web/ui';
import { useCurrentUser } from '@/hooks/use-current-user';
import { type Prescription, usePrescriptionCounts, usePrescriptions } from '@/hooks/use-prescriptions';
import { useSectionNav } from '@/hooks/use-section-nav';
import { normaliseScheduleDate } from '@/lib/medication/dates';
import type { SectionProps } from '@/lib/nav/sections';
import { clinicToday } from '@/lib/time/clinic';

// Patient "Prescriptions" section. Two tabs, kept in the `tab` URL param so a refresh and Back keep them:
//   Medications (default): the medication schedule of a day (mark doses taken or missed) above the prescription
//                          history. `tab=schedule` (dose reminder links) opens this tab too.
//   Reminders (tab=reminders): the patient's own reminders (manual, follow-up, care plan). Manual reminder
//                          notifications link here, deliberately without an id.
//
// URL params (CONTRACTS 7.1 and 12.1):
//   tab=schedule   the link is about the schedule (dose reminders); `id` then names a dose of the day, not a prescription
//   tab=reminders  the Reminders tab
//   date           the day the schedule opens on ("yyyy-MM-dd"); absent = the clinic's today
//   id             without `tab`: a prescription to show at the top of the screen (new, expired or cancelled prescription links)
//   status         the status filter of the list

const PAGE_SIZE = 20;

type PrescriptionTab = 'medications' | 'reminders';

const TAB_LABEL: Record<PrescriptionTab, string> = { medications: 'Medications', reminders: 'Reminders' };

const TABS: { key: PrescriptionTab; label: string }[] = [
  { key: 'medications', label: TAB_LABEL.medications },
  { key: 'reminders',   label: TAB_LABEL.reminders },
];

function positiveInt(value: string | undefined): number | null {
  if (!value || !/^\d+$/.test(value)) return null;
  const n = Number(value);
  return Number.isSafeInteger(n) && n > 0 ? n : null;
}

export type PatientWebPrescriptionProps = Partial<SectionProps>;

export function PatientWebPrescription({ params: paramsProp, theme, navigate: navigateProp }: PatientWebPrescriptionProps) {
  const nav = useSectionNav();
  // The registry may render this screen without passing the section params; the URL is the source either way.
  const params = paramsProp ?? nav.params;
  const navigate = navigateProp ?? nav.navigate;
  const { setParams } = nav;
  const { user, loading: userLoading, error: userError, refetch: refetchUser } = useCurrentUser();
  const own = user?.system_id ?? null;

  const tab: PrescriptionTab = params.tab === 'reminders' ? 'reminders' : 'medications';
  const changeTab = useCallback((next: PrescriptionTab) => {
    // An id belongs to the tab it was linked with (a prescription, or a dose of the schedule).
    setParams({ tab: next === 'reminders' ? 'reminders' : undefined, id: undefined });
  }, [setParams]);

  return (
    <Screen
      title="Prescriptions"
      subtitle="Your medication history"
      theme={theme ?? ROLE_THEME.patient}
    >
      <Tabs tabs={TABS} active={tab} onChange={changeTab} label="Prescriptions" />
      {/* The content of the selected tab, named after it. */}
      <View style={styles.panel} role="tabpanel" aria-label={TAB_LABEL[tab]}>
        {tab === 'reminders' && !own ? (
          // The reminders are read by the patient's id: a failed account read gets a Retry instead of endless loading.
          userLoading
            ? <LoadingView label="Loading your reminders" />
            : <ErrorView message={userError ?? 'Your account could not be loaded.'} onRetry={refetchUser} />
        ) : tab === 'reminders' ? (
          <RemindersPanel
            patientSystemId={own}
            canManage
            theme={theme ?? ROLE_THEME.patient}
            onOpenNotificationSettings={() => navigate('settings')}
          />
        ) : (
          <MedicationsTab params={params} setParams={setParams} patientSystemId={own} />
        )}
      </View>
    </Screen>
  );
}

function MedicationsTab({ params, setParams, patientSystemId }: {
  params: Record<string, string | undefined>;
  setParams: ReturnType<typeof useSectionNav>['setParams'];
  patientSystemId: string | null;
}) {
  const today = clinicToday();
  const date = normaliseScheduleDate(params.date, today);
  const aboutSchedule = params.tab === 'schedule';
  const highlightedDose = aboutSchedule ? positiveInt(params.id) : null;
  const linkedPrescription = params.tab ? null : positiveInt(params.id);
  const filter = parsePrescriptionFilter(params.status);

  const setDate = useCallback((next: string) => {
    // Today is the default, so it is not written to the URL; a dose id belongs to the day it was linked with.
    setParams({ date: next === clinicToday() ? undefined : next, ...(aboutSchedule ? { id: undefined } : {}) });
  }, [setParams, aboutSchedule]);
  const setFilter = useCallback((next: PrescriptionFilter) => {
    setParams({ status: next === 'ALL' ? undefined : next });
  }, [setParams]);
  const closeLinked = useCallback(() => setParams({ id: undefined }), [setParams]);

  const rx = usePrescriptions({ status: filter === 'ALL' ? null : filter, pageSize: PAGE_SIZE });
  const counts = usePrescriptionCounts(patientSystemId ?? undefined);
  const refreshList = () => { rx.refetch(); counts.refetch(); };

  const renderPrescription = (item: Prescription) => (
    <View style={styles.item}>
      <PrescriptionCard item={item} />
      {patientSystemId ? (
        <View style={styles.itemActions}>
          <RequestCorrectionButton
            patientSystemId={patientSystemId}
            resourceType="PRESCRIPTION"
            resourceId={item.prescription_id}
            recordLabel={item.medication_name || 'Prescription'}
          />
        </View>
      ) : null}
    </View>
  );

  return (
    <>
      {/* A prescription opened from a notification comes first, above the day's schedule, so it is on screen. */}
      {linkedPrescription !== null ? (
        <Card>
          <LinkedPrescription prescriptionId={linkedPrescription} onClose={closeLinked} />
          {patientSystemId ? (
            <View style={styles.itemActions}>
              <RequestCorrectionButton
                patientSystemId={patientSystemId}
                resourceType="PRESCRIPTION"
                resourceId={linkedPrescription}
                recordLabel="The prescription you opened"
              />
            </View>
          ) : null}
        </Card>
      ) : null}

      <DoseList
        self
        canMark
        patientSystemId={patientSystemId ?? undefined}
        viewerSystemId={patientSystemId ?? undefined}
        date={date}
        onDateChange={setDate}
        highlightScheduleId={highlightedDose}
      />

      <SectionCard
        title="Your prescriptions"
        subtitle="Newest first. A prescription is valid through its last day and then shows as expired."
        action={(
          <IconButton
            label="Refresh prescriptions"
            icon="refresh-outline"
            variant="secondary"
            size="sm"
            loading={rx.paged.refreshing}
            onPress={refreshList}
          />
        )}
      >
        <PagedList
          state={rx.paged}
          label="Prescriptions"
          keyOf={item => item.prescription_id}
          renderItem={renderPrescription}
          header={<PrescriptionStatusFilter value={filter} onChange={setFilter} counts={counts.counts} />}
          empty={filter === 'ALL'
            ? { icon: 'medkit-outline', title: 'No prescriptions yet', sub: 'Prescriptions from your doctors appear here.' }
            : { icon: 'medkit-outline', title: `No ${prescriptionFilterLabel(filter).toLowerCase()} prescriptions` }}
        />
      </SectionCard>
    </>
  );
}

const styles = StyleSheet.create({
  // Keeps the spacing the Screen gives its direct children.
  panel:       { gap: space.lg, minWidth: 0 },
  item:        { gap: space.sm, minWidth: 0 },
  itemActions: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'flex-end', gap: space.sm, minWidth: 0 },
});

export default PatientWebPrescription;
