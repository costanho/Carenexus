import React, { lazy, Suspense, useState } from 'react';
import { ActivityIndicator, StyleSheet, useWindowDimensions, View } from 'react-native';
import { type CaregiverDependent } from '@/hooks/use-caregiver-dependents';
import { PatientProxySidebar }  from './patient/patient-proxy-sidebar';
import { PatientProxyTopNavbar } from './patient/patient-proxy-topnavbar';
import { PatientProxyMainDashboard } from './patient/patient-proxy-main-dashboard';

const ProxyAppointments         = lazy(() => import('./patient/patient-proxy-appointments'));
const ProxyMedications          = lazy(() => import('./patient/patient-proxy-medications'));
const ProxyMessages             = lazy(() => import('./patient/patient-proxy-messages'));
const ProxyCarePlans            = lazy(() => import('./patient/patient-proxy-care-plans'));
const ProxyDocuments            = lazy(() => import('./patient/patient-proxy-documents'));
const ProxyHealthSummary        = lazy(() => import('./patient/patient-proxy-health-summary'));
const ProxyProfileSettings      = lazy(() => import('./patient/patient-proxy-profile-settings'));
const ProxyNotificationSettings = lazy(() => import('./patient/patient-proxy-notification-settings'));
const ProxyAddDependent         = lazy(() => import('./patient/patient-proxy-web-add-dependent'));
const ProxyPatientProfile       = lazy(() => import('./patient/patient-proxy-web-profile'));

function Loader() {
  return (
    <View style={styles.loader}>
      <ActivityIndicator size="large" color="#7C3AED" />
    </View>
  );
}

export function PatientProxyDashboardWeb() {
  const { width } = useWindowDimensions();
  const isCollapsed = width < 1024;
  const [activeKey, setActiveKey]               = useState('dashboard');
  const [selectedDependent, setSelectedDependent] = useState<CaregiverDependent | null>(null);

  function selectPatient(dep: CaregiverDependent) {
    setSelectedDependent(dep);
    setActiveKey('patient-profile');
  }

  function renderContent() {
    switch (activeKey) {
      case 'appointments':
        return <Suspense fallback={<Loader />}><ProxyAppointments /></Suspense>;
      case 'medications':
        return <Suspense fallback={<Loader />}><ProxyMedications /></Suspense>;
      case 'messages':
        return <Suspense fallback={<Loader />}><ProxyMessages /></Suspense>;
      case 'care-plans':
        return <Suspense fallback={<Loader />}><ProxyCarePlans /></Suspense>;
      case 'documents-results':
        return <Suspense fallback={<Loader />}><ProxyDocuments /></Suspense>;
      case 'health-summary':
        return <Suspense fallback={<Loader />}><ProxyHealthSummary /></Suspense>;
      case 'profile-settings':
        return <Suspense fallback={<Loader />}><ProxyProfileSettings /></Suspense>;
      case 'notification-settings':
        return <Suspense fallback={<Loader />}><ProxyNotificationSettings /></Suspense>;
      case 'add-dependent':
        return <Suspense fallback={<Loader />}><ProxyAddDependent onDone={() => setActiveKey('dashboard')} onCancel={() => setActiveKey('dashboard')} /></Suspense>;
      case 'patient-profile':
        return selectedDependent
          ? <Suspense fallback={<Loader />}><ProxyPatientProfile dependent={selectedDependent} onBack={() => setActiveKey('dashboard')} /></Suspense>
          : <PatientProxyMainDashboard onNavigate={setActiveKey} onSelectPatient={selectPatient} />;
      default:
        return <PatientProxyMainDashboard onNavigate={setActiveKey} onSelectPatient={selectPatient} />;
    }
  }

  return (
    <View style={styles.container}>
      <PatientProxySidebar
        collapsed={isCollapsed}
        activeKey={activeKey}
        onNavigate={setActiveKey}
        onSelectPatient={selectPatient}
      />
      <View style={styles.main}>
        <PatientProxyTopNavbar />
        {renderContent()}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, flexDirection: 'row', backgroundColor: '#F9FAFB' },
  main:      { flex: 1, flexDirection: 'column' },
  loader:    { flex: 1, alignItems: 'center', justifyContent: 'center' },
});
