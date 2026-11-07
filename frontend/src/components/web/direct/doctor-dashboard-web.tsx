import { Suspense, lazy, useCallback, useState } from 'react';
import { ActivityIndicator, StyleSheet, useWindowDimensions, View } from 'react-native';
import { DoctorLeftSidebar } from './doctor/doctor-left-sidebar';
import { DoctorMainDashboardArea } from './doctor/doctor-main-dashboard-area';
import { DoctorTopNavbar } from './doctor/doctor-top-navbar';

const DoctorWebSchedule     = lazy(() => import('./doctor/doctor-web-schedule'));
const DoctorWebConsultation = lazy(() => import('./doctor/doctor-web-consultation'));
const DoctorWebAppointment  = lazy(() => import('./doctor/doctor-web-appointment'));
const DoctorWebPatients        = lazy(() => import('./doctor/doctor-web-patients'));
const DoctorWebMedicalRecords  = lazy(() => import('./doctor/doctor-web-medical-records'));
const DoctorWebPrescription    = lazy(() => import('./doctor/doctor-web-prescription'));
const DoctorWebLabResults      = lazy(() => import('./doctor/doctor-web-lab-results'));
const DoctorWebProfile         = lazy(() => import('./doctor/doctor-web-profile'));
const MessagesScreen           = lazy(() => import('../messages/messages-screen').then(m => ({ default: m.MessagesScreen })));
const NotificationList         = lazy(() => import('../notifications/notification-list').then(m => ({ default: m.NotificationList })));

const DOCTOR_THEME = { accent: '#4F46E5', accentBg: '#EEF2FF' };

const styles = StyleSheet.create({
  container: { flex: 1, flexDirection: 'row', backgroundColor: '#F9FAFB' },
  main:      { flex: 1, flexDirection: 'column', minWidth: 0 },
  loader:    { flex: 1, alignItems: 'center', justifyContent: 'center' },
});

const loader = (
  <View style={styles.loader}>
    <ActivityIndicator size="large" color="#4F46E5" />
  </View>
);

export function DoctorDashboardWeb() {
  const { width } = useWindowDimensions();
  const isCollapsed = width < 1024;
  const [activeKey, setActiveKey] = useState('dashboard');
  const [openThreadId, setOpenThreadId] = useState<number | null>(null);

  const openThread = useCallback((threadId: number) => {
    setOpenThreadId(threadId);
    setActiveKey('messages');
  }, []);
  const clearOpenThread = useCallback(() => setOpenThreadId(null), []);

  function renderContent() {
    if (activeKey === 'schedule') {
      return (
        <Suspense fallback={loader}>
          <DoctorWebSchedule onOpenThread={openThread} />
        </Suspense>
      );
    }
    if (activeKey === 'consultations') {
      return (
        <Suspense fallback={loader}>
          <DoctorWebConsultation />
        </Suspense>
      );
    }
    if (activeKey === 'appointments') {
      return (
        <Suspense fallback={loader}>
          <DoctorWebAppointment onOpenThread={openThread} />
        </Suspense>
      );
    }
    if (activeKey === 'patients') {
      return (
        <Suspense fallback={loader}>
          <DoctorWebPatients onOpenThread={openThread} />
        </Suspense>
      );
    }
    if (activeKey === 'prescriptions') {
      return (
        <Suspense fallback={loader}>
          <DoctorWebPrescription />
        </Suspense>
      );
    }
    if (activeKey === 'medical-records') {
      return (
        <Suspense fallback={loader}>
          <DoctorWebMedicalRecords />
        </Suspense>
      );
    }
    if (activeKey === 'labs') {
      return (
        <Suspense fallback={loader}>
          <DoctorWebLabResults />
        </Suspense>
      );
    }
    if (activeKey === 'messages') {
      return (
        <Suspense fallback={loader}>
          <MessagesScreen
            mode="DOCTOR"
            theme={DOCTOR_THEME}
            openThreadId={openThreadId}
            onOpenThreadHandled={clearOpenThread}
          />
        </Suspense>
      );
    }
    if (activeKey === 'notifications') {
      return (
        <Suspense fallback={loader}>
          <NotificationList theme={DOCTOR_THEME} onOpenThread={openThread} />
        </Suspense>
      );
    }
    if (activeKey === 'profile') {
      return (
        <Suspense fallback={loader}>
          <DoctorWebProfile />
        </Suspense>
      );
    }
    return <DoctorMainDashboardArea onNavigate={setActiveKey} />;
  }

  return (
    <View style={styles.container}>
      <DoctorLeftSidebar
        collapsed={isCollapsed}
        activeKey={activeKey}
        onNavigate={setActiveKey}
      />
      <View style={styles.main}>
        <DoctorTopNavbar onProfilePress={() => setActiveKey('profile')} onOpenThread={openThread} />
        {renderContent()}
      </View>
    </View>
  );
}
