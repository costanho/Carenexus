import { useEffect } from 'react';
import { CareTeamPanel } from '@/components/web/careteam';
import { EmptyView, ErrorView, LoadingView, ROLE_THEME, Screen } from '@/components/web/ui';
import { refreshDependents } from '@/components/web/sections/proxy-dependents';
import { useCaregiverDependents } from '@/hooks/use-caregiver-dependents';
import { useSectionNav } from '@/hooks/use-section-nav';
import type { SectionProps } from '@/lib/nav/sections';
import PatientProxyWebProfile from '../patient-proxy-web-profile';

// `?section=patient-profile&patient=<PAT id>`: a loved one's profile (also the CARE_TEAM notification target).
// The loved one is looked up in the caregiver's own list, loaded here on every visit rather than taken from the
// sidebar's list, because this screen edits the data it shows. Their care team is shown read-only: a caregiver
// with any active grant may read it, and nothing on it can be changed from here.

const HOME = 'dashboard';

export default function ProxyPatientProfileSection(props: Partial<SectionProps>) {
  const nav = useSectionNav();
  const params = props.params ?? nav.params;
  const navigate = props.navigate ?? nav.navigate;
  const openThread = props.openThread ?? nav.openThread;
  const theme = props.theme ?? ROLE_THEME.proxy;

  const { dependents, loading, error, refetch } = useCaregiverDependents();
  // The name and the access level can be changed on this screen, so the sidebar's list is reloaded on leaving.
  useEffect(() => refreshDependents, []);
  const patient = params.patient?.trim();
  const dependent = patient ? dependents.find(d => d.patientSystemId === patient) : undefined;

  if (dependent) {
    return (
      <PatientProxyWebProfile
        key={dependent.accessId}
        dependent={dependent}
        theme={theme}
        onBack={() => navigate(HOME)}
        onNavigate={(key, sectionParams) => navigate(key, sectionParams)}
        onOpenThread={openThread}
        careTeamSlot={(
          <CareTeamPanel
            patientSystemId={dependent.patientSystemId}
            patientName={dependent.patientName}
            theme={theme}
          />
        )}
      />
    );
  }

  let body;
  if (loading) {
    body = <LoadingView label="Loading patient profile" />;
  } else if (error) {
    body = <ErrorView message={error} onRetry={refetch} />;
  } else {
    body = (
      <EmptyView
        icon="person-outline"
        title="Patient not found"
        sub="This person is not one of your linked patients, or your access to them has ended."
        action={{ label: 'Back to dashboard', icon: 'arrow-back-outline', onPress: () => navigate(HOME) }}
      />
    );
  }
  return (
    <Screen title="Patient Profile" theme={theme}>
      {body}
    </Screen>
  );
}
