import { Platform } from "react-native";
import LoginMobile from "./auth/login/mobile/login-mobile";
import LoginScreenWeb from "./auth/login/web/login-web";

export default function HomeScreenWeb() {
  return Platform.OS === "web" ? <LoginScreenWeb /> : <LoginMobile />;
}
