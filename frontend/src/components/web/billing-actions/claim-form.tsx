import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import {
  Button, colors, type DialogCloseReason, fontSize, FormActions, FormError, Icon, Modal, NoAccessView, radius, space,
  TextField,
} from '@/components/web/ui';
import { UploadDocumentButton } from '@/components/web/documents';
import { useAction } from '@/hooks/use-action';
import { usePaymentMethods } from '@/hooks/use-payment-methods';
import type { ApiError } from '@/lib/api/errors';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { useIdempotencyKey } from '@/lib/api/idempotency';
import { submitClaim } from '@/lib/billing/api';
import type { InsuranceClaim, Invoice } from '@/lib/billing/types';
import type { DocumentDto } from '@/lib/documents/types';
import { formatMoney } from '@/lib/format/money';
import { confirmAction } from '@/lib/ui/dialogs';
import {
  CLAIM_MAX_DOCUMENTS, claimFormSchema, type ClaimFormValues, moneyInputValue, toSubmitClaimRequest,
} from '@/lib/validation/billing';
import { inProgressMessage } from './rules';

// The Submit claim dialog (api/be-billing-claims.md 5.1). The insurer details are always typed in: saved insurance
// methods never return their policy or member number, so only the insurer name can be filled in from one.
// Attachments are uploaded first into the patient's record (linkType INSURANCE_CLAIM, no linkId) and their ids
// sent with the claim. One Idempotency-Key per dialog open, reused when the same claim is retried.

export interface ClaimFormProps {
  /** Mounts the dialog. Each opening starts with a fresh form and a fresh Idempotency-Key. */
  open:            boolean;
  invoice:         Invoice;
  /** The invoice's patient: the patient's own id, or the loved one's for a caregiver. */
  patientSystemId: string;
  /** Shown in the upload dialog so a caregiver sees whose record receives the file. */
  patientName?:    string | null;
  /** False hides the form (the caller should not open the dialog then). */
  canClaim:        boolean;
  /** Shows the attach control (patient; caregiver who may also manage documents). */
  canAttach:       boolean;
  onClose:         () => void;
  /** The claim was submitted (or a retried submission answered with the stored claim). */
  onSubmitted:     (claim: InsuranceClaim) => void;
  /** The server refused the claim in a way that means the invoice on screen is out of date. */
  onChanged?:      () => void;
  /** The server answered 403 (a caregiver whose access changed). */
  onForbidden?:    () => void;
}

const FORM_FIELDS = {
  insuranceProvider: 'insuranceProvider',
  policyNumber:      'policyNumber',
  memberNumber:      'memberNumber',
  claimAmount:       'claimAmount',
  // documentIds has no input of its own: its errors are shown above the form.
} as const;

interface Attachment {
  documentId: number;
  title:      string;
}

export function ClaimForm(props: ClaimFormProps) {
  if (!props.open) return null;
  return <ClaimFormBody {...props} />;
}

function ClaimFormBody({
  invoice, patientSystemId, patientName, canClaim, canAttach, onClose, onSubmitted, onChanged, onForbidden,
}: ClaimFormProps) {
  const currency = invoice.currency;
  const outstanding = invoice.outstanding;
  const schema = useMemo(() => claimFormSchema(outstanding, currency), [outstanding, currency]);
  const { control, handleSubmit, setError, setValue, formState: { isDirty } } = useForm<ClaimFormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      insuranceProvider: '',
      policyNumber:      '',
      memberNumber:      '',
      claimAmount:       moneyInputValue(outstanding),
      documentIds:       [],
    },
  });
  const insurer = useWatch({ control, name: 'insuranceProvider' });

  const onForbiddenRef = useRef(onForbidden);
  onForbiddenRef.current = onForbidden;

  // Saved insurance policies, to fill in the insurer name.
  const methods = usePaymentMethods(patientSystemId, { enabled: canClaim });
  const methodsForbidden = methods.errorStatus === 403;
  useEffect(() => {
    if (methodsForbidden) onForbiddenRef.current?.();
  }, [methodsForbidden]);
  const insurers = useMemo(() => {
    const names = new Map<string, string>();
    for (const m of methods.data ?? []) {
      const name = (m.insurerName ?? m.provider ?? '').trim();
      if (m.type === 'INSURANCE' && name && !names.has(name.toLowerCase())) names.set(name.toLowerCase(), name);
    }
    return Array.from(names.values());
  }, [methods.data]);

  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const applyAttachments = (next: Attachment[]) => {
    setAttachments(next);
    setValue('documentIds', next.map(a => a.documentId), { shouldDirty: true, shouldValidate: true });
  };
  const addAttachment = (doc: DocumentDto) => {
    if (attachments.some(a => a.documentId === doc.documentId) || attachments.length >= CLAIM_MAX_DOCUMENTS) return;
    const title = doc.title?.trim() || doc.originalFilename?.trim() || `Document ${doc.documentId}`;
    applyAttachments([...attachments, { documentId: doc.documentId, title }]);
  };
  const removeAttachment = (documentId: number) => {
    applyAttachments(attachments.filter(a => a.documentId !== documentId));
  };

  const key = useIdempotencyKey();
  const [formError, setFormError] = useState<string | null>(null);
  const submitAction = useAction(
    (values: ClaimFormValues) => submitClaim(invoice.invoiceId, toSubmitClaimRequest(values), key.current()),
    { errorMessage: 'We could not submit the claim. Please try again.' },
  );
  const submitting = submitAction.pending;

  const showFailure = (error: ApiError) => {
    if (error.status === 403) onForbidden?.();
    if (error.status === 404 || error.status === 409) onChanged?.();
    const busy = inProgressMessage(error, 'This claim is still being submitted.');
    setFormError(busy ?? applyFieldErrors(error, setError, FORM_FIELDS));
  };

  const submit = handleSubmit(async values => {
    setFormError(null);
    const result = await submitAction.run(values);
    if (result.ok) {
      key.renew();
      onSubmitted(result.data);
    } else if (result.error) {
      showFailure(result.error);
    }
  });

  const close = (reason: DialogCloseReason) => {
    // Escape also closes a select or file popup, so typed details are not thrown away without asking.
    if (reason === 'escape' && (isDirty || attachments.length > 0)) {
      void confirmAction('Discard this claim?', 'What you entered will be lost.', 'Discard', true)
        .then(ok => { if (ok) onClose(); });
      return;
    }
    onClose();
  };

  if (!canClaim) {
    return (
      <Modal open title={`Insurance claim for ${invoice.invoiceNo}`} onClose={() => onClose()}>
        <NoAccessView compact message="You can't submit an insurance claim for this invoice." />
      </Modal>
    );
  }

  const full = attachments.length >= CLAIM_MAX_DOCUMENTS;

  return (
    <Modal
      open
      title={`Insurance claim for ${invoice.invoiceNo}`}
      onClose={close}
      dismissOnBackdrop={false}
      busy={submitting}
      footer={(
        <FormActions
          submitLabel="Submit claim"
          submitIcon="shield-checkmark-outline"
          onSubmit={() => { void submit(); }}
          onCancel={onClose}
          submitting={submitting}
        />
      )}
    >
      <View style={styles.summary}>
        <Text style={styles.meta}>Balance due</Text>
        <Text style={styles.amount}>{formatMoney(outstanding, currency)}</Text>
        <Text style={styles.meta}>
          The clinic sends the claim to the insurer. An approved amount is paid onto this invoice.
        </Text>
      </View>
      <FormError message={formError} />

      <TextField
        label="Insurer"
        control={control}
        name="insuranceProvider"
        required
        disabled={submitting}
        maxLength={150}
        placeholder="For example Jubilee Health"
      />
      {insurers.length > 0 ? (
        <View style={styles.row} role="group" aria-label="Saved insurers">
          {insurers.filter(name => name !== insurer.trim()).map(name => (
            <Button
              key={name}
              label={`Use ${name}`}
              variant="ghost"
              size="sm"
              disabled={submitting}
              accessibilityLabel={`Use ${name} as the insurer`}
              onPress={() => setValue('insuranceProvider', name, { shouldDirty: true, shouldValidate: true })}
            />
          ))}
        </View>
      ) : null}

      <View style={styles.pair}>
        <View style={styles.cell}>
          <TextField
            label="Policy number"
            control={control}
            name="policyNumber"
            required
            disabled={submitting}
            maxLength={100}
            autoCapitalize="characters"
          />
        </View>
        <View style={styles.cell}>
          <TextField
            label="Member number (optional)"
            control={control}
            name="memberNumber"
            disabled={submitting}
            maxLength={100}
            autoCapitalize="characters"
          />
        </View>
      </View>

      <TextField
        label={`Amount to claim (${currency})`}
        control={control}
        name="claimAmount"
        disabled={submitting}
        inputMode="decimal"
        hint={`At most ${formatMoney(outstanding, currency)}, the balance due. Leave it empty to claim the whole balance.`}
      />

      <View style={styles.attachments}>
        <Text style={styles.label}>Supporting documents</Text>
        {attachments.length > 0 ? (
          <View role="list" aria-label="Attached documents" style={styles.list}>
            {attachments.map(a => (
              <View key={a.documentId} role="listitem" style={styles.attachment}>
                <Icon name="document-attach-outline" size={18} color={colors.textMuted} />
                <Text style={styles.attachmentTitle} numberOfLines={2}>{a.title}</Text>
                <Button
                  label="Remove"
                  variant="ghost"
                  size="sm"
                  disabled={submitting}
                  accessibilityLabel={`Remove ${a.title} from the claim`}
                  onPress={() => removeAttachment(a.documentId)}
                />
              </View>
            ))}
          </View>
        ) : null}
        {canAttach ? (
          <>
            <UploadDocumentButton
              patientSystemId={patientSystemId}
              patientName={patientName}
              linkType="INSURANCE_CLAIM"
              label={attachments.length > 0 ? 'Attach another document' : 'Attach document'}
              variant="secondary"
              size="sm"
              disabled={submitting || full}
              onUploaded={addAttachment}
            />
            <Text style={styles.meta}>
              {full
                ? `You have attached the most documents a claim can carry (${CLAIM_MAX_DOCUMENTS}).`
                : `Optional: an insurance card, a pre-authorisation letter or a referral, up to ${CLAIM_MAX_DOCUMENTS} documents. Uploaded files are kept in the documents of the record; removing one here only leaves it out of this claim.`}
            </Text>
          </>
        ) : (
          <Text style={styles.meta}>
            You can submit the claim without documents. Attaching documents needs permission to manage your loved
            one's documents.
          </Text>
        )}
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  row:  { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs },
  pair: { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  cell: { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0 },

  summary: { gap: space.xs, padding: space.md, borderRadius: radius.md, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.bgSubtle },
  amount:  { fontSize: fontSize.xl, fontWeight: '700', color: colors.text },
  label:   { fontSize: fontSize.md, fontWeight: '600', color: colors.text },
  meta:    { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },

  attachments:     { gap: space.sm, minWidth: 0 },
  list:            { gap: space.xs },
  attachment:      { flexDirection: 'row', alignItems: 'center', gap: space.sm, paddingVertical: space.xs, minWidth: 0 },
  attachmentTitle: { flex: 1, minWidth: 0, fontSize: fontSize.md, color: colors.text },
});
