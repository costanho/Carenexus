import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Banner, Button, Card, colors, ErrorView, fontSize, FormActions, FormError, InfoRow, LoadingView,
  PagedList, Screen, SelectField, SidePanel, space, StatusBadge, statusTone, SwitchField, TextField,
  type SelectOption,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { useAdminCorrection, useAdminCorrections, useAssignableDoctors } from '@/hooks/use-admin-ops-corrections';
import { useSectionNav } from '@/hooks/use-section-nav';
import {
  assignCorrection, CORRECTION_RESOURCE_LABEL, CORRECTION_STATUS_LABEL, CORRECTION_STATUSES, correctionRecordLabel,
  isCorrectionDecision, isCorrectionOpen, isCorrectionStatus, resolveCorrection, resolveCorrectionSchema,
  type AssignableDoctor, type CorrectionRequest, type CorrectionStatus, type ResolveCorrectionValues,
} from '@/lib/admin/corrections-api';
import { accountRoleLabel, formatInstant } from '@/lib/admin/ops-types';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { ROLE_THEME, type SectionProps } from '@/lib/nav/sections';
import { confirmAction } from '@/lib/ui/dialogs';

// Admin section `corrections`: every request to correct a clinical record. An administrator gives a request
// that has no reviewer to one of the patient's doctors, or resolves it with a note. `?id=<requestId>` (the link
// of the CORRECTIONS notification) opens that request's panel.

type StatusChoice = CorrectionStatus | 'ALL';

const STATUS_OPTIONS: readonly SelectOption<StatusChoice>[] = [
  ...CORRECTION_STATUSES.map(status => ({ value: status, label: CORRECTION_STATUS_LABEL[status] })),
  { value: 'ALL', label: 'All statuses' },
];

const DECISION_OPTIONS: readonly SelectOption[] = [
  { value: 'ACCEPTED', label: 'Accept the request' },
  { value: 'REJECTED', label: 'Reject the request' },
];

function parseId(value: string | undefined): number | null {
  if (!value || !/^\d+$/.test(value)) return null;
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

function doctorOptionLabel(doctor: AssignableDoctor): string {
  const parts = [doctor.doctorName || doctor.doctorSystemId];
  if (doctor.specialization) parts.push(doctor.specialization);
  return `${parts.join(', ')}${doctor.currentAssignee ? ' (assigned now)' : ''}`;
}

function AssignmentBadge({ request }: { request: CorrectionRequest }) {
  if (request.assignedDoctorSystemId) {
    return (
      <StatusBadge
        label={`Assigned to ${request.assignedDoctorName ?? request.assignedDoctorSystemId}`}
        tone="neutral"
        icon="person-outline"
      />
    );
  }
  return isCorrectionOpen(request)
    ? <StatusBadge label="Unassigned" tone="warning" />
    : <StatusBadge label="No doctor assigned" tone="neutral" icon="person-outline" />;
}

function CorrectionRow({ request, onOpen }: { request: CorrectionRequest; onOpen: () => void }) {
  const record = correctionRecordLabel(request);
  const open = isCorrectionOpen(request);
  return (
    <Card style={styles.row}>
      <View style={styles.rowHead}>
        <View style={styles.rowTitles}>
          <Text style={styles.rowTitle} accessibilityRole="header" {...ariaLevel(2)}>{record}</Text>
          <Text style={styles.rowSub}>{`Patient: ${request.patientName} · ${request.patientSystemId}`}</Text>
        </View>
        <View style={styles.badges}>
          <StatusBadge label={CORRECTION_STATUS_LABEL[request.status] ?? request.status} tone={statusTone('correction', request.status)} />
          <AssignmentBadge request={request} />
        </View>
      </View>
      <Text style={styles.meta}>
        {`Asked by ${request.requestedByName} (${accountRoleLabel(request.requestedByRole).toLowerCase()}) on ${formatInstant(request.createdAt, 'an unknown date')}`}
      </Text>
      <Text style={styles.text} numberOfLines={2}>{request.requestedText}</Text>
      <View style={styles.rowActions}>
        <Button
          label={open ? 'Review' : 'View details'}
          icon={open ? 'clipboard-outline' : 'eye-outline'}
          variant={open ? 'primary' : 'secondary'}
          size="sm"
          onPress={onOpen}
          accessibilityLabel={`${open ? 'Review' : 'View details of'} the correction request for ${record} of ${request.patientName}`}
        />
      </View>
    </Card>
  );
}

function AssignSection({ request, disabled, onPendingChange, onAssigned, onConflict }: {
  request:    CorrectionRequest;
  disabled:   boolean;
  onPendingChange: (pending: boolean) => void;
  onAssigned: (next: CorrectionRequest) => void;
  /** The request changed on the server (resolved meanwhile, or it is gone). */
  onConflict: () => void;
}) {
  const doctors = useAssignableDoctors(request.requestId);
  const [doctorId, setDoctorId] = useState('');
  const [choiceError, setChoiceError] = useState<string | null>(null);
  const assign = useAction((id: string) => assignCorrection(request.requestId, id), {
    errorMessage: 'The request could not be assigned.',
  });

  // The panel cannot be closed, and the request not resolved, while the assignment is being saved.
  useEffect(() => {
    onPendingChange(assign.pending);
    return () => onPendingChange(false);
  }, [onPendingChange, assign.pending]);

  // A doctor chosen for the previous assignee list may no longer be on it.
  useEffect(() => {
    if (doctorId && doctors.data && !doctors.data.some(d => d.doctorSystemId === doctorId)) setDoctorId('');
  }, [doctors.data, doctorId]);

  const onAssign = async () => {
    if (!doctorId) { setChoiceError('Choose a doctor'); return; }
    setChoiceError(null);
    const result = await assign.run(doctorId);
    if (result.ok) {
      setDoctorId('');
      doctors.refetch();
      onAssigned(result.data);
    } else if (result.error && (result.error.status === 409 || result.error.status === 404)) {
      onConflict();
    }
  };

  let content;
  if (doctors.loading) {
    content = <LoadingView compact label="Loading the patient's doctors…" />;
  } else if (!doctors.data) {
    content = <ErrorView compact message={doctors.error ?? 'The doctors for this patient could not be loaded.'} onRetry={doctors.refetch} />;
  } else if (doctors.data.length === 0) {
    content = (
      <Banner
        tone="info"
        text="This patient has no active doctor with a care relationship, so the request cannot be assigned. Resolve it below."
      />
    );
  } else {
    const options: SelectOption[] = doctors.data.map(d => ({
      value: d.doctorSystemId, label: doctorOptionLabel(d), disabled: d.currentAssignee,
    }));
    content = (
      <>
        <SelectField
          label={request.assignedDoctorSystemId ? 'Reassign to' : 'Assign to'}
          value={doctorId}
          options={options}
          onChange={value => { setDoctorId(value); setChoiceError(null); assign.reset(); }}
          placeholder="Choose a doctor"
          allowEmpty
          error={choiceError}
          hint="Only doctors with a care relationship with this patient are listed. The doctor is notified."
          disabled={disabled || assign.pending}
        />
        {assign.error ? <Banner tone="danger" text={assign.error} onDismiss={assign.reset} /> : null}
        <Button
          label={request.assignedDoctorSystemId ? 'Reassign' : 'Assign'}
          icon="person-add-outline"
          variant="secondary"
          onPress={() => { void onAssign(); }}
          loading={assign.pending}
          disabled={disabled}
        />
      </>
    );
  }

  return (
    <View style={styles.block}>
      <Text style={styles.blockTitle} accessibilityRole="header" {...ariaLevel(3)}>Reviewer</Text>
      {content}
    </View>
  );
}

function ResolveSection({ request, disabled, onPendingChange, onResolved, onConflict }: {
  request:         CorrectionRequest;
  disabled:        boolean;
  onPendingChange: (pending: boolean) => void;
  onResolved:      (next: CorrectionRequest) => void;
  onConflict:      () => void;
}) {
  const [formError, setFormError] = useState<string | null>(null);
  const { control, handleSubmit, setError } = useForm<ResolveCorrectionValues>({
    resolver: zodResolver(resolveCorrectionSchema),
    defaultValues: { decision: '', note: '' },
  });
  const resolve = useAction(
    (values: ResolveCorrectionValues) => {
      if (!isCorrectionDecision(values.decision)) return Promise.reject(new Error('No decision chosen'));
      return resolveCorrection(request.requestId, values.decision, values.note.trim());
    },
    { errorMessage: 'The request could not be resolved.' },
  );

  // The panel cannot be closed, and the request not reassigned, while the decision is being saved.
  useEffect(() => {
    onPendingChange(resolve.pending);
    return () => onPendingChange(false);
  }, [onPendingChange, resolve.pending]);

  const submit = handleSubmit(async values => {
    setFormError(null);
    const accepting = values.decision === 'ACCEPTED';
    const confirmed = await confirmAction(
      accepting ? 'Accept this correction request?' : 'Reject this correction request?',
      'Your decision and note are sent to the person who asked. A resolved request cannot be reopened or changed. '
        + 'Resolving does not change the record itself.',
      accepting ? 'Accept' : 'Reject',
      !accepting,
    );
    if (!confirmed) return;
    const result = await resolve.run(values);
    if (result.ok) {
      onResolved(result.data);
    } else if (result.error) {
      setFormError(applyFieldErrors(result.error, setError, ['decision', 'note']));
      if (result.error.status === 409 || result.error.status === 404) onConflict();
    }
  });

  return (
    <View style={styles.block}>
      <Text style={styles.blockTitle} accessibilityRole="header" {...ariaLevel(3)}>Resolve</Text>
      <Text style={styles.note}>
        Resolving records the decision only. The record is corrected by its doctor through the normal editing
        screens, before or after the request is resolved.
      </Text>
      <FormError message={formError} />
      <SelectField
        control={control}
        name="decision"
        label="Decision"
        required
        options={DECISION_OPTIONS}
        placeholder="Choose a decision"
        disabled={disabled || resolve.pending}
      />
      <TextField
        control={control}
        name="note"
        label="Note"
        required
        multiline
        rows={4}
        maxLength={1000}
        showCount
        hint="Say how the request was resolved. The person who asked sees this note."
        disabled={disabled || resolve.pending}
      />
      <FormActions
        submitLabel="Resolve request"
        onSubmit={() => { void submit(); }}
        submitting={resolve.pending}
        disabled={disabled}
      />
    </View>
  );
}

function CorrectionPanel({ requestId, onClose, onChanged }: {
  requestId: number;
  onClose:   () => void;
  onChanged: (next: CorrectionRequest) => void;
}) {
  const detail = useAdminCorrection(requestId);
  const request = detail.data;
  const [notice, setNotice] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [resolving, setResolving] = useState(false);
  const [assigning, setAssigning] = useState(false);

  const applied = (next: CorrectionRequest, message: string) => {
    detail.setData(next);
    onChanged(next);
    setConflict(false);
    setNotice(message);
  };

  const onConflict = () => {
    setNotice(null);
    setConflict(true);
    detail.refetch();
  };

  let body;
  if (detail.loading) {
    body = <LoadingView label="Loading the request…" />;
  } else if (!request) {
    body = <ErrorView message={detail.error ?? 'The correction request could not be loaded.'} onRetry={detail.refetch} />;
  } else {
    const open = isCorrectionOpen(request);
    body = (
      <>
        {notice ? <Banner tone="success" text={notice} onDismiss={() => setNotice(null)} /> : null}
        {conflict ? (
          <Banner
            tone="warning"
            text="This request changed while you were working on it. What you see now is its current state."
            onDismiss={() => setConflict(false)}
          />
        ) : null}
        {detail.error ? <Banner tone="danger" text={detail.error} action={{ label: 'Retry', onPress: detail.refetch }} /> : null}

        <View style={styles.badges}>
          <StatusBadge label={CORRECTION_STATUS_LABEL[request.status] ?? request.status} tone={statusTone('correction', request.status)} />
          <AssignmentBadge request={request} />
        </View>

        <View>
          <InfoRow label="Record" value={correctionRecordLabel(request)} />
          <InfoRow label="Record type" value={CORRECTION_RESOURCE_LABEL[request.resourceType] ?? request.resourceType} />
          <InfoRow label="Patient" value={`${request.patientName} (${request.patientSystemId})`} />
          <InfoRow
            label="Asked by"
            value={`${request.requestedByName} (${accountRoleLabel(request.requestedByRole).toLowerCase()}, ${request.requestedBySystemId})`}
          />
          <InfoRow label="Filed" value={formatInstant(request.createdAt)} emptyText="Unknown" />
          <InfoRow label="Last change" value={formatInstant(request.updatedAt)} emptyText="Unknown" />
          <InfoRow
            label="Assigned doctor"
            value={request.assignedDoctorSystemId
              ? `${request.assignedDoctorName ?? 'Doctor'} (${request.assignedDoctorSystemId})`
              : null}
            emptyText="Nobody"
          />
        </View>

        <View style={styles.block}>
          <Text style={styles.blockTitle} accessibilityRole="header" {...ariaLevel(3)}>What should be corrected</Text>
          <Text style={styles.text} selectable>{request.requestedText}</Text>
        </View>

        {open ? (
          <>
            <AssignSection
              request={request}
              disabled={resolving}
              onPendingChange={setAssigning}
              onAssigned={next => applied(next, `Assigned to ${next.assignedDoctorName ?? next.assignedDoctorSystemId ?? 'the doctor'}. They were notified.`)}
              onConflict={onConflict}
            />
            <ResolveSection
              request={request}
              disabled={assigning}
              onPendingChange={setResolving}
              onResolved={next => applied(
                next,
                `The request was ${next.status === 'ACCEPTED' ? 'accepted' : 'rejected'}. The person who asked was told, with your note.`,
              )}
              onConflict={onConflict}
            />
          </>
        ) : (
          <View style={styles.block}>
            <Text style={styles.blockTitle} accessibilityRole="header" {...ariaLevel(3)}>Resolution</Text>
            <View>
              <InfoRow
                label="Resolved by"
                value={request.resolverSystemId ? `${request.resolverName ?? 'Unknown'} (${request.resolverSystemId})` : null}
                emptyText="Unknown"
              />
              <InfoRow label="Resolved" value={formatInstant(request.resolvedAt)} emptyText="Unknown" />
              <InfoRow label="Note" value={request.resolutionNote} emptyText="No note" />
            </View>
            <Text style={styles.note}>A resolved request is final. A new request can be filed for the same record.</Text>
          </View>
        )}
      </>
    );
  }

  return (
    <SidePanel
      open
      title={`Correction request #${requestId}`}
      onClose={onClose}
      dismissOnBackdrop={false}
      busy={resolving || assigning}
    >
      {body}
    </SidePanel>
  );
}

export default function AdminCorrections({ theme, params }: Partial<SectionProps>) {
  const nav = useSectionNav();
  const look = theme ?? ROLE_THEME.admin;
  const urlParams = params ?? nav.params;

  // Open requests are the work to do, so they are the default; `status=ALL` lists everything.
  const status: CorrectionStatus | undefined =
    urlParams.status === 'ALL' ? undefined : isCorrectionStatus(urlParams.status) ? urlParams.status : 'OPEN';
  const unassigned = urlParams.unassigned === 'true';
  const list = useAdminCorrections({ status, unassigned });
  const openId = parseId(urlParams.id);

  const onChanged = (next: CorrectionRequest) => {
    list.setItems(prev => prev.map(r => (r.requestId === next.requestId ? next : r)));
    // The row may no longer match the filters (resolved under "Open", assigned under "Only unassigned").
    if ((status && next.status !== status) || (unassigned && next.assignedDoctorSystemId)) list.refetch();
  };

  const empty = unassigned
    ? { icon: 'checkmark-done-outline' as const, title: 'No unassigned requests', sub: 'Every request in this view has a doctor to review it.' }
    : status === 'OPEN'
      ? { icon: 'checkmark-done-outline' as const, title: 'No open correction requests', sub: 'Requests from patients and caregivers appear here.' }
      : { icon: 'file-tray-outline' as const, title: 'No correction requests', sub: 'Nothing matches the status filter.' };

  return (
    <Screen
      title="Correction requests"
      subtitle="Requests from patients and caregivers to correct a clinical record. Give a request with no reviewer to one of the patient's doctors, or resolve it with a note."
      theme={look}
    >
      <PagedList
        state={list}
        label="Correction requests"
        keyOf={r => r.requestId}
        header={(
          <View style={styles.filters}>
            <View style={styles.filter}>
              <SelectField<StatusChoice>
                label="Status"
                value={status ?? 'ALL'}
                options={STATUS_OPTIONS}
                onChange={value => nav.setParams({ status: value === 'OPEN' ? undefined : value })}
              />
            </View>
            <View style={styles.filterSwitch}>
              <SwitchField
                label="Only unassigned"
                description="Requests that no doctor has been asked to review."
                value={unassigned}
                onChange={value => nav.setParams({ unassigned: value ? 'true' : undefined })}
              />
            </View>
          </View>
        )}
        renderItem={request => (
          <CorrectionRow request={request} onOpen={() => nav.setParams({ id: request.requestId })} />
        )}
        empty={empty}
      />

      {openId != null ? (
        <CorrectionPanel
          key={openId}
          requestId={openId}
          onClose={() => nav.setParams({ id: undefined })}
          onChanged={onChanged}
        />
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  filters:      { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: space.md },
  filter:       { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0, maxWidth: 320 },
  filterSwitch: { flexGrow: 1, flexShrink: 1, flexBasis: 240, minWidth: 0, maxWidth: 420 },

  row:        { gap: space.sm },
  rowHead:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', gap: space.sm },
  rowTitles:  { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0, gap: 2 },
  rowTitle:   { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  rowSub:     { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  rowActions: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  badges:     { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs, maxWidth: '100%' },
  meta:       { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  text:       { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },

  block:      { gap: space.md, borderTopWidth: 1, borderTopColor: colors.border, paddingTop: space.md },
  blockTitle: { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  note:       { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
});
