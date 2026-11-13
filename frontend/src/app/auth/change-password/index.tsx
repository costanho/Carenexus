import ChangePasswordWeb from './web/change-password-web';

// The address of this page is /auth/change-password because of this file: the dashboard guard sends a session that must change its password here.
// The screen itself lives in web/change-password-web.tsx and is the same on native.
export default function ChangePasswordRoute() {
  return <ChangePasswordWeb />;
}
