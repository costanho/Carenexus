import { zodResolver } from '@hookform/resolvers/zod';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { type Resolver, useForm, useWatch } from 'react-hook-form';
import { Platform, StyleSheet, Text, View } from 'react-native';
import {
  Banner, Button, Card, colors, DateField, fontSize, FormActions, FormError, Modal, SectionCard, space,
  SwitchField, TextField,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import {
  cancelConsultation, completeConsultation, type ConsultationDto, getConsultation, saveConsultation,
  startConsultation,
} from '@/hooks/use-doctor-consultations';
import { useDraft } from '@/hooks/use-draft';
import type { ApiError } from '@/lib/api/errors';
import { installSignOutReset } from '@/lib/communications/session';
import { clinicNowLocal, clinicToday, formatClinicTime } from '@/lib/time/clinic';
import {
  completionBlockedMessage, completionIssues, consultationFieldIssues, CONSULTATION_DURATION_MAX,
  CONSULTATION_DURATION_MIN, CONSULTATION_FIELD_LABELS, CONSULTATION_FORM_FIELDS, consultationFormSchema,
  type ConsultationFormValues, consultationFormValues, CONSULTATION_TEXT_MAX, consultationUpdateBody,
  fieldIssueFromMessage, sameConsultationValues,
} from '@/lib/validation/consultation';
import { consultationDraftKey } from './consultation-format';

// The editable consultation: notes, follow-up and duration, with Save draft, Start, Complete and Cancel.
// Text that has not been saved is kept in this browser (use-draft, the same store as message drafts, cleared on
// sign-out) and restored when the consultation is opened again.

export interface ConsultationFormProps {
  /** A DRAFT or IN_PROGRESS consultation authored by the signed-in doctor. */
  consultation:   ConsultationDto;
  /** The signed-in doctor (owner of the local draft). */
  doctorSystemId: string;
  /** The consultation as the server returned it after a save or a status change. */
  onChange:       (consultation: ConsultationDto) => void;
  /** Loads the consultation again after the server refused a change because its state moved on (409). */
  onReload:       () => void;
}

// Unsaved notes are clinical text: make sure the sign-out handler that empties the draft store is installed
// even when no messaging screen has been opened in this tab (the call is idempotent).
installSignOutReset();

const DRAFT_VERSION = 1;

/** Complete form values from a partial set (a watched form, a stored draft), falling back field by field. */
function fullValues(partial: Partial<Record<keyof ConsultationFormValues, unknown>>, fallback: ConsultationFormValues): ConsultationFormValues {
  const out: Record<string, string | boolean> = { ...fallback };
  for (const field of CONSULTATION_FORM_FIELDS) {
    const value = partial[field];
    if (typeof value === typeof fallback[field]) out[field] = value as string | boolean;
  }
  return out as unknown as ConsultationFormValues;
}

/** The unsaved values kept in this browser, or null when there are none (or they equal what is saved). */
function readDraft(stored: string, saved: ConsultationFormValues): ConsultationFormValues | null {
  if (!stored) return null;
  try {
    const parsed = JSON.parse(stored) as { v?: unknown; values?: unknown } | null;
    if (!parsed || parsed.v !== DRAFT_VERSION || !parsed.values || typeof parsed.values !== 'object') return null;
    const values = fullValues(parsed.values as Partial<Record<keyof ConsultationFormValues, unknown>>, saved);
    return sameConsultationValues(values, saved) ? null : values;
  } catch {
    return null;
  }
}

type Confirm = 'complete' | 'cancel' | null;

export function ConsultationForm({ consultation, doctorSystemId, onChange, onReload }: ConsultationFormProps) {
  const consultationId = consultation.consultationId;
  const saved = useMemo(() => consultationFormValues(consultation), [consultation]);

  const draft = useDraft(consultationDraftKey(doctorSystemId, consultationId));
  // Read once, when the form opens: the draft text at that moment is what an earlier visit left behind.
  const [restored] = useState(() => readDraft(draft.text, saved));
  const [restoredNotice, setRestoredNotice] = useState(restored !== null);

  const storedFollowUpDate = consultation.followUpRequired ? consultation.followUpDate : null;
  const storedDurationMinutes = consultation.durationMinutes;
  const resolver = useMemo<Resolver<ConsultationFormValues>>(() => (values, context, options) => (
    // Built per validation so "today" is the clinic date at that moment.
    zodResolver(consultationFormSchema({ today: clinicToday(), storedFollowUpDate, storedDurationMinutes }))(values, context, options)
  ), [storedFollowUpDate, storedDurationMinutes]);

  const form = useForm<ConsultationFormValues>({ resolver, defaultValues: restored ?? saved, mode: 'onBlur' });
  const { control, setError } = form;

  const watched = useWatch({ control });
  const values = useMemo(() => fullValues(watched, saved), [watched, saved]);
  const dirty = !sameConsultationValues(values, saved);

  // Keep unsaved text in this browser while it differs from what is saved; forget it once it does not.
  const serialized = dirty ? JSON.stringify({ v: DRAFT_VERSION, values }) : '';
  const { text: draftText, setText: setDraftText, clear: clearDraft } = draft;
  useEffect(() => {
    if (serialized === draftText) return;
    if (serialized) setDraftText(serialized);
    else clearDraft();
  }, [serialized, draftText, setDraftText, clearDraft]);

  // Closing or reloading the tab with unsaved notes asks first (the stored draft does not survive a closed tab).
  useEffect(() => {
    if (Platform.OS !== 'web' || !dirty || typeof window === 'undefined') return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  const [formError, setFormError] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [confirm, setConfirm] = useState<Confirm>(null);
  const [savedAt, setSavedAt] = useState<string | null>(null);

  const save = useAction(
    (body: ReturnType<typeof consultationUpdateBody>) => saveConsultation(consultationId, body),
    { errorMessage: 'Your notes could not be saved. Please try again.' },
  );
  const start = useAction(() => startConsultation(consultationId), { errorMessage: 'The consultation could not be started. Please try again.' });
  const complete = useAction(() => completeConsultation(consultationId), { errorMessage: 'The consultation could not be completed. Please try again.' });
  const cancel = useAction(() => cancelConsultation(consultationId), { errorMessage: 'The consultation could not be cancelled. Please try again.' });
  const busy = save.pending || start.pending || complete.pending || cancel.pending;

  /** Shows a refused request where it belongs: on the field the server names, otherwise above the buttons. */
  const showFailure = useCallback((error: ApiError) => {
    if (error.status === 400) {
      const { issues, unmatched } = consultationFieldIssues(error.fieldErrors);
      // Rules checked in the service arrive as "<field>: <text>" without an errors list.
      const named = issues.length === 0 ? fieldIssueFromMessage(error.message) : null;
      const onFields = named ? [named] : issues;
      onFields.forEach((issue, index) => {
        setError(issue.field, { type: 'server', message: issue.message }, { shouldFocus: index === 0 });
      });
      setFormError(onFields.length > 0 && unmatched === 0 ? null : error.message);
      return;
    }
    setFormError(error.message);
    // The consultation was completed or cancelled elsewhere, or the transition is no longer allowed.
    if (error.status === 409) setConflict(true);
  }, [setError]);

  /**
   * After a successful save the form shows what is stored. `sent` is what the form held when the request left:
   * text typed while the save was in flight is put back on top of the stored copy and stays in the local draft.
   */
  const applySaved = useCallback((next: ConsultationDto, sent: ConsultationFormValues) => {
    const stored = consultationFormValues(next);
    const current = form.getValues();
    const newer = CONSULTATION_FORM_FIELDS.filter(field => current[field] !== sent[field]);
    form.reset(stored);
    if (newer.length === 0) {
      clearDraft();
    } else {
      // The draft effect stores these again on the next render, because the form now differs from `stored`.
      for (const field of newer) {
        form.setValue(field, current[field] as never, { shouldDirty: true });
      }
    }
    setRestoredNotice(false);
    setSavedAt(clinicNowLocal());
    onChange(next);
  }, [form, clearDraft, onChange]);

  const onSave = form.handleSubmit(async submitted => {
    setFormError(null);
    // Raw form values (before the resolver's output), to tell later typing apart from what is sent.
    const sent = { ...form.getValues() };
    const result = await save.run(consultationUpdateBody(submitted, consultation));
    if (result.ok) applySaved(result.data, sent);
    else if (result.error) showFailure(result.error);
  }, () => setFormError('Some fields need attention. Check the messages under the highlighted fields.'));

  const onStart = async () => {
    setFormError(null);
    const result = await start.run();
    // Unsaved text stays in the form: starting changes the status only.
    if (result.ok) onChange(result.data);
    else if (result.error) showFailure(result.error);
  };

  const askComplete = async () => {
    setFormError(null);
    const valid = await form.trigger(undefined, { shouldFocus: true });
    const missing = completionIssues(form.getValues());
    missing.forEach((issue, index) => {
      setError(issue.field, { type: 'completion', message: issue.message }, { shouldFocus: valid && index === 0 });
    });
    if (!valid) return;
    if (missing.length > 0) { setFormError(completionBlockedMessage(missing)); return; }
    setConfirm('complete');
  };

  const onComplete = async () => {
    let current = consultation;
    if (dirty) {
      const sent = { ...form.getValues() };
      const stored = await save.run(consultationUpdateBody(sent, consultation));
      if (!stored.ok) {
        setConfirm(null);
        if (stored.error) showFailure(stored.error);
        return;
      }
      applySaved(stored.data, sent);
      current = stored.data;
    }
    const result = await complete.run();
    if (!result.ok) {
      setConfirm(null);
      if (result.error) showFailure(result.error);
      return;
    }
    let completed = result.data;
    if (completed.medicalRecordId === null) {
      // The record is created while completing; ask once more before showing "no record".
      completed = await getConsultation(current.consultationId).catch(() => completed);
    }
    clearDraft();
    setConfirm(null);
    onChange(completed);
  };

  const onCancelConsultation = async () => {
    const result = await cancel.run();
    setConfirm(null);
    if (result.ok) {
      clearDraft();
      onChange(result.data);
    } else if (result.error) {
      showFailure(result.error);
    }
  };

  const discardRestored = () => {
    form.reset(saved);
    clearDraft();
    setRestoredNotice(false);
    setFormError(null);
  };

  const reload = () => {
    setConflict(false);
    setFormError(null);
    onReload();
  };

  const saveStatus = dirty
    ? 'Unsaved changes. They are kept in this browser until you save.'
    : savedAt ? `Saved at ${formatClinicTime(savedAt)}.` : 'All changes saved.';

  return (
    <View style={styles.form}>
      {restoredNotice ? (
        <Banner
          tone="info"
          text="Notes you had not saved were restored from this browser. Save draft to keep them."
          action={{ label: 'Discard changes', onPress: discardRestored }}
          onDismiss={() => setRestoredNotice(false)}
        />
      ) : null}
      {conflict ? (
        <Banner
          tone="warning"
          text="The server refused this change. The consultation may have been completed or cancelled elsewhere. Reload to see its current state; text you have not saved stays here until you do."
          action={{ label: 'Reload', onPress: reload }}
        />
      ) : null}

      <SectionCard title="Assessment">
        <TextField
          control={control} name="chiefComplaint" label={CONSULTATION_FIELD_LABELS.chiefComplaint}
          hint="Required to complete the consultation." multiline rows={3}
          maxLength={CONSULTATION_TEXT_MAX} showCount
        />
        <TextField
          control={control} name="historyOfPresentIllness" label={CONSULTATION_FIELD_LABELS.historyOfPresentIllness}
          multiline rows={5} maxLength={CONSULTATION_TEXT_MAX} showCount
        />
        <TextField
          control={control} name="physicalExamination" label={CONSULTATION_FIELD_LABELS.physicalExamination}
          multiline rows={5} maxLength={CONSULTATION_TEXT_MAX} showCount
        />
      </SectionCard>

      <SectionCard title="Diagnosis and plan">
        <TextField
          control={control} name="diagnosis" label={CONSULTATION_FIELD_LABELS.diagnosis}
          hint="Required to complete the consultation." multiline rows={3}
          maxLength={CONSULTATION_TEXT_MAX} showCount
        />
        <TextField
          control={control} name="treatmentPlan" label={CONSULTATION_FIELD_LABELS.treatmentPlan}
          multiline rows={5} maxLength={CONSULTATION_TEXT_MAX} showCount
        />
      </SectionCard>

      <SectionCard title="Follow-up">
        <SwitchField
          control={control} name="followUpRequired" label={CONSULTATION_FIELD_LABELS.followUpRequired}
          description="Turn on when the patient should come back, and choose the date."
        />
        {values.followUpRequired ? (
          <DateField
            control={control} name="followUpDate" label={CONSULTATION_FIELD_LABELS.followUpDate}
            required min={clinicToday()} hint="Today or later (clinic date)."
          />
        ) : null}
        <TextField
          control={control} name="followUpInstructions" label={CONSULTATION_FIELD_LABELS.followUpInstructions}
          multiline rows={4} maxLength={CONSULTATION_TEXT_MAX} showCount
        />
      </SectionCard>

      <SectionCard title="Notes and duration">
        <TextField
          control={control} name="clinicalNotes" label={CONSULTATION_FIELD_LABELS.clinicalNotes}
          multiline rows={5} maxLength={CONSULTATION_TEXT_MAX} showCount
        />
        <TextField
          control={control} name="durationMinutes" label={CONSULTATION_FIELD_LABELS.durationMinutes}
          hint={`How long the consultation took, ${CONSULTATION_DURATION_MIN} to ${CONSULTATION_DURATION_MAX} minutes. Optional.`}
          inputMode="numeric" maxLength={3}
        />
      </SectionCard>

      <Card style={styles.footer}>
        <FormError message={formError} />
        <Text style={styles.saveStatus} role="status" aria-live="polite">{saveStatus}</Text>
        <View style={styles.actions}>
          <Button
            label="Cancel consultation"
            variant="ghost"
            onPress={() => { setFormError(null); setConfirm('cancel'); }}
            disabled={busy}
          />
          <View style={styles.primaryActions}>
            <Button
              label="Save draft"
              icon="save-outline"
              variant="secondary"
              onPress={() => { void onSave(); }}
              loading={save.pending && confirm === null}
              // Nothing to save while the form shows what is stored.
              disabled={!dirty || (busy && !(save.pending && confirm === null))}
            />
            {consultation.status === 'DRAFT' ? (
              <Button
                label="Start consultation"
                icon="play-outline"
                variant="secondary"
                onPress={() => { void onStart(); }}
                loading={start.pending}
                disabled={busy && !start.pending}
              />
            ) : null}
            <Button
              label="Complete consultation"
              icon="checkmark-circle-outline"
              onPress={() => { void askComplete(); }}
              disabled={busy}
            />
          </View>
        </View>
      </Card>

      <Modal
        open={confirm === 'complete'}
        title="Complete this consultation?"
        onClose={() => setConfirm(null)}
        size="sm"
        dismissOnBackdrop={false}
        busy={busy}
        footer={(
          <FormActions
            submitLabel="Complete consultation"
            onSubmit={() => { void onComplete(); }}
            onCancel={() => setConfirm(null)}
            cancelLabel="Keep editing"
            submitting={busy}
          />
        )}
      >
        <Text style={styles.dialogText}>
          {dirty ? 'Your notes are saved and the consultation is closed.' : 'The consultation is closed.'}
          {' '}After that the notes can no longer be changed. The appointment is marked as completed and a medical
          record of the visit is added to the patient&apos;s records.
        </Text>
      </Modal>

      <Modal
        open={confirm === 'cancel'}
        title="Cancel this consultation?"
        onClose={() => setConfirm(null)}
        size="sm"
        dismissOnBackdrop={false}
        busy={busy}
        footer={(
          <FormActions
            submitLabel="Cancel consultation"
            onSubmit={() => { void onCancelConsultation(); }}
            onCancel={() => setConfirm(null)}
            cancelLabel="Keep consultation"
            submitting={busy}
            destructive
          />
        )}
      >
        <Text style={styles.dialogText}>
          The saved notes are kept for reference but can no longer be changed{dirty ? ', and text you have not saved is discarded' : ''}.
          {' '}If the appointment is still active, you can open a new consultation for it afterwards.
        </Text>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  form:           { gap: space.lg, minWidth: 0 },
  footer:         { gap: space.md },
  saveStatus:     { fontSize: fontSize.sm, color: colors.textMuted },
  actions:        { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  primaryActions: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, marginLeft: 'auto' },
  dialogText:     { fontSize: fontSize.md, color: colors.text, lineHeight: 21 },
});
