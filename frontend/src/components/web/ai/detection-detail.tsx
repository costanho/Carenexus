import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Banner, Button, colors, fontSize, FormError, InfoRow, radius, space, StatusBadge, TextField,
} from '@/components/web/ui';
import { useReloadDetection, useReviewDetection } from '@/hooks/use-disease-detections';
import {
  confidencePercent, detectionTrigger, reviewStatusLabel, reviewStatusTone, riskLevelLabel, riskLevelTone,
} from '@/lib/ai/labels';
import type { DetectedCondition, DetectionDecision, DiseaseDetection } from '@/lib/ai/types';
import { REVIEW_NOTES_MAX, type ReviewDetectionForm, reviewDetectionSchema } from '@/lib/ai/validation';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { formatClinicDateTime } from '@/lib/time/clinic';
import { confirmAction } from '@/lib/ui/dialogs';
import { type AlertViewer, useClinicianName } from './clinician-name';

export interface DetectionDetailProps {
  detection: DiseaseDetection;
  /** 'clinician' shows the patient, recommendations, review notes and (when allowed) Confirm and Reject. */
  viewer:    AlertViewer;
  /**
   * Called with the detection as the server now has it: after this doctor's review (`reviewedNow` true), or
   * after it turned out that someone else had already reviewed it (`reviewedNow` false).
   */
  onChanged?: (detection: DiseaseDetection, reviewedNow: boolean) => void;
  /** Reports whether a review request is in flight, so a dialog around the detail can refuse to close. */
  onPendingChange?: (pending: boolean) => void;
  /** Heading level of the "Findings" and "Review" headings. Default 3 (inside a dialog or a card with a level-2 title). */
  headingLevel?: 3 | 4;
}

function ConditionBlock({ condition, primary }: { condition: DetectedCondition; primary: boolean }) {
  const confidence = confidencePercent(condition.confidence);
  return (
    <View style={styles.condition} role="listitem">
      <View style={styles.conditionHead}>
        <Text style={styles.conditionName}>{condition.name}</Text>
        <View style={styles.badges}>
          <StatusBadge label={riskLevelLabel(condition.riskLevel)} tone={riskLevelTone(condition.riskLevel)} />
          {primary ? <StatusBadge label="Primary finding" tone="neutral" icon={null} /> : null}
        </View>
      </View>
      {confidence ? <Text style={styles.muted}>Screening confidence {confidence}</Text> : null}
      {condition.evidence?.length ? (
        <View style={styles.evidence} role="list" aria-label={`What matched for ${condition.name}`}>
          {condition.evidence.map((line, index) => (
            <View key={`${index}-${line}`} style={styles.bulletRow} role="listitem">
              <Text style={styles.bullet} aria-hidden>{'•'}</Text>
              <Text style={styles.bulletText}>{line}</Text>
            </View>
          ))}
        </View>
      ) : null}
    </View>
  );
}

interface ReviewFormProps {
  detection: DiseaseDetection;
  onChanged?: (detection: DiseaseDetection, reviewedNow: boolean) => void;
  onPendingChange?: (pending: boolean) => void;
  /** The server said the detection was already reviewed (409); the message is shown by the detail. */
  onStale: (message: string) => void;
}

/** Optional notes with Confirm and Reject. Mounted only while the signed-in doctor may review the detection. */
function ReviewForm({ detection, onChanged, onPendingChange, onStale }: ReviewFormProps) {
  const review = useReviewDetection();
  const reload = useReloadDetection();
  const [decision, setDecision] = useState<DetectionDecision | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  const { control, handleSubmit, setError, reset } = useForm<ReviewDetectionForm>({
    resolver:      zodResolver(reviewDetectionSchema),
    defaultValues: { notes: '' },
    mode:          'onChange',
  });

  // Another detection in the same mounted panel starts with a clean form.
  useEffect(() => {
    reset({ notes: '' });
    setFormError(null);
  }, [detection.detectionId, reset]);

  const pending = review.pending || reload.pending;
  const pendingChange = useRef(onPendingChange);
  pendingChange.current = onPendingChange;
  useEffect(() => { pendingChange.current?.(pending); }, [pending]);
  // The form goes away the moment the review lands; the dialog around it must not stay locked.
  useEffect(() => () => { pendingChange.current?.(false); }, []);

  const loadCurrent = async () => {
    const fresh = await reload.run(detection.detectionId);
    if (fresh.ok) onChanged?.(fresh.data, false);
  };

  const submit = (choice: DetectionDecision) => handleSubmit(async values => {
    const confirmed = await confirmAction(
      choice === 'CONFIRM' ? 'Confirm this screening result?' : 'Reject this screening result?',
      choice === 'CONFIRM'
        ? `${detection.patientName} and the caregivers allowed to see their records will be able to see this result. A review cannot be undone.`
        : `The result stays hidden from ${detection.patientName} and their caregivers. A review cannot be undone.`,
      choice === 'CONFIRM' ? 'Confirm' : 'Reject',
    );
    if (!confirmed) return;

    setFormError(null);
    setDecision(choice);
    const result = await review.run(detection.detectionId, choice, values.notes);
    setDecision(null);
    if (result.ok) {
      onChanged?.(result.data, true);
      return;
    }
    if (!result.error) return; // a double submit that was ignored

    if (result.error.status === 409) {
      // Someone reviewed it first: say so and show who, from the detection as it is now.
      onStale(result.error.message);
      await loadCurrent();
      return;
    }
    setFormError(applyFieldErrors(result.error, setError, ['notes']));
  })();

  return (
    <View style={styles.form}>
      <Text style={styles.muted}>
        Confirming makes this result visible to the patient and the caregivers allowed to see their records.
        Rejecting keeps it hidden from them. Either decision is final.
      </Text>
      {reload.error ? (
        <Banner tone="danger" text={reload.error} action={{ label: 'Retry', onPress: () => { void loadCurrent(); } }} />
      ) : null}
      <TextField
        label="Review notes"
        control={control}
        name="notes"
        multiline
        rows={4}
        maxLength={REVIEW_NOTES_MAX}
        showCount
        hint="Optional. Only doctors and administrators can read these notes."
        disabled={pending}
      />
      <FormError message={formError} />
      <View style={styles.actions}>
        <Button
          label="Reject"
          icon="close-outline"
          variant="secondary"
          onPress={() => { void submit('REJECT'); }}
          loading={decision === 'REJECT'}
          disabled={pending && decision !== 'REJECT'}
          accessibilityLabel={`Reject screening result for ${detection.patientName}`}
        />
        <Button
          label="Confirm"
          icon="checkmark-outline"
          onPress={() => { void submit('CONFIRM'); }}
          loading={decision === 'CONFIRM'}
          disabled={pending && decision !== 'CONFIRM'}
          accessibilityLabel={`Confirm screening result for ${detection.patientName}`}
        />
      </View>
    </View>
  );
}

/**
 * The content of one screening result: risk level, what triggered it, the findings with their evidence and
 * confidence, the suggested next steps (clinicians) and the review. A doctor who may review it gets an optional
 * notes field with Confirm and Reject. It does not render the "not a diagnosis" label: the view around it does.
 */
export function DetectionDetail({ detection, viewer, onChanged, onPendingChange, headingLevel = 3 }: DetectionDetailProps) {
  const clinician = viewer === 'clinician';
  const requestedBy = useClinicianName(detection.requestedBySystemId, viewer);
  const reviewedBy = useClinicianName(detection.reviewedBySystemId, viewer);
  /** Shown after a 409: the detection was reviewed by someone else while this view was open. */
  const [staleNotice, setStaleNotice] = useState<{ id: number; message: string } | null>(null);
  const stale = staleNotice && staleNotice.id === detection.detectionId ? staleNotice.message : null;

  const created = formatClinicDateTime(detection.createdAt);
  const reviewedAt = formatClinicDateTime(detection.reviewedAt);
  const confidence = confidencePercent(detection.confidenceScore);
  const trigger = detection.sourceType === null && detection.requestedBySystemId && requestedBy
    ? `Run by ${requestedBy}`
    : detectionTrigger(detection);
  const conditions = detection.detectedConditions ?? [];
  const recommendations = clinician ? (detection.recommendations ?? []).filter(line => line.trim()) : [];
  const reviewed = detection.reviewStatus === 'CONFIRMED' || detection.reviewStatus === 'REJECTED';
  const notes = clinician ? detection.reviewNotes?.trim() : '';

  return (
    <View style={styles.wrap}>
      <View style={styles.badges}>
        <StatusBadge label={riskLevelLabel(detection.riskLevel)} tone={riskLevelTone(detection.riskLevel)} />
        <StatusBadge label={reviewStatusLabel(detection.reviewStatus)} tone={reviewStatusTone(detection.reviewStatus)} />
      </View>

      <View>
        {clinician ? <InfoRow label="Patient" value={detection.patientName} /> : null}
        <InfoRow label="Screened" value={created} emptyText="Not recorded" />
        <InfoRow label="How it started" value={trigger} />
        {confidence ? <InfoRow label="Screening confidence" value={confidence} /> : null}
        {clinician ? <InfoRow label="Rule set" value={detection.modelVersion} emptyText="Not recorded" /> : null}
      </View>

      <View style={styles.block}>
        <Text style={styles.heading} accessibilityRole="header" {...ariaLevel(headingLevel)}>Findings</Text>
        {conditions.length > 0 ? (
          <View style={styles.conditions} role="list" aria-label="Findings">
            {conditions.map((condition, index) => (
              <ConditionBlock key={`${condition.code}-${index}`} condition={condition} primary={index === 0 && conditions.length > 1} />
            ))}
          </View>
        ) : (
          <Text style={styles.muted}>No findings were recorded with this result.</Text>
        )}
      </View>

      {recommendations.length > 0 ? (
        <View style={styles.block}>
          <Text style={styles.heading} accessibilityRole="header" {...ariaLevel(headingLevel)}>Suggested next steps</Text>
          <View role="list" aria-label="Suggested next steps" style={styles.evidence}>
            {recommendations.map((line, index) => (
              <View key={`${index}-${line}`} style={styles.bulletRow} role="listitem">
                <Text style={styles.bullet} aria-hidden>{'•'}</Text>
                <Text style={styles.bulletText}>{line}</Text>
              </View>
            ))}
          </View>
        </View>
      ) : null}

      <View style={styles.block}>
        <Text style={styles.heading} accessibilityRole="header" {...ariaLevel(headingLevel)}>
          {clinician ? 'Review' : "Doctor's review"}
        </Text>
        {stale ? <Banner tone="warning" text={stale} onDismiss={() => setStaleNotice(null)} /> : null}

        {reviewed ? (
          <View style={styles.reviewed}>
            <Text style={styles.body}>
              {detection.reviewStatus === 'CONFIRMED' ? 'Confirmed' : 'Rejected'}
              {reviewedBy ? ` by ${reviewedBy}` : ''}
              {reviewedAt ? ` on ${reviewedAt}` : ''}.
            </Text>
            {notes ? (
              <View style={styles.notes}>
                <Text style={styles.notesLabel}>Review notes</Text>
                <Text style={styles.body}>{notes}</Text>
              </View>
            ) : null}
          </View>
        ) : detection.canReview ? (
          <ReviewForm
            detection={detection}
            onChanged={onChanged}
            onPendingChange={onPendingChange}
            onStale={message => setStaleNotice({ id: detection.detectionId, message })}
          />
        ) : (
          <Text style={styles.muted}>Waiting for a doctor on the patient's care team to confirm or reject this result.</Text>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap:    { gap: space.lg, minWidth: 0 },
  badges:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.xs },
  block:   { gap: space.sm, minWidth: 0 },
  heading: { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  body:    { fontSize: fontSize.md, color: colors.text, lineHeight: 21 },
  muted:   { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },

  conditions:    { gap: space.sm },
  condition:     { gap: space.xs, padding: space.md, borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, minWidth: 0 },
  conditionHead: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  conditionName: { flexGrow: 1, flexShrink: 1, flexBasis: 160, minWidth: 0, fontSize: fontSize.md, fontWeight: '700', color: colors.text },

  evidence:   { gap: 2 },
  bulletRow:  { flexDirection: 'row', alignItems: 'flex-start', gap: space.sm },
  bullet:     { fontSize: fontSize.md, color: colors.textMuted, lineHeight: 20 },
  bulletText: { flex: 1, minWidth: 0, fontSize: fontSize.md, color: colors.text, lineHeight: 20 },

  reviewed:   { gap: space.sm },
  notes:      { gap: 2, padding: space.md, borderRadius: radius.md, backgroundColor: colors.bgSubtle },
  notesLabel: { fontSize: fontSize.xs, fontWeight: '700', color: colors.textMuted },
  form:       { gap: space.md },
  actions:    { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'flex-end', alignItems: 'center', gap: space.sm },
});
