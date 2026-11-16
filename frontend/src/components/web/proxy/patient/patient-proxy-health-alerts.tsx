import { useEffect, useMemo } from 'react';
import { StyleSheet, View } from 'react-native';
import { HealthAlertsList } from '@/components/web/ai';
import {
  EmptyView, ErrorView, LoadingView, NoAccessView, Screen, SelectField, type SelectOption, space,
} from '@/components/web/ui';
import { type CaregiverDependent, NO_ACCESS_MESSAGE, useCaregiverDependents } from '@/hooks/use-caregiver-dependents';
import { useSectionNav } from '@/hooks/use-section-nav';
import { effectivePermission, isGrantActive } from '@/lib/caregiver/permissions';
import type { SectionProps } from '@/lib/nav/sections';

// Health alerts for a caregiver (`/dashboard/proxy?section=health-alerts&patient=<PAT id>`): the read-only
// alerts of one loved one at a time. The loved one travels in the `patient` URL param, so notification links,
// a refresh and Back keep the selection. Alerts are part of the loved one's records: the caregiver needs the
// "view records" permission (or full access) on a grant that is still active, and the server checks the same on
// every request.

function dependentName(dependent: CaregiverDependent): string {
  return dependent.patientName?.trim() || 'Name not set';
}

function optionLabel(dependent: CaregiverDependent): string {
  const relationship = dependent.relationship?.trim();
  const name = relationship ? `${dependentName(dependent)}, ${relationship}` : dependentName(dependent);
  return isGrantActive(dependent) ? name : `${name} (access ended)`;
}

export default function PatientProxyHealthAlerts(props: Partial<SectionProps>) {
  const nav = useSectionNav();
  const { setParams, hasSection } = nav;
  const navigate = props.navigate ?? nav.navigate;
  const params = props.params ?? nav.params;
  const { dependents, loading, error, refetch } = useCaregiverDependents();

  const selectedId = params.patient?.trim() || null;
  const selected = selectedId ? dependents.find(d => d.patientSystemId === selectedId) : undefined;

  // People the caregiver can choose: active grants, plus the selected one when its access has ended, so the
  // select never shows a different person than the one the page is about.
  const options = useMemo<SelectOption[]>(() => {
    const seen = new Set<string>();
    return dependents
      .filter(d => d.patientSystemId && (isGrantActive(d) || d.patientSystemId === selectedId))
      .filter(d => (seen.has(d.patientSystemId) ? false : (seen.add(d.patientSystemId), true)))
      .map(d => ({ value: d.patientSystemId, label: optionLabel(d) }))
      .sort((a, b) => a.label.localeCompare(b.label));
  }, [dependents, selectedId]);

  // With a single loved one there is nothing to choose: open their alerts straight away.
  const onlyChoice = !loading && !selectedId && options.length === 1 ? options[0].value : null;
  useEffect(() => {
    if (onlyChoice) setParams({ patient: onlyChoice });
  }, [onlyChoice, setParams]);

  let body;
  if (loading) {
    body = <LoadingView label="Loading your loved ones" />;
  } else if (error && dependents.length === 0) {
    body = error === NO_ACCESS_MESSAGE
      ? <NoAccessView />
      : <ErrorView message={error} onRetry={refetch} />;
  } else if (dependents.length === 0) {
    body = (
      <EmptyView
        icon="people-outline"
        title="No loved ones linked yet"
        sub="Health alerts appear here for the people you care for."
        action={hasSection('add-dependent')
          ? { label: 'Add a loved one', icon: 'person-add-outline', onPress: () => navigate('add-dependent') }
          : undefined}
      />
    );
  } else {
    let content;
    if (!selectedId) {
      content = options.length === 0
        ? <NoAccessView message="Your access to your loved ones has ended." sub="Ask them to renew your caregiver access." />
        : (
          <EmptyView
            icon="person-outline"
            title="Choose a loved one"
            sub="Select the person whose health alerts you want to see."
          />
        );
    } else if (!selected) {
      content = (
        <NoAccessView
          message="This person is not one of your linked patients."
          sub="Choose one of your loved ones above, or ask them to give you caregiver access."
        />
      );
    } else if (!isGrantActive(selected)) {
      content = (
        <NoAccessView
          message={`Your access to ${dependentName(selected)} has ended.`}
          sub="Ask your loved one to renew your caregiver access."
        />
      );
    } else if (!effectivePermission(selected, 'canViewRecords')) {
      content = (
        <NoAccessView
          message={`You don't have permission to see health alerts for ${dependentName(selected)}.`}
          sub="Health alerts are part of their records. Ask your loved one to allow you to view records."
        />
      );
    } else {
      content = (
        <HealthAlertsList
          // A different loved one is a different list: nothing of the previous person stays mounted.
          key={selected.patientSystemId}
          patientSystemId={selected.patientSystemId}
          noAccessMessage={`You don't have access to health alerts for ${dependentName(selected)}.`}
        />
      );
    }

    body = (
      <View style={styles.wrap}>
        {options.length > 0 ? (
          <View style={styles.picker}>
            <SelectField
              label="Loved one"
              value={selectedId ?? ''}
              onChange={value => setParams({ patient: value || undefined })}
              options={options}
              placeholder="Select a loved one"
            />
          </View>
        ) : null}
        {content}
      </View>
    );
  }

  return (
    <Screen
      title="Health alerts"
      subtitle="Reminders from automated screening and results a doctor has confirmed, for the people you care for"
      theme={props.theme}
      role={props.theme ? undefined : 'proxy'}
    >
      {body}
    </Screen>
  );
}

const styles = StyleSheet.create({
  wrap:   { gap: space.lg, minWidth: 0 },
  picker: { maxWidth: 420 },
});
