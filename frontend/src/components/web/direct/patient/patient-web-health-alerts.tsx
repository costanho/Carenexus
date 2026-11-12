import { HealthAlertsList } from '@/components/web/ai';
import { ErrorView, LoadingView, Screen } from '@/components/web/ui';
import { useCurrentUser } from '@/hooks/use-current-user';
import type { SectionProps } from '@/lib/nav/sections';

// Health alerts: the patient's read-only view of automated-screening alerts
// (`/dashboard/patient?section=health-alerts`). It lists reminders that are not a diagnosis (missed doses, a
// follow-up that is due) and screening results one of the patient's doctors has confirmed. Results that are
// waiting for review, or that a doctor rejected, are never sent to the patient.

export default function PatientWebHealthAlerts(props: Partial<SectionProps>) {
  const { user, loading, error, refetch } = useCurrentUser();

  return (
    <Screen
      title="Health alerts"
      subtitle="Reminders from automated screening and results your doctor has confirmed"
      theme={props.theme}
      role={props.theme ? undefined : 'patient'}
    >
      {loading ? (
        <LoadingView label="Loading your health alerts" />
      ) : !user?.system_id ? (
        <ErrorView message={error ?? 'Your account could not be loaded.'} onRetry={refetch} />
      ) : (
        <HealthAlertsList patientSystemId={user.system_id} />
      )}
    </Screen>
  );
}
