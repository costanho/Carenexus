import AcceptTermsWeb from './web/accept-terms-web';

// The address of this page is /auth/accept-terms because of this file: the dashboard guard sends a session that must accept new terms here.
// The screen itself lives in web/accept-terms-web.tsx and is the same on native.
export default function AcceptTermsRoute() {
  return <AcceptTermsWeb />;
}
