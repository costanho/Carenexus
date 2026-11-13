import { useCallback, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { Banner, Button, PagedList, Screen, type SelectOption, space, Tabs } from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { useCurrentUser } from '@/hooks/use-current-user';
import {
  cancelLabResult, type DoctorLabResult, getLabResult, reviewLabResult, updateLabResultStatus,
  useCriticalLabResultCount, useDoctorLabPendingReview, useDoctorLabResultList, useDoctorPatientName,
} from '@/hooks/use-doctor-lab-results';
import { confirmAction } from '@/lib/ui/dialogs';
import { isLabTestType, LAB_TEST_TYPE_LABELS, LAB_TEST_TYPES, type LabTestType } from '@/lib/validation/results';
import { resultAttachmentSlot, resultAttachmentViewSlot } from './results/attachments';
import { LabResultForm } from './results/lab-result-form';
import { LabResultCard, LabResultDetail } from './results/lab-result-views';
import { ReviewResultDialog } from './results/review-dialog';
import {
  ResultActions, ResultDetailPanel, ResultFilters, type ResultNotice, ResultNoticeBanner, resultPermissions,
  type ResultSectionProps, resultStatusLabel, ResultSummary, type ResultTab, useOpenedResult, useResultSection,
} from './results/shared';

// Doctor "Labs & Results" section: the paged list with server-side filters, the review queue, the record and
// edit form, Mark reviewed, and the detail panel a notification link opens (`?section=labs&id=<resultId>`).
// URL params: tab (all | review), patient, status, type, critical, id, mode=new, consultationId.

const TYPE_OPTIONS: SelectOption<LabTestType>[] = LAB_TEST_TYPES.map(t => ({ value: t, label: LAB_TEST_TYPE_LABELS[t] }));

const TABS = [
  { key: 'all',    label: 'All results' },
  { key: 'review', label: 'Pending review' },
] as const;

function permissionsOf(result: DoctorLabResult, doctorId: string | null) {
  return resultPermissions(
    { status: result.status, reviewedAt: result.reviewed_at, doctorSystemId: result.doctor_system_id },
    doctorId,
  );
}

export default function DoctorWebLabResults(props: ResultSectionProps) {
  // Uploads go through the documents API unless the host supplies its own controls.
  const attachmentSlot = props.attachmentSlot ?? resultAttachmentSlot;
  const attachmentViewSlot = props.attachmentViewSlot ?? resultAttachmentViewSlot;
  const { params, setParams, theme, tab, patientSystemId, status, critical, openId, creating, consultationId } = useResultSection(props);
  const typeParam = (params.type ?? '').trim().toUpperCase();
  const type = isLabTestType(typeParam) ? typeParam : null;

  const { user } = useCurrentUser();
  const doctorId = user?.system_id ?? null;

  const list          = useDoctorLabResultList({ patientSystemId, status, type, critical });
  const pending       = useDoctorLabPendingReview();
  const criticalCount = useCriticalLabResultCount(patientSystemId);
  const patientName   = useDoctorPatientName(patientSystemId);

  const opened = useOpenedResult<DoctorLabResult>(
    openId,
    r => r.result_id,
    id => list.items.find(r => r.result_id === id) ?? pending.items.find(r => r.result_id === id),
    getLabResult,
  );

  const [editing, setEditing]     = useState<DoctorLabResult | null>(null);
  const [reviewing, setReviewing] = useState<DoctorLabResult | null>(null);
  const [notice, setNotice]       = useState<ResultNotice | null>(null);
  // Read outside the review callback: the React Compiler hoists property reads in closures into memo dependencies
  // that run on every render, so `reviewing!.result_id` inside it would throw while nothing is being reviewed.
  const reviewingId = reviewing?.result_id ?? null;

  const refetchList = list.refetch, refetchPending = pending.refetch, refetchCritical = criticalCount.refetch;
  const refreshAll = useCallback(() => {
    refetchList();
    refetchPending();
    refetchCritical();
  }, [refetchList, refetchPending, refetchCritical]);

  const setListItems = list.setItems, setPendingItems = pending.setItems, setOpened = opened.set;
  /** Shows the result as the server answered at once, then reloads the lists (a filter may no longer match it). */
  const applyResult = useCallback((updated: DoctorLabResult) => {
    const replace = (rows: DoctorLabResult[]) => rows.map(r => (r.result_id === updated.result_id ? updated : r));
    setListItems(replace);
    setPendingItems(replace);
    setOpened(updated);
    refreshAll();
  }, [setListItems, setPendingItems, setOpened, refreshAll]);

  const sendAction   = useAction((row: DoctorLabResult) => updateLabResultStatus(row.result_id, 'REQUIRES_REVIEW'));
  const cancelAction = useAction((row: DoctorLabResult) => cancelLabResult(row.result_id));
  const busy = sendAction.pending || cancelAction.pending;

  const sendForReview = async (row: DoctorLabResult) => {
    if (row.reviewed_at) {
      const lost = row.is_critical
        ? ' It is no longer marked critical until it is reviewed again.'
        : '';
      const ok = await confirmAction(
        'Request another review?',
        `The current sign-off is cleared and the result goes back to the review queue.${lost}`,
        'Request review',
      );
      if (!ok) return;
    }
    setNotice(null);
    const result = await sendAction.run(row);
    if (result.ok) {
      applyResult(result.data);
      setNotice({ tone: 'success', text: `${row.test_name} is waiting for review. Status: ${resultStatusLabel(result.data.status)}.` });
    } else if (result.error) {
      setNotice({ tone: 'danger', text: result.error.message });
    }
  };

  const cancelResult = async (row: DoctorLabResult) => {
    const ok = await confirmAction(
      'Cancel this result?',
      `${row.test_name} for ${row.patient_name} will be marked as cancelled and removed from the lists. This cannot be undone.`,
      'Cancel result',
      true,
    );
    if (!ok) return;
    setNotice(null);
    const result = await cancelAction.run(row);
    if (result.ok) {
      const without = (rows: DoctorLabResult[]) => rows.filter(r => r.result_id !== row.result_id);
      setListItems(without);
      setPendingItems(without);
      setParams({ id: undefined });
      refreshAll();
      setNotice({ tone: 'success', text: `${row.test_name} for ${row.patient_name} was cancelled.` });
    } else if (result.error) {
      setNotice({ tone: 'danger', text: result.error.message });
    }
  };

  const openForm  = () => { setNotice(null); setParams({ mode: 'new' }); };
  const closeForm = () => {
    if (editing) setEditing(null);
    else setParams({ mode: undefined, consultationId: undefined });
  };
  const onSaved = (result: DoctorLabResult, created: boolean) => {
    closeForm();
    if (created) refreshAll();
    else applyResult(result);
    // The status shown is the server's: a completed result flagged critical is stored as Critical.
    setNotice({
      tone: 'success',
      text: `${result.test_name} for ${result.patient_name} was ${created ? 'recorded' : 'updated'}. Status: ${resultStatusLabel(result.status)}.`,
    });
  };

  const filtered = status != null || type != null || critical != null;
  const count = criticalCount.count ?? 0;
  const detail = opened.item;
  const detailPermissions = detail ? permissionsOf(detail, doctorId) : null;

  const renderCard = (result: DoctorLabResult) => (
    <LabResultCard
      result={result}
      onOpen={() => setParams({ id: result.result_id })}
      onReview={permissionsOf(result, doctorId).canReview ? () => setReviewing(result) : undefined}
    />
  );

  return (
    <Screen
      title="Labs & Results"
      subtitle={patientSystemId
        ? "This patient's lab results from every doctor"
        : 'Laboratory test results for all your patients'}
      theme={theme}
      actions={(
        <>
          <Button label="Refresh" variant="secondary" icon="refresh-outline" onPress={refreshAll} loading={list.refreshing || pending.refreshing} />
          <Button label="Record result" icon="add" onPress={openForm} />
        </>
      )}
    >
      <ResultNoticeBanner notice={notice} onDismiss={() => setNotice(null)} />

      {tab === 'all' && count > 0 && critical !== true ? (
        <Banner
          tone="danger"
          text={`${count} critical lab ${count === 1 ? 'result' : 'results'} ${patientSystemId ? 'for this patient' : 'among the results you recorded'}.`}
          action={{ label: 'Show critical', onPress: () => setParams({ critical: 'true', status: undefined }) }}
        />
      ) : null}

      <ResultSummary
        items={[
          { label: filtered ? 'Matching' : 'Total', value: list.loading || (list.error && list.items.length === 0) ? null : list.total, icon: 'flask-outline' },
          { label: 'Pending review', value: pending.loading || (pending.error && pending.items.length === 0) ? null : pending.total, icon: 'eye-outline', tone: 'warning' },
          { label: 'Critical', value: criticalCount.count, icon: 'alert-circle-outline', tone: 'danger' },
        ]}
      />

      <Tabs<ResultTab>
        tabs={TABS.map(t => (t.key === 'review' ? { ...t, badge: pending.total } : t))}
        active={tab}
        onChange={key => setParams({ tab: key === 'all' ? undefined : key })}
        label="Lab result lists"
      />

      {tab === 'all' ? (
        <View role="tabpanel" aria-label="All results">
          <PagedList
            state={list}
            keyOf={r => r.result_id}
            renderItem={renderCard}
            label="Lab results"
            header={(
              <ResultFilters<LabTestType>
                status={status}
                onStatus={value => setParams({ status: value ?? undefined })}
                typeLabel="Test type"
                type={type}
                typeOptions={TYPE_OPTIONS}
                onType={value => setParams({ type: value ?? undefined })}
                critical={critical}
                onCritical={value => setParams({ critical: value == null ? undefined : String(value) })}
                patient={patientSystemId ? patientName : null}
                onClearPatient={() => setParams({ patient: undefined })}
                onReset={() => setParams({ status: undefined, type: undefined, critical: undefined })}
                scopeNote={patientSystemId
                  ? 'Showing this patient\'s lab results from every doctor, newest first.'
                  : 'Showing the lab results you recorded, newest first. Open a patient\'s results from their record to see those of every doctor.'}
              />
            )}
            empty={filtered
              ? { icon: 'flask-outline', title: 'No lab results match these filters', sub: 'Change or clear the filters to see more.' }
              : patientSystemId
                ? { icon: 'flask-outline', title: 'No lab results for this patient', action: { label: 'Record result', onPress: openForm, icon: 'add' } }
                : {
                    icon: 'flask-outline', title: 'No lab results yet', sub: 'Results you record appear here.',
                    action: { label: 'Record result', onPress: openForm, icon: 'add' },
                  }}
          />
        </View>
      ) : (
        <View role="tabpanel" aria-label="Pending review">
          <PagedList
            state={pending}
            keyOf={r => r.result_id}
            renderItem={renderCard}
            label="Lab results waiting for review"
            header={patientSystemId
              ? <Banner tone="info" text="The review queue covers all your patients. The patient filter applies to All results." />
              : undefined}
            empty={{
              icon: 'checkmark-done-outline', title: 'Nothing is waiting for review',
              sub: 'Unreviewed results that need review or are critical appear here, for results you recorded and for patients on your care team.',
            }}
          />
        </View>
      )}

      <ResultDetailPanel
        open={openId != null}
        title={detail?.test_name || 'Lab result'}
        loading={opened.loading}
        error={opened.error}
        onRetry={opened.retry}
        onClose={() => setParams({ id: undefined })}
        footer={detail && detailPermissions ? (
          <ResultActions
            busy={busy}
            onReview={detailPermissions.canReview ? () => setReviewing(detail) : undefined}
            onSendForReview={detailPermissions.canSendForReview ? () => { void sendForReview(detail); } : undefined}
            sendForReviewLabel={detailPermissions.clearsSignOff ? 'Request another review' : 'Send for review'}
            onEdit={detailPermissions.canEdit ? () => setEditing(detail) : undefined}
            onCancel={detailPermissions.canCancel ? () => { void cancelResult(detail); } : undefined}
          />
        ) : undefined}
      >
        {detail ? (
          <View style={styles.detail}>
            <ResultNoticeBanner notice={notice} onDismiss={() => setNotice(null)} />
            <LabResultDetail result={detail} attachmentViewSlot={attachmentViewSlot} />
          </View>
        ) : null}
      </ResultDetailPanel>

      <LabResultForm
        open={creating || editing != null}
        editing={editing}
        initialPatientSystemId={patientSystemId}
        initialConsultationId={consultationId}
        attachmentSlot={attachmentSlot}
        onClose={closeForm}
        onSaved={onSaved}
      />

      <ReviewResultDialog<DoctorLabResult>
        open={reviewing != null}
        subject={reviewing ? `${reviewing.test_name} for ${reviewing.patient_name}` : ''}
        defaultStatus={reviewing?.is_critical ? 'CRITICAL' : 'COMPLETED'}
        review={body => (reviewingId === null
          ? Promise.reject(new Error('No lab result is open for review.'))
          : reviewLabResult(reviewingId, body))}
        onReviewed={updated => {
          setReviewing(null);
          applyResult(updated);
          setNotice({
            tone: 'success',
            text: `${updated.test_name} for ${updated.patient_name} was reviewed. Status: ${resultStatusLabel(updated.status)}.`,
          });
        }}
        onClose={() => setReviewing(null)}
      />
    </Screen>
  );
}

const styles = StyleSheet.create({
  detail: { gap: space.lg },
});
