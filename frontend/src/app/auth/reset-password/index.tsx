import ResetPasswordWeb from './web/reset-password-web';

// The address of this page is /auth/reset-password because of this file: emailed reset and invitation links point to it (?token=).
// The screen itself lives in web/reset-password-web.tsx and is the same on native.
export default function ResetPasswordRoute() {
  return <ResetPasswordWeb />;
}
