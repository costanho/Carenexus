import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import {
  Banner, colors, decorative, fontSize, FormActions, FormError, Icon, SectionCard, space, STATUS_TONES, TextField,
} from '@/components/web/ui';
import { type ChangePasswordResult, useChangePassword } from '@/hooks/use-account-password';
import { SIGN_OUT_DELAY_NOTE } from '@/lib/account/format';
import { changePasswordSchema, type ChangePasswordValues, PASSWORD_RULES } from '@/lib/account/validation';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { accountStyles } from './account-ui';

const EMPTY: ChangePasswordValues = { currentPassword: '', newPassword: '', confirmPassword: '' };

/** The password rules as a checklist that fills in while the new password is typed. */
function PasswordRules({ password }: { password: string }) {
  return (
    <View style={styles.rules} accessibilityRole="list" aria-label="Password rules">
      {PASSWORD_RULES.map(rule => {
        const met = rule.test(password);
        const fg = met ? STATUS_TONES.success.fg : colors.textMuted;
        return (
          <View
            key={rule.key}
            style={styles.rule}
            role="listitem"
            accessibilityLabel={`${rule.label}: ${met ? 'met' : 'not met yet'}`}
          >
            <Icon name={met ? 'checkmark-circle' : 'ellipse-outline'} size={14} color={fg} />
            <Text style={[styles.ruleText, { color: fg }]} {...decorative}>{rule.label}</Text>
          </View>
        );
      })}
    </View>
  );
}

function successText(result: ChangePasswordResult): string {
  return result.keptThisSession
    ? `${result.message} You stay signed in on this device; every other device was signed out. ${SIGN_OUT_DELAY_NOTE}`
    : `${result.message} Every device was signed out, this one included: you will be asked to sign in again within 15 minutes.`;
}

/**
 * Changes the signed-in user's password. The current session is kept (its id is sent as `keepSessionId`) and
 * every other session of the account is ended.
 */
export function ChangePasswordCard() {
  const change = useChangePassword();
  const { control, handleSubmit, reset, setError } = useForm<ChangePasswordValues>({
    resolver:      zodResolver(changePasswordSchema),
    defaultValues: EMPTY,
  });
  const newPassword = useWatch({ control, name: 'newPassword' }) ?? '';
  const [formError, setFormError] = useState<string | null>(null);
  const [done, setDone] = useState<ChangePasswordResult | null>(null);

  const onSubmit = handleSubmit(async values => {
    setFormError(null);
    setDone(null);
    const result = await change.run({ currentPassword: values.currentPassword, newPassword: values.newPassword });
    if (result.ok) {
      reset(EMPTY);
      setDone(result.data);
      return;
    }
    const error = result.error;
    if (!error) return; // a second submit while the first is still running
    if (error.code === 'CURRENT_PASSWORD_INCORRECT') {
      setError('currentPassword', { type: 'server', message: error.message }, { shouldFocus: true });
    } else if (error.code === 'PASSWORD_UNCHANGED') {
      setError('newPassword', { type: 'server', message: error.message }, { shouldFocus: true });
    } else if (error.status === 0) {
      // No answer arrived, so the change may or may not have been applied.
      setFormError(`${error.message} If the request did reach the server, your password has already been changed: try signing in with the new password before changing it again.`);
    } else {
      setFormError(applyFieldErrors(error, setError, ['currentPassword', 'newPassword']));
    }
  });

  return (
    <SectionCard
      title="Password"
      subtitle="Changing your password signs out every other device that is using your account."
    >
      {done ? <Banner tone="success" text={successText(done)} onDismiss={() => setDone(null)} /> : null}
      <View style={accountStyles.form}>
        <TextField
          control={control} name="currentPassword" label="Current password" required secure
          autoComplete="current-password" autoCapitalize="none" maxLength={128} disabled={change.pending}
        />
        <TextField
          control={control} name="newPassword" label="New password" required secure
          autoComplete="new-password" autoCapitalize="none" disabled={change.pending}
        />
        <PasswordRules password={newPassword} />
        <TextField
          control={control} name="confirmPassword" label="Confirm new password" required secure
          autoComplete="new-password" autoCapitalize="none" disabled={change.pending} onSubmit={onSubmit}
        />
        <FormError message={formError} />
        <FormActions submitLabel="Change password" onSubmit={onSubmit} submitting={change.pending} />
      </View>
    </SectionCard>
  );
}

const styles = StyleSheet.create({
  rules:    { gap: space.xs },
  rule:     { flexDirection: 'row', alignItems: 'center', gap: space.xs },
  ruleText: { fontSize: fontSize.xs, lineHeight: 17, flexShrink: 1 },
});
