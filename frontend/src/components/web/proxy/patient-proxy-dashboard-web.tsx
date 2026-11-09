import React, { lazy, Suspense, useCallback, useState } from 'react';
import { ActivityIndicator, StyleSheet, useWindowDimensions, View } from 'react-native';
import { type CaregiverDependent } from '@/hooks/use-caregiver-dependents';
import { NavDrawer } from '../layout/nav-drawer';
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
const NotificationList          = lazy(() => import('../notifications/notification-list').then(m => ({ default: m.NotificationList })));

const PROXY_THEME = { accent: '#7C3AED', accentBg: '#F5F3FF' };

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
  // Phones: the sidebar becomes a drawer behind a menu button, so pages get the full width.
  const isSmall = width < 768;
  const [drawerOpen, setDrawerOpen] = useState(false);
  const closeDrawer = useCallback(() => setDrawerOpen(false), []);
  const [activeKey, setActiveKey]               = useState('dashboard');
  const [navKey,    setNavKey]                  = useState(0);
  const [selectedDependent, setSelectedDependent] = useState<CaregiverDependent | null>(null);
  const [openThreadId, setOpenThreadId] = useState<number | null>(null);

  function navigate(key: string) {
    setNavKey(k => k + 1);
    setActiveKey(key);
  }

  const openThread = useCallback((threadId: number) => {
    setOpenThreadId(threadId);
    setActiveKey('messages');
  }, []);
  const clearOpenThread = useCallback(() => setOpenThreadId(null), []);

  function selectPatient(dep: CaregiverDependent) {
    setSelectedDependent(dep);
    navigate('patient-profile');
  }

  function renderContent() {
    switch (activeKey) {
      case 'appointments':
        return <Suspense fallback={<Loader />}><ProxyAppointments key={navKey} /></Suspense>;
      case 'medications':
        return <Suspense fallback={<Loader />}><ProxyMedications /></Suspense>;
      case 'messages':
        return <Suspense fallback={<Loader />}><ProxyMessages openThreadId={openThreadId} onOpenThreadHandled={clearOpenThread} /></Suspense>;
      case 'care-plans':
        return <Suspense fallback={<Loader />}><ProxyCarePlans /></Suspense>;
      case 'documents-results':
        return <Suspense fallback={<Loader />}><ProxyDocuments /></Suspense>;
      case 'health-summary':
        return <Suspense fallback={<Loader />}><ProxyHealthSummary /></Suspense>;
      case 'profile-settings':
        return <Suspense fallback={<Loader />}><ProxyProfileSettings onNavigate={navigate} onSelectPatient={selectPatient} /></Suspense>;
      case 'notifications':
        return <Suspense fallback={<Loader />}><NotificationList theme={PROXY_THEME} onOpenThread={openThread} /></Suspense>;
      case 'notification-settings':
        return <Suspense fallback={<Loader />}><ProxyNotificationSettings /></Suspense>;
      case 'add-dependent':
        return <Suspense fallback={<Loader />}><ProxyAddDependent onDone={() => navigate('dashboard')} onCancel={() => navigate('dashboard')} /></Suspense>;
      case 'patient-profile':
        return selectedDependent
          ? <Suspense fallback={<Loader />}><ProxyPatientProfile dependent={selectedDependent} onBack={() => navigate('dashboard')} onNavigate={navigate} onOpenThread={openThread} /></Suspense>
          : <PatientProxyMainDashboard onNavigate={navigate} onSelectPatient={selectPatient} onOpenThread={openThread} />;
      default:
        return <PatientProxyMainDashboard onNavigate={navigate} onSelectPatient={selectPatient} onOpenThread={openThread} />;
    }
  }

  return (
    <View style={styles.container}>
      {!isSmall && (
        <PatientProxySidebar
          collapsed={isCollapsed}
          activeKey={activeKey}
          onNavigate={navigate}
          onSelectPatient={selectPatient}
        />
      )}
      <View style={styles.main}>
        <PatientProxyTopNavbar
          onNavigate={navigate}
          onOpenThread={openThread}
          onMenuPress={isSmall ? () => setDrawerOpen(true) : undefined}
        />
        {renderContent()}
      </View>
      <NavDrawer open={isSmall && drawerOpen} onClose={closeDrawer}>
        <PatientProxySidebar
          collapsed={false}
          activeKey={activeKey}
          onNavigate={key => { setDrawerOpen(false); navigate(key); }}
          onSelectPatient={dep => { setDrawerOpen(false); selectPatient(dep); }}
        />
      </NavDrawer>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, flexDirection: 'row', backgroundColor: '#F9FAFB' },
  main:      { flex: 1, flexDirection: 'column', minWidth: 0 },
  loader:    { flex: 1, alignItems: 'center', justifyContent: 'center' },
});
