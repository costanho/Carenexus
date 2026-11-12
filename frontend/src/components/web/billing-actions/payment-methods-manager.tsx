import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useRef, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import {
  Banner, Button, colors, type DialogCloseReason, EmptyView, ErrorView, fontSize, FormActions, FormError, Icon,
  type IoniconsName, LoadingView, Modal, NoAccessView, radius, SectionCard, SelectField, type SelectOption, space,
  StatusBadge, SwitchField, TextField,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { usePaymentMethodActions, usePaymentMethods } from '@/hooks/use-payment-methods';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { useIdempotencyKey } from '@/lib/api/idempotency';
import { addPaymentMethod } from '@/lib/billing/api';
import { formatBillingDay, SAVED_METHOD_TYPE_LABEL } from '@/lib/billing/format';
import type { PaymentMethod, SavedPaymentMethodType } from '@/lib/billing/types';
import { confirmAction } from '@/lib/ui/dialogs';
import {
  BILLING_PHONE_MAX_LENGTH, paymentMethodFormSchema, type PaymentMethodFormValues, SAVED_METHOD_TYPES,
  toCreatePaymentMethodRequest,
} from '@/lib/validation/billing';
import { inProgressMessage } from './rules';

// The patient's saved payment methods (api/be-billing-payment-methods.md 5.1 to 5.5): list, add, make default,
// remove. Responses are masked for everyone (only `displayLabel` and `lastFour` describe the instrument), and
// only the owning patient may change them: anyone else sees the list read-only.

/** The server keeps at most this many active methods per patient. */
const MAX_SAVED_METHODS = 10;

const TYPE_ICON: Record<SavedPaymentMethodType, IoniconsName> = {
  CARD:      'card-outline',
  MPESA:     'phone-portrait-outline',
  INSURANCE: 'shield-checkmark-outline',
};

const TYPE_OPTIONS: SelectOption<SavedPaymentMethodType>[] = SAVED_METHOD_TYPES.map(type => ({
  value: type,
  label: SAVED_METHOD_TYPE_LABEL[type],
}));

const FORM_FIELDS = ['type', 'provider', 'lastFour', 'mpesaPhone', 'insurerName', 'policyNumber', 'memberNumber'] as const;

export interface PaymentMethodsManagerProps {
  /** Whose methods: the patient's own id. Nothing loads until it is known. */
  patientSystemId: string | null | undefined;
  /** True for the owning patient: add, make default and remove. Anyone else sees the list read-only. */
  canManage:       boolean;
  /** Change the value to make the list load again. */
  refreshKey?:     string | number;
  /** Called after a method was added, made the default or removed. */
  onChanged?:      () => void;
  /** Called when the server refuses the list with 403. */
  onForbidden?:    () => void;
}

function MethodRow({ method, canManage, busy, onMakeDefault, onRemove }: {
  method:        PaymentMethod;
  canManage:     boolean;
  busy:          'default' | 'remove' | null;
  onMakeDefault: () => void;
  onRemove:      () => void;
}) {
  return (
    <View style={styles.row}>
      <View style={styles.iconWrap}>
        <Icon name={TYPE_ICON[method.type] ?? 'card-outline'} size={20} color={colors.textMuted} />
      </View>
      <View style={styles.rowText}>
        <View style={styles.rowTitle}>
          <Text style={styles.label}>{method.displayLabel}</Text>
          {method.isDefault ? <StatusBadge label="Default" tone="info" icon="star" /> : null}
        </View>
        <Text style={styles.meta}>
          {SAVED_METHOD_TYPE_LABEL[method.type] ?? method.type}
          {method.createdAt ? ` · Added ${formatBillingDay(method.createdAt)}` : ''}
        </Text>
      </View>
      {canManage ? (
        <View style={styles.rowActions}>
          {method.isDefault ? null : (
            <Button
              label="Make default"
              icon="star-outline"
              variant="ghost"
              size="sm"
              loading={busy === 'default'}
              disabled={busy !== null}
              accessibilityLabel={`Make ${method.displayLabel} the default`}
              onPress={onMakeDefault}
            />
          )}
          <Button
            label="Remove"
            icon="trash-outline"
            variant="ghost"
            size="sm"
            loading={busy === 'remove'}
            disabled={busy !== null}
            accessibilityLabel={`Remove ${method.displayLabel}`}
            onPress={onRemove}
          />
        </View>
      ) : null}
    </View>
  );
}

/**
 * Saved payment methods with Add (dialog), Make default and Remove (after a confirmation). Renders its own
 * loading, failed (with Retry), empty and no-access states.
 */
export function PaymentMethodsManager({
  patientSystemId, canManage, refreshKey, onChanged, onForbidden,
}: PaymentMethodsManagerProps) {
  const methods = usePaymentMethods(patientSystemId);
  const { refetch } = methods;

  const lastRefreshKey = useRef(refreshKey);
  useEffect(() => {
    if (lastRefreshKey.current === refreshKey) return;
    lastRefreshKey.current = refreshKey;
    refetch();
  }, [refreshKey, refetch]);

  const onForbiddenRef = useRef(onForbidden);
  onForbiddenRef.current = onForbidden;
  const forbidden = methods.errorStatus === 403;
  useEffect(() => {
    if (forbidden) onForbiddenRef.current?.();
  }, [forbidden]);

  const onChangedRef = useRef(onChanged);
  onChangedRef.current = onChanged;
  const actions = usePaymentMethodActions(() => { refetch(); onChangedRef.current?.(); });

  const [busy, setBusy] = useState<{ methodId: number; action: 'default' | 'remove' } | null>(null);
  const [notice, setNotice] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null);
  const [adding, setAdding] = useState(false);

  const makeDefault = async (method: PaymentMethod) => {
    setNotice(null);
    setBusy({ methodId: method.methodId, action: 'default' });
    const result = await actions.setDefault.run(method.methodId);
    setBusy(null);
    if (result.ok) {
      setNotice({ tone: 'success', text: `${method.displayLabel} is now your default payment method.` });
    } else if (result.error) {
      if (result.error.status === 404) refetch();
      setNotice({ tone: 'danger', text: result.error.message });
    }
  };

  const remove = async (method: PaymentMethod) => {
    const ok = await confirmAction(
      'Remove payment method?',
      `${method.displayLabel} will no longer be offered when you pay. Past payments made with it are kept.`,
      'Remove',
      true,
    );
    if (!ok) return;
    setNotice(null);
    setBusy({ methodId: method.methodId, action: 'remove' });
    const result = await actions.remove.run(method.methodId);
    setBusy(null);
    if (result.ok) setNotice({ tone: 'success', text: `${method.displayLabel} was removed.` });
    else if (result.error) setNotice({ tone: 'danger', text: result.error.message });
  };

  const list = methods.data ?? [];
  const full = list.length >= MAX_SAVED_METHODS;
  const addButton = canManage && !full ? (
    <Button label="Add payment method" icon="add-outline" variant="secondary" size="sm" onPress={() => { setNotice(null); setAdding(true); }} />
  ) : undefined;

  let body;
  if (!patientSystemId || (methods.loading && !methods.data)) {
    body = <LoadingView compact label="Loading saved payment methods" />;
  } else if (forbidden) {
    body = <NoAccessView compact message="You don't have access to these payment methods." />;
  } else if (!methods.data) {
    body = <ErrorView compact message={methods.error ?? 'We could not load the saved payment methods. Please try again.'} onRetry={refetch} />;
  } else if (list.length === 0) {
    body = (
      <EmptyView
        compact
        icon="wallet-outline"
        title="No saved payment methods"
        sub={canManage
          ? 'Save a card, an M-PESA number or an insurance policy to choose it quickly when you pay or claim.'
          : 'No payment methods have been saved.'}
        action={canManage ? { label: 'Add payment method', icon: 'add-outline', onPress: () => setAdding(true) } : undefined}
      />
    );
  } else {
    body = (
      <View style={styles.listWrap}>
        {methods.error ? <Banner tone="danger" text={methods.error} action={{ label: 'Retry', onPress: refetch }} /> : null}
        <View role="list" aria-label="Saved payment methods">
          {list.map((method, index) => (
            <View key={method.methodId} role="listitem" style={[styles.item, index > 0 && styles.divider]}>
              <MethodRow
                method={method}
                canManage={canManage}
                busy={busy?.methodId === method.methodId ? busy.action : null}
                onMakeDefault={() => { void makeDefault(method); }}
                onRemove={() => { void remove(method); }}
              />
            </View>
          ))}
        </View>
        {canManage && full ? (
          <Text style={styles.meta}>You have saved the most payment methods allowed ({MAX_SAVED_METHODS}). Remove one to add another.</Text>
        ) : null}
      </View>
    );
  }

  return (
    <SectionCard
      title="Payment methods"
      subtitle="Only a masked label is kept: the last four digits of a card or phone number, or of a policy number. Full card numbers are never stored."
      action={addButton}
    >
      <View style={styles.listWrap}>
        {notice ? <Banner tone={notice.tone} text={notice.text} onDismiss={() => setNotice(null)} /> : null}
        {body}
      </View>
      <AddPaymentMethodDialog
        open={adding}
        hasMethods={list.length > 0}
        onClose={() => setAdding(false)}
        onAdded={method => {
          setAdding(false);
          setNotice({ tone: 'success', text: `${method.displayLabel} was saved.` });
          refetch();
          onChangedRef.current?.();
        }}
      />
    </SectionCard>
  );
}

// ─── Add dialog ───────────────────────────────────────────────────────────────

export interface AddPaymentMethodDialogProps {
  open:        boolean;
  /** Whether the patient already has a method (otherwise the new one becomes the default anyway). */
  hasMethods:  boolean;
  onClose:     () => void;
  onAdded:     (method: PaymentMethod) => void;
}

/** Saves a payment method for the signed-in patient. One Idempotency-Key per opening, reused on retry. */
export function AddPaymentMethodDialog(props: AddPaymentMethodDialogProps) {
  if (!props.open) return null;
  return <AddBody {...props} />;
}

function AddBody({ hasMethods, onClose, onAdded }: AddPaymentMethodDialogProps) {
  const { control, handleSubmit, setError, formState: { isDirty } } = useForm<PaymentMethodFormValues>({
    resolver: zodResolver(paymentMethodFormSchema),
    defaultValues: {
      type: 'MPESA', provider: '', lastFour: '', mpesaPhone: '', insurerName: '', policyNumber: '', memberNumber: '',
      makeDefault: !hasMethods,
    },
  });
  const type = useWatch({ control, name: 'type' });
  const key = useIdempotencyKey();
  const [formError, setFormError] = useState<string | null>(null);
  const save = useAction(
    (values: PaymentMethodFormValues) => addPaymentMethod(toCreatePaymentMethodRequest(values), key.current()),
    { errorMessage: 'We could not save the payment method. Please try again.' },
  );

  const submit = handleSubmit(async values => {
    setFormError(null);
    const result = await save.run(values);
    if (result.ok) {
      key.renew();
      onAdded(result.data);
    } else if (result.error) {
      const busy = inProgressMessage(result.error, 'This payment method is still being saved.');
      setFormError(busy ?? applyFieldErrors(result.error, setError, FORM_FIELDS));
    }
  });

  const close = (reason: DialogCloseReason) => {
    if (reason === 'escape' && isDirty) {
      void confirmAction('Discard this payment method?', 'What you entered will be lost.', 'Discard', true)
        .then(ok => { if (ok) onClose(); });
      return;
    }
    onClose();
  };

  const submitting = save.pending;
  return (
    <Modal
      open
      title="Add payment method"
      onClose={close}
      dismissOnBackdrop={false}
      busy={submitting}
      footer={(
        <FormActions
          submitLabel="Save payment method"
          onSubmit={() => { void submit(); }}
          onCancel={onClose}
          submitting={submitting}
        />
      )}
    >
      <FormError message={formError} />
      <SelectField label="Type" control={control} name="type" options={TYPE_OPTIONS} required disabled={submitting} />

      {type === 'CARD' ? (
        <View style={styles.pair}>
          <View style={styles.cell}>
            <TextField
              label="Card brand (optional)" control={control} name="provider" disabled={submitting}
              maxLength={100} placeholder="For example Visa"
            />
          </View>
          <View style={styles.cell}>
            <TextField
              label="Last 4 digits" control={control} name="lastFour" required disabled={submitting}
              inputMode="numeric" maxLength={4}
              hint="Only the last four digits. Never enter the full card number."
            />
          </View>
        </View>
      ) : null}

      {type === 'MPESA' ? (
        <TextField
          label="M-PESA phone number" control={control} name="mpesaPhone" required disabled={submitting}
          inputMode="tel" autoComplete="tel" maxLength={BILLING_PHONE_MAX_LENGTH}
          hint="Payment requests are sent to this phone. Include the country code, for example +254 712 345 678."
        />
      ) : null}

      {type === 'INSURANCE' ? (
        <>
          <TextField
            label="Insurer" control={control} name="insurerName" required disabled={submitting} maxLength={100}
            placeholder="For example Jubilee Insurance"
          />
          <View style={styles.pair}>
            <View style={styles.cell}>
              <TextField
                label="Policy number" control={control} name="policyNumber" required disabled={submitting}
                maxLength={100} autoCapitalize="characters"
              />
            </View>
            <View style={styles.cell}>
              <TextField
                label="Member number (optional)" control={control} name="memberNumber" disabled={submitting}
                maxLength={100} autoCapitalize="characters"
              />
            </View>
          </View>
          <Text style={styles.meta}>
            After saving, only the insurer and the last four characters of the policy number are shown. Type the
            policy and member numbers again when you submit a claim.
          </Text>
        </>
      ) : null}

      <SwitchField
        label="Use as default"
        control={control}
        name="makeDefault"
        disabled={submitting || !hasMethods}
        description={hasMethods ? 'The default is selected first when you pay.' : 'Your first payment method is always the default.'}
      />
    </Modal>
  );
}

const styles = StyleSheet.create({
  listWrap: { gap: space.md, minWidth: 0 },
  item:     { paddingVertical: space.md, minWidth: 0 },
  divider:  { borderTopWidth: 1, borderTopColor: colors.border },

  row:        { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', columnGap: space.md, rowGap: space.sm, minWidth: 0 },
  iconWrap:   { width: 36, height: 36, borderRadius: radius.pill, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.bgSubtle },
  rowText:    { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0, gap: 2 },
  rowTitle:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
  rowActions: { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs },
  label:      { fontSize: fontSize.md, fontWeight: '600', color: colors.text },
  meta:       { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },

  pair: { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  cell: { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0 },
});
