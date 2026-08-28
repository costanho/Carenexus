import { Suspense, lazy, useState } from 'react';
import { ActivityIndicator, StyleSheet, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { DoctorBottomNavbar } from './doctor/doctor-bottom-navbar';
import { DoctorMainDashboardAreaMobile } from './doctor/doctor-main-dashboard-area';
import { DoctorTopNavbarMobile } from './doctor/doctor-topnavbar';

const DoctorMobileSchedule     = lazy(() => import('./doctor/doctor-mobile-schedule'));
const DoctorMobileConsultation = lazy(() => import('./doctor/doctor-mobile-consultation'));
const DoctorMobileAppointment  = lazy(() => import('./doctor/doctor-mobile-appointment'));
const DoctorMobilePatients        = lazy(() => import('./doctor/doctor-mobile-patients'));
const DoctorMobileMedicalRecords  = lazy(() => import('./doctor/doctor-mobile-medical-records'));
const DoctorMobilePrescription    = lazy(() => import('./doctor/doctor-mobile-prescription'));
const DoctorMobileLabResults      = lazy(() => import('./doctor/doctor-mobile-lab-results'));
const DoctorMobileProfile         = lazy(() => import('./doctor/doctor-mobile-profile'));

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F9FAFB' },
  loader:    { flex: 1, alignItems: 'center', justifyContent: 'center' },
});

const loader = (
  <View style={styles.loader}>
    <ActivityIndicator size="large" color="#4F46E5" />
  </View>
);

export function DoctorDashboardMobile() {
  const [activeKey, setActiveKey] = useState('dashboard');

  function renderContent() {
    if (activeKey === 'schedule') {
      return (
        <Suspense fallback={loader}>
          <DoctorMobileSchedule />
        </Suspense>
      );
    }
    if (activeKey === 'consultations') {
      return (
        <Suspense fallback={loader}>
          <DoctorMobileConsultation />
        </Suspense>
      );
    }
    if (activeKey === 'appointments') {
      return (
        <Suspense fallback={loader}>
          <DoctorMobileAppointment />
        </Suspense>
      );
    }
    if (activeKey === 'patients') {
      return (
        <Suspense fallback={loader}>
          <DoctorMobilePatients />
        </Suspense>
      );
    }
    if (activeKey === 'prescriptions') {
      return (
        <Suspense fallback={loader}>
          <DoctorMobilePrescription />
        </Suspense>
      );
    }
    if (activeKey === 'medical-records') {
      return (
        <Suspense fallback={loader}>
          <DoctorMobileMedicalRecords />
        </Suspense>
      );
    }
    if (activeKey === 'labs') {
      return (
        <Suspense fallback={loader}>
          <DoctorMobileLabResults />
        </Suspense>
      );
    }
    if (activeKey === 'profile') {
      return (
        <Suspense fallback={loader}>
          <DoctorMobileProfile />
        </Suspense>
      );
    }
    return <DoctorMainDashboardAreaMobile />;
  }

  return (
    <SafeAreaProvider>
      <View style={styles.container}>
        <DoctorTopNavbarMobile />
        {renderContent()}
        <DoctorBottomNavbar activeKey={activeKey} onNavigate={setActiveKey} />
      </View>
    </SafeAreaProvider>
  );
}
