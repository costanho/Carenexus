import ConfirmEmailChangeWeb from './web/confirm-email-change-web';

// The address of this page is /auth/confirm-email-change because of this file: emailed confirmation links point to it (?token=).
// The screen itself lives in web/confirm-email-change-web.tsx and is the same on native.
export default function ConfirmEmailChangeRoute() {
  return <ConfirmEmailChangeWeb />;
}
