import { Platform } from 'react-native';
import { PatientDashboardWeb } from '@/components/web/direct/patient-dashboard-web';
import { PatientDashboardMobile } from '@/components/mobile/direct/patient-dashboard-mobile';

export default function PatientDashboard() {
  return Platform.OS === 'web' ? <PatientDashboardWeb /> : <PatientDashboardMobile />;
}
