import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { parseInvoiceIdParam, PatientBillingView } from '@/components/web/billing-actions';
import { DependentPicker } from '@/components/web/pickers';
import {
  Banner, Button, EmptyView, ErrorView, LoadingView, NoAccessView, Screen, space,
} from '@/components/web/ui';
import { type CaregiverDependent, NO_ACCESS_MESSAGE, useCaregiverDependents } from '@/hooks/use-caregiver-dependents';
import { useInvoice } from '@/hooks/use-invoice';
import { useSectionNav } from '@/hooks/use-section-nav';
import { effectivePermission, hasCaregiverPermission, isGrantActive } from '@/lib/caregiver/permissions';
import type { SectionProps } from '@/lib/nav/sections';

// The caregiver's Billing & Payments section (`/dashboard/proxy?section=billing&patient=<PAT id>[&id=<invoiceId>]`):
// one loved one at a time, chosen by the `patient` URL param or the picker. Invoices, payments and claims are
// shown only while the grant is active and effectively allows viewing billing; Pay, Submit claim and Appeal also
// need the permission to manage billing (the payment status read needs the view permission, so both are
// required). Paying uses the loved one's saved methods read-only or new details. A 403 from any request (access
// revoked, expired or narrowed meanwhile) reloads the loved ones and shows the no-access state.

function dependentName(dependent: CaregiverDependent): string {
  return dependent.patientName?.trim() || 'your loved one';
}

export default function PatientProxyBilling(props: Partial<SectionProps>) {
  const nav = useSectionNav();
  const { setParams, hasSection } = nav;
  const params = props.params ?? nav.params;
  const navigate = props.navigate ?? nav.navigate;
  const { dependents, loading, error, refetch } = useCaregiverDependents();

  const selectedId = params.patient?.trim() || null;
  const selected = selectedId ? dependents.find(d => d.patientSystemId === selectedId) : undefined;

  const activeIds = useMemo(
    () => Array.from(new Set(dependents.filter(d => d.patientSystemId && isGrantActive(d)).map(d => d.patientSystemId))),
    [dependents],
  );

  // A link that names only the invoice (`id`) finds its loved one from the invoice itself.
  const linkedInvoiceId = !selectedId ? parseInvoiceIdParam(params.id) : null;
  const linkedInvoice = useInvoice(linkedInvoiceId, { enabled: !loading && linkedInvoiceId !== null && linkedInvoiceId > 0 });
  const linkedPatient = linkedInvoice.data?.patientSystemId ?? null;
  useEffect(() => {
    if (!selectedId && linkedPatient) setParams({ patient: linkedPatient });
  }, [selectedId, linkedPatient, setParams]);

  // With a single loved one there is nothing to choose: open their billing straight away.
  const resolvingLink = linkedInvoiceId !== null && linkedInvoiceId > 0 && (linkedInvoice.loading || linkedPatient !== null);
  const onlyChoice = !loading && !selectedId && !resolvingLink && activeIds.length === 1 ? activeIds[0] : null;
  useEffect(() => {
    if (onlyChoice) setParams({ patient: onlyChoice });
  }, [onlyChoice, setParams]);

  // A 403 means the grant changed since the list was loaded: reload the loved ones once per person and show the
  // no-access state until the caregiver asks to check again.
  const [forbiddenFor, setForbiddenFor] = useState<string | null>(null);
  const reported = useRef<string | null>(null);
  const onForbidden = useCallback(() => {
    if (!selectedId || reported.current === selectedId) return;
    reported.current = selectedId;
    setForbiddenFor(selectedId);
    refetch();
  }, [selectedId, refetch]);
  const checkAgain = () => {
    reported.current = null;
    setForbiddenFor(null);
    refetch();
  };
  const forbidden = forbiddenFor !== null && forbiddenFor === selectedId;

  const choose = (patientSystemId: string | null) => {
    reported.current = null;
    setForbiddenFor(null);
    setParams({ patient: patientSystemId ?? undefined, id: undefined, tab: undefined, status: undefined });
  };

  let body;
  if (loading && dependents.length === 0) {
    body = <LoadingView label="Loading your loved ones" />;
  } else if (error && dependents.length === 0) {
    body = error === NO_ACCESS_MESSAGE ? <NoAccessView /> : <ErrorView message={error} onRetry={refetch} />;
  } else if (dependents.length === 0) {
    body = (
      <EmptyView
        icon="people-outline"
        title="No loved ones linked yet"
        sub="Invoices, payments and insurance claims appear here for the people you care for."
        action={hasSection('add-dependent')
          ? { label: 'Add a loved one', icon: 'person-add-outline', onPress: () => navigate('add-dependent') }
          : undefined}
      />
    );
  } else {
    let content;
    if (!selectedId) {
      content = resolvingLink
        ? <LoadingView label="Opening the invoice" />
        : activeIds.length === 0
          ? <NoAccessView message="Your access to your loved ones has ended." sub="Ask them to renew your caregiver access." />
          : <EmptyView icon="person-outline" title="Choose a loved one" sub="Select the person whose billing you want to see." />;
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
    } else if (forbidden || !effectivePermission(selected, 'canViewBilling')) {
      content = (
        <View style={styles.stack}>
          <NoAccessView
            message={`You don't have permission to see billing for ${dependentName(selected)}.`}
            sub={forbidden
              ? 'Your access changed. Ask your loved one to allow you to view billing, then check again.'
              : 'Ask your loved one to allow you to view billing in your caregiver access.'}
          />
          {forbidden ? (
            <View style={styles.center}>
              <Button label="Check again" icon="refresh-outline" variant="secondary" onPress={checkAgain} loading={loading} />
            </View>
          ) : null}
        </View>
      );
    } else {
      const canManage = effectivePermission(selected, 'canManageBilling');
      content = (
        <View style={styles.stack}>
          {canManage ? null : (
            <Banner
              tone="info"
              text={`You can see ${dependentName(selected)}'s billing. Paying invoices and insurance claims need permission to manage billing.`}
            />
          )}
          <PatientBillingView
            // A different loved one is a different screen: nothing of the previous person stays mounted.
            key={selected.patientSystemId}
            patientSystemId={selected.patientSystemId}
            patientName={selected.patientName}
            viewer="proxy"
            canPay={canManage}
            canClaim={canManage}
            canAttach={canManage && hasCaregiverPermission(selected, 'MANAGE_DOCUMENTS')}
            canViewDocuments={hasCaregiverPermission(selected, 'DOCUMENTS')}
            canManageMethods={false}
            params={params}
            setParams={setParams}
            onForbidden={onForbidden}
          />
        </View>
      );
    }

    body = (
      <View style={styles.stack}>
        <View style={styles.picker}>
          <DependentPicker
            value={selectedId}
            onChange={id => choose(id)}
            includeInactive={!!selected && !isGrantActive(selected)}
            placeholder="Select a loved one"
          />
        </View>
        {content}
      </View>
    );
  }

  return (
    <Screen
      title="Billing & Payments"
      subtitle="Invoices, payments and insurance claims of the people you care for"
      theme={props.theme}
      role={props.theme ? undefined : 'proxy'}
    >
      {body}
    </Screen>
  );
}

const styles = StyleSheet.create({
  stack:  { gap: space.lg, minWidth: 0 },
  picker: { maxWidth: 420 },
  center: { alignItems: 'center' },
});
