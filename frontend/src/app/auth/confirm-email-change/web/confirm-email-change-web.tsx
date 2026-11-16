import { type Href, useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import {
  AccountPage, HomeLink, PageText, SIGN_IN_ROUTE, useAccountSession, useLinkToken,
} from '@/app/auth/forgot-password/web/forgot-password-web';
import { Button, ErrorView, LoadingView } from '@/components/web/ui';
import { apiError } from '@/lib/api/errors';
import { confirmEmailChangeOnce } from '@/lib/auth/auth-flows-api';
import { EMAIL_TOKEN_MAX_LENGTH } from '@/lib/validation/account-recovery';

const TITLE = 'Confirm email change';

type Phase =
  | { step: 'checking' }
  /** The sign-in email is now `email`; every session of the account has ended. */
  | { step: 'changed'; email: string }
  /** Another account took the address after the change was requested; nothing was changed. */
  | { step: 'taken' }
  /** The link has no token, or the server refused it: unknown, used, cancelled, replaced or expired. */
  | { step: 'invalid' }
  /** No verdict on the link (no connection, server error): the same request can be sent again. */
  | { step: 'failed'; message: string };

/**
 * /auth/confirm-email-change?token=: applies a requested change of sign-in email as soon as the page opens.
 * The token works once, so the request is sent a single time per page load. A successful change signs the
 * account out everywhere, this browser included, and the person signs in again with the new address.
 */
export default function ConfirmEmailChangeWeb() {
  const router  = useRouter();
  const session = useAccountSession();
  const token   = useLinkToken(EMAIL_TOKEN_MAX_LENGTH);
  const [phase, setPhase]     = useState<Phase>({ step: 'checking' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (token === undefined) return;               // the link has not been read yet
    if (token === null) { setPhase({ step: 'invalid' }); return; }
    let current = true;
    setPhase({ step: 'checking' });
    // On success the session stored in this browser is discarded with the request (see confirmEmailChangeOnce).
    confirmEmailChangeOnce(token)
      .then(result => { if (current) setPhase({ step: 'changed', email: result.email }); })
      .catch(e => {
        if (!current) return;
        const error = apiError(e, 'We could not confirm the change. Please try again.');
        if (error.status === 409) setPhase({ step: 'taken' });
        else if (error.status === 400) setPhase({ step: 'invalid' });
        else setPhase({ step: 'failed', message: error.message });
      });
    return () => { current = false; };
  }, [token, attempt]);

  const footer = <HomeLink session={session} signedOutLabel="Go to sign in" />;

  if (phase.step === 'checking') {
    return (
      <AccountPage title={TITLE} heading="Confirming your new email address">
        <LoadingView label="Confirming your new email address…" compact />
      </AccountPage>
    );
  }

  if (phase.step === 'failed') {
    return (
      <AccountPage title={TITLE} heading="We could not confirm your new email address" footer={footer}>
        <ErrorView message={phase.message} onRetry={() => setAttempt(n => n + 1)} compact />
      </AccountPage>
    );
  }

  if (phase.step === 'changed') {
    return (
      <AccountPage
        title={TITLE}
        heading="Your email address has been changed"
        intro={phase.email
          ? `From now on you sign in with ${phase.email}.`
          : 'From now on you sign in with your new email address.'}
        status="success"
      >
        <PageText>
          For your security you have been signed out on every device. Sign in again with your new email
          address and your usual password.
        </PageText>
        <Button
          label="Go to sign in"
          icon="log-in-outline"
          fullWidth
          onPress={() => router.replace(SIGN_IN_ROUTE as Href)}
        />
      </AccountPage>
    );
  }

  if (phase.step === 'taken') {
    return (
      <AccountPage
        title={TITLE}
        heading="That email address is no longer available"
        intro="Another account started using this address after you asked for the change, so your sign-in email has not been changed."
        status="warning"
        footer={footer}
      >
        <PageText>
          You still sign in with your current email address. To change it, open your account settings and
          request the change again with a different address.
        </PageText>
      </AccountPage>
    );
  }

  return (
    <AccountPage
      title={TITLE}
      heading="This confirmation link is invalid or has expired"
      intro="Confirmation links work once and for a limited time, and a newer request or a cancellation replaces them."
      status="warning"
      footer={footer}
    >
      <PageText>
        If you have already confirmed this change, sign in with your new email address. Otherwise sign in with
        your current address and request the change again from your account settings.
      </PageText>
    </AccountPage>
  );
}
