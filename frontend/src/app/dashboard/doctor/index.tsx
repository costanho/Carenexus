import { Platform } from 'react-native';
import { DoctorDashboardWeb } from '@/components/web/direct/doctor-dashboard-web';
import { DoctorDashboardMobile } from '@/components/mobile/direct/doctor-dashboard-mobile';

export default function DoctorDashboard() {
  return Platform.OS === 'web' ? <DoctorDashboardWeb /> : <DoctorDashboardMobile />;
}
