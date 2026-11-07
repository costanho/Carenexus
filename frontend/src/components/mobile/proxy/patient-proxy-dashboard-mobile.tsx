import React, { lazy, Suspense, useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, BackHandler, StyleSheet, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { type CaregiverDependent } from '@/hooks/use-caregiver-dependents';
import { MobileMessagingProvider } from '../messages/messaging-actions';
import { PatientProxyBottomNavbar } from './patient/patient-bottom-navbar';
import { PatientProxyMainDashboardMobile } from './patient/patient-proxy-main-dashboard';
import { PatientProxyTopNavMobile } from './patient/patient-proxy-topnav';
import { BarInsets } from '../layout/bar-insets';

const MobileAddDependent  = lazy(() => import('./patient/patient-proxy-mobile-add-dependent'));
const MobilePatientProfile = lazy(() => import('./patient/patient-proxy-mobile-profile'));
const MobileAppointments   = lazy(() => import('./patient/patient-proxy-mobile-appointments'));
const MobileMedications    = lazy(() => import('./patient/patient-proxy-mobile-medications'));
const MobileDocuments      = lazy(() => import('./patient/patient-proxy-mobile-documents'));
const MobileHealthSummary  = lazy(() => import('./patient/patient-proxy-mobile-health-summary'));
const MobileCarePlans      = lazy(() => import('./patient/patient-proxy-mobile-care-plans'));
const MobileSettings       = lazy(() => import('./patient/patient-proxy-mobile-settings'));
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

  // Android back on a sub-screen returns to the dashboard instead of leaving it
  // A record screen opened from a loved one's profile starts on that loved one, and its Back returns to the profile.
  const [recordFocus, setRecordFocus] = useState<{ key: string; patientSystemId: string } | null>(null);

  useEffect(() => {
    if (activeKey === 'dashboard') return;
    const backTo = recordFocus?.key === activeKey ? 'patient-profile' : 'dashboard';
    const sub = BackHandler.addEventListener('hardwareBackPress', () => { setActiveKey(backTo); return true; });
    return () => sub.remove();
  }, [activeKey, recordFocus]);
  useEffect(() => { if (activeKey === 'dashboard' || activeKey === 'patient-profile') setRecordFocus(null); }, [activeKey]);

  function openRecordFor(key: string, dep: CaregiverDependent) {
    setRecordFocus({ key, patientSystemId: dep.patientSystemId });
    setActiveKey(key);
  }
  function recordProps(key: string) {
    const fromProfile = recordFocus?.key === key;
    return {
      initialPatientId: fromProfile ? recordFocus.patientSystemId : undefined,
      onBack: () => setActiveKey(fromProfile ? 'patient-profile' : 'dashboard'),
      fromProfile,
    };
  }

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
            onOpenRecord={(key) => openRecordFor(key, selectedDependent)}
          />
        </Suspense>
      );
    }
    if (activeKey === 'schedule') {
      const { initialPatientId, onBack, fromProfile } = recordProps('schedule');
      return <Suspense fallback={<Loader />}><MobileAppointments initialPatientId={initialPatientId} onBack={fromProfile ? onBack : undefined} /></Suspense>;
    }
    if (activeKey === 'medications') {
      const { initialPatientId, onBack } = recordProps('medications');
      return <Suspense fallback={<Loader />}><MobileMedications initialPatientId={initialPatientId} onBack={onBack} /></Suspense>;
    }
    if (activeKey === 'documents') {
      const { initialPatientId, onBack } = recordProps('documents');
      return <Suspense fallback={<Loader />}><MobileDocuments initialPatientId={initialPatientId} onBack={onBack} /></Suspense>;
    }
    if (activeKey === 'health-summary') {
      const { initialPatientId, onBack } = recordProps('health-summary');
      return <Suspense fallback={<Loader />}><MobileHealthSummary initialPatientId={initialPatientId} onBack={onBack} /></Suspense>;
    }
    if (activeKey === 'care-plans') {
      return <Suspense fallback={<Loader />}><MobileCarePlans onBack={() => setActiveKey('dashboard')} /></Suspense>;
    }
    if (activeKey === 'profile') {
      return <Suspense fallback={<Loader />}><MobileSettings onNavigate={setActiveKey} onSelectPatient={selectPatient} /></Suspense>;
    }
    return <PatientProxyMainDashboardMobile onNavigate={setActiveKey} onSelectPatient={selectPatient} />;
  }

  return (
    <SafeAreaProvider>
      <MobileMessagingProvider role="CAREGIVER" onOpenThread={openThread} onOpenMessages={openMessages}>
        <View style={styles.container}>
          {!FULL_SCREEN.has(activeKey) && <PatientProxyTopNavMobile onOpenNotifications={() => setActiveKey('notifications')} />}
          <BarInsets topBar={!FULL_SCREEN.has(activeKey)}>{renderContent()}</BarInsets>
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
