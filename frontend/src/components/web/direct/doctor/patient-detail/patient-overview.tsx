import type { ReactNode } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Button, colors, EmptyView, ErrorView, fontSize, Icon, InfoRow, type IoniconsName, LoadingView, NoAccessView, radius,
  space, STATUS_TONES, StatusBadge, statusLabel, statusTone, type StatusTone,
} from '@/components/web/ui';
import { type HealthStatusResult, usePatientEmergencyContacts } from '@/hooks/use-doctor-patient-detail';
import { type DoctorPatientAppointment, type DoctorPatientDto, toStringList } from '@/hooks/use-doctor-patients';
import { formatClinicDate, formatClinicDateTime } from '@/lib/time/clinic';
import {
  appointmentTypeIcon, appointmentTypeLabel, dateOfBirthText, genderLabel, lastVisitText, nextAppointmentText, plural,
} from './format';
import { HealthStatusControl } from './health-status-control';
import { PanelCard } from './panel-card';

/**
 * The built-in tab of the patient detail panel: health status, allergies and chronic conditions, the
 * appointments with this doctor, demographics and emergency contacts. It sits inside the patient side panel
 * (title at level 2), so each card title is level 3 and the headings inside a card level 4.
 */
export function PatientOverview({ patient, onHealthStatusUpdated, onViewAppointments }: {
  patient: DoctorPatientDto;
  onHealthStatusUpdated: (result: HealthStatusResult) => void;
  /** Opens the Appointments section filtered to this patient; the link is hidden when omitted. */
  onViewAppointments?: () => void;
}) {
  return (
    <View style={styles.wrap}>
      <PanelCard title="Health status" subtitle="Set by the care team. The patient and caregivers see the status, not the note.">
        <HealthStatusControl key={patient.patientSystemId} patient={patient} onUpdated={onHealthStatusUpdated} />
      </PanelCard>

      <PanelCard title="Allergies and chronic conditions">
        <ChipGroup
          label="Allergies"
          icon="warning-outline"
          tone="danger"
          items={toStringList(patient.allergies)}
          emptyText="No allergies recorded"
        />
        <ChipGroup
          label="Chronic conditions"
          icon="heart-outline"
          tone="warning"
          items={toStringList(patient.chronicConditions)}
          emptyText="No chronic conditions recorded"
        />
      </PanelCard>

      <AppointmentsCard patient={patient} onViewAll={onViewAppointments} />

      <PanelCard title="Demographics">
        <View>
          <InfoRow label="Patient ID" value={patient.patientSystemId} />
          <InfoRow label="Date of birth" value={dateOfBirthText(patient.dateOfBirth)} />
          <InfoRow label="Gender" value={genderLabel(patient.gender)} />
          <InfoRow label="Blood type" value={patient.bloodType} />
          <InfoRow label="Email" value={patient.email} />
          <InfoRow label="Phone" value={patient.phone} />
          <InfoRow label="Patient since" value={formatClinicDate(patient.patientCreatedAt) || null} />
        </View>
      </PanelCard>

      <EmergencyContactsCard patientSystemId={patient.patientSystemId} />
    </View>
  );
}

// ─── Allergies and conditions ─────────────────────────────────────────────────

function ChipGroup({ label, icon, tone, items, emptyText }: {
  label:     string;
  icon:      IoniconsName;
  tone:      StatusTone;
  items:     string[];
  emptyText: string;
}) {
  const c = STATUS_TONES[tone];
  return (
    <View style={styles.chipGroup}>
      <View style={styles.chipHeading}>
        <Icon name={icon} size={14} color={c.fg} />
        {/* A sub-heading of its level-3 card ("Allergies", "Chronic conditions"). */}
        <Text style={styles.subheading} accessibilityRole="header" {...ariaLevel(4)}>{label}</Text>
      </View>
      {items.length === 0 ? (
        <Text style={styles.muted}>{emptyText}</Text>
      ) : (
        <View style={styles.chips} role="list" aria-label={label}>
          {items.map((item, index) => (
            <View key={`${item}-${index}`} role="listitem" style={[styles.chip, { backgroundColor: c.bg, borderColor: c.border }]}>
              <Text style={[styles.chipText, { color: c.fg }]}>{item}</Text>
            </View>
          ))}
        </View>
      )}
    </View>
  );
}

// ─── Appointments with this doctor ────────────────────────────────────────────

function AppointmentsCard({ patient, onViewAll }: { patient: DoctorPatientDto; onViewAll?: () => void }) {
  const next   = nextAppointmentText(patient.nextAppointmentAt, patient.nextAppointmentType);
  const last   = lastVisitText(patient.lastVisitAt);
  const recent = patient.recentAppointments ?? [];
  return (
    <PanelCard
      title="Appointments with you"
      action={onViewAll ? (
        <Button label="View all" variant="ghost" size="sm" icon="open-outline" onPress={onViewAll} accessibilityLabel="View all appointments with this patient" />
      ) : undefined}
    >
      <View>
        <InfoRow label="Next appointment" value={next} emptyText="No upcoming appointment" />
        <InfoRow label="Last visit" value={last} emptyText="No completed visits yet" />
        <InfoRow label="Completed visits" value={patient.completedVisits ?? 0} />
        <InfoRow label="Upcoming" value={patient.upcomingAppointmentCount ?? 0} />
        <InfoRow label="Cancelled" value={patient.cancelledAppointmentCount ?? 0} />
        <InfoRow label="No-shows" value={patient.noShowCount ?? 0} />
      </View>

      <Text style={styles.subheading} accessibilityRole="header" {...ariaLevel(4)}>Recent appointments</Text>
      {recent.length === 0 ? (
        <EmptyView compact icon="calendar-outline" title="No appointments yet" sub="This patient has not had an appointment with you." />
      ) : (
        <View style={styles.rows} role="list" aria-label="Recent appointments">
          {recent.map(a => <AppointmentRow key={a.appointmentId} appointment={a} />)}
        </View>
      )}
    </PanelCard>
  );
}

function AppointmentRow({ appointment }: { appointment: DoctorPatientAppointment }) {
  const type = appointmentTypeLabel(appointment.type);
  const reason = (appointment.reasonForVisit ?? '').trim();
  return (
    <View style={styles.row} role="listitem">
      <View style={styles.rowMain}>
        <Text style={styles.rowTitle}>{formatClinicDateTime(appointment.scheduledAt, 'Time not set')}</Text>
        <View style={styles.rowMeta}>
          <Icon name={appointmentTypeIcon(appointment.type)} size={13} color={colors.textMuted} />
          <Text style={styles.muted}>
            {[type, appointment.durationMinutes ? `${appointment.durationMinutes} min` : null].filter(Boolean).join(' · ')}
          </Text>
        </View>
        {reason ? <Text style={styles.rowReason}>{reason}</Text> : null}
      </View>
      <StatusBadge label={statusLabel(appointment.status)} tone={statusTone('appointment', appointment.status)} />
    </View>
  );
}

// ─── Emergency contacts ───────────────────────────────────────────────────────

function EmergencyContactsCard({ patientSystemId }: { patientSystemId: string }) {
  const { contacts, loading, error, errorStatus, refetch } = usePatientEmergencyContacts(patientSystemId);

  let body: ReactNode;
  if (loading) {
    body = <LoadingView compact label="Loading emergency contacts…" />;
  } else if (error) {
    body = errorStatus === 403
      ? <NoAccessView compact message="You don't have access to this patient's emergency contacts." />
      : <ErrorView compact message={error} onRetry={refetch} />;
  } else if (contacts.length === 0) {
    body = (
      <EmptyView
        compact
        icon="people-outline"
        title="No emergency contacts"
        sub="The patient has not added an emergency contact."
      />
    );
  } else {
    body = (
      <View style={styles.rows} role="list" aria-label="Emergency contacts">
        {contacts.map(contact => (
          <View key={contact.contactId} style={styles.contact} role="listitem">
            <View style={styles.contactHead}>
              <Text style={styles.rowTitle}>{contact.name}</Text>
              {contact.isPrimary ? <StatusBadge label="Primary" tone="info" icon="star-outline" /> : null}
            </View>
            {contact.relationship ? <Text style={styles.muted}>{contact.relationship}</Text> : null}
            <View style={styles.rowMeta}>
              <Icon name="call-outline" size={13} color={colors.textMuted} />
              <Text style={styles.contactValue} selectable>{contact.phone}</Text>
            </View>
            {contact.email ? (
              <View style={styles.rowMeta}>
                <Icon name="mail-outline" size={13} color={colors.textMuted} />
                <Text style={styles.contactValue} selectable>{contact.email}</Text>
              </View>
            ) : null}
          </View>
        ))}
      </View>
    );
  }

  return (
    <PanelCard title="Emergency contacts" subtitle={contacts.length > 0 ? plural(contacts.length, 'contact') : undefined}>
      {body}
    </PanelCard>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: space.md, minWidth: 0 },

  subheading: { fontSize: fontSize.sm, fontWeight: '700', color: colors.text },
  muted:      { flexShrink: 1, minWidth: 0, fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },

  chipGroup:   { gap: space.xs },
  chipHeading: { flexDirection: 'row', alignItems: 'center', gap: space.xs },
  chips:       { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs },
  chip:        { maxWidth: '100%', paddingHorizontal: space.sm, paddingVertical: 3, borderWidth: 1, borderRadius: radius.pill },
  chipText:    { fontSize: fontSize.xs, fontWeight: '600' },

  rows: { gap: space.sm },
  row: {
    flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', gap: space.sm,
    padding: space.md, borderWidth: 1, borderColor: colors.border, borderRadius: radius.md,
  },
  rowMain:   { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0, gap: 2 },
  rowTitle:  { flexShrink: 1, minWidth: 0, fontSize: fontSize.md, fontWeight: '600', color: colors.text },
  rowMeta:   { flexDirection: 'row', alignItems: 'center', gap: space.xs, minWidth: 0 },
  rowReason: { fontSize: fontSize.sm, color: colors.text, lineHeight: 19 },

  contact:      { gap: 2, padding: space.md, borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, minWidth: 0 },
  contactHead:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  contactValue: { flexShrink: 1, minWidth: 0, fontSize: fontSize.sm, color: colors.text, lineHeight: 19 },
});
