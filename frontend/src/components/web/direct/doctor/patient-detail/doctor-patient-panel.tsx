import { type ReactNode, useCallback, useMemo, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { Banner, Button } from '@/components/web/ui';
import { type DoctorPatientDto, patientFullName } from '@/hooks/use-doctor-patients';
import { useSectionNav } from '@/hooks/use-section-nav';
import type { AppointmentDto } from '@/lib/appointments/types';
import { formatClinicDateTime } from '@/lib/time/clinic';
import { DoctorBookingDialog } from '../doctor-booking-dialog';
import { type DoctorPatientTabsOptions, useDoctorPatientTabs } from './doctor-patient-tabs';
import {
  PatientDetailPanel, type PatientDetailPanelProps, type PatientDetailTab, type PatientPanelHelpers,
} from './patient-detail-panel';

export interface DoctorPatientPanelProps
  extends Omit<PatientDetailPanelProps, 'extraTabs' | 'actions'>, DoctorPatientTabsOptions {
  /** Tabs after the doctor dashboard's own set (History, Care team, Vitals, ...). */
  extraTabs?: readonly PatientDetailTab[];
  /** More buttons for the action row, after Book appointment and New care plan. */
  actions?: (patient: DoctorPatientDto, helpers: PatientPanelHelpers) => ReactNode;
  /** Called after an appointment was booked from the panel, so the host can refresh its lists. */
  onBooked?: (appointment: AppointmentDto) => void;
}

/**
 * The patient detail panel as the doctor dashboard uses it everywhere: Overview plus History, Care team, Vitals,
 * Alerts, Care plans, Documents, Referrals and Reminders, with Book appointment and New care plan buttons.
 */
export function DoctorPatientPanel({
  focusSymptomId, onAccessLost, extraTabs, actions, onBooked, ...panel
}: DoctorPatientPanelProps) {
  const { navigate, hasSection } = useSectionNav();
  const ownTabs = useDoctorPatientTabs({ focusSymptomId, onAccessLost });
  const tabs = useMemo(() => [...ownTabs, ...(extraTabs ?? [])], [ownTabs, extraTabs]);

  const [booking, setBooking] = useState<DoctorPatientDto | null>(null);
  const [booked, setBooked] = useState<{ patientSystemId: string; text: string } | null>(null);

  const renderActions = useCallback((patient: DoctorPatientDto, helpers: PatientPanelHelpers) => {
    const name = patientFullName(patient);
    const notice = booked && booked.patientSystemId === patient.patientSystemId ? booked.text : null;
    return (
      <>
        <Button
          label="Book appointment"
          icon="calendar-number-outline"
          variant="secondary"
          size="sm"
          onPress={() => { setBooked(null); setBooking(patient); }}
          accessibilityLabel={`Book appointment for ${name}`}
        />
        {hasSection('care-plans') ? (
          <Button
            label="New care plan"
            icon="clipboard-outline"
            variant="secondary"
            size="sm"
            onPress={() => navigate('care-plans', { patient: patient.patientSystemId, mode: 'new' })}
            accessibilityLabel={`New care plan for ${name}`}
          />
        ) : null}
        {actions?.(patient, helpers)}
        {notice ? (
          <View style={styles.notice}>
            <Banner
              tone="success"
              text={notice}
              action={hasSection('appointments')
                ? { label: 'View appointments', onPress: () => navigate('appointments', { patient: patient.patientSystemId }) }
                : undefined}
              onDismiss={() => setBooked(null)}
            />
          </View>
        ) : null}
        <DoctorBookingDialog
          open={booking?.patientSystemId === patient.patientSystemId}
          patientSystemId={patient.patientSystemId}
          patientName={name}
          onClose={() => setBooking(null)}
          onBooked={appointment => {
            setBooking(null);
            setBooked({
              patientSystemId: patient.patientSystemId,
              text: `Appointment booked for ${name} on ${formatClinicDateTime(appointment.scheduledAt, appointment.scheduledAt)}.`,
            });
            helpers.refetch();
            onBooked?.(appointment);
          }}
        />
      </>
    );
  }, [actions, booked, booking, hasSection, navigate, onBooked]);

  return <PatientDetailPanel {...panel} extraTabs={tabs} actions={renderActions} />;
}

const styles = StyleSheet.create({
  // The confirmation takes a line of its own under the buttons.
  notice: { flexBasis: '100%', minWidth: 0 },
});
