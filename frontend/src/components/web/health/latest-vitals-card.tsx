import { Button, ErrorView, LoadingView, type RoleTheme, SectionCard, UiThemeProvider } from '@/components/web/ui';
import { useCurrentUser } from '@/hooks/use-current-user';
import { useLatestHealthMetrics } from '@/hooks/use-health-metrics';
import { VitalsTiles } from './vitals-tiles';

export interface LatestVitalsCardProps {
  /** Whose readings to show. Omit on the patient's own dashboard: the signed-in patient is used. */
  patientSystemId?: string | null;
  /** Opens the full Vitals & Symptoms section; shown as "View all" and as the empty state's button. */
  onOpen?:          () => void;
  /** The dashboard's accent colours (`SectionProps.theme`). */
  theme?:           RoleTheme;
  title?:           string;
}

/**
 * A dashboard card with the latest reading of each type (compact tiles, abnormal and critical readings marked
 * in words). Loads by itself and renders its own loading, failed, no-access and empty states.
 */
export function LatestVitalsCard({ patientSystemId, onOpen, theme, title = 'Latest Vitals' }: LatestVitalsCardProps) {
  // The signed-in user is only looked at when no patient was passed in.
  const own = patientSystemId === undefined;
  const { user, loading: userLoading, error: userError, refetch: refetchUser } = useCurrentUser();
  const id = own ? user?.system_id ?? null : patientSystemId;
  const latest = useLatestHealthMetrics(id);

  let body;
  if (own && userLoading) body = <LoadingView label="Loading the latest readings" compact />;
  else if (own && !user) body = <ErrorView message={userError ?? 'Could not load your account.'} onRetry={refetchUser} compact />;
  else {
    body = (
      <VitalsTiles
        state={latest}
        compact
        // On the patient's own dashboard the empty state leads to the section where readings are recorded.
        onRecord={own ? onOpen : undefined}
        emptySub={own ? 'Record your blood pressure, heart rate and other readings to see them here.' : undefined}
      />
    );
  }

  return (
    <UiThemeProvider theme={theme}>
      <SectionCard
        title={title}
        action={onOpen ? <Button label="View all" accessibilityLabel="View all vitals and symptoms" variant="ghost" size="sm" icon="chevron-forward" onPress={onOpen} /> : undefined}
      >
        {body}
      </SectionCard>
    </UiThemeProvider>
  );
}
