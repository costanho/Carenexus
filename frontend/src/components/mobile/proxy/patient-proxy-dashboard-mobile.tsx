import React, { lazy, Suspense, useState } from 'react';
import { ActivityIndicator, StyleSheet, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { type CaregiverDependent } from '@/hooks/use-caregiver-dependents';
import { PatientProxyBottomNavbar } from './patient/patient-bottom-navbar';
import { PatientProxyMainDashboardMobile } from './patient/patient-proxy-main-dashboard';
import { PatientProxyTopNavMobile } from './patient/patient-proxy-topnav';

const MobileAddDependent  = lazy(() => import('./patient/patient-proxy-mobile-add-dependent'));
const MobilePatientProfile = lazy(() => import('./patient/patient-proxy-mobile-profile'));

function Loader() {
  return (
    <View style={styles.loader}>
      <ActivityIndicator size="large" color="#7C3AED" />
    </View>
  );
}

export function PatientProxyDashboardMobile() {
  const [activeKey, setActiveKey]               = useState('dashboard');
  const [selectedDependent, setSelectedDependent] = useState<CaregiverDependent | null>(null);

  function selectPatient(dep: CaregiverDependent) {
    setSelectedDependent(dep);
    setActiveKey('patient-profile');
  }

  function renderContent() {
    if (activeKey === 'add-dependent') {
      return (
        <Suspense fallback={<Loader />}>
          <MobileAddDependent
            onDone={()   => setActiveKey('dashboard')}
            onCancel={() => setActiveKey('dashboard')}
          />
        </Suspense>
      );
    }
    if (activeKey === 'patient-profile' && selectedDependent) {
      return (
        <Suspense fallback={<Loader />}>
          <MobilePatientProfile
            dependent={selectedDependent}
            onBack={() => setActiveKey('dashboard')}
          />
        </Suspense>
      );
    }
    return <PatientProxyMainDashboardMobile onNavigate={setActiveKey} onSelectPatient={selectPatient} />;
  }

  return (
    <SafeAreaProvider>
      <View style={styles.container}>
        <PatientProxyTopNavMobile />
        {renderContent()}
        <PatientProxyBottomNavbar />
      </View>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F9FAFB' },
  loader:    { flex: 1, alignItems: 'center', justifyContent: 'center' },
});
