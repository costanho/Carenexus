import ForgotPasswordWeb from './web/forgot-password-web';

// The address of this page is /auth/forgot-password because of this file: the sign-in screen links to it.
// The screen itself lives in web/forgot-password-web.tsx and is the same on native.
export default function ForgotPasswordRoute() {
  return <ForgotPasswordWeb />;
}
