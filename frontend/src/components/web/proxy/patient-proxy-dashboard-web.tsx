import { StyleSheet, useWindowDimensions, View } from "react-native";
import { PatientProxyMainDashboard } from "./patient/patient-proxy-main-dashboard";
import { PatientProxySidebar } from "./patient/patient-proxy-sidebar";
import { PatientProxyTopNavbar } from "./patient/patient-proxy-topnavbar";

export function PatientProxyDashboardWeb() {
  const { width } = useWindowDimensions();
  const isCollapsed = width < 1024;

  return (
    <View style={styles.container}>
      <PatientProxySidebar collapsed={isCollapsed} />
      <View style={styles.main}>
        <PatientProxyTopNavbar />
        <PatientProxyMainDashboard />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    flexDirection: "row",
    backgroundColor: "#F9FAFB",
  },
  main: {
    flex: 1,
    flexDirection: "column",
  },
});
