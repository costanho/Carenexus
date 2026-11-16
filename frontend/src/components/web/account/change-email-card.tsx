import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { View } from 'react-native';
import {
  Banner, Button, ErrorView, FormActions, FormError, InfoRow, LoadingView, SectionCard, TextField,
} from '@/components/web/ui';
import { useEmailChange } from '@/hooks/use-account-email';
import { useCurrentUser } from '@/hooks/use-current-user';
import { formatInstant } from '@/lib/account/format';
import { emailChangeSchema, type EmailChangeValues, normaliseEmail } from '@/lib/account/validation';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { accountStyles, ButtonRow, Para } from './account-ui';

const EMPTY: EmailChangeValues = { newEmail: '', currentPassword: '' };

/**
 * Changes the sign-in email. The request needs the current password; nothing changes until the link sent to
 * the new address is opened. While a request is pending it is shown here and can be cancelled.
 */
export function ChangeEmailCard() {
  const { user } = useCurrentUser();
  const email = useEmailChange();
  const { control, handleSubmit, reset, setError } = useForm<EmailChangeValues>({
    resolver:      zodResolver(emailChangeSchema),
    defaultValues: EMPTY,
  });
  const [formError, setFormError] = useState<string | null>(null);
  const [cancelled, setCancelled] = useState(false);

  const busy = email.request.pending || email.cancel.pending;

  const onSubmit = handleSubmit(async values => {
    setFormError(null);
    setCancelled(false);
    email.cancel.reset();
    const result = await email.request.run({
      currentPassword: values.currentPassword,
      newEmail:        normaliseEmail(values.newEmail),
    });
    if (result.ok) {
      reset(EMPTY);
      return;
    }
    const error = result.error;
    if (!error) return;
    if (error.code === 'INVALID_CURRENT_PASSWORD') {
      setError('currentPassword', { type: 'server', message: error.message }, { shouldFocus: true });
    } else if (error.code === 'EMAIL_UNCHANGED' || error.status === 409) {
      setError('newEmail', { type: 'server', message: error.message }, { shouldFocus: true });
    } else {
      setFormError(applyFieldErrors(error, setError, ['currentPassword', 'newEmail']));
    }
  });

  const onCancel = async () => {
    setFormError(null);
    const result = await email.cancel.run();
    if (result.ok) setCancelled(true);
  };

  const pending = email.pending;

  return (
    <SectionCard
      title="Sign-in email"
      subtitle="The address you sign in with. A change takes effect only after you open the link we send to the new address."
    >
      {email.loading ? (
        <LoadingView compact label="Loading your email settings" />
      ) : email.error && !pending ? (
        <ErrorView compact message={email.error} onRetry={email.refetch} />
      ) : (
        <View style={accountStyles.stack}>
          {user?.email ? <InfoRow label="Current email" value={user.email} /> : null}

          {pending ? (
            <View style={accountStyles.stack}>
              <Banner
                tone="info"
                icon="mail-unread-outline"
                text={`Waiting for confirmation: we sent a link to ${pending.newEmail}. Open it before ${formatInstant(pending.expiresAt)} to finish the change. Until then you keep signing in with your current email.`}
              />
              <Para muted>
                Confirming the change signs you out on every device; you then sign in with the new address.
              </Para>
              <FormError message={email.cancel.error} />
              <ButtonRow>
                <Button
                  label="Cancel request" variant="secondary" icon="close-circle-outline"
                  accessibilityLabel={`Cancel request to change your email to ${pending.newEmail}`}
                  onPress={onCancel} loading={email.cancel.pending} disabled={email.request.pending}
                />
              </ButtonRow>
            </View>
          ) : cancelled ? (
            <Banner tone="success" text="The request was cancelled. The link we sent no longer works." onDismiss={() => setCancelled(false)} />
          ) : null}

          <View style={accountStyles.form}>
            <TextField
              control={control} name="newEmail" label="New email" required
              inputMode="email" autoComplete="email" autoCapitalize="none" maxLength={150} disabled={busy}
              hint={pending ? 'Sending a new request replaces the one that is waiting.' : undefined}
            />
            <TextField
              control={control} name="currentPassword" label="Current password" required secure
              autoComplete="current-password" autoCapitalize="none" maxLength={128} disabled={busy} onSubmit={onSubmit}
            />
            <FormError message={formError} />
            <FormActions
              submitLabel={pending ? 'Send a new confirmation link' : 'Send confirmation link'}
              onSubmit={onSubmit} submitting={email.request.pending} disabled={email.cancel.pending}
            />
          </View>
        </View>
      )}
    </SectionCard>
  );
}
