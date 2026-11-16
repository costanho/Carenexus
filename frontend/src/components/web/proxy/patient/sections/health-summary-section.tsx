import { HealthPanel } from '@/components/web/health';
import { ROLE_THEME } from '@/components/web/ui';
import type { CaregiverDependent } from '@/hooks/use-caregiver-dependents';
import { effectivePermission, isGrantActive } from '@/lib/caregiver/permissions';
import type { SectionProps } from '@/lib/nav/sections';
import PatientProxyHealthSummary, { HealthSummarySection } from '../patient-proxy-health-summary';

// The caregiver's Health Summary as the dashboard mounts it (`?section=health-summary&patient=<PAT id>`, also
// the HEALTH_TRACKING notification target): each loved one's card gets their vitals and symptoms, read-only.
// Health readings are part of the records: without "view records" on an active grant the block is the
// no-access state and nothing is requested. The link's `id` is a reading id, never a symptom log id, so it is
// not passed on.

export default function ProxyHealthSummarySection(props: Partial<SectionProps>) {
  const theme = props.theme ?? ROLE_THEME.proxy;
  const vitalsSlot = (dependent: CaregiverDependent) => {
    const name = dependent.patientName?.trim() || 'your loved one';
    const active = isGrantActive(dependent);
    return (
      <HealthSummarySection title="Vitals & Symptoms">
        <HealthPanel
          patientSystemId={dependent.patientSystemId}
          canView={effectivePermission(dependent, 'canViewRecords')}
          canRecord={false}
          compact
          theme={theme}
          patientName={dependent.patientName}
          noAccessMessage={active
            ? `Vitals and symptoms of ${name} are not shared with you.`
            : `Your access to ${name} has ended.`}
          noAccessSub={active
            ? 'Seeing health readings needs permission to view records. Your loved one decides who can see them.'
            : 'Ask your loved one to renew your caregiver access if you still help with their care.'}
        />
      </HealthSummarySection>
    );
  };
  return <PatientProxyHealthSummary {...props} vitalsSlot={vitalsSlot} />;
}
