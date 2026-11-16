import { useMemo, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  CarePlanForm, CarePlanList, CarePlanStatusDialog, CarePlanStatusFilter, ReviewCarePlanDialog, statusActionLabel,
} from '@/components/web/careplans';
import { type PickerOption, PickerShell } from '@/components/web/pickers/picker-shell';
import {
  Banner, Button, colors, fontSize, Icon, IconButton, radius, Screen, space, Tabs,
} from '@/components/web/ui';
import {
  useCarePlanBoard, useCarePlanPatientOptions, useDoctorCarePlans, usePatientCarePlans,
} from '@/hooks/use-care-plans';
import { useCurrentUser } from '@/hooks/use-current-user';
import { useSectionNav } from '@/hooks/use-section-nav';
import {
  canChangePlan, isPlanAuthor, isTerminalPlan, nextPlanStatuses, PLAN_STATUS_LABEL, toCarePlanStatus,
  toPatientSystemId, toPlanId, toPositiveId,
} from '@/lib/careplans/rules';
import type { CarePlan, CarePlanPatientOption, CarePlanStatus } from '@/lib/careplans/types';
import { ROLE_THEME, type SectionProps } from '@/lib/nav/sections';

// Doctor > Care Plans (`/dashboard/doctor?section=care-plans`). URL parameters:
//   patient         only plans for this patient (filtered by the server); adds the "All care plans" tab
//   tab=all         with `patient`: every plan of that patient, including other doctors' (read-only for them)
//   status          ACTIVE | ON_HOLD | COMPLETED | DISCONTINUED
//   id              the plan to show opened (notification and reminder links carry only this)
//   mode=new        opens the new-plan form, prefilled with `patient`
//   consultationId  with `patient`: opens the new-plan form for that patient, linked to that consultation;
//                   without `patient` it opens a plain new-plan form

type ListTab = 'mine' | 'all';

const LIST_TABS = [
  { key: 'mine' as const, label: 'Written by me' },
  { key: 'all' as const,  label: 'All care plans' },
];

const STATUS_NOTICE: Record<CarePlanStatus, string> = {
  ACTIVE:       'The care plan is active again.',
  ON_HOLD:      'The care plan is on hold.',
  COMPLETED:    'The care plan is completed.',
  DISCONTINUED: 'The care plan is discontinued.',
};

type Notice = { tone: 'success' | 'warning'; text: string } | null;

export default function DoctorWebCarePlans(props: Partial<SectionProps>) {
  const nav = useSectionNav();
  const params = props.params ?? nav.params;
  const theme = props.theme ?? ROLE_THEME.doctor;

  const patient = toPatientSystemId(params.patient);
  const status = toCarePlanStatus(params.status);
  const openId = toPlanId(params.id);
  const linkConsultation = toPositiveId(params.consultationId);
  // A consultation is used only together with its patient: without `patient` the link opens a plain new-plan
  // form (the doctor picks the patient and the plan is not linked to a consultation).
  const consultationId = patient ? linkConsultation : null;
  const tab: ListTab = patient && params.tab === 'all' ? 'all' : 'mine';
  const creating = params.mode === 'new' || linkConsultation !== null;

  const { user, loading: userLoading, error: userError, refetch: refetchUser } = useCurrentUser();
  const me = user?.system_id ?? null;

  const patients = useCarePlanPatientOptions();
  const patientLabel = (id: string) => patients.nameOf(id) ?? (patients.loading ? 'this patient' : `patient ${id}`);

  const mine = useDoctorCarePlans({ status, patientSystemId: patient, enabled: tab === 'mine' });
  const everyone = usePatientCarePlans(patient, { status, enabled: tab === 'all' });
  const board = useCarePlanBoard(tab === 'all' ? everyone : mine, openId);

  const [editing, setEditing] = useState<CarePlan | null>(null);
  const [reviewing, setReviewing] = useState<CarePlan | null>(null);
  const [statusChange, setStatusChange] = useState<{ plan: CarePlan; status: CarePlanStatus } | null>(null);
  const [notice, setNotice] = useState<Notice>(null);

  const pickerOptions = useMemo<PickerOption<CarePlanPatientOption>[]>(
    () => patients.options.map(o => ({ id: o.patientSystemId, label: o.fullName || o.patientSystemId, item: o })),
    [patients.options],
  );

  const openCreate = () => { setNotice(null); nav.setParams({ mode: 'new' }); };
  const closeCreate = () => nav.setParams({ mode: undefined, consultationId: undefined });

  /** The server refused a change because the plan is no longer what the screen shows: reload and say why. */
  const onStale = (message: string) => {
    setEditing(null);
    setReviewing(null);
    setStatusChange(null);
    setNotice({ tone: 'warning', text: message });
    board.refresh();
  };

  /**
   * Puts a plan returned by a status change or a review on screen. When the list is filtered by status and the
   * plan no longer has that status it leaves the list; it stays shown as the opened plan (its id is in the URL).
   */
  const settle = (plan: CarePlan) => {
    if (status && plan.status !== status) board.drop(plan);
    else board.apply(plan);
  };

  const onFormSaved = (plan: CarePlan, mode: 'create' | 'edit') => {
    if (mode === 'edit') {
      board.apply(plan);
      setEditing(null);
      setNotice({ tone: 'success', text: 'The care plan was saved.' });
      return;
    }
    // A new plan is active and newest, so it leads the list when the filters include it. When they do not,
    // it is still shown opened above the list through its id.
    const listed = (!status || status === plan.status) && (!patient || patient === plan.patientSystemId);
    if (listed) board.list.setItems(prev => [plan, ...prev.filter(p => p.planId !== plan.planId)]);
    setNotice({ tone: 'success', text: 'The care plan was created.' });
    nav.setParams({ mode: undefined, consultationId: undefined, id: plan.planId });
  };

  const renderActions = (plan: CarePlan) => {
    if (!me) return null;
    if (!isPlanAuthor(plan, me)) {
      return <Text style={styles.muted}>Only the doctor who wrote this care plan can change it.</Text>;
    }
    if (isTerminalPlan(plan)) {
      return (
        <Text style={styles.muted}>
          This care plan is {PLAN_STATUS_LABEL[plan.status].toLowerCase()} and can no longer be changed.
        </Text>
      );
    }
    return (
      <>
        <Button
          label="Edit" icon="create-outline" variant="secondary" size="sm"
          accessibilityLabel={`Edit, ${plan.title}`}
          onPress={() => { setNotice(null); setEditing(plan); }}
        />
        <Button
          label="Mark reviewed" icon="checkmark-done-outline" variant="secondary" size="sm"
          accessibilityLabel={`Mark reviewed, ${plan.title}`}
          onPress={() => { setNotice(null); setReviewing(plan); }}
        />
        {nextPlanStatuses(plan.status).map(next => (
          <Button
            key={next}
            label={statusActionLabel(next)}
            variant={next === 'DISCONTINUED' ? 'danger' : 'secondary'}
            size="sm"
            accessibilityLabel={`${statusActionLabel(next)}, ${plan.title}`}
            onPress={() => { setNotice(null); setStatusChange({ plan, status: next }); }}
          />
        ))}
      </>
    );
  };

  const filtered = !!status || !!patient;
  const empty = tab === 'all' && patient
    ? {
        icon: 'clipboard-outline' as const,
        title: status ? 'No care plans match this filter' : `No care plans for ${patientLabel(patient)}`,
        sub: status ? 'Choose another status to see more care plans.' : undefined,
      }
    : {
        icon: 'clipboard-outline' as const,
        title: status ? 'No care plans match this filter'
          : patient ? `You have not written a care plan for ${patientLabel(patient)}`
          : 'No care plans yet',
        sub: status ? 'Choose another status to see more care plans.'
          : 'A care plan sets out goals and interventions for a patient in your care.',
        action: status ? undefined : { label: 'New care plan', icon: 'add' as const, onPress: openCreate },
      };

  const header = (
    <View style={styles.header}>
      <View style={styles.filters}>
        <View style={styles.filter}>
          <CarePlanStatusFilter
            value={status}
            onChange={next => nav.setParams({ status: next ?? undefined, id: undefined })}
          />
        </View>
        <View style={styles.filter}>
          <PickerShell<CarePlanPatientOption>
            label="Patient"
            value={patient}
            onChange={id => nav.setParams({ patient: id ?? undefined, tab: undefined, id: undefined })}
            options={pickerOptions}
            loading={patients.loading}
            loadError={patients.error}
            onRetry={patients.refetch}
            emptyText="No patients yet"
            searchLabel="Search patients"
            placeholder="All patients"
            allowEmpty
          />
        </View>
      </View>
      {patient ? (
        <>
          <View style={[styles.chip, { backgroundColor: theme.accentBg }]}>
            <Icon name="person-outline" size={14} color={colors.text} />
            <Text style={styles.chipText}>Care plans for {patientLabel(patient)}</Text>
            <IconButton
              label="Remove patient filter"
              icon="close"
              size="sm"
              onPress={() => nav.setParams({ patient: undefined, tab: undefined, id: undefined })}
            />
          </View>
          <Tabs<ListTab>
            tabs={LIST_TABS}
            active={tab}
            onChange={key => nav.setParams({ tab: key === 'all' ? 'all' : undefined, id: undefined })}
            label="Whose care plans to show"
          />
        </>
      ) : null}
    </View>
  );

  return (
    <Screen
      title="Care Plans"
      subtitle="Goals, interventions and review dates for the patients in your care"
      theme={theme}
      actions={<Button label="New care plan" icon="add" onPress={openCreate} />}
    >
      {userError && !userLoading ? (
        <Banner
          tone="warning"
          text="Your account could not be loaded, so the controls for changing care plans are hidden."
          action={{ label: 'Retry', onPress: refetchUser }}
        />
      ) : null}

      <CarePlanList
        board={board}
        onOpenChange={id => nav.setParams({ id: id ?? undefined })}
        header={header}
        emptyBesideOpened={status ? { title: 'No other care plans match this filter', sub: 'Choose another status to see more care plans.' } : undefined}
        empty={empty}
        label={patient ? `Care plans for ${patientLabel(patient)}` : filtered ? 'Care plans matching the filter' : 'Care plans you wrote'}
        noAccessMessage={tab === 'all'
          ? "You are not part of this patient's care team, so their care plans are not available to you."
          : undefined}
        patientName={plan => patients.nameOf(plan.patientSystemId) ?? (patients.loading ? null : `patient ${plan.patientSystemId}`)}
        authorLabel={plan => (isPlanAuthor(plan, me) ? 'you' : undefined)}
        goalActor={plan => (canChangePlan(plan, me) ? 'doctor' : null)}
        renderActions={renderActions}
        notice={notice}
        onDismissNotice={() => setNotice(null)}
      />

      {creating ? (
        <CarePlanForm
          patientSystemId={patient}
          consultationId={consultationId}
          patients={patients}
          onSaved={onFormSaved}
          onClose={closeCreate}
        />
      ) : editing ? (
        <CarePlanForm
          key={editing.planId}
          plan={editing}
          patients={patients}
          onSaved={onFormSaved}
          onClose={() => setEditing(null)}
          onStale={onStale}
        />
      ) : null}

      {reviewing ? (
        <ReviewCarePlanDialog
          plan={reviewing}
          onClose={() => setReviewing(null)}
          onStale={onStale}
          onSaved={plan => {
            settle(plan);
            setReviewing(null);
            setNotice({ tone: 'success', text: 'The review was recorded.' });
          }}
        />
      ) : null}

      {statusChange ? (
        <CarePlanStatusDialog
          plan={statusChange.plan}
          status={statusChange.status}
          onClose={() => setStatusChange(null)}
          onStale={onStale}
          onSaved={plan => {
            settle(plan);
            setStatusChange(null);
            setNotice({ tone: 'success', text: STATUS_NOTICE[plan.status] });
          }}
        />
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  header:  { gap: space.md, minWidth: 0 },
  filters: { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  filter:  { flexGrow: 1, flexShrink: 1, flexBasis: 220, minWidth: 0, maxWidth: 360 },
  chip: {
    flexDirection: 'row', alignItems: 'center', alignSelf: 'flex-start', gap: space.sm, maxWidth: '100%',
    paddingLeft: space.md, paddingRight: space.xs, paddingVertical: 2, borderRadius: radius.pill,
  },
  chipText: { flexShrink: 1, minWidth: 0, fontSize: fontSize.sm, fontWeight: '600', color: colors.text },
  muted:    { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
});
