import { StyleSheet, useWindowDimensions, View } from "react-native";
import { DoctorLeftSidebar } from "./doctor/doctor-left-sidebar";
import { DoctorMainDashboardArea } from "./doctor/doctor-main-dashboard-area";
import { DoctorTopNavbar } from "./doctor/doctor-top-navbar";

export function DoctorDashboardWeb() {
  const { width } = useWindowDimensions();
  const isCollapsed = width < 1024;

  return (
    <View style={styles.container}>
      <DoctorLeftSidebar collapsed={isCollapsed} />
      <View style={styles.main}>
        <DoctorTopNavbar />
        <DoctorMainDashboardArea />
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
