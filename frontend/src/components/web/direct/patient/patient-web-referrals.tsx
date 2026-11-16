import { PatientReferralList } from '@/components/web/referrals/patient-referral-list';
import { ErrorView, LoadingView, ROLE_THEME, Screen } from '@/components/web/ui';
import { useCurrentUser } from '@/hooks/use-current-user';
import type { SectionProps } from '@/lib/nav/sections';
import { parseReferralId } from '@/lib/referrals/params';

/**
 * Patient dashboard section `referrals`: the patient's own referrals, read-only, each with the specialist and a
 * status timeline. A notification link (`?section=referrals&id=<referralId>`) brings that referral forward.
 */
export default function PatientWebReferrals({ params, theme }: Partial<SectionProps>) {
  const { user, loading, error, refetch } = useCurrentUser();
  const highlightId = parseReferralId(params?.id);

  return (
    <Screen
      title="Referrals"
      subtitle="Referrals your doctors have made to specialists, and how each one is progressing."
      theme={theme ?? ROLE_THEME.patient}
    >
      {loading ? (
        <LoadingView label="Loading your referrals…" />
      ) : !user?.system_id ? (
        <ErrorView message={error ?? 'Could not load your account. Please try again.'} onRetry={refetch} />
      ) : (
        <PatientReferralList patientSystemId={user.system_id} audience="patient" highlightId={highlightId} />
      )}
    </Screen>
  );
}
