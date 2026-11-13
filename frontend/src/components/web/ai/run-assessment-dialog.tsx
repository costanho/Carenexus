import { useEffect, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { PatientPicker } from '@/components/web/pickers';
import { Banner, type BannerTone, Button, colors, fontSize, InfoRow, Modal, space } from '@/components/web/ui';
import { refreshUnacknowledgedAlertCount } from '@/hooks/use-ai-alerts';
import { useRunDetection } from '@/hooks/use-disease-detections';
import { DETECTION_DISCLAIMER } from '@/lib/ai/labels';
import type { DetectionRunOutcome, DetectionRunResult, DiseaseDetection } from '@/lib/ai/types';
import { formatClinicDateTime } from '@/lib/time/clinic';
import { DetectionDetail } from './detection-detail';
import { ScreeningNotice } from './screening-notice';

export interface RunAssessmentDialogProps {
  open:    boolean;
  onClose: () => void;
  /** The patient selected when the dialog opens (the inbox's patient filter, or the patient of a detail view). */
  patientSystemId?: string | null;
  /**
   * That patient's name. With `lockPatient` it is shown instead of the picker. Without it, a patient whose name
   * is not known is not preselected: the dialog always says in words who will be screened.
   */
  patientName?:     string | null;
  /** The dialog is about one patient (opened from that patient's panel): no picker. */
  lockPatient?:     boolean;
  /** A detection was stored by the run: the alert lists behind the dialog have a new row. */
  onDetectionCreated?:  (detection: DiseaseDetection) => void;
  /** This doctor confirmed or rejected the detection shown in the dialog. */
  onDetectionReviewed?: (detection: DiseaseDetection) => void;
}

// "Nothing found" and "nothing to screen" are normal results, not errors.
const OUTCOME_TONE: Record<DetectionRunOutcome, BannerTone> = {
  DETECTION_CREATED: 'warning',
  DETECTION_EXISTS:  'info',
  NO_FINDING:        'success',
  NO_DATA:           'info',
};

/**
 * Runs the rule-based screening for a chosen patient now and shows what it found: the server's sentence for
 * the outcome and, when a detection was stored or already existed, the detection with Confirm and Reject.
 */
export function RunAssessmentDialog({
  open, onClose, patientSystemId, patientName, lockPatient = false, onDetectionCreated, onDetectionReviewed,
}: RunAssessmentDialogProps) {
  const run = useRunDetection();
  const { reset } = run;
  const givenName = patientName?.trim() || null;
  // Without a name to show, the choice is left to the picker.
  const initialPatient = patientSystemId && (lockPatient || givenName) ? patientSystemId : null;
  const [patient, setPatient] = useState<string | null>(initialPatient);
  const [pickedName, setPickedName] = useState<string | null>(null);
  const [result, setResult] = useState<DetectionRunResult | null>(null);
  const [reviewing, setReviewing] = useState(false);

  // Every opening starts from the patient the caller is looking at, with no earlier result.
  useEffect(() => {
    if (!open) return;
    setPatient(initialPatient);
    setPickedName(null);
    setResult(null);
    setReviewing(false);
    reset();
    // The name only decides the starting selection; it may arrive or change while the dialog is open.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, patientSystemId, reset]);

  const start = async () => {
    if (!patient) return;
    setResult(null);
    const outcome = await run.run(patient);
    if (!outcome.ok) return;
    setResult(outcome.data);
    if (outcome.data.outcome === 'DETECTION_CREATED' && outcome.data.detection) {
      // The run raised one alert for every doctor of the patient, this one included.
      void refreshUnacknowledgedAlertCount();
      onDetectionCreated?.(outcome.data.detection);
    }
  };

  const failure = run.failure;
  // A refusal (not on the care team) will not change on a second try; an unavailable provider or a lost
  // connection may.
  const retryable = !!failure && (failure.status === 0 || failure.status >= 500);
  const shownName = lockPatient
    ? givenName ?? 'This patient'
    : pickedName ?? (patient && patient === patientSystemId ? givenName : null);
  const assessedAt = formatClinicDateTime(result?.assessedAt);
  const outcome = result?.outcome;
  const tone: BannerTone = outcome && OUTCOME_TONE[outcome] ? OUTCOME_TONE[outcome] : 'info';

  return (
    <Modal
      open={open}
      title="Run assessment"
      onClose={onClose}
      size="lg"
      dismissOnBackdrop={false}
      busy={run.pending || reviewing}
    >
      <ScreeningNotice text={result?.disclaimer || DETECTION_DISCLAIMER} />
      <Text style={styles.intro}>
        The screening checks the patient's recent readings and symptom logs against fixed rules. A result that
        needs attention is stored for review and raises an alert for the patient's doctors.
      </Text>

      {lockPatient ? (
        <InfoRow label="Patient" value={shownName} />
      ) : (
        <PatientPicker
          value={patient}
          onChange={(id, item) => {
            setPatient(id);
            setPickedName(item?.full_name ?? null);
            setResult(null);
            reset();
          }}
          required
          disabled={run.pending || reviewing}
          placeholder="Select a patient"
          hint="Only patients on your care list can be screened."
        />
      )}

      <View style={styles.actions}>
        {!lockPatient && patient && shownName ? (
          <Text style={styles.target}>Screening will run for {shownName}.</Text>
        ) : null}
        <Button
          label={result ? 'Run again' : 'Run assessment'}
          icon="play-outline"
          onPress={() => { void start(); }}
          loading={run.pending}
          disabled={!patient || reviewing}
          accessibilityLabel={shownName ? `${result ? 'Run again' : 'Run assessment'} for ${shownName}` : undefined}
        />
      </View>

      {run.error ? (
        <Banner
          tone="danger"
          text={run.error}
          action={retryable ? { label: 'Try again', onPress: () => { void start(); } } : undefined}
        />
      ) : null}

      {result ? (
        <View style={styles.result}>
          <Banner tone={tone} text={result.message} />
          <Text style={styles.meta}>
            {assessedAt ? `Assessed ${assessedAt}` : 'Assessed just now'}
            {result.modelVersion ? ` with rule set ${result.modelVersion}` : ''}
          </Text>
          {result.detection ? (
            <DetectionDetail
              detection={result.detection}
              viewer="clinician"
              onPendingChange={setReviewing}
              onChanged={(next, reviewedNow) => {
                setResult(prev => (prev ? { ...prev, detection: next } : prev));
                if (reviewedNow) onDetectionReviewed?.(next);
              }}
            />
          ) : null}
        </View>
      ) : null}
    </Modal>
  );
}

const styles = StyleSheet.create({
  intro:   { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  actions: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'flex-end', gap: space.md },
  target:  { flexGrow: 1, flexShrink: 1, flexBasis: 160, minWidth: 0, fontSize: fontSize.sm, color: colors.text },
  result:  { gap: space.md, minWidth: 0 },
  meta:    { fontSize: fontSize.sm, color: colors.textMuted },
});
