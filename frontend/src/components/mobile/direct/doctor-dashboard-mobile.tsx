import { StyleSheet, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { DoctorBottomNavbar } from './doctor/doctor-bottom-navbar';
import { DoctorMainDashboardAreaMobile } from './doctor/doctor-main-dashboard-area';
import { DoctorTopNavbarMobile } from './doctor/doctor-topnavbar';

export function DoctorDashboardMobile() {
  return (
    <SafeAreaProvider>
      <View style={styles.container}>
        <DoctorTopNavbarMobile />
        <DoctorMainDashboardAreaMobile />
        <DoctorBottomNavbar />
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
