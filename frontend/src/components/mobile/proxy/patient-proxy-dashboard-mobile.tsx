import React, { lazy, Suspense, useCallback, useState } from 'react';
import { ActivityIndicator, StyleSheet, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { type CaregiverDependent } from '@/hooks/use-caregiver-dependents';
import { MobileMessagingProvider } from '../messages/messaging-actions';
import { PatientProxyBottomNavbar } from './patient/patient-bottom-navbar';
import { PatientProxyMainDashboardMobile } from './patient/patient-proxy-main-dashboard';
import { PatientProxyTopNavMobile } from './patient/patient-proxy-topnav';

const MobileAddDependent  = lazy(() => import('./patient/patient-proxy-mobile-add-dependent'));
const MobilePatientProfile = lazy(() => import('./patient/patient-proxy-mobile-profile'));
const MobileMessagesScreen = lazy(() =>
  import('../messages/mobile-messages-screen').then(m => ({ default: m.MobileMessagesScreen })));
const MobileNotificationsScreen = lazy(() =>
  import('../notifications/mobile-notifications-screen').then(m => ({ default: m.MobileNotificationsScreen })));

const PROXY_THEME = { accent: '#7C3AED', accentBg: '#F5F3FF' };
/** Full-screen sections with their own header (no top navbar above them). */
const FULL_SCREEN = new Set(['messages', 'notifications']);

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
  const [openThreadId, setOpenThreadId]         = useState<number | null>(null);

  const openThread = useCallback((threadId: number) => {
    setOpenThreadId(threadId);
    setActiveKey('messages');
  }, []);
  const clearOpenThread = useCallback(() => setOpenThreadId(null), []);
  const openMessages = useCallback(() => setActiveKey('messages'), []);

  function selectPatient(dep: CaregiverDependent) {
    setSelectedDependent(dep);
    setActiveKey('patient-profile');
  }

  function renderContent() {
    if (activeKey === 'messages') {
      return (
        <Suspense fallback={<Loader />}>
          <MobileMessagesScreen mode="CAREGIVER" theme={PROXY_THEME} openThreadId={openThreadId} onOpenThreadHandled={clearOpenThread} />
        </Suspense>
      );
    }
    if (activeKey === 'notifications') {
      return (
        <Suspense fallback={<Loader />}>
          <MobileNotificationsScreen theme={PROXY_THEME} onOpenThread={openThread} />
        </Suspense>
      );
    }
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
      <MobileMessagingProvider role="CAREGIVER" onOpenThread={openThread} onOpenMessages={openMessages}>
        <View style={styles.container}>
          {!FULL_SCREEN.has(activeKey) && <PatientProxyTopNavMobile onOpenNotifications={() => setActiveKey('notifications')} />}
          {renderContent()}
          <PatientProxyBottomNavbar activeKey={activeKey} onNavigate={setActiveKey} />
        </View>
      </MobileMessagingProvider>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F9FAFB' },
  loader:    { flex: 1, alignItems: 'center', justifyContent: 'center' },
});
