import { PatientBillingView } from '@/components/web/billing-actions';
import { ErrorView, LoadingView, Screen } from '@/components/web/ui';
import { useCurrentUser } from '@/hooks/use-current-user';
import { useSectionNav } from '@/hooks/use-section-nav';
import type { SectionProps } from '@/lib/nav/sections';

/**
 * The patient's Billing & Payments section (`/dashboard/patient?section=billing`): the outstanding balance and
 * overdue summary, invoices with a status filter, the invoice detail opened by the `id` param (the BILLING
 * notification link) with Pay and Submit claim, the payment history, insurance claims with Appeal, and the saved
 * payment methods. URL params: `tab` (payments | claims | methods), `status` (invoice filter), `id` (invoice id).
 */
export default function PatientWebBilling(props: Partial<SectionProps>) {
  const nav = useSectionNav();
  const params = props.params ?? nav.params;
  const { user, loading, error, refetch } = useCurrentUser();
  const patientSystemId = user?.system_id?.trim() || null;

  let body;
  if (!patientSystemId && loading) {
    body = <LoadingView label="Loading your billing" />;
  } else if (!patientSystemId) {
    body = <ErrorView message={error ?? 'We could not load your account. Please try again.'} onRetry={refetch} />;
  } else {
    body = (
      <PatientBillingView
        patientSystemId={patientSystemId}
        viewer="patient"
        canPay
        canClaim
        canAttach
        canViewDocuments
        canManageMethods
        params={params}
        setParams={nav.setParams}
      />
    );
  }

  return (
    <Screen
      title="Billing & Payments"
      subtitle="Your invoices, payments, insurance claims and saved payment methods"
      theme={props.theme}
      role={props.theme ? undefined : 'patient'}
    >
      {body}
    </Screen>
  );
}
