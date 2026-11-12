import { Suspense, lazy, useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, BackHandler, StyleSheet, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { MobileMessagingProvider } from '../messages/messaging-actions';
import { DoctorBottomNavbar } from './doctor/doctor-bottom-navbar';
import { DoctorMainDashboardAreaMobile } from './doctor/doctor-main-dashboard-area';
import { DoctorTopNavbarMobile } from './doctor/doctor-topnavbar';
import { BarInsets } from '../layout/bar-insets';

const DoctorMobileSchedule     = lazy(() => import('./doctor/doctor-mobile-schedule'));
const DoctorMobileConsultation = lazy(() => import('./doctor/doctor-mobile-consultation'));
const DoctorMobileAppointment  = lazy(() => import('./doctor/doctor-mobile-appointment'));
const DoctorMobilePatients        = lazy(() => import('./doctor/doctor-mobile-patients'));
const DoctorMobileMedicalRecords  = lazy(() => import('./doctor/doctor-mobile-medical-records'));
const DoctorMobilePrescription    = lazy(() => import('./doctor/doctor-mobile-prescription'));
const DoctorMobileLabResults      = lazy(() => import('./doctor/doctor-mobile-lab-results'));
const DoctorMobileProfile         = lazy(() => import('./doctor/doctor-mobile-profile'));
const MobileMessagesScreen = lazy(() =>
  import('../messages/mobile-messages-screen').then(m => ({ default: m.MobileMessagesScreen })));
const MobileNotificationsScreen = lazy(() =>
  import('../notifications/mobile-notifications-screen').then(m => ({ default: m.MobileNotificationsScreen })));

const DOCTOR_THEME = { accent: '#4F46E5', accentBg: '#EEF2FF' };
/** Full-screen sections with their own header (no top navbar above them). */
const FULL_SCREEN = new Set(['messages', 'notifications']);

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
  const [openThreadId, setOpenThreadId] = useState<number | null>(null);

  const openThread = useCallback((threadId: number) => {
    setOpenThreadId(threadId);
    setActiveKey('messages');
  }, []);
  const clearOpenThread = useCallback(() => setOpenThreadId(null), []);
  const openMessages = useCallback(() => setActiveKey('messages'), []);

  // Android back from a sub-screen returns to the dashboard instead of leaving the app.
  useEffect(() => {
    if (activeKey === 'dashboard') return;
    const sub = BackHandler.addEventListener('hardwareBackPress', () => { setActiveKey('dashboard'); return true; });
    return () => sub.remove();
  }, [activeKey]);

  function renderContent() {
    if (activeKey === 'messages') {
      return (
        <Suspense fallback={loader}>
          <MobileMessagesScreen mode="DOCTOR" theme={DOCTOR_THEME} openThreadId={openThreadId} onOpenThreadHandled={clearOpenThread} />
        </Suspense>
      );
    }
    if (activeKey === 'notifications') {
      return (
        <Suspense fallback={loader}>
          <MobileNotificationsScreen theme={DOCTOR_THEME} onOpenThread={openThread} />
        </Suspense>
      );
    }
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
    return <DoctorMainDashboardAreaMobile onNavigate={setActiveKey} />;
  }

  return (
    <SafeAreaProvider>
      <MobileMessagingProvider role="DOCTOR" onOpenThread={openThread} onOpenMessages={openMessages}>
        <View style={styles.container}>
          {!FULL_SCREEN.has(activeKey) && (
            <DoctorTopNavbarMobile onProfilePress={() => setActiveKey('profile')} onOpenNotifications={() => setActiveKey('notifications')} />
          )}
          <BarInsets topBar={!FULL_SCREEN.has(activeKey)}>{renderContent()}</BarInsets>
          <DoctorBottomNavbar activeKey={activeKey} onNavigate={setActiveKey} />
        </View>
      </MobileMessagingProvider>
    </SafeAreaProvider>
  );
}
