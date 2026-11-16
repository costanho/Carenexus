import { zodResolver } from '@hookform/resolvers/zod';
import { type Href, useRouter } from 'expo-router';
import { useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { StyleSheet, View } from 'react-native';
import {
  AccountPage, PageLink, PasswordChecklist, SignOutButton, useRequiredSession,
} from '@/app/auth/forgot-password/web/forgot-password-web';
import { Button, FormError, LoadingView, space, TextField } from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { changePassword } from '@/lib/auth/auth-flows-api';
import { updateSessionFlags } from '@/lib/auth/auth.storage';
import { requiredAccountStep, ROLE_HOME } from '@/lib/auth/auth.types';
import {
  type ChangePasswordForm, changePasswordSchema, CURRENT_PASSWORD_INCORRECT_MESSAGE, PASSWORD_UNCHANGED_MESSAGE,
} from '@/lib/validation/account-recovery';

const TITLE = 'Change password';

/**
 * /auth/change-password: the signed-in person replaces their password. The dashboard guard sends a session
 * here while its password was issued by someone else (flag `mustChangePassword`) and no dashboard opens until
 * the change is made; the page also works for someone who simply opens it.
 */
export default function ChangePasswordWeb() {
  const router  = useRouter();
  const session = useRequiredSession();

  const [formError, setFormError] = useState<string | null>(null);
  const [visible, setVisible]     = useState(false);
  const [done, setDone]           = useState(false);

  const { control, handleSubmit, setError } = useForm<ChangePasswordForm>({
    resolver:      zodResolver(changePasswordSchema),
    defaultValues: { currentPassword: '', newPassword: '', confirmPassword: '' },
  });
  const typed = useWatch({ control, name: 'newPassword' });

  // changePassword names this device's session as the one to keep, so the person stays signed in here.
  const change = useAction(
    (values: ChangePasswordForm) => changePassword(values.currentPassword, values.newPassword),
    { errorMessage: 'We could not change your password. Please try again.' },
  );

  if (session.status !== 'signed-in') {
    return (
      <AccountPage title={TITLE} heading="Change your password">
        <LoadingView compact />
      </AccountPage>
    );
  }

  const { user, flags } = session;
  const forced = flags.mustChangePassword === true;

  const submit = handleSubmit(async values => {
    setFormError(null);
    const result = await change.run(values);
    if (!result.ok) {
      const error = result.error;
      if (!error) return;
      // The server marks these two with a code (and status 400, never 401); each belongs to one field.
      if (error.code === 'CURRENT_PASSWORD_INCORRECT') {
        setError('currentPassword', { type: 'server', message: CURRENT_PASSWORD_INCORRECT_MESSAGE }, { shouldFocus: true });
      } else if (error.code === 'PASSWORD_UNCHANGED') {
        setError('newPassword', { type: 'server', message: PASSWORD_UNCHANGED_MESSAGE }, { shouldFocus: true });
      } else if (error.status === 0) {
        // No answer arrived, so the change may or may not have been made.
        setFormError(
          'We could not confirm the change. Check your connection and try again. If your current password is '
          + 'then refused, the change already went through and your new password is the one to use.',
        );
      } else {
        setFormError(applyFieldErrors(error, setError, ['currentPassword', 'newPassword']));
      }
      return;
    }
    // The server reports the cleared flag only from the next sign-in or refresh, so it is cleared here first;
    // otherwise the dashboard guard would send the person straight back to this page.
    const next = await updateSessionFlags({ mustChangePassword: false });
    if (forced) router.replace((requiredAccountStep(next) ?? ROLE_HOME[user.role]) as Href);
    else setDone(true);
  });

  if (done) {
    return (
      <AccountPage
        title={TITLE}
        heading="Your password has been changed"
        intro="Use your new password the next time you sign in. Other devices signed in to your account have been signed out."
        status="success"
        role={user.role}
      >
        <Button
          label="Back to dashboard"
          fullWidth
          onPress={() => router.replace(ROLE_HOME[user.role] as Href)}
        />
      </AccountPage>
    );
  }

  return (
    <AccountPage
      title={TITLE}
      heading={forced ? 'Choose a new password' : 'Change your password'}
      intro={forced
        ? 'Your current password was set up for you by someone else. Choose one that only you know before you continue.'
        : 'Enter your current password, then the new one. Other devices signed in to your account will be signed out.'}
      icon="lock-closed-outline"
      role={user.role}
      footer={(
        <>
          {forced ? null : <PageLink href={ROLE_HOME[user.role]} label="Back to dashboard" replace />}
          <SignOutButton />
        </>
      )}
    >
      <TextField
        label={forced ? 'Current (temporary) password' : 'Current password'}
        control={control}
        name="currentPassword"
        required
        secure={!visible}
        autoComplete="current-password"
        autoCapitalize="none"
        onSubmit={() => { void submit(); }}
        disabled={change.pending}
      />
      <TextField
        label="New password"
        control={control}
        name="newPassword"
        required
        secure={!visible}
        autoComplete="new-password"
        autoCapitalize="none"
        onSubmit={() => { void submit(); }}
        disabled={change.pending}
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
        disabled={change.pending}
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
        label={forced ? 'Save password and continue' : 'Change password'}
        fullWidth
        loading={change.pending}
        onPress={() => { void submit(); }}
      />
    </AccountPage>
  );
}

const styles = StyleSheet.create({
  toggle: { marginTop: -space.sm, alignItems: 'flex-start' },
});
