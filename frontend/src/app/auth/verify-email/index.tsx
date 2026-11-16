import VerifyEmailWeb from './web/verify-email-web';

// The address of this page is /auth/verify-email because of this file: emailed verification links point to it (?token=).
// The screen itself lives in web/verify-email-web.tsx and is the same on native.
export default function VerifyEmailRoute() {
  return <VerifyEmailWeb />;
}
