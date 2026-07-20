import { lazy, Suspense, useState } from 'react';
import { ActivityIndicator, StyleSheet, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { PatientBottomNavbar } from './patient/patient-bottom-navbar';
import { PatientMainDashboardAreaMobile } from './patient/patient-main-dashboard-area';
import { PatientTopNavbar } from './patient/patient-topnavbar';

type ActiveKey = 'dashboard' | 'appointments' | 'book-appointment' | 'consultation-history';

const PatientMobileAppointmentStatus = lazy(
  () => import('./patient/patient-mobile-appointment-status'),
);
const PatientMobileAppointment = lazy(
  () => import('./patient/patient-mobile-appointment'),
);
const PatientMobileConsultationHistory = lazy(
  () => import('./patient/patient-mobile-consultation-history'),
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

  function handleNavigate(key: string) {
    if (
      key === 'appointments' ||
      key === 'dashboard' ||
      key === 'consultation-history'
    ) {
      setActiveKey(key as ActiveKey);
    }
    // records, messages, profile — not yet implemented, just update highlight
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
    return (
      <>
        <PatientTopNavbar />
        <PatientMainDashboardAreaMobile
          onBookAppointment={() => setActiveKey('book-appointment')}
          onViewConsultations={() => setActiveKey('consultation-history')}
        />
      </>
    );
  }

  return (
    <SafeAreaProvider>
      <View style={styles.container}>
        <View style={styles.content}>{renderContent()}</View>
        <PatientBottomNavbar activeKey={activeKey} onNavigate={handleNavigate} />
      </View>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F9FAFB' },
  content:   { flex: 1 },
  loading:   { flex: 1, alignItems: 'center', justifyContent: 'center' },
});
