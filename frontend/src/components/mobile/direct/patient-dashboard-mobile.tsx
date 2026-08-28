import { lazy, Suspense, useState } from 'react';
import { ActivityIndicator, StyleSheet, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { PatientBottomNavbar } from './patient/patient-bottom-navbar';
import { PatientMainDashboardAreaMobile } from './patient/patient-main-dashboard-area';
import { PatientTopNavbar } from './patient/patient-topnavbar';

type ActiveKey = 'dashboard' | 'appointments' | 'book-appointment' | 'consultation-history' | 'records' | 'prescriptions' | 'lab-results' | 'imaging-results' | 'profile';

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

  function handleNavigate(key: string) {
    if (
      key === 'appointments' ||
      key === 'dashboard' ||
      key === 'consultation-history' ||
      key === 'records' ||
      key === 'prescriptions' ||
      key === 'lab-results' ||
      key === 'imaging-results' ||
      key === 'profile'
    ) {
      setActiveKey(key as ActiveKey);
    }
    // messages — not yet implemented
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
    if (activeKey === 'profile') {
      return (
        <Suspense fallback={<LoadingFallback />}>
          <PatientMobileProfile />
        </Suspense>
      );
    }
    return (
      <>
        <PatientTopNavbar />
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
