import { StyleSheet, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { PatientProxyBottomNavbar } from './patient/patient-bottom-navbar';
import { PatientProxyMainDashboardMobile } from './patient/patient-proxy-main-dashboard';
import { PatientProxyTopNavMobile } from './patient/patient-proxy-topnav';

export function PatientProxyDashboardMobile() {
  return (
    <SafeAreaProvider>
      <View style={styles.container}>
        <PatientProxyTopNavMobile />
        <PatientProxyMainDashboardMobile />
        <PatientProxyBottomNavbar />
      </View>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#F9FAFB',
  },
});
