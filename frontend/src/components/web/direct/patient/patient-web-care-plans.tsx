import { StyleSheet, View } from 'react-native';
import { CarePlanList, CarePlanStatusFilter } from '@/components/web/careplans';
import { ErrorView, LoadingView, Screen } from '@/components/web/ui';
import { useCarePlanBoard, usePatientCarePlans } from '@/hooks/use-care-plans';
import { useCurrentUser } from '@/hooks/use-current-user';
import { useSectionNav } from '@/hooks/use-section-nav';
import { toCarePlanStatus, toPlanId } from '@/lib/careplans/rules';
import { ROLE_THEME, type SectionProps } from '@/lib/nav/sections';

// Patient > Care Plans (`/dashboard/patient?section=care-plans`). URL parameters:
//   status  ACTIVE | ON_HOLD | COMPLETED | DISCONTINUED
//   id      the plan to show opened (care-plan notifications and review reminders link here with it)
//
// The plans are written by the patient's doctors and are read-only here, except goal progress: the patient can
// set a goal to Open, In progress or Achieved and leave a progress note while the plan is active or on hold.

export default function PatientWebCarePlans(props: Partial<SectionProps>) {
  const nav = useSectionNav();
  const params = props.params ?? nav.params;
  const theme = props.theme ?? ROLE_THEME.patient;

  const status = toCarePlanStatus(params.status);
  const openId = toPlanId(params.id);

  const { user, loading: userLoading, error: userError, refetch: refetchUser } = useCurrentUser();
  const me = user?.system_id ?? null;

  const list = usePatientCarePlans(me, { status, enabled: !!me });
  const board = useCarePlanBoard(list, openId, { patientSystemId: me, enabled: !!me });

  return (
    <Screen
      title="Care Plans"
      subtitle="The goals and next steps your care team has set out with you"
      theme={theme}
    >
      {!me ? (
        userLoading
          ? <LoadingView label="Loading your care plans" />
          : <ErrorView message={userError ?? 'Your account could not be loaded.'} onRetry={refetchUser} />
      ) : (
        <CarePlanList
          board={board}
          onOpenChange={id => nav.setParams({ id: id ?? undefined })}
          label="Your care plans"
          header={(
            <View style={styles.filter}>
              <CarePlanStatusFilter
                value={status}
                onChange={next => nav.setParams({ status: next ?? undefined, id: undefined })}
              />
            </View>
          )}
          empty={status
            ? {
                icon: 'clipboard-outline',
                title: 'No care plans match this filter',
                sub: 'Choose another status to see more of your care plans.',
              }
            : {
                icon: 'clipboard-outline',
                title: 'No care plans yet',
                sub: 'When one of your doctors writes a care plan for you, it appears here with its goals and review date.',
              }}
          emptyBesideOpened={status ? { title: 'No other care plans match this filter', sub: 'Choose another status to see more care plans.' } : undefined}
          goalActor={() => 'patient'}
        />
      )}
    </Screen>
  );
}

const styles = StyleSheet.create({
  filter: { maxWidth: 320, minWidth: 0 },
});
