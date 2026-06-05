import { useState } from 'react';
import { StyleSheet, useWindowDimensions, View } from 'react-native';
import { PatientSidebar as PatientLeftSidebar } from './patient/patient-left-sidebar';
import { PatientMainDashboardArea } from './patient/patient-main-dashboard-area';
import { PatientRightSidebar } from './patient/patient-right-sidebar';
import { PatientTopNavbar } from './patient/patient-top-navbar';

export function PatientDashboardWeb() {
  const { width } = useWindowDimensions();
  const [drawerOpen, setDrawerOpen] = useState(false);

  const isLarge  = width >= 1024;
  const isMedium = width >= 768 && width < 1024;
  const isSmall  = width < 768;

  return (
    <View style={styles.container}>
      {/* Left sidebar: hidden on small, icon-only on medium, full on large */}
      {!isSmall && <PatientLeftSidebar collapsed={isMedium} />}

      {/* Drawer overlay on small screens */}
      {isSmall && drawerOpen && (
        <View style={styles.drawerOverlay}>
          <PatientLeftSidebar collapsed={false} onClose={() => setDrawerOpen(false)} />
        </View>
      )}

      <View style={styles.main}>
        <PatientTopNavbar
          showMenuButton={isSmall}
          onMenuPress={() => setDrawerOpen(true)}
        />
        <View style={styles.body}>
          <PatientMainDashboardArea />
          {isLarge && <PatientRightSidebar />}
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
