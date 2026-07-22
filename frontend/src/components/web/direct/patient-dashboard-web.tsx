import { Suspense, lazy, useState } from 'react';
import { ActivityIndicator, StyleSheet, useWindowDimensions, View } from 'react-native';
import { PatientSidebar as PatientLeftSidebar } from './patient/patient-left-sidebar';
import { PatientMainDashboardArea } from './patient/patient-main-dashboard-area';
import { PatientRightSidebar } from './patient/patient-right-sidebar';
import { PatientTopNavbar } from './patient/patient-top-navbar';

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

  const isLarge  = width >= 1024;
  const isMedium = width >= 768 && width < 1024;
  const isSmall  = width < 768;

  function handleNavigate(key: string) {
    setActiveKey(key);
    if (isSmall) setDrawerOpen(false);
  }

  const loader = <View style={styles.loader}><ActivityIndicator size="large" color="#0D9488" /></View>;

  function renderCenter() {
    if (activeKey === 'book-appointment') {
      return (
        <>
          <Suspense fallback={loader}>
            <PatientWebAppointment onBack={() => setActiveKey('dashboard')} />
          </Suspense>
          {isLarge && <PatientRightSidebar />}
        </>
      );
    }
    if (activeKey === 'my-appointments') {
      return (
        <>
          <Suspense fallback={loader}>
            <PatientWebAppointmentStatus />
          </Suspense>
          {isLarge && <PatientRightSidebar />}
        </>
      );
    }
    if (activeKey === 'consultation-history') {
      return (
        <>
          <Suspense fallback={loader}>
            <PatientWebConsultationHistory onBack={() => setActiveKey('dashboard')} />
          </Suspense>
          {isLarge && <PatientRightSidebar />}
        </>
      );
    }
    if (activeKey === 'medical-records') {
      return (
        <>
          <Suspense fallback={loader}>
            <PatientWebMedicalRecords onBack={() => setActiveKey('dashboard')} />
          </Suspense>
          {isLarge && <PatientRightSidebar />}
        </>
      );
    }
    if (activeKey === 'prescriptions') {
      return (
        <>
          <Suspense fallback={loader}>
            <PatientWebPrescription onBack={() => setActiveKey('dashboard')} />
          </Suspense>
          {isLarge && <PatientRightSidebar />}
        </>
      );
    }
    if (activeKey === 'lab-results') {
      return (
        <>
          <Suspense fallback={loader}>
            <PatientWebLabResults onBack={() => setActiveKey('dashboard')} />
          </Suspense>
          {isLarge && <PatientRightSidebar />}
        </>
      );
    }
    if (activeKey === 'imaging-results') {
      return (
        <>
          <Suspense fallback={loader}>
            <PatientWebImagingResults onBack={() => setActiveKey('dashboard')} />
          </Suspense>
          {isLarge && <PatientRightSidebar />}
        </>
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
        />
        {isLarge && <PatientRightSidebar />}
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
