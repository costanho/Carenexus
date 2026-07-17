import { Platform } from 'react-native';
import { PatientProxyDashboardWeb } from '@/components/web/proxy/patient-proxy-dashboard-web';
import { PatientProxyDashboardMobile } from '@/components/mobile/proxy/patient-proxy-dashboard-mobile';

export default function ProxyDashboard() {
  return Platform.OS === 'web' ? <PatientProxyDashboardWeb /> : <PatientProxyDashboardMobile />;
}
