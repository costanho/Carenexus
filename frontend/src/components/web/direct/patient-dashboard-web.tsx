import { Suspense, lazy, useCallback, useState } from 'react';
import { ActivityIndicator, StyleSheet, useWindowDimensions, View } from 'react-native';
import { PatientSidebar as PatientLeftSidebar } from './patient/patient-left-sidebar';
import { PatientMainDashboardArea } from './patient/patient-main-dashboard-area';
import { PatientRightSidebar } from './patient/patient-right-sidebar';
import { PatientTopNavbar } from './patient/patient-top-navbar';

const PATIENT_THEME = { accent: '#0F766E', accentBg: '#E8F5F4' };

const MessagesScreen = lazy(() =>
  import('../messages/messages-screen').then(m => ({ default: m.MessagesScreen }))
);

const NotificationList = lazy(() =>
  import('../notifications/notification-list').then(m => ({ default: m.NotificationList }))
);

const PatientWebCaregiverAccess = lazy(() =>
  import('./patient/patient-web-caregiver-access')
);

const PatientWebAppointment = lazy(() =>
  import('./patient/patient-web-appointment')
);

const PatientWebAppointmentStatus = lazy(() =>
  import('./patient/patient-web-appointment-status')
);

const PatientWebConsultationHistory = lazy(() =>
  import('./patient/patient-web-consultation-history')
);

const PatientWebMedicalRecords = lazy(() =>
  import('./patient/patient-web-medical-records')
);

const PatientWebPrescription = lazy(() =>
  import('./patient/patient-web-prescription')
);

const PatientWebLabResults = lazy(() =>
  import('./patient/patient-web-lab-results')
);

const PatientWebImagingResults = lazy(() =>
  import('./patient/patient-web-imaging-results')
);

const PatientWebProfile = lazy(() =>
  import('./patient/patient-web-profile')
);

export function PatientDashboardWeb() {
  const { width } = useWindowDimensions();
  const [drawerOpen, setDrawerOpen]   = useState(false);
  const [activeKey, setActiveKey]     = useState('dashboard');
  const [openThreadId, setOpenThreadId] = useState<number | null>(null);

  const isLarge  = width >= 1024;
  const isMedium = width >= 768 && width < 1024;
  const isSmall  = width < 768;

  function handleNavigate(key: string) {
    // Video consultations are joined from the appointment list.
    setActiveKey(key === 'join-consultation' ? 'my-appointments' : key);
    if (isSmall) setDrawerOpen(false);
  }

  const openThread = useCallback((threadId: number) => {
    setOpenThreadId(threadId);
    setActiveKey('messages');
  }, []);
  const clearOpenThread = useCallback(() => setOpenThreadId(null), []);

  const loader = <View style={styles.loader}><ActivityIndicator size="large" color="#0D9488" /></View>;

  function renderCenter() {
    if (activeKey === 'book-appointment') {
      return (
        <>
          <Suspense fallback={loader}>
            <PatientWebAppointment onBack={() => setActiveKey('dashboard')} />
          </Suspense>
          {isLarge && <PatientRightSidebar onNavigate={handleNavigate} />}
        </>
      );
    }
    if (activeKey === 'my-appointments') {
      return (
        <>
          <Suspense fallback={loader}>
            <PatientWebAppointmentStatus />
          </Suspense>
          {isLarge && <PatientRightSidebar onNavigate={handleNavigate} />}
        </>
      );
    }
    if (activeKey === 'consultation-history') {
      return (
        <>
          <Suspense fallback={loader}>
            <PatientWebConsultationHistory onBack={() => setActiveKey('dashboard')} />
          </Suspense>
          {isLarge && <PatientRightSidebar onNavigate={handleNavigate} />}
        </>
      );
    }
    if (activeKey === 'medical-records') {
      return (
        <>
          <Suspense fallback={loader}>
            <PatientWebMedicalRecords onBack={() => setActiveKey('dashboard')} />
          </Suspense>
          {isLarge && <PatientRightSidebar onNavigate={handleNavigate} />}
        </>
      );
    }
    if (activeKey === 'prescriptions') {
      return (
        <>
          <Suspense fallback={loader}>
            <PatientWebPrescription onBack={() => setActiveKey('dashboard')} />
          </Suspense>
          {isLarge && <PatientRightSidebar onNavigate={handleNavigate} />}
        </>
      );
    }
    if (activeKey === 'lab-results') {
      return (
        <>
          <Suspense fallback={loader}>
            <PatientWebLabResults onBack={() => setActiveKey('dashboard')} />
          </Suspense>
          {isLarge && <PatientRightSidebar onNavigate={handleNavigate} />}
        </>
      );
    }
    if (activeKey === 'imaging-results') {
      return (
        <>
          <Suspense fallback={loader}>
            <PatientWebImagingResults onBack={() => setActiveKey('dashboard')} />
          </Suspense>
          {isLarge && <PatientRightSidebar onNavigate={handleNavigate} />}
        </>
      );
    }
    if (activeKey === 'messages') {
      return (
        <Suspense fallback={loader}>
          <MessagesScreen
            mode="PATIENT"
            theme={PATIENT_THEME}
            openThreadId={openThreadId}
            onOpenThreadHandled={clearOpenThread}
          />
        </Suspense>
      );
    }
    if (activeKey === 'notifications') {
      return (
        <Suspense fallback={loader}>
          <NotificationList theme={PATIENT_THEME} onOpenThread={openThread} />
        </Suspense>
      );
    }
    if (activeKey === 'my-proxies') {
      return (
        <Suspense fallback={loader}>
          <PatientWebCaregiverAccess onBack={() => setActiveKey('dashboard')} />
        </Suspense>
      );
    }
    if (activeKey === 'profile-settings') {
      return (
        <Suspense fallback={loader}>
          <PatientWebProfile />
        </Suspense>
      );
    }
    return (
      <>
        <PatientMainDashboardArea
          onBookAppointment={() => setActiveKey('book-appointment')}
          onViewConsultations={() => setActiveKey('consultation-history')}
          onViewPrescriptions={() => setActiveKey('prescriptions')}
          onNavigate={handleNavigate}
        />
        {isLarge && <PatientRightSidebar onNavigate={handleNavigate} />}
      </>
    );
  }

  return (
    <View style={styles.container}>
      {!isSmall && (
        <PatientLeftSidebar
          collapsed={isMedium}
          activeKey={activeKey}
          onNavigate={handleNavigate}
        />
      )}

      {isSmall && drawerOpen && (
        <View style={styles.drawerOverlay}>
          <PatientLeftSidebar
            collapsed={false}
            activeKey={activeKey}
            onNavigate={handleNavigate}
            onClose={() => setDrawerOpen(false)}
          />
        </View>
      )}

      <View style={styles.main}>
        <PatientTopNavbar
          showMenuButton={isSmall}
          onMenuPress={() => setDrawerOpen(true)}
          onProfilePress={() => handleNavigate('profile-settings')}
          onOpenThread={openThread}
        />
        <View style={styles.body}>
          {renderCenter()}
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    flexDirection: 'row',
    backgroundColor: '#F9FAFB',
  },
  main: {
    flex: 1,
    flexDirection: 'column',
    minWidth: 0,
  },
  body: {
    flex: 1,
    flexDirection: 'row',
    minWidth: 0,
  },
loader: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  drawerOverlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    bottom: 0,
    zIndex: 100,
    shadowColor: '#000',
    shadowOffset: { width: 2, height: 0 },
    shadowOpacity: 0.2,
    shadowRadius: 8,
    elevation: 10,
  },
});
