import { zodResolver } from '@hookform/resolvers/zod';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { View } from 'react-native';
import {
  type AppRole, Banner, type BannerTone, Button, ErrorView, FormActions, FormError, LoadingView, Modal, SectionCard,
  StatusBadge, TextField,
} from '@/components/web/ui';
import type { ActionState } from '@/hooks/use-action';
import { useCloseAccount, useDataExports, useDeletionRequests } from '@/hooks/use-account-data';
import { exportState, formatInstant } from '@/lib/account/format';
import { SIGN_IN_ROUTE } from '@/lib/account/session';
import type { AccountClosure, AccountRequest, PasswordReasonRequest } from '@/lib/account/types';
import {
  CLOSE_CONFIRMATION_WORD, closeAccountSchema, type CloseAccountValues, deletionRequestSchema,
  type DeletionRequestValues, optionalReason, passwordConfirmSchema, type PasswordConfirmValues, REASON_MAX_LENGTH,
} from '@/lib/account/validation';
import type { ApiError } from '@/lib/api/errors';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { accountStyles, ButtonRow, CardPart, ListRow, ListRowText, Para } from './account-ui';

interface Notice { tone: BannerTone; text: string }

const RETENTION_STATEMENT =
  'Your clinical records are retained as required for medical record keeping and are not deleted.';

const EXPORT_CONTENTS =
  'A JSON file with your account details, profile, settings, emergency contacts, your own clinical records, '
  + 'caregiver access, the messages you sent, notifications and your activity log. Available for 7 days.';

/** The three password-confirming requests answer a wrong password with 400 `PASSWORD_INCORRECT`. */
function isWrongPassword(error: ApiError): boolean {
  return error.code === 'PASSWORD_INCORRECT';
}

// ─── Dialog: request an export ───────────────────────────────────────────────

function ExportDialog({ request, onDone, onClose }: {
  request: ActionState<[string], AccountRequest>;
  onDone:  (created: AccountRequest) => void;
  onClose: () => void;
}) {
  const { control, handleSubmit, setError } = useForm<PasswordConfirmValues>({
    resolver:      zodResolver(passwordConfirmSchema),
    defaultValues: { password: '' },
  });
  const [formError, setFormError] = useState<string | null>(null);

  const onSubmit = handleSubmit(async values => {
    setFormError(null);
    const result = await request.run(values.password);
    if (result.ok) { onDone(result.data); return; }
    const error = result.error;
    if (!error) return;
    if (isWrongPassword(error)) setError('password', { type: 'server', message: error.message }, { shouldFocus: true });
    else setFormError(applyFieldErrors(error, setError, ['password']));
  });

  return (
    <Modal
      open title="Request a data export" size="sm" onClose={onClose}
      dismissOnBackdrop={false} busy={request.pending}
      footer={(
        <FormActions
          submitLabel="Prepare export" onSubmit={onSubmit} submitting={request.pending} onCancel={onClose}
        />
      )}
    >
      <View style={accountStyles.stack}>
        <Para>{EXPORT_CONTENTS}</Para>
        <Para muted>
          Preparing the file can take up to a minute. A new export replaces the previous one, which can then no
          longer be downloaded.
        </Para>
        <TextField
          control={control} name="password" label="Password" required secure hint="Enter your password to confirm it is you."
          autoComplete="current-password" autoCapitalize="none" maxLength={200} disabled={request.pending} onSubmit={onSubmit}
        />
        <FormError message={formError} />
      </View>
    </Modal>
  );
}

// ─── Dialog: request deletion ────────────────────────────────────────────────

function DeletionDialog({ request, onDone, onClose }: {
  request: ActionState<[PasswordReasonRequest], AccountRequest>;
  onDone:  () => void;
  onClose: () => void;
}) {
  const { control, handleSubmit, setError } = useForm<DeletionRequestValues>({
    resolver:      zodResolver(deletionRequestSchema),
    defaultValues: { password: '', reason: '' },
  });
  const [formError, setFormError] = useState<string | null>(null);

  const onSubmit = handleSubmit(async values => {
    setFormError(null);
    const result = await request.run({ password: values.password, ...optionalReason(values.reason) });
    if (result.ok) { onDone(); return; }
    const error = result.error;
    if (!error) return;
    if (isWrongPassword(error)) setError('password', { type: 'server', message: error.message }, { shouldFocus: true });
    else setFormError(applyFieldErrors(error, setError, ['password', 'reason']));
  });

  return (
    <Modal
      open title="Request account deletion" size="md" onClose={onClose}
      dismissOnBackdrop={false} busy={request.pending}
      footer={(
        <FormActions
          submitLabel="Send deletion request" destructive onSubmit={onSubmit} submitting={request.pending} onCancel={onClose}
        />
      )}
    >
      <View style={accountStyles.stack}>
        <Para>
          An administrator reviews your request. Nothing is deleted and your account keeps working until it is
          approved; you can withdraw the request while it is waiting.
        </Para>
        <Banner
          tone="warning"
          text={`If the request is approved, your personal details are removed from the account and you can no longer sign in. ${RETENTION_STATEMENT}`}
        />
        <TextField
          control={control} name="reason" label="Reason (optional)" multiline rows={3}
          maxLength={REASON_MAX_LENGTH} showCount disabled={request.pending}
          hint="Shared with the administrator who reviews the request."
        />
        <TextField
          control={control} name="password" label="Password" required secure hint="Enter your password to confirm it is you."
          autoComplete="current-password" autoCapitalize="none" maxLength={200} disabled={request.pending} onSubmit={onSubmit}
        />
        <FormError message={formError} />
      </View>
    </Modal>
  );
}

// ─── Dialog: close the account ───────────────────────────────────────────────

function CloseDialog({ role, close, onClose, onSignIn }: {
  role:     AppRole;
  close:    ActionState<[PasswordReasonRequest], AccountClosure>;
  onClose:  () => void;
  /** Called once the account is closed and the person has read the confirmation. */
  onSignIn: () => void;
}) {
  const { control, handleSubmit, setError } = useForm<CloseAccountValues>({
    resolver:      zodResolver(closeAccountSchema),
    defaultValues: { password: '', reason: '', confirmation: '' },
  });
  const [formError, setFormError] = useState<string | null>(null);
  const [closure, setClosure] = useState<AccountClosure | null>(null);

  const onSubmit = handleSubmit(async values => {
    setFormError(null);
    const result = await close.run({ password: values.password, ...optionalReason(values.reason) });
    if (result.ok) { setClosure(result.data); return; }
    const error = result.error;
    if (!error) return;
    if (isWrongPassword(error)) setError('password', { type: 'server', message: error.message }, { shouldFocus: true });
    else setFormError(applyFieldErrors(error, setError, ['password', 'reason']));
  });

  // The account is closed and the session is already gone: the only way on is the sign-in screen.
  if (closure) {
    return (
      <Modal
        open title="Your account is closed" size="sm" onClose={onSignIn}
        footer={<Button label="Go to sign in" onPress={onSignIn} />}
      >
        <View style={accountStyles.stack}>
          <Banner tone="info" text={closure.message} />
          <Para muted>{`Closed on ${formatInstant(closure.closedAt)}. You have been signed out on every device.`}</Para>
        </View>
      </Modal>
    );
  }

  return (
    <Modal
      open title="Close your account" size="md" onClose={onClose}
      dismissOnBackdrop={false} busy={close.pending}
      footer={(
        <FormActions
          submitLabel="Close my account" destructive onSubmit={onSubmit} submitting={close.pending} onCancel={onClose}
        />
      )}
    >
      <View style={accountStyles.stack}>
        <Para>
          Closing your account takes effect at once: you are signed out on every device and can no longer sign
          in. Only an administrator can reopen a closed account.
        </Para>
        {role === 'doctor' ? (
          <Para>You are also removed from the doctor directory, so patients can no longer book with you.</Para>
        ) : null}
        <Banner tone="warning" text={RETENTION_STATEMENT} />
        <TextField
          control={control} name="reason" label="Reason (optional)" multiline rows={3}
          maxLength={REASON_MAX_LENGTH} showCount disabled={close.pending}
        />
        <TextField
          control={control} name="password" label="Password" required secure hint="Enter your password to confirm it is you."
          autoComplete="current-password" autoCapitalize="none" maxLength={200} disabled={close.pending}
        />
        <TextField
          control={control} name="confirmation" label={`Type ${CLOSE_CONFIRMATION_WORD} to confirm`} required
          autoCapitalize="characters" autoComplete="off" maxLength={12} disabled={close.pending} onSubmit={onSubmit}
        />
        <FormError message={formError} />
      </View>
    </Modal>
  );
}

// ─── Card parts ──────────────────────────────────────────────────────────────

function ExportRow({ row, downloading, disabled, onDownload }: {
  row: AccountRequest;
  downloading: boolean;
  disabled:    boolean;
  onDownload:  () => void;
}) {
  const state = exportState(row);
  const requested = formatInstant(row.requestedAt);
  return (
    <ListRow>
      <ListRowText
        title={`Export requested ${requested}`}
        lines={[
          state === 'ready' && `Available until ${formatInstant(row.expiresAt)}`,
          state === 'preparing' && 'The file is being prepared. Check again in a moment.',
          state === 'expired' && 'This export can no longer be downloaded. Request a new one.',
        ]}
      />
      {state === 'ready' ? <StatusBadge label="Ready" tone="success" /> : null}
      {state === 'preparing' ? <StatusBadge label="Preparing" tone="info" /> : null}
      {state === 'expired' ? <StatusBadge label="Expired" tone="neutral" /> : null}
      {state === 'ready' ? (
        <Button
          label="Download" icon="download-outline" variant="secondary" size="sm"
          accessibilityLabel={`Download the export requested ${requested}`}
          onPress={onDownload} loading={downloading} disabled={disabled}
        />
      ) : null}
    </ListRow>
  );
}

/**
 * Privacy and data: export of the account's own data (request, status list, authenticated download), a request
 * to delete the account (reviewed by an administrator) and closing the account with a typed confirmation.
 */
export function PrivacyDataCard({ role }: { role: AppRole }) {
  const router = useRouter();
  const exportsState = useDataExports();
  const deletion = useDeletionRequests();
  const close = useCloseAccount();
  const [dialog, setDialog] = useState<'export' | 'deletion' | 'close' | null>(null);
  const [exportNotice, setExportNotice] = useState<Notice | null>(null);
  const [deletionNotice, setDeletionNotice] = useState<Notice | null>(null);

  const onDownload = async (row: AccountRequest) => {
    setExportNotice(null);
    const result = await exportsState.download.run(row);
    if (!result.ok && result.error) setExportNotice({ tone: 'danger', text: result.error.message });
  };

  const onWithdraw = async (requestId: number) => {
    setDeletionNotice(null);
    const result = await deletion.cancel.run(requestId);
    if (result.ok) setDeletionNotice({ tone: 'success', text: 'Your deletion request was withdrawn. Your account stays as it is.' });
    else if (result.error) setDeletionNotice({ tone: 'danger', text: result.error.message });
  };

  const latest = deletion.latest;
  const awaitingReview = latest?.status === 'REQUESTED';

  return (
    <SectionCard title="Privacy and data" subtitle="Get a copy of your data, ask for your account to be deleted, or close it.">
      <CardPart title="Download your data" first>
        <Para muted>{EXPORT_CONTENTS}</Para>
        {exportNotice ? <Banner tone={exportNotice.tone} text={exportNotice.text} onDismiss={() => setExportNotice(null)} /> : null}
        {exportsState.loading ? (
          <LoadingView compact label="Loading your data exports" />
        ) : exportsState.error && exportsState.exports.length === 0 ? (
          <ErrorView compact message={exportsState.error} onRetry={exportsState.refetch} />
        ) : (
          <View style={accountStyles.stack}>
            {exportsState.error ? (
              <Banner tone="danger" text={exportsState.error} action={{ label: 'Retry', onPress: exportsState.refetch }} />
            ) : null}
            {exportsState.exports.length === 0 ? (
              <Para muted>You have not requested an export yet.</Para>
            ) : (
              <View style={accountStyles.list} accessibilityRole="list" aria-label="Data exports">
                {exportsState.exports.map(row => (
                  <View key={row.requestId} role="listitem">
                    <ExportRow
                      row={row}
                      downloading={exportsState.downloadingId === row.requestId}
                      disabled={exportsState.download.pending && exportsState.downloadingId !== row.requestId}
                      onDownload={() => onDownload(row)}
                    />
                  </View>
                ))}
              </View>
            )}
            <ButtonRow>
              <Button
                label="Request data export" icon="document-text-outline" variant="secondary"
                onPress={() => { setExportNotice(null); exportsState.request.reset(); setDialog('export'); }}
              />
            </ButtonRow>
          </View>
        )}
      </CardPart>

      <CardPart title="Delete your account">
        <Para muted>
          {`Ask an administrator to delete your account. Until the request is approved your account keeps working. ${RETENTION_STATEMENT}`}
        </Para>
        {deletionNotice ? <Banner tone={deletionNotice.tone} text={deletionNotice.text} onDismiss={() => setDeletionNotice(null)} /> : null}
        {deletion.loading ? (
          <LoadingView compact label="Loading your deletion request" />
        ) : deletion.error && !latest ? (
          <ErrorView compact message={deletion.error} onRetry={deletion.refetch} />
        ) : (
          <View style={accountStyles.stack}>
            {deletion.error ? (
              <Banner tone="danger" text={deletion.error} action={{ label: 'Retry', onPress: deletion.refetch }} />
            ) : null}
            {latest && awaitingReview ? (
              <ListRow>
                <ListRowText
                  title={`Deletion requested ${formatInstant(latest.requestedAt)}`}
                  lines={[
                    'An administrator will review your request. You will be notified if it is declined.',
                    latest.reason ? `Your reason: ${latest.reason}` : null,
                  ]}
                />
                <StatusBadge label="Awaiting review" tone="info" />
                <Button
                  label="Withdraw request" variant="secondary" size="sm"
                  accessibilityLabel="Withdraw request to delete your account"
                  onPress={() => onWithdraw(latest.requestId)} loading={deletion.cancel.pending}
                />
              </ListRow>
            ) : null}
            {latest?.status === 'REJECTED' ? (
              <Banner
                tone="warning"
                text={`Your request of ${formatInstant(latest.requestedAt)} was declined${latest.decisionReason ? `: ${latest.decisionReason}` : '.'} You can send a new request.`}
              />
            ) : null}
            {latest?.status === 'CANCELLED' && !deletionNotice ? (
              <Para muted>{`You withdrew your last request${latest.processedAt ? ` on ${formatInstant(latest.processedAt)}` : ''}.`}</Para>
            ) : null}
            {!awaitingReview ? (
              <ButtonRow>
                <Button
                  label="Request account deletion" icon="trash-outline" variant="secondary"
                  onPress={() => { setDeletionNotice(null); deletion.request.reset(); setDialog('deletion'); }}
                />
              </ButtonRow>
            ) : null}
          </View>
        )}
      </CardPart>

      <CardPart title="Close your account">
        <Para muted>
          {`Closing takes effect at once: you are signed out everywhere and can no longer sign in. ${RETENTION_STATEMENT} An administrator can reopen a closed account.`}
        </Para>
        <ButtonRow>
          <Button
            label="Close account" icon="lock-closed-outline" variant="danger"
            onPress={() => { close.reset(); setDialog('close'); }}
          />
        </ButtonRow>
      </CardPart>

      {dialog === 'export' ? (
        <ExportDialog
          request={exportsState.request}
          onClose={() => setDialog(null)}
          onDone={created => {
            setDialog(null);
            setExportNotice(exportState(created) === 'ready'
              ? { tone: 'success', text: 'Your export is ready to download. It is available for 7 days.' }
              : { tone: 'info', text: 'Your export is being prepared. It appears in the list when it is ready.' });
          }}
        />
      ) : null}
      {dialog === 'deletion' ? (
        <DeletionDialog
          request={deletion.request}
          onClose={() => setDialog(null)}
          onDone={() => {
            setDialog(null);
            setDeletionNotice({ tone: 'success', text: 'Your deletion request was sent. An administrator will review it.' });
          }}
        />
      ) : null}
      {dialog === 'close' ? (
        <CloseDialog
          role={role}
          close={close}
          onClose={() => setDialog(null)}
          onSignIn={() => router.replace(SIGN_IN_ROUTE as never)}
        />
      ) : null}
    </SectionCard>
  );
}
