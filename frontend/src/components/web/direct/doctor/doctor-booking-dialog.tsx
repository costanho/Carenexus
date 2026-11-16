import { useEffect, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { BookingForm } from '@/components/web/appointments';
import { PatientPicker } from '@/components/web/pickers';
import { colors, ErrorView, fontSize, LoadingView, Modal, ROLE_THEME, space } from '@/components/web/ui';
import { useCurrentUser } from '@/hooks/use-current-user';
import type { AppointmentDto } from '@/lib/appointments/types';

// "Book appointment" for a doctor: the shared booking form with the signed-in doctor fixed as the doctor, so the
// appointment goes into the doctor's own schedule (POST /api/appointments; the server allows a doctor to book a
// patient they are related to). Used from the patient detail panel, the consultation editor (Book follow-up) and
// the Appointments section (`?section=appointments&mode=book&patient=<PAT id>&date=<yyyy-MM-dd>`).

const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

export interface DoctorBookingDialogProps {
  open: boolean;
  /** The patient the appointment is for. Leave empty to let the doctor choose one of their patients first. */
  patientSystemId?: string | null;
  /** Shown in the form's sentences ("The doctor will call <name>"). */
  patientName?: string | null;
  /** Clinic day ("yyyy-MM-dd") the calendar starts on, for example a consultation's follow-up date. */
  initialDate?: string | null;
  /** Dialog title. Default "Book appointment". */
  title?: string;
  onClose: () => void;
  /** Called with the appointment the server created; the caller refreshes its own lists. */
  onBooked: (appointment: AppointmentDto, patientName: string | null) => void;
}

/** The booking form in a dialog. Mounted fresh on every open, so each opening has its own Idempotency-Key. */
export function DoctorBookingDialog({ open, ...rest }: DoctorBookingDialogProps) {
  return open ? <OpenDialog {...rest} /> : null;
}

function OpenDialog({
  patientSystemId, patientName, initialDate, title = 'Book appointment', onClose, onBooked,
}: Omit<DoctorBookingDialogProps, 'open'>) {
  const { user, loading, error, refetch } = useCurrentUser();
  const fixedPatient = (patientSystemId ?? '').trim() || null;
  const [picked, setPicked] = useState<{ id: string; name: string } | null>(null);
  useEffect(() => { setPicked(null); }, [fixedPatient]);

  const patient = fixedPatient ?? picked?.id ?? null;
  const name = fixedPatient ? (patientName ?? null) : picked?.name ?? null;
  const date = initialDate && DATE_ONLY.test(initialDate) ? initialDate : null;

  let body;
  if (loading && !user) {
    body = <LoadingView label="Loading your account…" compact />;
  } else if (!user) {
    body = <ErrorView message={error ?? 'Your account could not be loaded.'} onRetry={refetch} compact />;
  } else {
    body = (
      <View style={styles.body}>
        {fixedPatient ? null : (
          <PatientPicker
            value={picked?.id ?? null}
            onChange={(id, item) => setPicked(id && item ? { id, name: item.full_name } : null)}
            required
            placeholder="Choose a patient"
            hint="The appointment is booked into your own schedule."
          />
        )}
        {patient ? (
          <BookingForm
            // A different patient starts a new form, with its own idempotency key and no leftover choices.
            key={patient}
            patientSystemId={patient}
            patientName={name ?? patient}
            fixedDoctorSystemId={user.system_id}
            initialDate={date}
            theme={ROLE_THEME.doctor}
            onBooked={appointment => onBooked(appointment, name)}
          />
        ) : (
          <Text style={styles.note}>Choose the patient to see your free times.</Text>
        )}
      </View>
    );
  }

  return (
    <Modal open title={title} onClose={() => onClose()} size="lg" dismissOnBackdrop={false}>
      {body}
    </Modal>
  );
}

const styles = StyleSheet.create({
  body: { gap: space.lg, minWidth: 0 },
  note: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
});
