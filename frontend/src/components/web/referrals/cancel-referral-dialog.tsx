import { StyleSheet, Text } from 'react-native';
import { colors, fontSize, FormActions, FormError, Modal } from '@/components/web/ui';
import { useCancelReferral } from '@/hooks/use-referrals';
import { specialistName } from '@/lib/referrals/format';
import type { Referral } from '@/lib/referrals/types';

export interface CancelReferralDialogProps {
  referral: Referral;
  onClose:  () => void;
  onDone:   (updated: Referral, message: string) => void;
  /** The server refused because the referral changed meanwhile (409, 403, 404): reload what is on screen. */
  onStale:  () => void;
}

/**
 * Confirms withdrawing a pending referral (the referring doctor only). The server's refusal, for example a 409
 * because the specialist accepted it a moment ago, is shown inside the dialog.
 */
export function CancelReferralDialog({ referral, onClose, onDone, onStale }: CancelReferralDialogProps) {
  const cancel = useCancelReferral();
  const specialist = specialistName(referral);

  const submit = async () => {
    const result = await cancel.run(referral.referralId);
    if (result.ok) {
      onDone(result.data, `Referral for ${result.data.patientName} cancelled.`);
    } else if (result.error && (result.error.status === 409 || result.error.status === 403 || result.error.status === 404)) {
      onStale();
    }
  };

  return (
    <Modal
      open
      title="Cancel referral"
      size="sm"
      onClose={onClose}
      busy={cancel.pending}
      footer={(
        <FormActions
          submitLabel="Cancel referral"
          cancelLabel="Keep referral"
          onSubmit={() => { void submit(); }}
          onCancel={onClose}
          submitting={cancel.pending}
          destructive
        />
      )}
    >
      <Text style={styles.text}>
        {specialist
          ? `Withdraw the referral of ${referral.patientName} to ${specialist}?`
          : `Withdraw the referral of ${referral.patientName}?`}
      </Text>
      <Text style={styles.sub}>
        A cancelled referral cannot be reopened. To refer the patient again, create a new referral.
      </Text>
      <FormError message={cancel.error} />
    </Modal>
  );
}

const styles = StyleSheet.create({
  text: { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  sub:  { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
});
