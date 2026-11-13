import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Banner, Button, colors, fontSize, InfoRow, type IoniconsName, SectionCard, space,
} from '@/components/web/ui';
import type { ConsultationDto } from '@/hooks/use-doctor-consultations';
import type { SectionParamsInput } from '@/lib/nav/sections';
import { CONSULTATION_FIELD_LABELS, type ConsultationTextField } from '@/lib/validation/consultation';
import { DoctorBookingDialog } from '../doctor-booking-dialog';
import { clinicDateTime, followUpDateText } from './consultation-format';
import { ConsultationInvoiceCard } from './consultation-invoice-card';

/** The part of the dashboard navigation the consultation screens use. */
export interface ConsultationNav {
  navigate:   (section: string, params?: SectionParamsInput) => void;
  /** True when the dashboard has this section; links to sections that are not there are left out. */
  hasSection: (section: string) => boolean;
}

export interface ConsultationViewProps {
  consultation: ConsultationDto;
  patientName:  string;
  /** The signed-in doctor wrote this consultation. */
  isAuthor:     boolean;
  nav:          ConsultationNav;
  /** Loads the consultation again (used when the linked medical record is not there yet). */
  onRefresh:    () => void;
  refreshing:   boolean;
  /**
   * Opens the appointment again to document it (a cancelled consultation does not block a new one). Left out
   * when the appointment cannot take a consultation.
   */
  onDocumentAgain?: () => void;
}

interface NextStep {
  key:     string;
  label:   string;
  icon:    IoniconsName;
  onPress: () => void;
}

interface LinkStep {
  section: string;
  label:   string;
  icon:    IoniconsName;
  params:  SectionParamsInput;
}

function NoteBlock({ field, value }: { field: ConsultationTextField; value: string | null }) {
  return (
    <View style={styles.note}>
      <Text style={styles.noteLabel} accessibilityRole="header" {...ariaLevel(3)}>{CONSULTATION_FIELD_LABELS[field]}</Text>
      <Text style={[styles.noteText, !value && styles.noteEmpty]}>{value ?? 'Not recorded'}</Text>
    </View>
  );
}

/** A consultation that can no longer be edited here: completed, cancelled, or written by another doctor. */
export function ConsultationView({
  consultation: c, patientName, isAuthor, nav, onRefresh, refreshing, onDocumentAgain,
}: ConsultationViewProps) {
  const completed = c.status === 'COMPLETED';
  const cancelled = c.status === 'CANCELLED';

  const [booking, setBooking] = useState(false);
  const [booked, setBooked] = useState<string | null>(null);

  // What usually follows a completed visit. Prescribe, Refer and Write care plan open their section with the
  // consultation and patient in the URL (each section opens its form prefilled from those params); a section the
  // dashboard does not have is not offered. Book follow-up opens the booking form here, so the doctor stays on
  // the consultation.
  const linkSteps: LinkStep[] = completed && isAuthor
    ? ([
        { section: 'prescriptions', label: 'Prescribe', icon: 'medkit-outline', params: { consultationId: c.consultationId, patient: c.patientSystemId } },
        { section: 'referrals', label: 'Refer', icon: 'arrow-redo-outline', params: { mode: 'new', patient: c.patientSystemId, consultationId: c.consultationId } },
        { section: 'care-plans', label: 'Write care plan', icon: 'clipboard-outline', params: { patient: c.patientSystemId, consultationId: c.consultationId } },
      ] satisfies LinkStep[]).filter(step => nav.hasSection(step.section))
    : [];
  const nextSteps: NextStep[] = completed && isAuthor
    ? [
        ...linkSteps.map(step => ({
          key: step.section, label: step.label, icon: step.icon, onPress: () => nav.navigate(step.section, step.params),
        })),
        {
          key: 'follow-up',
          label: 'Book follow-up',
          icon: 'calendar-outline' as IoniconsName,
          onPress: () => { setBooked(null); setBooking(true); },
        },
      ]
    : [];

  return (
    <View style={styles.view}>
      {completed ? (
        <Banner tone="success" text={`This consultation was completed on ${clinicDateTime(c.completedAt, 'an unrecorded date')}. The notes can no longer be changed.`} />
      ) : cancelled ? (
        <Banner
          tone="info"
          text="This consultation was cancelled. The notes are kept for reference and can no longer be changed."
          action={onDocumentAgain ? { label: 'Document appointment again', onPress: onDocumentAgain } : undefined}
        />
      ) : (
        <Banner tone="info" text="Only the doctor who created this consultation can change it." />
      )}

      {completed ? (
        <SectionCard title="Medical record">
          {c.medicalRecordId !== null ? (
            <>
              <Text style={styles.body}>A medical record of this visit is in the patient&apos;s records.</Text>
              {nav.hasSection('medical-records') ? (
                <Button
                  label="View medical record"
                  icon="folder-open-outline"
                  variant="secondary"
                  onPress={() => nav.navigate('medical-records', { id: c.medicalRecordId ?? undefined })}
                  accessibilityLabel={`View medical record of ${patientName}`}
                />
              ) : null}
            </>
          ) : (
            <>
              <Text style={styles.body}>No medical record is linked to this consultation.</Text>
              <Button label="Check again" icon="refresh-outline" variant="secondary" size="sm" onPress={onRefresh} loading={refreshing} />
            </>
          )}
        </SectionCard>
      ) : null}

      {nextSteps.length > 0 ? (
        <SectionCard title="Next steps" subtitle={`Continue the care of ${patientName} from this consultation.`}>
          {booked ? (
            <Banner
              tone="success"
              text={booked}
              action={nav.hasSection('appointments')
                ? { label: 'View appointments', onPress: () => nav.navigate('appointments', { patient: c.patientSystemId }) }
                : undefined}
              onDismiss={() => setBooked(null)}
            />
          ) : null}
          {c.followUpRequired && c.followUpDate ? (
            <Text style={styles.body}>{`Follow-up planned for ${followUpDateText(c.followUpDate)}.`}</Text>
          ) : null}
          <View style={styles.steps}>
            {nextSteps.map(step => (
              <Button
                key={step.key}
                label={step.label}
                icon={step.icon}
                variant="secondary"
                onPress={step.onPress}
                accessibilityLabel={`${step.label} for ${patientName}`}
              />
            ))}
          </View>
        </SectionCard>
      ) : null}

      {completed && isAuthor && nav.hasSection('billing') ? (
        <ConsultationInvoiceCard
          consultationId={c.consultationId}
          appointmentId={c.appointmentId}
          patientName={patientName}
          nav={nav}
        />
      ) : null}

      <DoctorBookingDialog
        open={booking}
        title="Book follow-up"
        patientSystemId={c.patientSystemId}
        patientName={patientName}
        initialDate={c.followUpRequired ? c.followUpDate : null}
        onClose={() => setBooking(false)}
        onBooked={appointment => {
          setBooking(false);
          setBooked(`Follow-up booked for ${patientName} on ${clinicDateTime(appointment.scheduledAt)}.`);
        }}
      />

      <SectionCard title="Assessment">
        <NoteBlock field="chiefComplaint" value={c.chiefComplaint} />
        <NoteBlock field="historyOfPresentIllness" value={c.historyOfPresentIllness} />
        <NoteBlock field="physicalExamination" value={c.physicalExamination} />
      </SectionCard>

      <SectionCard title="Diagnosis and plan">
        <NoteBlock field="diagnosis" value={c.diagnosis} />
        <NoteBlock field="treatmentPlan" value={c.treatmentPlan} />
      </SectionCard>

      <SectionCard title="Follow-up">
        <View>
          <InfoRow label="Follow-up required" value={c.followUpRequired ? 'Yes' : 'No'} />
          {c.followUpRequired ? <InfoRow label="Follow-up date" value={followUpDateText(c.followUpDate)} /> : null}
        </View>
        <NoteBlock field="followUpInstructions" value={c.followUpInstructions} />
      </SectionCard>

      <SectionCard title="Notes and duration">
        <NoteBlock field="clinicalNotes" value={c.clinicalNotes} />
        <View>
          <InfoRow
            label="Duration"
            value={c.durationMinutes !== null ? `${c.durationMinutes} ${c.durationMinutes === 1 ? 'minute' : 'minutes'}` : null}
            emptyText="Not recorded"
          />
          <InfoRow label="Created" value={clinicDateTime(c.createdAt)} />
        </View>
      </SectionCard>
    </View>
  );
}

const styles = StyleSheet.create({
  view:      { gap: space.lg, minWidth: 0 },
  body:      { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  steps:     { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  note:      { gap: space.xs, minWidth: 0 },
  noteLabel: { fontSize: fontSize.sm, fontWeight: '700', color: colors.text },
  noteText:  { fontSize: fontSize.md, color: colors.text, lineHeight: 21 },
  noteEmpty: { color: colors.textSubtle },
});
