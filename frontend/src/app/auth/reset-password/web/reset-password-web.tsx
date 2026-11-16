import { zodResolver } from '@hookform/resolvers/zod';
import { type Href, useRouter } from 'expo-router';
import { useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { StyleSheet, View } from 'react-native';
import {
  AccountPage, FORGOT_PASSWORD_ROUTE, HomeLink, PageLink, PageText, PasswordChecklist, SIGN_IN_ROUTE,
  useAccountSession, useLinkToken,
} from '@/app/auth/forgot-password/web/forgot-password-web';
import { Button, FormError, LoadingView, space, TextField } from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { resetPassword } from '@/lib/auth/auth-flows-api';
import { logout } from '@/lib/auth/auth.service';
import { isAuthenticated } from '@/lib/auth/auth.storage';
import {
  RESET_TOKEN_MAX_LENGTH, type ResetPasswordForm, resetPasswordSchema,
} from '@/lib/validation/account-recovery';

const TITLE = 'Reset password';

/**
 * /auth/reset-password?token=: sets a new password with the token of an emailed link. The same link format
 * is used for a password reset and for the first password of an invited account, so the wording fits both.
 */
export default function ResetPasswordWeb() {
  const router  = useRouter();
  const session = useAccountSession();
  const token   = useLinkToken(RESET_TOKEN_MAX_LENGTH);

  const [outcome, setOutcome]     = useState<'form' | 'invalid' | 'done'>('form');
  const [formError, setFormError] = useState<string | null>(null);
  const [visible, setVisible]     = useState(false);

  const { control, handleSubmit, setError } = useForm<ResetPasswordForm>({
    resolver:      zodResolver(resetPasswordSchema),
    defaultValues: { newPassword: '', confirmPassword: '' },
  });
  const typed = useWatch({ control, name: 'newPassword' });

  const save = useAction(async (linkTokenValue: string, newPassword: string) => {
    await resetPassword(linkTokenValue, newPassword);
    // The reset ended every session of the account. A session still stored in this browser is signed out
    // too, so "Go to sign in" really opens the sign-in form instead of bouncing to a dashboard that would
    // stop working within minutes. The reset itself has succeeded whatever happens here.
    try {
      if (await isAuthenticated()) await logout();
    } catch {
      // the stored session could not be cleared; it ends by itself when its access token expires
    }
  }, { errorMessage: 'We could not reset your password. Please try again.' });

  const submit = handleSubmit(async values => {
    if (!token) return;
    setFormError(null);
    // The password is sent exactly as typed: the server does not trim it either.
    const result = await save.run(token, values.newPassword);
    if (result.ok) { setOutcome('done'); return; }
    const error = result.error;
    if (!error) return;
    if (error.code === 'PASSWORD_RESET_TOKEN_INVALID' || error.fieldErrors?.token) { setOutcome('invalid'); return; }
    if (error.status === 0) {
      // No answer arrived, so the reset may or may not have happened; the link works only once.
      setFormError(
        'We could not confirm that your password was reset. Check your connection, then try signing in with '
        + 'the new password before you ask for another link.',
      );
      return;
    }
    setFormError(applyFieldErrors(error, setError, ['newPassword']));
  });

  if (token === undefined) {
    return (
      <AccountPage title={TITLE} heading="Set a new password">
        <LoadingView label="Opening your link…" compact />
      </AccountPage>
    );
  }

  if (token === null || outcome === 'invalid') {
    return (
      <AccountPage
        title={TITLE}
        heading="This link is invalid or has expired"
        intro="Password links work once and only for a short time. Ask for a new one to continue."
        status="warning"
        footer={<HomeLink session={session} />}
      >
        <Button
          label="Request a new link"
          icon="mail-outline"
          fullWidth
          onPress={() => router.replace(FORGOT_PASSWORD_ROUTE as Href)}
        />
        <PageText>
          If you were invited to CareNexus and have not set a password yet, ask the person who invited you to
          send a new invitation. A reset link cannot be sent to an account that has no password.
        </PageText>
      </AccountPage>
    );
  }

  if (outcome === 'done') {
    return (
      <AccountPage
        title={TITLE}
        heading="Your password has been set"
        intro="Sign in with your new password to continue. Any device that was signed in to this account has been signed out."
        status="success"
      >
        <Button
          label="Go to sign in"
          icon="log-in-outline"
          fullWidth
          onPress={() => router.replace(SIGN_IN_ROUTE as Href)}
        />
      </AccountPage>
    );
  }

  return (
    <AccountPage
      title={TITLE}
      heading="Set a new password"
      intro="Choose the password you will use to sign in to CareNexus."
      icon="lock-closed-outline"
      footer={(
        <>
          <PageLink href={FORGOT_PASSWORD_ROUTE} label="Request a new link" />
          <HomeLink session={session} />
        </>
      )}
    >
      <TextField
        label="New password"
        control={control}
        name="newPassword"
        required
        secure={!visible}
        autoComplete="new-password"
        autoCapitalize="none"
        onSubmit={() => { void submit(); }}
        disabled={save.pending}
      />
      <PasswordChecklist value={typed ?? ''} />
      <TextField
        label="Confirm new password"
        control={control}
        name="confirmPassword"
        required
        secure={!visible}
        autoComplete="new-password"
        autoCapitalize="none"
        onSubmit={() => { void submit(); }}
        disabled={save.pending}
      />
      <View style={styles.toggle}>
        <Button
          label={visible ? 'Hide passwords' : 'Show passwords'}
          icon={visible ? 'eye-off-outline' : 'eye-outline'}
          variant="ghost"
          size="sm"
          onPress={() => setVisible(v => !v)}
        />
      </View>
      <FormError message={formError} />
      <Button
        label="Save new password"
        fullWidth
        loading={save.pending}
        onPress={() => { void submit(); }}
      />
    </AccountPage>
  );
}

const styles = StyleSheet.create({
  toggle: { marginTop: -space.sm, alignItems: 'flex-start' },
});
