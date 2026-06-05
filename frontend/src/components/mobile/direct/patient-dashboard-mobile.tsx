import { StyleSheet, View } from "react-native";
import { SafeAreaProvider } from "react-native-safe-area-context";
import { PatientBottomNavbar } from "./patient/patient-bottom-navbar";
import { PatientMainDashboardAreaMobile } from "./patient/patient-main-dashboard-area";
import { PatientTopNavbar } from "./patient/patient-topnavbar";

export function PatientDashboardMobile() {
  return (
    <SafeAreaProvider>
      <View style={styles.container}>
        <PatientTopNavbar />
        <PatientMainDashboardAreaMobile />
        <PatientBottomNavbar />
      </View>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#F9FAFB",
  },
});
