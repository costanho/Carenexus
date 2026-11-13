import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { Platform, StyleSheet, Text, View } from 'react-native';
import {
  Banner, type BannerTone, Button, colors, ErrorView, fontSize, FormActions, FormError, InfoRow, LoadingView, Modal,
  radius, SectionCard, SelectField, space, StatusBadge, TextField,
} from '@/components/web/ui';
import type { ActionState } from '@/hooks/use-action';
import { useMfa } from '@/hooks/use-account-mfa';
import { copyText } from '@/lib/account/browser';
import { formatInstant, groupSecret } from '@/lib/account/format';
import type { MfaDisableRequest, MfaSetup, MfaStatus } from '@/lib/account/types';
import {
  mfaCodeSchema, type MfaCodeValues, mfaDisableSchema, type MfaDisableValues, normaliseRecoveryCode, normaliseTotpCode,
} from '@/lib/account/validation';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { accountStyles, ButtonRow, Para, SubHeading } from './account-ui';
import { QrCode } from './qr-code';
import { RecoveryCodesPanel } from './recovery-codes-panel';

interface Notice { tone: BannerTone; text: string }

/** Below this many unused recovery codes the card suggests generating a new set. */
const LOW_RECOVERY_CODES = 3;

const CODE_HINT = 'The 6-digit code your authenticator app shows right now.';

// ─── Setup: QR code, manual key, first code ──────────────────────────────────

function SetupSteps({ setup, enable, onEnabled, onCancel, onConflict, onUncertain }: {
  setup:  MfaSetup;
  enable: ActionState<[string], string[]>;
  onEnabled:   (recoveryCodes: string[]) => void;
  onCancel:    () => void;
  /** The server no longer has this setup (already turned on elsewhere, or reset by an administrator). */
  onConflict:  (message: string) => void;
  /** No answer arrived, so it is unknown whether two-factor authentication was turned on. */
  onUncertain: (message: string) => void;
}) {
  const { control, handleSubmit, reset, setError } = useForm<MfaCodeValues>({
    resolver:      zodResolver(mfaCodeSchema),
    defaultValues: { code: '' },
  });
  const [formError, setFormError] = useState<string | null>(null);
  const [copied, setCopied] = useState<Notice | null>(null);

  const onSubmit = handleSubmit(async values => {
    setFormError(null);
    const result = await enable.run(normaliseTotpCode(values.code));
    if (result.ok) { onEnabled(result.data); return; }
    const error = result.error;
    if (!error) return;
    if (error.code === 'MFA_CODE_INVALID') {
      reset({ code: '' });
      setError('code', { type: 'server', message: error.message }, { shouldFocus: true });
    } else if (error.status === 409) {
      onConflict(error.message);
    } else if (error.status === 0) {
      onUncertain(error.message);
    } else {
      setFormError(applyFieldErrors(error, setError, ['code']));
    }
  });

  const onCopyKey = async () => {
    const ok = await copyText(setup.secret);
    setCopied(ok
      ? { tone: 'success', text: 'Setup key copied.' }
      : { tone: 'danger', text: 'The key could not be copied. Type it into your app instead.' });
  };

  return (
    <View style={accountStyles.stack}>
      <SubHeading>1. Add CareNexus to your authenticator app</SubHeading>
      <Para muted>
        Open an authenticator app on your phone (for example Google Authenticator, Microsoft Authenticator or
        Authy) and scan this QR code.
      </Para>
      <QrCode value={setup.otpauthUri} label="QR code to add CareNexus to your authenticator app" />

      <Para muted>If you cannot scan the code, choose to enter a setup key in the app and type this key:</Para>
      <View style={styles.keyBox}>
        <Text style={styles.key} selectable>{groupSecret(setup.secret)}</Text>
      </View>
      <ButtonRow>
        <Button label="Copy setup key" icon="copy-outline" variant="secondary" size="sm" onPress={onCopyKey} />
      </ButtonRow>
      {copied ? <Banner tone={copied.tone} text={copied.text} onDismiss={() => setCopied(null)} /> : null}

      <SubHeading>2. Enter the code from the app</SubHeading>
      <View style={accountStyles.form}>
        <TextField
          control={control} name="code" label="6-digit code" required hint={CODE_HINT}
          inputMode="numeric" autoComplete="one-time-code" maxLength={7} disabled={enable.pending} onSubmit={onSubmit}
        />
        <FormError message={formError} />
        <FormActions
          submitLabel="Turn on two-factor authentication" onSubmit={onSubmit} submitting={enable.pending}
          onCancel={onCancel} cancelLabel="Cancel setup"
        />
      </View>
    </View>
  );
}

// ─── Dialog: new recovery codes ──────────────────────────────────────────────

function RegenerateDialog({ regenerate, onDone, onClose, onConflict }: {
  regenerate: ActionState<[string], string[]>;
  onDone:     (recoveryCodes: string[]) => void;
  onClose:    () => void;
  onConflict: (message: string) => void;
}) {
  const { control, handleSubmit, reset, setError } = useForm<MfaCodeValues>({
    resolver:      zodResolver(mfaCodeSchema),
    defaultValues: { code: '' },
  });
  const [formError, setFormError] = useState<string | null>(null);

  const onSubmit = handleSubmit(async values => {
    setFormError(null);
    const result = await regenerate.run(normaliseTotpCode(values.code));
    if (result.ok) { onDone(result.data); return; }
    const error = result.error;
    if (!error) return;
    if (error.code === 'MFA_CODE_INVALID') {
      reset({ code: '' });
      setError('code', { type: 'server', message: error.message }, { shouldFocus: true });
    } else if (error.status === 409) {
      onConflict(error.message);
    } else {
      setFormError(applyFieldErrors(error, setError, ['code']));
    }
  });

  return (
    <Modal
      open title="Generate new recovery codes" size="sm" onClose={onClose}
      dismissOnBackdrop={false} busy={regenerate.pending}
      footer={(
        <FormActions
          submitLabel="Generate new codes" onSubmit={onSubmit} submitting={regenerate.pending} onCancel={onClose}
        />
      )}
    >
      <View style={accountStyles.stack}>
        <Para>
          You get ten new recovery codes. The codes you have now stop working, including any you have not used.
        </Para>
        <TextField
          control={control} name="code" label="6-digit code" required hint={CODE_HINT}
          inputMode="numeric" autoComplete="one-time-code" maxLength={7} disabled={regenerate.pending} onSubmit={onSubmit}
        />
        <FormError message={formError} />
      </View>
    </Modal>
  );
}

// ─── Dialog: turn off ────────────────────────────────────────────────────────

const DISABLE_METHODS = [
  { value: 'code',         label: 'A code from my authenticator app' },
  { value: 'recoveryCode', label: 'One of my recovery codes' },
] as const;

function DisableDialog({ disable, onDone, onClose, onConflict }: {
  disable:    ActionState<[MfaDisableRequest], MfaStatus>;
  onDone:     () => void;
  onClose:    () => void;
  onConflict: (message: string) => void;
}) {
  const { control, handleSubmit, setError, setValue } = useForm<MfaDisableValues>({
    resolver:      zodResolver(mfaDisableSchema),
    defaultValues: { password: '', method: 'code', code: '', recoveryCode: '' },
  });
  const method = useWatch({ control, name: 'method' });
  const [formError, setFormError] = useState<string | null>(null);

  const onSubmit = handleSubmit(async values => {
    setFormError(null);
    const body: MfaDisableRequest = values.method === 'code'
      ? { password: values.password, code: normaliseTotpCode(values.code) }
      : { password: values.password, recoveryCode: normaliseRecoveryCode(values.recoveryCode) };
    const result = await disable.run(body);
    if (result.ok) { onDone(); return; }
    const error = result.error;
    if (!error) return;
    const codeField = values.method === 'code' ? 'code' : 'recoveryCode';
    if (error.code === 'MFA_CODE_INVALID') {
      setValue(codeField, '');
      setError(codeField, { type: 'server', message: error.message }, { shouldFocus: true });
    } else if (error.status === 409) {
      onConflict(error.message);
    } else if (error.status === 400 && !error.code && !error.fieldErrors) {
      // The only uncoded 400 of this endpoint is a wrong password (never a 401 on this path).
      setError('password', { type: 'server', message: error.message }, { shouldFocus: true });
    } else {
      setFormError(applyFieldErrors(error, setError, ['password', 'code', 'recoveryCode']));
    }
  });

  return (
    <Modal
      open title="Turn off two-factor authentication" size="sm" onClose={onClose}
      dismissOnBackdrop={false} busy={disable.pending}
      footer={(
        <FormActions
          submitLabel="Turn off" destructive onSubmit={onSubmit} submitting={disable.pending} onCancel={onClose}
        />
      )}
    >
      <View style={accountStyles.stack}>
        <Para>
          Without two-factor authentication your account is protected by your password alone. Your recovery
          codes are deleted.
        </Para>
        <TextField
          control={control} name="password" label="Password" required secure
          autoComplete="current-password" autoCapitalize="none" maxLength={72} disabled={disable.pending}
        />
        <SelectField
          control={control} name="method" label="Confirm with" options={DISABLE_METHODS} disabled={disable.pending}
        />
        {method === 'recoveryCode' ? (
          <TextField
            control={control} name="recoveryCode" label="Recovery code" required
            hint="For example ABCDE-FGHJK. The code is used up." autoCapitalize="characters" autoComplete="off"
            maxLength={16} disabled={disable.pending} onSubmit={onSubmit}
          />
        ) : (
          <TextField
            control={control} name="code" label="6-digit code" required hint={CODE_HINT}
            inputMode="numeric" autoComplete="one-time-code" maxLength={7} disabled={disable.pending} onSubmit={onSubmit}
          />
        )}
        <FormError message={formError} />
      </View>
    </Modal>
  );
}

// ─── Card ────────────────────────────────────────────────────────────────────

/**
 * Two-factor authentication of the signed-in account: status, setup with a QR code and a manual key, the
 * recovery codes (shown once, with copy and download), new recovery codes, and turning it off unless the
 * role must keep it.
 */
export function MfaCard() {
  const mfa = useMfa();
  const [setup, setSetup] = useState<MfaSetup | null>(null);
  const [freshCodes, setFreshCodes] = useState<string[] | null>(null);
  const [dialog, setDialog] = useState<'disable' | 'regenerate' | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);

  const status = mfa.status;

  /** The server's state differs from what the card showed: say why and show the real state. */
  const onConflict = (message: string) => {
    setSetup(null);
    setDialog(null);
    setNotice({ tone: 'warning', text: message });
    mfa.refetch();
  };

  const onStart = async () => {
    setNotice(null);
    const result = await mfa.setup.run();
    if (result.ok) { setSetup(result.data); return; }
    if (!result.error) return;
    if (result.error.status === 409) onConflict(result.error.message);
    else setNotice({ tone: 'danger', text: result.error.message });
  };

  const showCodes = (codes: string[]) => {
    setSetup(null);
    setDialog(null);
    setNotice(null);
    setFreshCodes(codes);
  };

  let body;
  if (mfa.loading) {
    body = <LoadingView compact label="Loading two-factor authentication status" />;
  } else if (!status) {
    body = <ErrorView compact message={mfa.error ?? 'Two-factor authentication status could not be loaded.'} onRetry={mfa.refetch} />;
  } else if (freshCodes) {
    body = <RecoveryCodesPanel codes={freshCodes} onDone={() => setFreshCodes(null)} />;
  } else if (status.enabled) {
    const low = status.recoveryCodesRemaining < LOW_RECOVERY_CODES;
    body = (
      <View style={accountStyles.stack}>
        <View>
          <InfoRow label="Status"><StatusBadge label="On" tone="success" /></InfoRow>
          <InfoRow label="Turned on" value={formatInstant(status.enabledAt)} emptyText="Not recorded" />
          <InfoRow label="Recovery codes left" value={`${status.recoveryCodesRemaining} of 10`} />
        </View>
        {low ? (
          <Banner
            tone="warning"
            text={status.recoveryCodesRemaining === 0
              ? 'You have no recovery codes left. Generate new ones so you can still sign in if you lose your phone.'
              : 'You are running low on recovery codes. Generate new ones so you can still sign in if you lose your phone.'}
          />
        ) : null}
        {status.required ? (
          <Para muted>
            Two-factor authentication is required for your role and cannot be turned off. To move to a new
            phone, ask an administrator to reset it; you then set it up again the next time you sign in.
          </Para>
        ) : null}
        <ButtonRow>
          <Button
            label="Generate new recovery codes" icon="key-outline" variant="secondary"
            onPress={() => { setNotice(null); mfa.regenerate.reset(); setDialog('regenerate'); }}
          />
          {!status.required ? (
            <Button
              label="Turn off" variant="danger" accessibilityLabel="Turn off two-factor authentication"
              onPress={() => { setNotice(null); mfa.disable.reset(); setDialog('disable'); }}
            />
          ) : null}
        </ButtonRow>
      </View>
    );
  } else if (setup) {
    body = (
      <SetupSteps
        setup={setup}
        enable={mfa.enable}
        onEnabled={showCodes}
        onCancel={() => { setSetup(null); setNotice(null); }}
        onConflict={onConflict}
        onUncertain={message => {
          setNotice({
            tone: 'warning',
            text: `${message} If two-factor authentication now shows as on, your recovery codes could not be shown: generate new ones.`,
          });
          mfa.refetch();
        }}
      />
    );
  } else {
    body = (
      <View style={accountStyles.stack}>
        <View>
          <InfoRow label="Status"><StatusBadge label="Off" tone="neutral" /></InfoRow>
        </View>
        <Para>
          With two-factor authentication, signing in needs your password and a 6-digit code from an
          authenticator app on your phone, so a stolen password alone is not enough.
        </Para>
        {status.required ? (
          <Banner tone="warning" text="Two-factor authentication is required for your role. Set it up now." />
        ) : null}
        <ButtonRow>
          <Button
            label="Set up two-factor authentication" icon="shield-checkmark-outline"
            onPress={onStart} loading={mfa.setup.pending}
          />
        </ButtonRow>
      </View>
    );
  }

  return (
    <SectionCard title="Two-factor authentication" subtitle="A second step when you sign in, using an authenticator app.">
      {notice ? <Banner tone={notice.tone} text={notice.text} onDismiss={() => setNotice(null)} /> : null}
      {status && mfa.error && !mfa.loading ? (
        <Banner tone="danger" text={mfa.error} action={{ label: 'Retry', onPress: mfa.refetch }} />
      ) : null}
      {body}
      {dialog === 'regenerate' ? (
        <RegenerateDialog
          regenerate={mfa.regenerate}
          onDone={showCodes}
          onClose={() => setDialog(null)}
          onConflict={onConflict}
        />
      ) : null}
      {dialog === 'disable' ? (
        <DisableDialog
          disable={mfa.disable}
          onDone={() => {
            setDialog(null);
            setNotice({ tone: 'success', text: 'Two-factor authentication is turned off. Signing in now needs your password only.' });
          }}
          onClose={() => setDialog(null)}
          onConflict={onConflict}
        />
      ) : null}
    </SectionCard>
  );
}

const styles = StyleSheet.create({
  keyBox: {
    alignSelf: 'flex-start', maxWidth: '100%', paddingHorizontal: space.md, paddingVertical: space.sm,
    backgroundColor: colors.bgSubtle, borderWidth: 1, borderColor: colors.border, borderRadius: radius.md,
  },
  key: {
    fontFamily: Platform.select({ web: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace', ios: 'Menlo', default: 'monospace' }),
    fontSize: fontSize.md, fontWeight: '600', color: colors.text, letterSpacing: 1, lineHeight: 22,
  },
});
