import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import {
  AccountPage, HomeLink, PageText, useAccountSession, useLinkToken,
} from '@/app/auth/forgot-password/web/forgot-password-web';
import { Banner, Button, ErrorView, FormError, LoadingView, TextField } from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { apiError } from '@/lib/api/errors';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { resendVerification, verifyEmailOnce } from '@/lib/auth/auth-flows-api';
import {
  EMAIL_TOKEN_MAX_LENGTH, type ResendVerificationForm, resendVerificationSchema,
} from '@/lib/validation/account-recovery';

const TITLE = 'Verify email';

type Phase =
  | { step: 'checking' }
  | { step: 'verified' }
  /** The link has no token, or the server refused it: unknown, used, replaced by a newer link, or expired. */
  | { step: 'invalid' }
  /** No verdict on the link (no connection, server error, rate limit): the same request can be sent again. */
  | { step: 'failed'; message: string };

/**
 * /auth/verify-email?token=: confirms an email address as soon as the page opens. The token works once, so
 * the request is sent a single time per page load; a link that is no longer valid offers a new one.
 */
export default function VerifyEmailWeb() {
  const session = useAccountSession();
  const token   = useLinkToken(EMAIL_TOKEN_MAX_LENGTH);
  const [phase, setPhase]     = useState<Phase>({ step: 'checking' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (token === undefined) return;               // the link has not been read yet
    if (token === null) { setPhase({ step: 'invalid' }); return; }
    let current = true;
    setPhase({ step: 'checking' });
    verifyEmailOnce(token)
      .then(() => { if (current) setPhase({ step: 'verified' }); })
      .catch(e => {
        if (!current) return;
        const error = apiError(e, 'We could not verify your email address. Please try again.');
        // Every 400 means the link cannot be used; the server does not say why.
        setPhase(error.status === 400 ? { step: 'invalid' } : { step: 'failed', message: error.message });
      });
    return () => { current = false; };
  }, [token, attempt]);

  const footer = <HomeLink session={session} signedOutLabel="Go to sign in" />;

  if (phase.step === 'checking') {
    return (
      <AccountPage title={TITLE} heading="Verifying your email address">
        <LoadingView label="Verifying your email address…" compact />
      </AccountPage>
    );
  }

  if (phase.step === 'failed') {
    return (
      <AccountPage title={TITLE} heading="We could not verify your email address" footer={footer}>
        <ErrorView message={phase.message} onRetry={() => setAttempt(n => n + 1)} compact />
      </AccountPage>
    );
  }

  if (phase.step === 'verified') {
    return (
      <AccountPage
        title={TITLE}
        heading="Your email address has been verified"
        intro="Thank you. This address is now confirmed as yours."
        status="success"
        footer={footer}
      />
    );
  }

  return (
    <AccountPage
      title={TITLE}
      heading="This verification link is invalid or has expired"
      intro="Verification links work once and for a limited time. If you have already verified your address, you can simply sign in."
      status="warning"
      footer={footer}
    >
      <ResendVerification />
    </AccountPage>
  );
}

/** Asks for a new verification link. The answer is the same whether or not the address needs one. */
function ResendVerification() {
  const [sent, setSent] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const { control, handleSubmit, setError, reset } = useForm<ResendVerificationForm>({
    resolver:      zodResolver(resendVerificationSchema),
    defaultValues: { email: '' },
  });
  const resend = useAction((email: string) => resendVerification(email), {
    errorMessage: 'We could not send a new link. Please try again.',
  });

  const submit = handleSubmit(async values => {
    setFormError(null);
    const result = await resend.run(values.email);
    if (result.ok) { setSent(true); return; }
    if (result.error) setFormError(applyFieldErrors(result.error, setError, ['email']));
  });

  if (sent) {
    return (
      <>
        <Banner
          tone="success"
          text="If that address belongs to an account that still needs verification, we have sent a new link. Only the newest link works."
        />
        <Button
          label="Use a different email address"
          variant="secondary"
          fullWidth
          onPress={() => { reset({ email: '' }); setFormError(null); setSent(false); }}
        />
      </>
    );
  }

  return (
    <>
      <PageText>Enter the email address of your account and we will send a new verification link.</PageText>
      <TextField
        label="Email address"
        control={control}
        name="email"
        required
        placeholder="you@example.com"
        inputMode="email"
        autoComplete="email"
        autoCapitalize="none"
        onSubmit={() => { void submit(); }}
        disabled={resend.pending}
      />
      <FormError message={formError} />
      <Button
        label="Send a new link"
        icon="mail-outline"
        fullWidth
        loading={resend.pending}
        onPress={() => { void submit(); }}
      />
    </>
  );
}
