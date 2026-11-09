import { Suspense, lazy, useCallback, useRef, useState } from 'react';
import { ActivityIndicator, StyleSheet, useWindowDimensions, View } from 'react-native';
import { apiErrorMessage } from '@/hooks/use-appointments';
import { useCurrentUser } from '@/hooks/use-current-user';
import { createThread } from '@/lib/communications/api';
import { showMessage } from '@/lib/ui/dialogs';
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
  const { user } = useCurrentUser();
  const messagingDoctor = useRef(false);

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
  // A "Caregiver approval needed" notification opens the caregiver access screen.
  const openCaregiverAccess = useCallback(() => setActiveKey('my-proxies'), []);

  // "Message Dr X" from an appointment: opens the private conversation with that doctor. The API
  // returns the existing private thread for this pair (200) or creates it (201), so repeated
  // presses never make duplicates.
  const messageDoctor = useCallback(async (doctorSystemId: string, doctorName: string) => {
    if (messagingDoctor.current) return;
    if (!user?.system_id) { setActiveKey('messages'); return; }
    messagingDoctor.current = true;
    try {
      const thread = await createThread({
        patientSystemId: user.system_id,
        type:            'PRIVATE',
        memberSystemIds: [doctorSystemId],
      });
      openThread(thread.threadId);
    } catch (e) {
      setActiveKey('messages');
      showMessage(
        'Could not open the conversation',
        apiErrorMessage(e, `We could not open a conversation with ${doctorName}. You can start one from Messages.`),
      );
    } finally {
      messagingDoctor.current = false;
    }
  }, [user?.system_id, openThread]);

  const loader = <View style={styles.loader}><ActivityIndicator size="large" color="#0F766E" /></View>;

  function renderCenter() {
    if (activeKey === 'book-appointment') {
      return (
        <>
          <Suspense fallback={loader}>
            <PatientWebAppointment onBack={() => setActiveKey('dashboard')} />
          </Suspense>
          {isLarge && <PatientRightSidebar onNavigate={handleNavigate} onMessageDoctor={messageDoctor} />}
        </>
      );
    }
    if (activeKey === 'my-appointments') {
      return (
        <>
          <Suspense fallback={loader}>
            <PatientWebAppointmentStatus onMessageDoctor={messageDoctor} />
          </Suspense>
          {isLarge && <PatientRightSidebar onNavigate={handleNavigate} onMessageDoctor={messageDoctor} />}
        </>
      );
    }
    if (activeKey === 'consultation-history') {
      return (
        <>
          <Suspense fallback={loader}>
            <PatientWebConsultationHistory onBack={() => setActiveKey('dashboard')} />
          </Suspense>
          {isLarge && <PatientRightSidebar onNavigate={handleNavigate} onMessageDoctor={messageDoctor} />}
        </>
      );
    }
    if (activeKey === 'medical-records') {
      return (
        <>
          <Suspense fallback={loader}>
            <PatientWebMedicalRecords onBack={() => setActiveKey('dashboard')} />
          </Suspense>
          {isLarge && <PatientRightSidebar onNavigate={handleNavigate} onMessageDoctor={messageDoctor} />}
        </>
      );
    }
    if (activeKey === 'prescriptions') {
      return (
        <>
          <Suspense fallback={loader}>
            <PatientWebPrescription onBack={() => setActiveKey('dashboard')} />
          </Suspense>
          {isLarge && <PatientRightSidebar onNavigate={handleNavigate} onMessageDoctor={messageDoctor} />}
        </>
      );
    }
    if (activeKey === 'lab-results') {
      return (
        <>
          <Suspense fallback={loader}>
            <PatientWebLabResults onBack={() => setActiveKey('dashboard')} />
          </Suspense>
          {isLarge && <PatientRightSidebar onNavigate={handleNavigate} onMessageDoctor={messageDoctor} />}
        </>
      );
    }
    if (activeKey === 'imaging-results') {
      return (
        <>
          <Suspense fallback={loader}>
            <PatientWebImagingResults onBack={() => setActiveKey('dashboard')} />
          </Suspense>
          {isLarge && <PatientRightSidebar onNavigate={handleNavigate} onMessageDoctor={messageDoctor} />}
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
          <NotificationList theme={PATIENT_THEME} onOpenThread={openThread} onOpenCaregiverAccess={openCaregiverAccess} />
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
        {isLarge && <PatientRightSidebar onNavigate={handleNavigate} onMessageDoctor={messageDoctor} />}
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
          onOpenCaregiverAccess={openCaregiverAccess}
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
