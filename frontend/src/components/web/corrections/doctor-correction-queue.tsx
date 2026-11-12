import { zodResolver } from '@hookform/resolvers/zod';
import { useCallback, useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import {
  Banner, Button, colors, type DialogCloseReason, fontSize, FormActions, FormError, LoadingView, Modal, PagedList,
  radius, space, TextField,
} from '@/components/web/ui';
import {
  useAssignedCorrectionRequests, useCorrectionRequest, usePatientCorrectionRequests, useResolveCorrectionRequest,
} from '@/hooks/use-correction-requests';
import { useCurrentUser } from '@/hooks/use-current-user';
import { useSectionNav } from '@/hooks/use-section-nav';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { subscribeCorrectionsChanged } from '@/lib/corrections/events';
import { canDoctorResolve, correctionRecordLink, correctionResourcePhrase } from '@/lib/corrections/labels';
import { CORRECTION_NOTE_MAX, type CorrectionResolveFormValues, correctionResolveSchema } from '@/lib/corrections/schema';
import type { CorrectionDecision, CorrectionRequest } from '@/lib/corrections/types';
import { confirmAction } from '@/lib/ui/dialogs';
import { CorrectionRequestCard } from './correction-request-card';
import { type CorrectionStatusFilter, CorrectionStatusSelect, statusOfFilter, toRequestId } from './status-filter';

export interface DoctorCorrectionQueueProps {
  /** The request a notification link points at (`id` URL param). It is shown first and marked. */
  highlightId?:     number | string | null;
  /**
   * The `patient` URL filter. When set the list shows every correction request about that patient (the doctor
   * must be part of the patient's care team) instead of the doctor's own queue; requests assigned to other
   * doctors are listed without the Accept and Reject actions.
   */
  patientSystemId?: string | null;
  /** The filtered patient's name, for the sentence above the list. */
  patientName?:     string | null;
  /**
   * Opens the record a request is about. By default the queue navigates to the doctor section that shows that
   * kind of record with `id` and `patient`. Pass null for no link.
   */
  onOpenRecord?:    ((request: CorrectionRequest) => void) | null;
}

interface Decision { request: CorrectionRequest; decision: CorrectionDecision }

/**
 * The doctor's correction queue: requests assigned to the signed-in doctor, each with a link to the record and
 * Accept / Reject with a note for the person who asked. Resolving only records the decision; the record itself
 * is corrected from its own section.
 */
export function DoctorCorrectionQueue({ highlightId, patientSystemId, patientName, onOpenRecord }: DoctorCorrectionQueueProps) {
  const nav = useSectionNav();
  const { user } = useCurrentUser();
  const doctorSystemId = user?.system_id ?? null;
  const patient = (patientSystemId ?? '').trim() || null;

  const [filter, setFilter] = useState<CorrectionStatusFilter>('OPEN');
  const status = statusOfFilter(filter);
  // Both hooks are always called; only the one that applies sends requests.
  const assigned = useAssignedCorrectionRequests({ status, enabled: !patient });
  const ofPatient = usePatientCorrectionRequests(patient, { status, enabled: !!patient });
  const requests = patient ? ofPatient : assigned;

  const linkedId = toRequestId(highlightId);
  const onPage = linkedId !== null && requests.items.some(r => r.requestId === linkedId);
  const linked = useCorrectionRequest(linkedId, !requests.loading && !onPage);
  const refetchLinked = linked.refetch;
  useEffect(() => subscribeCorrectionsChanged(() => refetchLinked()), [refetchLinked]);

  const [deciding, setDeciding] = useState<Decision | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const openRecord = useCallback((request: CorrectionRequest) => {
    const link = correctionRecordLink('doctor', request);
    if (link) nav.navigate(link.section, link.params);
  }, [nav]);
  const opener = (request: CorrectionRequest) => {
    if (onOpenRecord === null) return undefined;
    if (onOpenRecord) return onOpenRecord;
    const link = correctionRecordLink('doctor', request);
    return link && nav.hasSection(link.section) ? openRecord : undefined;
  };

  const actionsFor = (request: CorrectionRequest) => (canDoctorResolve(request, doctorSystemId) ? (
    <>
      <Button
        label="Accept"
        accessibilityLabel={`Accept the correction request of ${request.requestedByName}`}
        icon="checkmark-outline"
        size="sm"
        onPress={() => { setNotice(null); setDeciding({ request, decision: 'ACCEPTED' }); }}
      />
      <Button
        label="Reject"
        accessibilityLabel={`Reject the correction request of ${request.requestedByName}`}
        icon="close-outline"
        variant="danger"
        size="sm"
        onPress={() => { setNotice(null); setDeciding({ request, decision: 'REJECTED' }); }}
      />
    </>
  ) : null);

  const card = (request: CorrectionRequest, highlighted: boolean) => (
    <CorrectionRequestCard
      request={request}
      showPatient
      highlighted={highlighted}
      onOpenRecord={opener(request)}
      actions={actionsFor(request)}
    />
  );

  const header = (
    <View style={styles.header}>
      {notice ? <Banner tone="success" text={notice} onDismiss={() => setNotice(null)} /> : null}
      <Text style={styles.intro}>
        {patient
          ? `Every correction request about ${patientName?.trim() || 'this patient'}. You can answer the ones assigned to you.`
          : 'Patients and their caregivers ask here for corrections to records you wrote. Open the record, correct it if needed, then accept or reject the request with a note.'}
      </Text>
      <CorrectionStatusSelect value={filter} onChange={setFilter} />
      {linkedId !== null && !onPage ? (
        linked.loading ? <LoadingView compact label="Loading the linked request" />
          : linked.data ? (
            <View style={styles.linked}>
              <Text style={styles.linkedLabel}>Request from your notification</Text>
              {card(linked.data, true)}
            </View>
          ) : linked.error ? (
            <Banner
              tone="warning"
              text={linked.errorStatus === 403 || linked.errorStatus === 404
                ? 'The correction request from your notification is not available.'
                : linked.error}
              action={linked.errorStatus === 403 || linked.errorStatus === 404 ? undefined : { label: 'Retry', onPress: refetchLinked }}
            />
          ) : null
      ) : null}
    </View>
  );

  return (
    <>
      <PagedList
        state={requests}
        label="Correction requests"
        keyOf={r => r.requestId}
        header={header}
        noAccessMessage="You are not part of this patient's care team. Remove the patient filter to see your own queue."
        empty={filter === 'OPEN'
          ? { icon: 'checkmark-done-outline', title: 'No open correction requests', sub: patient ? 'Nothing is waiting for a review for this patient.' : 'Nothing is waiting for your review.' }
          : { icon: 'create-outline', title: 'No correction requests', sub: 'No request matches this filter.' }}
        renderItem={request => card(request, request.requestId === linkedId)}
      />
      {deciding ? (
        <ResolveDialog
          key={`${deciding.request.requestId}-${deciding.decision}`}
          request={deciding.request}
          decision={deciding.decision}
          onClose={() => setDeciding(null)}
          onResolved={resolved => {
            setDeciding(null);
            setNotice(`Request ${resolved.status === 'ACCEPTED' ? 'accepted' : 'rejected'}. ${resolved.requestedByName} has been notified.`);
          }}
        />
      ) : null}
    </>
  );
}

function ResolveDialog({ request, decision, onClose, onResolved }: {
  request:    CorrectionRequest;
  decision:   CorrectionDecision;
  onClose:    () => void;
  onResolved: (request: CorrectionRequest) => void;
}) {
  const action = useResolveCorrectionRequest();
  const [formError, setFormError] = useState<string | null>(null);
  // Someone else resolved the request meanwhile: the form is replaced by that message.
  const [stale, setStale] = useState(false);
  const accepting = decision === 'ACCEPTED';
  const kind = correctionResourcePhrase(request.resourceType);

  const { control, handleSubmit, setError, formState: { isDirty } } = useForm<CorrectionResolveFormValues>({
    resolver:      zodResolver(correctionResolveSchema),
    defaultValues: { note: '' },
  });

  const close = async (reason: DialogCloseReason | 'cancel') => {
    if (!stale && isDirty && reason !== 'cancel') {
      const discard = await confirmAction('Discard your note?', 'What you typed will be lost.', 'Discard', true);
      if (!discard) return;
    }
    onClose();
  };

  const onSubmit = handleSubmit(async values => {
    setFormError(null);
    const result = await action.resolve(request, decision, values.note);
    if (result.ok) { onResolved(result.data); return; }
    if (!result.error) return;
    if (result.error.status === 409) {
      setFormError(result.error.message);
      setStale(true);
      return;
    }
    setFormError(applyFieldErrors(result.error, setError, ['note']));
  });

  return (
    <Modal
      open
      title={accepting ? 'Accept correction request' : 'Reject correction request'}
      onClose={reason => { void close(reason); }}
      dismissOnBackdrop={false}
      busy={action.pending}
      footer={stale ? (
        <FormActions submitLabel="Close" onSubmit={onClose} />
      ) : (
        <FormActions
          submitLabel={accepting ? 'Accept request' : 'Reject request'}
          destructive={!accepting}
          onSubmit={() => { void onSubmit(); }}
          onCancel={() => { void close('cancel'); }}
          submitting={action.pending}
        />
      )}
    >
      <View style={styles.quote}>
        <Text style={styles.quoteLabel}>
          {`${request.requestedByName} asked for a correction to ${kind} of ${request.patientName}`}
        </Text>
        <Text style={styles.quoteText}>{request.requestedText}</Text>
      </View>
      {stale ? null : (
          <TextField
            control={control}
            name="note"
            label={`Note for ${request.requestedByName}`}
            hint={accepting
              ? 'Say what was corrected. Accepting only records your decision: change the record itself in its own section.'
              : 'Explain why the record stays as it is.'}
            multiline
            rows={4}
            maxLength={CORRECTION_NOTE_MAX}
            showCount
            required
            disabled={action.pending}
          />
      )}
      <FormError message={formError} />
    </Modal>
  );
}

const styles = StyleSheet.create({
  header:      { gap: space.md, minWidth: 0 },
  intro:       { fontSize: fontSize.md, color: colors.textMuted, lineHeight: 21 },
  linked:      { gap: space.xs, minWidth: 0 },
  linkedLabel: { fontSize: fontSize.sm, fontWeight: '600', color: colors.textMuted },
  quote:       { gap: space.xs, padding: space.md, borderRadius: radius.md, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.bg },
  quoteLabel:  { fontSize: fontSize.xs, fontWeight: '700', color: colors.textMuted },
  quoteText:   { fontSize: fontSize.md, color: colors.text, lineHeight: 21 },
});
