import { Platform } from 'react-native';
import { PatientMobileAppointmentStatus } from '@/components/mobile/direct/patient/patient-mobile-appointment-status';
import { PatientWebAppointmentStatus }    from '@/components/web/direct/patient/patient-web-appointment-status';

export default function AppointmentStatus() {
  return Platform.OS === 'web'
    ? <PatientWebAppointmentStatus />
    : <PatientMobileAppointmentStatus />;
}
