import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { type HealthNotice, loggedNotice, recordedNotice, RecordVitalsDialog, SymptomDialog } from '@/components/web/health/health-dialogs';
import { SymptomTimeline } from '@/components/web/health/symptom-timeline';
import { toMetricFamily, toTrendDays, TrendChart } from '@/components/web/health/trend-chart';
import { VitalsHistory } from '@/components/web/health/vitals-history';
import { VitalsTiles } from '@/components/web/health/vitals-tiles';
import {
  Banner, Button, ErrorView, LoadingView, ROLE_THEME, Screen, SectionCard, space, Tabs, type TabItem,
} from '@/components/web/ui';
import { useCurrentUser } from '@/hooks/use-current-user';
import { useLatestHealthMetrics } from '@/hooks/use-health-metrics';
import { useSectionNav } from '@/hooks/use-section-nav';
import type { SectionProps } from '@/lib/nav/sections';

// Patient section "Vitals & Symptoms" (`/dashboard/patient?section=health-tracking`).
//
//   tab=readings | symptoms   the open tab (overview when absent)
//   metric=<family>&days=7|30|90   the measurement and period of the trend chart
//   id=<symptomId>            a symptom log to bring to the top of the Symptoms tab
//
// The dashboard card lives in components/web/health/latest-vitals-card and is re-exported here.

export { LatestVitalsCard, type LatestVitalsCardProps } from '@/components/web/health/latest-vitals-card';

type TabKey = 'overview' | 'readings' | 'symptoms';

const TABS: readonly TabItem<TabKey>[] = [
  { key: 'overview', label: 'Overview' },
  { key: 'readings', label: 'Readings history' },
  { key: 'symptoms', label: 'Symptom history' },
];

const NO_ACCESS = "You don't have access to these health readings.";

function toSymptomId(value: string | undefined): number | null {
  return value && /^\d{1,9}$/.test(value) ? Number(value) : null;
}

export default function PatientWebHealthTracking(props: Partial<SectionProps>) {
  const nav = useSectionNav();
  const params = props.params ?? nav.params;
  const theme = props.theme ?? ROLE_THEME.patient;

  const { user, loading: userLoading, error: userError, refetch: refetchUser } = useCurrentUser();
  const patientSystemId = user?.system_id ?? null;
  const latest = useLatestHealthMetrics(patientSystemId);

  const [dialog, setDialog] = useState<'vitals' | 'symptoms' | null>(null);
  const [notice, setNotice] = useState<HealthNotice | null>(null);

  const tab: TabKey = TABS.find(t => t.key === params.tab)?.key ?? 'overview';
  const setTab = (key: TabKey) => nav.setParams({ tab: key === 'overview' ? undefined : key });
  const family = toMetricFamily(params.metric) ?? 'BLOOD_PRESSURE';
  const days = toTrendDays(params.days);

  const openVitals = () => { setNotice(null); setDialog('vitals'); };
  const openSymptoms = () => { setNotice(null); setDialog('symptoms'); };

  return (
    <Screen
      title="Vitals & Symptoms"
      subtitle="Record your readings and symptoms, and see how they change over time."
      theme={theme}
      actions={patientSystemId ? (
        <>
          <Button label="Record vitals" icon="add" onPress={openVitals} />
          <Button label="Log symptoms" icon="add" variant="secondary" onPress={openSymptoms} />
        </>
      ) : undefined}
    >
      {userLoading ? (
        <LoadingView label="Loading your account" />
      ) : !patientSystemId ? (
        <ErrorView message={userError ?? 'Could not load your account.'} onRetry={refetchUser} />
      ) : (
        <>
          {notice ? <Banner tone={notice.tone} text={notice.text} onDismiss={() => setNotice(null)} /> : null}

          <Tabs tabs={TABS} active={tab} onChange={setTab} label="Vitals and symptoms" />

          {tab === 'overview' ? (
            <View style={styles.panel} role="tabpanel" aria-label="Overview">
              <SectionCard title="Latest readings" subtitle="The most recent value of each measurement.">
                <VitalsTiles
                  state={latest}
                  onRecord={openVitals}
                  emptySub="Record your blood pressure, heart rate and other readings to see them here."
                  noAccessMessage={NO_ACCESS}
                />
              </SectionCard>

              <SectionCard title="Trends" subtitle="Daily averages. Days without a reading are left as gaps.">
                <TrendChart
                  patientSystemId={patientSystemId}
                  family={family}
                  onFamilyChange={next => nav.setParams({ metric: next })}
                  days={days}
                  onDaysChange={next => nav.setParams({ days: next })}
                  noAccessMessage={NO_ACCESS}
                />
              </SectionCard>

              <SectionCard
                title="Recent symptoms"
                action={<Button label="View all" accessibilityLabel="View all symptom logs" variant="ghost" size="sm" icon="chevron-forward" onPress={() => setTab('symptoms')} />}
              >
                <SymptomTimeline
                  patientSystemId={patientSystemId}
                  compact
                  pageSize={3}
                  onLog={openSymptoms}
                  noAccessMessage={NO_ACCESS}
                />
              </SectionCard>
            </View>
          ) : null}

          {tab === 'readings' ? (
            <View style={styles.panel} role="tabpanel" aria-label="Readings history">
              <SectionCard
                title="Readings history"
                subtitle="Every recorded reading, newest first. You can delete a reading that was entered by hand."
              >
                <VitalsHistory patientSystemId={patientSystemId} onRecord={openVitals} noAccessMessage={NO_ACCESS} />
              </SectionCard>
            </View>
          ) : null}

          {tab === 'symptoms' ? (
            <View style={styles.panel} role="tabpanel" aria-label="Symptom history">
              <SectionCard
                title="Symptom history"
                subtitle="Every symptom log, newest first. You can delete a log you wrote within 24 hours."
              >
                <SymptomTimeline
                  patientSystemId={patientSystemId}
                  showFilter
                  focusSymptomId={toSymptomId(params.id)}
                  onLog={openSymptoms}
                  noAccessMessage={NO_ACCESS}
                />
              </SectionCard>
            </View>
          ) : null}

          <RecordVitalsDialog
            open={dialog === 'vitals'}
            onClose={() => setDialog(null)}
            patientSystemId={patientSystemId}
            onRecorded={result => setNotice(recordedNotice(result))}
          />
          <SymptomDialog
            open={dialog === 'symptoms'}
            onClose={() => setDialog(null)}
            patientSystemId={patientSystemId}
            onLogged={log => setNotice(loggedNotice(log))}
          />
        </>
      )}
    </Screen>
  );
}

const styles = StyleSheet.create({
  panel: { gap: space.lg, minWidth: 0 },
});
