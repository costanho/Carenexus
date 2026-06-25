import { PatientDashboardWeb } from "@/components/web/direct/patient-dashboard-web";
import { Platform } from "react-native";
import LoginMobile from "./auth/login/mobile/login-mobile";

export default function HomeScreenWeb() {
  return Platform.OS === "web" ? <PatientDashboardWeb /> : <LoginMobile />;
}
