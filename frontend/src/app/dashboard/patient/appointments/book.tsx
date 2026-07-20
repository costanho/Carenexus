import { Platform } from 'react-native';
import { PatientWebAppointment } from '@/components/web/direct/patient/patient-web-appointment';
import { PatientMobileAppointment } from '@/components/mobile/direct/patient/patient-mobile-appointment';

export default function BookAppointment() {
  return Platform.OS === 'web' ? <PatientWebAppointment /> : <PatientMobileAppointment />;
}
