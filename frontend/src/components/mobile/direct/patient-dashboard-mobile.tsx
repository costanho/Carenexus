import { lazy, Suspense, useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, BackHandler, StyleSheet, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { MobileMessagingProvider } from '../messages/messaging-actions';
import { PatientBottomNavbar } from './patient/patient-bottom-navbar';
import { PatientMainDashboardAreaMobile } from './patient/patient-main-dashboard-area';
import { PatientTopNavbar } from './patient/patient-topnavbar';
import { BarInsets } from '../layout/bar-insets';

type ActiveKey = 'dashboard' | 'appointments' | 'book-appointment' | 'consultation-history' | 'records' | 'prescriptions' | 'lab-results' | 'imaging-results' | 'profile'
  | 'messages' | 'notifications' | 'caregiver-access';

const PATIENT_THEME = { accent: '#0F766E', accentBg: '#E8F5F4' };

const MobileMessagesScreen = lazy(
  () => import('../messages/mobile-messages-screen').then(m => ({ default: m.MobileMessagesScreen })),
);
const MobileNotificationsScreen = lazy(
  () => import('../notifications/mobile-notifications-screen').then(m => ({ default: m.MobileNotificationsScreen })),
);
const PatientMobileCaregiverAccess = lazy(
  () => import('../caregiver-access/patient-mobile-caregiver-access'),
);

const PatientMobileAppointmentStatus = lazy(
  () => import('./patient/patient-mobile-appointment-status'),
);
const PatientMobileAppointment = lazy(
  () => import('./patient/patient-mobile-appointment'),
);
const PatientMobileConsultationHistory = lazy(
  () => import('./patient/patient-mobile-consultation-history'),
);
const PatientMobileMedicalRecords = lazy(
  () => import('./patient/patient-mobile-medical-records'),
);
const PatientMobilePrescription = lazy(
  () => import('./patient/patient-mobile-prescription'),
);
const PatientMobileLabResults = lazy(
  () => import('./patient/patient-mobile-lab-results'),
);
const PatientMobileImagingResults = lazy(
  () => import('./patient/patient-mobile-imaging-results'),
);
const PatientMobileProfile = lazy(
  () => import('./patient/patient-mobile-profile'),
);

function LoadingFallback() {
  return (
    <View style={styles.loading}>
      <ActivityIndicator size="large" color="#0D9488" />
    </View>
  );
}

export function PatientDashboardMobile() {
  const [activeKey, setActiveKey] = useState<ActiveKey>('dashboard');
  const [openThreadId, setOpenThreadId] = useState<number | null>(null);

  const openThread = useCallback((threadId: number) => {
    setOpenThreadId(threadId);
    setActiveKey('messages');
  }, []);
  const clearOpenThread = useCallback(() => setOpenThreadId(null), []);
  const openMessages = useCallback(() => setActiveKey('messages'), []);
  const openCaregiverAccess = useCallback(() => setActiveKey('caregiver-access'), []);

  // Android back: leave a sub-screen for the dashboard instead of leaving the app's dashboard route.
  useEffect(() => {
    if (activeKey === 'dashboard') return;
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      setActiveKey(activeKey === 'book-appointment' ? 'appointments' : 'dashboard');
      return true;
    });
    return () => sub.remove();
  }, [activeKey]);

  function handleNavigate(key: string) {
    if (
      key === 'appointments' ||
      key === 'dashboard' ||
      key === 'consultation-history' ||
      key === 'records' ||
      key === 'prescriptions' ||
      key === 'lab-results' ||
      key === 'imaging-results' ||
      key === 'profile' ||
      key === 'messages' ||
      key === 'notifications' ||
      key === 'caregiver-access'
    ) {
      setActiveKey(key as ActiveKey);
    }
  }

  function renderContent() {
    if (activeKey === 'appointments') {
      return (
        <Suspense fallback={<LoadingFallback />}>
          <PatientMobileAppointmentStatus
            onBack={() => setActiveKey('dashboard')}
            onBook={() => setActiveKey('book-appointment')}
          />
        </Suspense>
      );
    }
    if (activeKey === 'book-appointment') {
      return (
        <Suspense fallback={<LoadingFallback />}>
          <PatientMobileAppointment
            onBack={() => setActiveKey('appointments')}
          />
        </Suspense>
      );
    }
    if (activeKey === 'consultation-history') {
      return (
        <Suspense fallback={<LoadingFallback />}>
          <PatientMobileConsultationHistory
            onBack={() => setActiveKey('dashboard')}
          />
        </Suspense>
      );
    }
    if (activeKey === 'records') {
      return (
        <Suspense fallback={<LoadingFallback />}>
          <PatientMobileMedicalRecords
            onBack={() => setActiveKey('dashboard')}
          />
        </Suspense>
      );
    }
    if (activeKey === 'prescriptions') {
      return (
        <Suspense fallback={<LoadingFallback />}>
          <PatientMobilePrescription
            onBack={() => setActiveKey('dashboard')}
          />
        </Suspense>
      );
    }
    if (activeKey === 'lab-results') {
      return (
        <Suspense fallback={<LoadingFallback />}>
          <PatientMobileLabResults
            onBack={() => setActiveKey('dashboard')}
          />
        </Suspense>
      );
    }
    if (activeKey === 'imaging-results') {
      return (
        <Suspense fallback={<LoadingFallback />}>
          <PatientMobileImagingResults
            onBack={() => setActiveKey('dashboard')}
          />
        </Suspense>
      );
    }
    if (activeKey === 'messages') {
      return (
        <Suspense fallback={<LoadingFallback />}>
          <MobileMessagesScreen mode="PATIENT" theme={PATIENT_THEME} openThreadId={openThreadId} onOpenThreadHandled={clearOpenThread} />
        </Suspense>
      );
    }
    if (activeKey === 'notifications') {
      return (
        <Suspense fallback={<LoadingFallback />}>
          <MobileNotificationsScreen
            theme={PATIENT_THEME}
            onOpenThread={openThread}
            onOpenCaregiverAccess={openCaregiverAccess}
          />
        </Suspense>
      );
    }
    if (activeKey === 'caregiver-access') {
      return (
        <Suspense fallback={<LoadingFallback />}>
          <PatientMobileCaregiverAccess onBack={() => setActiveKey('dashboard')} />
        </Suspense>
      );
    }
    if (activeKey === 'profile') {
      return (
        <Suspense fallback={<LoadingFallback />}>
          <PatientMobileProfile />
        </Suspense>
      );
    }
    return (
      <>
        <PatientTopNavbar onProfilePress={() => setActiveKey('profile')} onOpenNotifications={() => setActiveKey('notifications')} />
        <PatientMainDashboardAreaMobile
          onBookAppointment={() => setActiveKey('book-appointment')}
          onViewAppointments={() => setActiveKey('appointments')}
          onViewConsultations={() => setActiveKey('consultation-history')}
          onViewRecords={() => setActiveKey('records')}
          onViewLabResults={() => setActiveKey('lab-results')}
          onViewImagingResults={() => setActiveKey('imaging-results')}
          onViewPrescriptions={() => setActiveKey('prescriptions')}
        />
      </>
    );
  }

  return (
    <SafeAreaProvider>
      <MobileMessagingProvider role="PATIENT" onOpenThread={openThread} onOpenMessages={openMessages} onOpenCaregiverAccess={openCaregiverAccess}>
        <View style={styles.container}>
          <View style={styles.content}>
            {/* No top bar above sub-screens (they keep the status-bar inset); the tab bar owns the bottom one. */}
            <BarInsets topBar={false}>{renderContent()}</BarInsets>
          </View>
          <PatientBottomNavbar activeKey={activeKey} onNavigate={handleNavigate} />
        </View>
      </MobileMessagingProvider>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F9FAFB' },
  content:   { flex: 1 },
  loading:   { flex: 1, alignItems: 'center', justifyContent: 'center' },
});
