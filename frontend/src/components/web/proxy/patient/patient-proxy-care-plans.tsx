import { useEffect, useMemo, useRef } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { CarePlanList, CarePlanStatusFilter } from '@/components/web/careplans';
import {
  ariaLevel, Banner, Button, colors, EmptyView, ErrorView, fontSize, LoadingView, NoAccessView, Screen, SelectField,
  space,
} from '@/components/web/ui';
import { type CaregiverDependent, useCaregiverDependents } from '@/hooks/use-caregiver-dependents';
import { useCarePlan, useCarePlanBoard, useDependentCarePlans } from '@/hooks/use-care-plans';
import { useSectionNav } from '@/hooks/use-section-nav';
import { isGrantActive } from '@/lib/caregiver/permissions';
import { toCarePlanStatus, toPatientSystemId, toPlanId } from '@/lib/careplans/rules';
import type { CarePlanStatus } from '@/lib/careplans/types';
import { ROLE_THEME, type SectionProps } from '@/lib/nav/sections';

// Caregiver > Care Plans (`/dashboard/proxy?section=care-plans`). URL parameters:
//   patient  the loved one to show (PAT id); without it every loved one is listed
//   status   ACTIVE | ON_HOLD | COMPLETED | DISCONTINUED
//   id       the plan to show opened (care-plan notifications link here with `patient` and `id`)
//
// Each loved one has their own list, loaded and refused on its own: losing access to one person never hides
// the others. What the caregiver may do comes from the grant through the shared caregiver permission rule:
// reading needs an active grant that covers care plans; goal progress controls appear only when the grant
// allows managing the care plan (never with view-only access). The server checks every request again.

function dependentName(dependent: CaregiverDependent): string {
  return dependent.patientName?.trim() || 'Your loved one';
}

function DependentCarePlans({ dependent, status, openId, linked, onOpenChange }: {
  dependent: CaregiverDependent;
  status:    CarePlanStatus | null;
  openId:    number | null;
  /** True when the URL names this loved one: a linked plan that is not on the loaded page is fetched for them. */
  linked:    boolean;
  onOpenChange: (planId: number | null) => void;
}) {
  const name = dependentName(dependent);
  const active = isGrantActive(dependent);
  const { plans, canView, canManage } = useDependentCarePlans(dependent, { status });
  const board = useCarePlanBoard(plans, openId, {
    patientSystemId: dependent.patientSystemId,
    enabled: canView && linked,
  });

  const access = !active ? 'Your access has ended'
    : !canView ? 'Your access does not include care plans'
    : canManage ? 'You can view care plans and update goal progress'
    : 'You can view care plans';
  const relationship = dependent.relationship?.trim();

  return (
    <View style={styles.person}>
      <View style={styles.personHead}>
        <Text style={styles.personName} accessibilityRole="header" {...ariaLevel(2)}>{name}</Text>
        <Text style={styles.personSub}>{relationship ? `${relationship}  ·  ${access}` : access}</Text>
      </View>

      {!active ? (
        <NoAccessView
          compact
          message={`Your access to ${name} has ended.`}
          sub="Ask them, or whoever manages their account, to renew your access if you still help with their care."
        />
      ) : !canView ? (
        <NoAccessView
          compact
          message={`Your access to ${name} does not include care plans.`}
          sub="Care plans can be read with permission to view records or to manage the care plan."
        />
      ) : (
        <CarePlanList
          board={board}
          onOpenChange={onOpenChange}
          headingLevel={3}
          label={`Care plans for ${name}`}
          noAccessMessage={`You don't have access to ${name}'s care plans.`}
          empty={status
            ? { icon: 'clipboard-outline', title: 'No care plans match this filter' }
            : {
                icon: 'clipboard-outline',
                title: `No care plans for ${name}`,
                sub: 'A care plan appears here when one of their doctors writes it.',
              }}
          emptyBesideOpened={status ? { title: 'No other care plans match this filter', sub: 'Choose another status to see more care plans.' } : undefined}
          goalActor={() => (canManage ? 'caregiver' : null)}
        />
      )}
    </View>
  );
}

export default function PatientProxyCarePlans(props: Partial<SectionProps>) {
  const nav = useSectionNav();
  const params = props.params ?? nav.params;
  const theme = props.theme ?? ROLE_THEME.proxy;

  const patient = toPatientSystemId(params.patient);
  const status = toCarePlanStatus(params.status);
  const openId = toPlanId(params.id);

  const { dependents, loading, error, refetch } = useCaregiverDependents();

  // One entry per person (an active grant wins over an ended one), by name.
  const people = useMemo(() => {
    const byPatient = new Map<string, CaregiverDependent>();
    for (const d of dependents) {
      if (!d.patientSystemId) continue;
      const seen = byPatient.get(d.patientSystemId);
      if (!seen || (!isGrantActive(seen) && isGrantActive(d))) byPatient.set(d.patientSystemId, d);
    }
    return [...byPatient.values()].sort((a, b) => dependentName(a).localeCompare(dependentName(b)));
  }, [dependents]);

  const selected = patient ? people.find(d => d.patientSystemId === patient) ?? null : null;
  const shown = patient ? (selected ? [selected] : []) : people;

  // A link that carries only a plan id: find out whose plan it is, then show that person with the plan opened.
  // Only the id the screen was opened with is looked up, and only once: the link is used up when its owner is
  // found, when its banner is dismissed or when a filter changes, so opening the same plan again later from the
  // "All loved ones" list never narrows the list.
  const linkOnlyId = useRef(patient === null ? openId : null);
  const resolving = linkOnlyId.current !== null && openId === linkOnlyId.current && patient === null;
  const lookup = useCarePlan(openId, { enabled: resolving && !loading && !error && people.length > 0 });
  const { setParams } = nav;
  const owner = resolving ? lookup.data?.patientSystemId ?? null : null;
  useEffect(() => {
    if (!owner) return;
    linkOnlyId.current = null;
    setParams({ patient: owner });
  }, [owner, setParams]);

  /** Changes the URL parameters (a filter, or the plan opened from a row) and ends the plan-id-only link. */
  const setFilters = (next: Parameters<typeof setParams>[0]) => {
    linkOnlyId.current = null;
    setParams(next);
  };
  const dismissLink = () => setFilters({ id: undefined });

  const personOptions = people.map(d => {
    const relationship = d.relationship?.trim();
    const ended = isGrantActive(d) ? '' : ' (access ended)';
    return {
      value: d.patientSystemId,
      label: `${relationship ? `${dependentName(d)}, ${relationship}` : dependentName(d)}${ended}`,
    };
  });

  const canAdd = nav.hasSection('add-dependent');

  return (
    <Screen title="Care Plans" subtitle="Care plans for the people you care for" theme={theme}>
      {loading ? (
        <LoadingView label="Loading your loved ones" />
      ) : error ? (
        <ErrorView message={error} onRetry={refetch} />
      ) : people.length === 0 ? (
        <EmptyView
          icon="people-outline"
          title="No loved ones linked yet"
          sub="Care plans appear here once you are linked to someone you care for."
          action={canAdd ? { label: 'Add a loved one', icon: 'person-add-outline', onPress: () => nav.navigate('add-dependent') } : undefined}
        />
      ) : (
        <>
          <View style={styles.filters}>
            <View style={styles.filter}>
              <SelectField
                label="Loved one"
                value={selected ? selected.patientSystemId : ''}
                onChange={next => setFilters({ patient: next || undefined, id: undefined })}
                options={personOptions}
                placeholder="All loved ones"
                allowEmpty
              />
            </View>
            <View style={styles.filter}>
              <CarePlanStatusFilter
                value={status}
                onChange={next => setFilters({ status: next ?? undefined, id: undefined })}
              />
            </View>
          </View>

          {resolving && lookup.loading ? <LoadingView compact label="Loading the care plan" /> : null}
          {resolving && lookup.error && !lookup.data ? (
            lookup.errorStatus === 403 ? (
              <Banner
                tone="warning"
                text="You don't have access to the care plan this link points to."
                onDismiss={dismissLink}
              />
            ) : lookup.errorStatus === 404 ? (
              <Banner
                tone="warning"
                text="The care plan this link points to was not found."
                onDismiss={dismissLink}
              />
            ) : (
              <Banner
                tone="danger"
                text={lookup.error}
                action={{ label: 'Retry', onPress: lookup.refetch }}
                onDismiss={dismissLink}
              />
            )
          ) : null}

          {patient && !selected ? (
            <View style={styles.missing}>
              <NoAccessView
                message="This person is not one of your loved ones."
                sub="They may have been unlinked from your account, or the link you followed is out of date."
              />
              <Button
                label="Show all loved ones"
                variant="secondary"
                onPress={() => setFilters({ patient: undefined, id: undefined })}
                style={styles.centered}
              />
            </View>
          ) : (
            shown.map(dependent => (
              <DependentCarePlans
                key={dependent.patientSystemId}
                dependent={dependent}
                status={status}
                openId={openId}
                linked={patient === dependent.patientSystemId}
                onOpenChange={id => setFilters({ id: id ?? undefined })}
              />
            ))
          )}
        </>
      )}
    </Screen>
  );
}

const styles = StyleSheet.create({
  filters: { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  filter:  { flexGrow: 1, flexShrink: 1, flexBasis: 220, minWidth: 0, maxWidth: 360 },

  person:     { gap: space.md, minWidth: 0, paddingTop: space.md, borderTopWidth: 1, borderTopColor: colors.border },
  personHead: { gap: 2, minWidth: 0 },
  personName: { fontSize: fontSize.xl, fontWeight: '700', color: colors.text },
  personSub:  { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },

  missing:  { gap: space.sm },
  centered: { alignSelf: 'center' },
});
