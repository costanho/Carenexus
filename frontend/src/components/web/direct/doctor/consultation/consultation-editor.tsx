import { useCallback, useEffect, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { VitalsTiles } from '@/components/web/health';
import {
  Banner, type BannerTone, Button, EmptyView, ErrorView, LoadingView, NoAccessView, SectionCard, space,
} from '@/components/web/ui';
import { useCurrentUser } from '@/hooks/use-current-user';
import {
  type ConsultationContext, type ConsultationDto, type ConsultationTarget, loadConsultationContext,
  patientNameFallback, useDoctorPatientOptions,
} from '@/hooks/use-doctor-consultations';
import { useLatestHealthMetrics } from '@/hooks/use-health-metrics';
import { useResource } from '@/hooks/use-resource';
import { clearDraft } from '@/lib/communications/drafts';
import type { SectionParamsInput } from '@/lib/nav/sections';
import { isAfterClinicNow } from '@/lib/time/clinic';
import { DoctorPatientPanel, PATIENT_OVERVIEW_TAB, PATIENT_TAB_KEYS } from '../patient-detail';
import { consultationDraftKey } from './consultation-format';
import { ConsultationForm } from './consultation-form';
import { ConsultationHeader } from './consultation-header';
import { ConsultationStart } from './consultation-start';
import { type ConsultationNav, ConsultationView } from './consultation-view';

export interface ConsultationEditorProps {
  /** The consultation (URL param `id`) or the appointment (`appointmentId`) the editor was opened on. */
  target:    ConsultationTarget;
  nav:       ConsultationNav;
  /** Rewrites this section's URL params in place (no history entry). */
  setParams: (params: SectionParamsInput) => void;
  /** Leaves the editor for the list. */
  onBack:    () => void;
  /**
   * The tab of the patient detail panel opened over the editor (URL param `panel`), or null while it is closed.
   * The panel shows the patient's history, vitals and care team before and during the consultation.
   */
  panel?:    string | null;
  /** Opens the panel on a tab, or closes it (null). */
  onPanelChange: (tab: string | null) => void;
}

interface Notice { tone: BannerTone; text: string }

/**
 * One consultation, opened from the list or from an appointment. Depending on what the server holds it shows:
 * the start step (the appointment has no consultation yet), the editable form (a draft or in-progress
 * consultation of the signed-in doctor) or the read-only view (completed, cancelled, or another doctor's).
 */
export function ConsultationEditor({ target, nav, setParams, onBack, panel = null, onPanelChange }: ConsultationEditorProps) {
  const { user, loading: userLoading, error: userError, refetch: refetchUser } = useCurrentUser();
  const doctorSystemId = user?.system_id ?? null;
  const byAppointment = target.appointmentId !== undefined;
  const targetKey = byAppointment ? `appointment:${target.appointmentId}` : `consultation:${target.consultationId}`;

  const context = useResource<ConsultationContext>(
    () => loadConsultationContext(target),
    [targetKey],
    { errorMessage: 'Failed to load the consultation.' },
  );
  const patients = useDoctorPatientOptions();
  const [notice, setNotice] = useState<Notice | null>(null);
  useEffect(() => { setNotice(null); }, [targetKey]);

  const { data, setData, refetch } = context;
  const consultation = data?.consultation ?? null;
  const appointment = data?.appointment ?? null;
  const terminal = consultation?.status === 'COMPLETED' || consultation?.status === 'CANCELLED';

  // Notes kept in this browser belong to an editable consultation; once it is closed they can never be saved.
  const closedId = terminal && consultation ? consultation.consultationId : null;
  useEffect(() => {
    if (doctorSystemId && closedId !== null) clearDraft(consultationDraftKey(doctorSystemId, closedId));
  }, [doctorSystemId, closedId]);

  const applyConsultation = useCallback((next: ConsultationDto) => {
    setData(prev => (prev ? { ...prev, consultation: next } : prev));
  }, [setData]);

  const onChange = useCallback((next: ConsultationDto) => {
    if (consultation?.status === 'DRAFT' && next.status === 'IN_PROGRESS') {
      setNotice({ tone: 'success', text: 'Consultation started.' });
    } else if (next.status === 'COMPLETED' || next.status === 'CANCELLED') {
      setNotice(null); // the read-only view says what happened
    }
    applyConsultation(next);
  }, [consultation?.status, applyConsultation]);

  const onCreated = useCallback((created: ConsultationDto) => {
    setNotice({
      tone: 'success',
      text: created.status === 'IN_PROGRESS' ? 'Consultation started.' : 'Draft consultation created.',
    });
    applyConsultation(created);
  }, [applyConsultation]);

  const onConflict = useCallback(async (): Promise<boolean> => {
    try {
      const fresh = await loadConsultationContext(target);
      setData(fresh);
      if (fresh.consultation) {
        setNotice({ tone: 'info', text: 'A consultation already exists for this appointment, so it was opened.' });
        return true;
      }
    } catch {
      // The reload failed: the start step shows the server's refusal instead.
    }
    return false;
    // `target` is identified by `targetKey`.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [targetKey, setData]);

  if (userLoading || (doctorSystemId && context.loading)) return <LoadingView label="Loading consultation…" />;
  if (!doctorSystemId) return <ErrorView message={userError ?? 'Failed to load your account.'} onRetry={refetchUser} />;

  if (!data) {
    if (context.errorStatus === 403) return <NoAccessView message="You don't have access to this consultation." />;
    if (context.errorStatus === 404) {
      return (
        <EmptyView
          icon="document-text-outline"
          title={byAppointment ? 'Appointment not found' : 'Consultation not found'}
          sub="It may have been removed, or the link is out of date."
          action={{ label: 'Back to consultations', onPress: onBack, icon: 'arrow-back' }}
        />
      );
    }
    return <ErrorView message={context.error ?? 'Failed to load the consultation.'} onRetry={refetch} />;
  }

  const patientSystemId = consultation?.patientSystemId || appointment?.patientSystemId || '';
  const patientName = patients.nameOf(patientSystemId) ?? appointment?.patientName ?? patientNameFallback(patientSystemId);
  // The patient's details open in a side panel over the editor, so the notes being written stay on screen.
  const onOpenPatient = patientSystemId ? () => onPanelChange(PATIENT_OVERVIEW_TAB) : undefined;
  const onOpenHistory = patientSystemId ? () => onPanelChange(PATIENT_TAB_KEYS.history) : undefined;

  const isAuthor = !!consultation && consultation.doctorSystemId === doctorSystemId;
  const editable = !!consultation && isAuthor && !terminal;

  // A cancelled consultation does not block a new one while the appointment can still be documented.
  const appointmentOpen = !!appointment
    && (appointment.doctorSystemId === null || appointment.doctorSystemId === doctorSystemId)
    && (appointment.appointmentStatus === 'SCHEDULED' || appointment.appointmentStatus === 'RESCHEDULED' || appointment.appointmentStatus === 'COMPLETED')
    && !!appointment.scheduledAt && !isAfterClinicNow(appointment.scheduledAt);
  const onDocumentAgain = consultation?.status === 'CANCELLED' && isAuthor && appointmentOpen
    ? () => {
        setNotice(null);
        // Already opened by appointment: reloading finds no active consultation and shows the start step.
        if (byAppointment) refetch();
        else setParams({ appointmentId: consultation.appointmentId, id: undefined, mode: 'edit' });
      }
    : undefined;

  return (
    <View style={styles.editor}>
      {context.error ? <Banner tone="danger" text={context.error} action={{ label: 'Retry', onPress: refetch }} /> : null}
      {notice ? <Banner tone={notice.tone} text={notice.text} onDismiss={() => setNotice(null)} /> : null}

      <ConsultationHeader
        patientName={patientName}
        patientSystemId={patientSystemId}
        consultation={consultation}
        appointment={appointment}
        onOpenPatient={onOpenPatient}
        onOpenHistory={onOpenHistory}
      />

      {patientSystemId ? (
        <LatestVitalsStrip
          patientSystemId={patientSystemId}
          patientName={patientName}
          onOpenVitals={() => onPanelChange(PATIENT_TAB_KEYS.vitals)}
        />
      ) : null}

      {!consultation ? (
        appointment ? (
          <ConsultationStart
            key={appointment.appointmentId}
            appointment={appointment}
            doctorSystemId={doctorSystemId}
            cancelled={data.cancelled}
            onCreated={onCreated}
            onConflict={onConflict}
            onRefresh={refetch}
            onOpenCancelled={id => setParams({ id, appointmentId: undefined, mode: 'edit' })}
          />
        ) : null
      ) : editable ? (
        <ConsultationForm
          key={consultation.consultationId}
          consultation={consultation}
          doctorSystemId={doctorSystemId}
          onChange={onChange}
          onReload={refetch}
        />
      ) : (
        <ConsultationView
          consultation={consultation}
          patientName={patientName}
          isAuthor={isAuthor}
          nav={nav}
          onRefresh={refetch}
          refreshing={context.refreshing}
          onDocumentAgain={onDocumentAgain}
        />
      )}

      <DoctorPatientPanel
        patientSystemId={panel !== null && patientSystemId ? patientSystemId : null}
        onClose={() => onPanelChange(null)}
        tab={panel}
        onTabChange={onPanelChange}
        fallbackTitle={patientName}
        // Leaving the care team from here: close the panel; the consultation itself stays readable to its author.
        onAccessLost={() => onPanelChange(null)}
      />
    </View>
  );
}

/** The patient's newest readings, compact, above the notes. "Vitals and symptoms" opens the full view in the panel. */
function LatestVitalsStrip({ patientSystemId, patientName, onOpenVitals }: {
  patientSystemId: string;
  patientName:     string;
  onOpenVitals:    () => void;
}) {
  const latest = useLatestHealthMetrics(patientSystemId);
  return (
    <SectionCard
      title="Latest vitals"
      action={(
        <Button
          label="Vitals and symptoms"
          icon="pulse-outline"
          variant="secondary"
          size="sm"
          onPress={onOpenVitals}
          accessibilityLabel={`Vitals and symptoms of ${patientName}`}
        />
      )}
    >
      <VitalsTiles
        state={latest}
        compact
        onRecord={onOpenVitals}
        emptySub={`No readings have been recorded for ${patientName}. You can record them in the patient's vitals.`}
      />
    </SectionCard>
  );
}

const styles = StyleSheet.create({
  editor: { gap: space.lg, minWidth: 0 },
});
