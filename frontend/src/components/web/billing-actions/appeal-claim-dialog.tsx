import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import {
  colors, type DialogCloseReason, fontSize, FormActions, FormError, Modal, radius, space, TextField,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { appealClaim } from '@/lib/billing/api';
import { claimStatusLabel, claimStatusMeaning } from '@/lib/billing/format';
import type { InsuranceClaim } from '@/lib/billing/types';
import { confirmAction } from '@/lib/ui/dialogs';
import {
  CLAIM_APPEAL_REASON_MAX_LENGTH, claimAppealSchema, type ClaimAppealFormValues,
} from '@/lib/validation/billing';

// Contests a REJECTED or PARTIAL decision (api/be-billing-claims.md 5.4). No Idempotency-Key: appealing an
// already appealed claim is a no-op on the server.

export interface AppealClaimDialogProps {
  /** The claim being appealed; null keeps the dialog closed. Each opening starts with an empty form. */
  claim:        InsuranceClaim | null;
  onClose:      () => void;
  /** The claim as the server stored it after the appeal. */
  onAppealed:   (claim: InsuranceClaim) => void;
  /** The server refused the appeal because the claim or the invoice changed meanwhile: reload them. */
  onChanged?:   () => void;
  /** The server answered 403 (a caregiver whose access changed). */
  onForbidden?: () => void;
}

export function AppealClaimDialog(props: AppealClaimDialogProps) {
  if (!props.claim) return null;
  // Keyed by claim so switching to another claim starts a fresh form.
  return <AppealBody key={props.claim.claimId} {...props} claim={props.claim} />;
}

function AppealBody({ claim, onClose, onAppealed, onChanged, onForbidden }: AppealClaimDialogProps & { claim: InsuranceClaim }) {
  const { control, handleSubmit, setError, formState: { isDirty } } = useForm<ClaimAppealFormValues>({
    resolver: zodResolver(claimAppealSchema),
    defaultValues: { reason: '' },
  });
  const [formError, setFormError] = useState<string | null>(null);
  const appeal = useAction(
    (reason: string) => appealClaim(claim.claimId, { reason }),
    { errorMessage: 'We could not send the appeal. Please try again.' },
  );

  const submit = handleSubmit(async values => {
    setFormError(null);
    const result = await appeal.run(values.reason.trim());
    if (result.ok) {
      onAppealed(result.data);
      return;
    }
    if (!result.error) return;
    if (result.error.status === 403) onForbidden?.();
    if (result.error.status === 404 || result.error.status === 409) onChanged?.();
    setFormError(applyFieldErrors(result.error, setError, ['reason']));
  });

  const close = (reason: DialogCloseReason) => {
    if (reason === 'escape' && isDirty) {
      void confirmAction('Discard this appeal?', 'What you wrote will be lost.', 'Discard', true)
        .then(ok => { if (ok) onClose(); });
      return;
    }
    onClose();
  };

  return (
    <Modal
      open
      title={`Appeal claim ${claim.claimNo}`}
      onClose={close}
      dismissOnBackdrop={false}
      busy={appeal.pending}
      footer={(
        <FormActions
          submitLabel="Send appeal"
          submitIcon="send-outline"
          onSubmit={() => { void submit(); }}
          onCancel={onClose}
          submitting={appeal.pending}
        />
      )}
    >
      <View style={styles.summary}>
        <Text style={styles.title}>{claim.insuranceProvider} · {claimStatusLabel(claim.status)}</Text>
        <Text style={styles.meta}>{claimStatusMeaning(claim)}</Text>
        {claim.rejectionReason ? (
          <Text style={styles.meta}>
            {claim.status === 'PARTIAL' ? "Reviewer's note" : 'Reason for the decision'}: {claim.rejectionReason}
          </Text>
        ) : null}
      </View>
      <FormError message={formError} />
      <TextField
        label="Why should the decision be reviewed?"
        control={control}
        name="reason"
        required
        multiline
        rows={4}
        maxLength={CLAIM_APPEAL_REASON_MAX_LENGTH}
        showCount
        disabled={appeal.pending}
        hint="For example: the pre-authorisation letter covers the full consultation fee. The clinic reviews the claim again."
      />
    </Modal>
  );
}

const styles = StyleSheet.create({
  summary: { gap: space.xs, padding: space.md, borderRadius: radius.md, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.bgSubtle },
  title:   { fontSize: fontSize.md, fontWeight: '600', color: colors.text },
  meta:    { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
});
