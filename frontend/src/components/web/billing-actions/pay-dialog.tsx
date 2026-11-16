import { zodResolver } from '@hookform/resolvers/zod';
import { type ReactNode, useEffect, useMemo, useRef, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { Linking, StyleSheet, Text, View } from 'react-native';
import {
  Banner, Button, colors, EmptyView, ErrorView, fontSize, FormActions, FormError, LoadingView, Modal, NoAccessView,
  radius, SelectField, type SelectOption, space, StatusBadge, statusTone, TextField,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { useBillingConfig } from '@/hooks/use-billing-config';
import { usePaymentMethods } from '@/hooks/use-payment-methods';
import { usePaymentWatch } from '@/hooks/use-payments';
import type { ApiError } from '@/lib/api/errors';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { useIdempotencyKey } from '@/lib/api/idempotency';
import { addPaymentMethod, initiatePayment, simulatePayment } from '@/lib/billing/api';
import {
  paymentMethodLabel, paymentMethodSummary, paymentReference, paymentStatusLabel,
} from '@/lib/billing/format';
import type {
  InitiatePaymentRequest, Invoice, OnlinePaymentMethodCode, Payment, PaymentMethod, SimulatedPaymentOutcome,
} from '@/lib/billing/types';
import { formatMoney } from '@/lib/format/money';
import {
  BILLING_PHONE_MAX_LENGTH, moneyInputValue, parseMoneyInput, paymentFormSchema, type PaymentFormValues,
  toInitiatePaymentRequest,
} from '@/lib/validation/billing';
import { GatewayNotice } from './gateway-notice';
import { inProgressMessage } from './rules';

// The Pay dialog (api/be-billing-payments.md 5.1, 5.2, 5.7). It offers only the methods the active gateway lets a
// patient or caregiver use (`BillingConfig.onlineMethods`), with a saved method of the patient or new details
// (an M-PESA phone number; for the patient, optionally the last four digits of a card to save it). One
// Idempotency-Key per dialog open is reused when the same payment is retried and renewed once a payment was
// created. A PENDING payment is then followed (every 5 s, at most 24 times) until it is COMPLETED or FAILED.

export interface PayDialogProps {
  /** Mounts the dialog. Each opening starts with a fresh form and a fresh Idempotency-Key. */
  open:            boolean;
  invoice:         Invoice;
  /** The patient the invoice belongs to: the patient's own id, or the loved one's for a caregiver. */
  patientSystemId: string;
  /** Who pays. A patient may save a new card; a caregiver chooses among the patient's saved methods read-only. */
  viewer:          'patient' | 'proxy';
  /** False hides the form (the caller should not open the dialog then). */
  canPay:          boolean;
  /** The amount pending payments already reserve on the invoice; the dialog offers at most the rest. */
  reservedAmount?: number;
  onClose:         () => void;
  /** Something about the invoice changed (a payment was created or settled, or the server refused it): reload it. */
  onChanged?:      () => void;
  /** The server answered 403 (a caregiver whose access changed). */
  onForbidden?:    () => void;
}

/** One entry of the "Pay with" select: a saved method or new details for an online method. */
type Choice = `saved:${number}` | `new:${OnlinePaymentMethodCode}`;

const FORM_FIELDS = {
  method:          'method',
  paymentMethodId: 'paymentMethodId',
  amount:          'amount',
  payerPhone:      'payerPhone',
} as const;

const CARD_BRAND_MAX_LENGTH = 100;

function isOnlineMethod(value: string, online: readonly OnlinePaymentMethodCode[]): value is OnlinePaymentMethodCode {
  return (online as readonly string[]).includes(value);
}

/** The saved methods that can pay with one of the online methods (an insurance policy cannot). */
function usableSaved(methods: readonly PaymentMethod[], online: readonly OnlinePaymentMethodCode[]): PaymentMethod[] {
  return methods.filter(m => isOnlineMethod(m.type, online));
}

function choiceOf(value: string): { method: OnlinePaymentMethodCode | null; methodId: number | null } {
  if (value.startsWith('saved:')) {
    const id = Number(value.slice(6));
    return { method: null, methodId: Number.isInteger(id) && id > 0 ? id : null };
  }
  if (value === 'new:CARD' || value === 'new:MPESA') return { method: value.slice(4) as OnlinePaymentMethodCode, methodId: null };
  return { method: null, methodId: null };
}

function sameText(a: string | null | undefined, b: string | null | undefined): boolean {
  return (a ?? '').trim().toLowerCase() === (b ?? '').trim().toLowerCase();
}

/** What the payer has to do while the payment waits for the gateway. */
function pendingInstruction(payment: Payment): string {
  switch (payment.nextAction) {
    case 'STK_PUSH':
      return payment.payerPhone
        ? `A payment request was sent to the phone ending ${payment.payerPhone.replace(/[^0-9]/g, '').slice(-4) || payment.payerPhone}. Approve it with the M-PESA PIN on that phone.`
        : 'A payment request was sent to the M-PESA phone. Approve it with the M-PESA PIN on that phone.';
    case 'REDIRECT':
      return 'Finish the payment on the payment provider\'s page, then come back here.';
    case 'AWAIT_CONFIRMATION':
    default:
      return 'The payment provider is confirming the payment.';
  }
}

/** Only a web address is handed to the browser; anything else from the gateway is ignored. */
function safeRedirect(url: string | null): string | null {
  const value = url?.trim();
  return value && /^https?:\/\//i.test(value) ? value : null;
}

export function PayDialog(props: PayDialogProps) {
  if (!props.open) return null;
  return <PayDialogBody {...props} />;
}

function PayDialogBody({
  invoice, patientSystemId, viewer, canPay, reservedAmount = 0, onClose, onChanged, onForbidden,
}: PayDialogProps) {
  const configState = useBillingConfig({ enabled: canPay });
  const config = configState.config;
  const online = useMemo<readonly OnlinePaymentMethodCode[]>(() => config?.onlineMethods ?? [], [config]);
  const methods = usePaymentMethods(patientSystemId, { enabled: canPay });

  const onChangedRef = useRef(onChanged);
  onChangedRef.current = onChanged;
  const onForbiddenRef = useRef(onForbidden);
  onForbiddenRef.current = onForbidden;

  // A caregiver whose grant no longer allows managing billing gets 403 on the saved methods too.
  const methodsForbidden = methods.errorStatus === 403;
  useEffect(() => {
    if (methodsForbidden) onForbiddenRef.current?.();
  }, [methodsForbidden]);

  // ── The payment being followed ──────────────────────────────────────────────
  const [started, setStarted] = useState<Payment | null>(null);
  const watch = usePaymentWatch(started, { onSettled: () => onChangedRef.current?.() });
  const watchForbidden = watch.errorStatus === 403;
  useEffect(() => {
    if (watchForbidden) onForbiddenRef.current?.();
  }, [watchForbidden]);

  // ── The form ────────────────────────────────────────────────────────────────
  const currency = invoice.currency;
  const free = Math.max(0, Math.round((invoice.outstanding - reservedAmount) * 100) / 100);
  const reserved = free < invoice.outstanding;
  const schema = useMemo(() => paymentFormSchema(free, currency), [free, currency]);
  const { control, handleSubmit, setError, setValue, clearErrors } = useForm<PaymentFormValues>({
    resolver: zodResolver(schema),
    defaultValues: { method: 'MPESA', paymentMethodId: '', amount: moneyInputValue(free), payerPhone: '' },
  });
  // The amount as it will be sent (blank pays everything free), shown before Pay now so a misread separator is seen
  // before a gateway prompt goes out; null while the text is not a payable amount.
  const amountText = useWatch({ control, name: 'amount' });
  const payingAmount = (() => {
    if ((amountText ?? '').trim() === '') return free;
    const amount = parseMoneyInput(amountText);
    return amount !== null && amount > 0 && amount <= free ? amount : null;
  })();

  const saved = useMemo(() => usableSaved(methods.data ?? [], online), [methods.data, online]);
  const options = useMemo<SelectOption<Choice>[]>(() => {
    const list: SelectOption<Choice>[] = saved.map(m => ({
      value: `saved:${m.methodId}` as Choice,
      label: `${m.displayLabel}${m.isDefault ? ' (default)' : ''}`,
    }));
    for (const method of online) {
      list.push({
        value: `new:${method}` as Choice,
        label: method === 'MPESA'
          ? 'New M-PESA number'
          : viewer === 'patient' ? 'New card' : 'Card',
      });
    }
    return list;
  }, [saved, online, viewer]);

  const [choice, setChoice] = useState<Choice | ''>('');
  // A card saved from this dialog is selected before the reloaded list contains it; keep that choice meanwhile.
  const justSaved = useRef<Choice | null>(null);
  // Start with the default saved method when it can be used, else the first option; keep the person's choice
  // once they made one, unless it disappeared (the saved methods were reloaded).
  useEffect(() => {
    if (options.length === 0) return;
    if (choice && options.some(o => o.value === choice)) return;
    if (choice && choice === justSaved.current) return;
    if (methods.loading && !methods.data) return;
    const preferred = saved.find(m => m.isDefault) ?? saved[0];
    setChoice(preferred ? `saved:${preferred.methodId}` : options[0].value);
  }, [options, choice, saved, methods.loading, methods.data]);

  const selectedSaved = choice.startsWith('saved:')
    ? saved.find(m => m.methodId === choiceOf(choice).methodId) ?? null
    : null;
  const newMethod = choiceOf(choice).method;

  // Keep the form's method fields in step with the choice.
  useEffect(() => {
    if (!choice) return;
    const method = selectedSaved ? (selectedSaved.type as OnlinePaymentMethodCode) : newMethod;
    if (!method) return;
    setValue('method', method);
    setValue('paymentMethodId', selectedSaved ? String(selectedSaved.methodId) : '');
    clearErrors(['method', 'paymentMethodId', 'payerPhone']);
  }, [choice, selectedSaved, newMethod, setValue, clearErrors]);

  // A patient may type a new card's brand and last four digits to save it and pay with it.
  const offerCardSave = viewer === 'patient' && newMethod === 'CARD';
  const [cardBrand, setCardBrand] = useState('');
  const [cardLastFour, setCardLastFour] = useState('');
  const [cardErrors, setCardErrors] = useState<{ brand?: string; lastFour?: string }>({});

  const payKey = useIdempotencyKey();
  const cardKey = useIdempotencyKey();
  const [formError, setFormError] = useState<string | null>(null);

  const pay = useAction(
    (request: InitiatePaymentRequest) => initiatePayment(invoice.invoiceId, request, payKey.current()),
    { errorMessage: 'We could not start the payment. Please try again.' },
  );
  const saveCard = useAction(
    (brand: string | null, lastFour: string) => addPaymentMethod(
      { type: 'CARD', provider: brand, lastFour, makeDefault: false },
      cardKey.current(),
    ),
    { errorMessage: 'We could not save the card. Please try again.' },
  );
  const simulate = useAction(
    (paymentId: number, outcome: SimulatedPaymentOutcome) => simulatePayment(paymentId, outcome),
    { errorMessage: 'The simulated settlement did not go through. Please try again.' },
  );
  const submitting = pay.pending || saveCard.pending;

  /** Checks the card fields; true when they can be used (blank last four means "do not save a card"). */
  const cardFieldsOk = (): boolean => {
    const next: { brand?: string; lastFour?: string } = {};
    const lastFour = cardLastFour.trim();
    if (lastFour && !/^\d{4}$/.test(lastFour)) next.lastFour = 'Enter exactly the last 4 digits of the card';
    if (cardBrand.trim().length > CARD_BRAND_MAX_LENGTH) next.brand = `The card brand must be at most ${CARD_BRAND_MAX_LENGTH} characters`;
    if (!lastFour && cardBrand.trim()) next.lastFour = 'Enter the last 4 digits to save this card, or clear the brand';
    setCardErrors(next);
    return !next.brand && !next.lastFour;
  };

  /**
   * Saves the typed card (or finds the same card among the saved ones) and selects it. Returns its id, or null
   * when saving failed (the reason is shown).
   */
  const ensureCardSaved = async (): Promise<number | null> => {
    const brand = cardBrand.trim() || null;
    const lastFour = cardLastFour.trim();
    const existing = (methods.data ?? []).find(m => m.type === 'CARD' && m.lastFour === lastFour && sameText(m.provider, brand));
    let methodId: number;
    if (existing) {
      methodId = existing.methodId;
    } else {
      const result = await saveCard.run(brand, lastFour);
      if (!result.ok) {
        if (result.error) {
          if (result.error.status === 409) methods.refetch();
          setFormError(inProgressMessage(result.error, 'The card is still being saved.') ?? result.error.message);
        }
        return null;
      }
      cardKey.renew();
      methodId = result.data.methodId;
      methods.refetch();
    }
    // From now on the card is a saved method: a retry pays with it instead of saving it again.
    setCardBrand('');
    setCardLastFour('');
    justSaved.current = `saved:${methodId}`;
    setChoice(`saved:${methodId}`);
    setValue('method', 'CARD');
    setValue('paymentMethodId', String(methodId));
    return methodId;
  };

  const showPayFailure = (error: ApiError) => {
    if (error.status === 403) onForbiddenRef.current?.();
    if (error.status === 404 && /payment method/i.test(error.message)) methods.refetch();
    // A refusal about the invoice (already paid, cancelled, another payment in progress) means the screen behind
    // the dialog is out of date.
    if (error.status === 404 || error.status === 409) onChangedRef.current?.();
    const busy = inProgressMessage(error, 'This payment is still being started.');
    setFormError(busy ?? applyFieldErrors(error, setError, FORM_FIELDS));
  };

  const submit = handleSubmit(async values => {
    setFormError(null);
    if (offerCardSave && !cardFieldsOk()) return;

    const request = toInitiatePaymentRequest(values);
    if (offerCardSave && cardLastFour.trim()) {
      const methodId = await ensureCardSaved();
      if (methodId === null) return;
      request.method = 'CARD';
      request.paymentMethodId = methodId;
    }
    // A blank amount means "the whole balance"; while part of it is reserved by a pending payment, send what is free.
    if (request.amount == null && reserved) request.amount = free;

    const result = await pay.run(request);
    if (result.ok) {
      payKey.renew();
      setStarted(result.data);
      onChangedRef.current?.();
    } else if (result.error) {
      showPayFailure(result.error);
    }
  });

  const runSimulation = async (outcome: SimulatedPaymentOutcome) => {
    const payment = watch.payment;
    if (!payment) return;
    const result = await simulate.run(payment.paymentId, outcome);
    if (result.ok) {
      setStarted(result.data);
    } else if (result.error?.status === 409) {
      // It settled meanwhile: read its current state.
      simulate.reset();
      void watch.checkNow();
    } else if (result.error?.status === 403) {
      onForbiddenRef.current?.();
    }
  };

  const tryAgain = () => {
    setStarted(null);
    setFormError(null);
    pay.reset();
    simulate.reset();
  };

  const title = `Pay invoice ${invoice.invoiceNo}`;
  const close = () => onClose();

  // ── What the dialog shows ───────────────────────────────────────────────────
  let body: ReactNode;
  let footer: ReactNode;

  if (!canPay) {
    body = <NoAccessView compact message="You can't pay this invoice." />;
    footer = <FormActions submitLabel="Close" onSubmit={close} />;
  } else if (watch.payment) {
    const payment = watch.payment;
    const redirect = payment.status === 'PENDING' && payment.nextAction === 'REDIRECT' ? safeRedirect(payment.redirectUrl) : null;
    const reference = paymentReference(payment);
    body = (
      <View style={styles.stack}>
        <GatewayNotice config={config} />
        <View style={styles.summary}>
          <View style={styles.summaryHead}>
            <Text style={styles.amount}>{formatMoney(payment.amount, payment.currency)}</Text>
            <StatusBadge label={paymentStatusLabel(payment.status)} tone={statusTone('payment', payment.status)} />
          </View>
          <Text style={styles.meta}>{paymentMethodSummary(payment)} · Invoice {invoice.invoiceNo}</Text>
          {reference ? <Text style={styles.meta} selectable>Reference: {reference}</Text> : null}
        </View>

        {payment.status === 'PENDING' ? (
          <View style={styles.stack}>
            <Text style={styles.body}>{pendingInstruction(payment)}</Text>
            {redirect ? (
              <Button
                label="Continue to the payment page"
                icon="open-outline"
                onPress={() => { void Linking.openURL(redirect); }}
              />
            ) : null}
            {watch.watching ? (
              <View role="status" aria-live="polite">
                <Text style={styles.meta}>Checking the payment status every few seconds…</Text>
              </View>
            ) : null}
            {watch.timedOut ? (
              <Banner
                tone="warning"
                text="The payment is still being processed. You can close this window: the invoice is updated and you are notified when the payment is confirmed."
                action={{ label: 'Check again', onPress: () => { void watch.checkNow(); } }}
              />
            ) : null}
            {watch.error && !watchForbidden ? (
              <Banner tone="danger" text={watch.error} action={{ label: 'Check again', onPress: () => { void watch.checkNow(); } }} />
            ) : null}
            {watchForbidden ? (
              <Banner tone="warning" text="The payment was started, but you can no longer see its status. Your access to this billing may have changed." />
            ) : null}
            {config?.simulationEnabled && !watchForbidden ? (
              <View style={styles.devBox} role="group" aria-label="Development only: simulate the gateway">
                <Text style={styles.devTitle}>Development only</Text>
                <Text style={styles.meta}>
                  Settle this test payment the way the payment provider would.
                </Text>
                <View style={styles.row}>
                  <Button
                    label="Simulate success"
                    icon="checkmark-done-outline"
                    variant="secondary"
                    size="sm"
                    loading={simulate.pending}
                    onPress={() => { void runSimulation('COMPLETED'); }}
                  />
                  <Button
                    label="Simulate failure"
                    icon="close-circle-outline"
                    variant="secondary"
                    size="sm"
                    disabled={simulate.pending}
                    onPress={() => { void runSimulation('FAILED'); }}
                  />
                </View>
                {simulate.error ? <FormError message={simulate.error} /> : null}
              </View>
            ) : null}
          </View>
        ) : null}

        {payment.status === 'COMPLETED' ? (
          <Banner
            tone="success"
            text={`Payment received. ${formatMoney(payment.amount, payment.currency)} was paid onto invoice ${invoice.invoiceNo}.`}
          />
        ) : null}
        {payment.status === 'FAILED' ? (
          <Banner
            tone="danger"
            text={payment.failureReason ? `The payment failed: ${payment.failureReason}` : 'The payment failed. Nothing was charged.'}
          />
        ) : null}
        {payment.status === 'REFUNDED' || payment.status === 'CANCELLED' ? (
          <Banner tone="info" text={`This payment is ${paymentStatusLabel(payment.status).toLowerCase()}.`} />
        ) : null}
      </View>
    );
    footer = payment.status === 'FAILED'
      ? <FormActions submitLabel="Try again" submitIcon="refresh-outline" onSubmit={tryAgain} onCancel={close} cancelLabel="Close" />
      : <FormActions submitLabel={payment.status === 'PENDING' ? 'Close' : 'Done'} onSubmit={close} />;
  } else if (configState.loading && !config) {
    body = <LoadingView compact label="Loading payment options" />;
    footer = <FormActions submitLabel="Close" onSubmit={close} />;
  } else if (!config) {
    body = <ErrorView compact message={configState.error ?? 'We could not load the payment options. Please try again.'} onRetry={configState.refetch} />;
    footer = <FormActions submitLabel="Close" onSubmit={close} />;
  } else if (online.length === 0) {
    body = (
      <EmptyView
        compact
        icon="card-outline"
        title="Paying online is not available"
        sub="The clinic's payment service does not accept card or M-PESA payments right now. Contact the clinic to pay."
      />
    );
    footer = <FormActions submitLabel="Close" onSubmit={close} />;
  } else if (free <= 0) {
    body = (
      <EmptyView
        compact
        icon="hourglass-outline"
        title="A payment is already being processed"
        sub="The rest of the balance is reserved by a payment that is waiting for confirmation. Try again once it has been confirmed or has failed."
      />
    );
    footer = <FormActions submitLabel="Close" onSubmit={close} />;
  } else {
    body = (
      <View style={styles.stack}>
        <GatewayNotice config={config} />
        <View style={styles.summary}>
          <Text style={styles.meta}>Balance due</Text>
          <Text style={styles.amount}>{formatMoney(invoice.outstanding, currency)}</Text>
          {reserved ? (
            <Text style={styles.meta}>
              {formatMoney(invoice.outstanding - free, currency)} is reserved by a payment that is waiting for
              confirmation, so you can pay up to {formatMoney(free, currency)} now.
            </Text>
          ) : null}
        </View>
        <FormError message={formError} />

        {methods.error && !methodsForbidden ? (
          <Banner
            tone="warning"
            text="The saved payment methods could not be loaded. You can still pay with new details."
            action={{ label: 'Retry', onPress: methods.refetch }}
          />
        ) : null}
        {methods.loading && !methods.data ? <LoadingView compact label="Loading saved payment methods" /> : null}

        <SelectField<Choice>
          label="Pay with"
          value={choice}
          onChange={value => { setChoice(value); setFormError(null); }}
          options={options}
          required
          disabled={submitting}
          hint={viewer === 'proxy' && saved.length > 0 ? 'Saved methods belong to your loved one; only they can add or remove them.' : undefined}
        />

        {selectedSaved?.type === 'MPESA' ? (
          <Text style={styles.meta}>The payment request is sent to the phone saved with this method.</Text>
        ) : null}

        {newMethod === 'MPESA' ? (
          <TextField
            label="M-PESA phone number"
            control={control}
            name="payerPhone"
            required
            disabled={submitting}
            inputMode="tel"
            autoComplete="tel"
            maxLength={BILLING_PHONE_MAX_LENGTH}
            hint="The payment request is sent to this phone. Include the country code, for example +254 712 345 678."
          />
        ) : null}

        {newMethod === 'CARD' && offerCardSave ? (
          <View style={styles.stack}>
            <Text style={styles.meta}>
              Card details are entered with the payment provider, never here. To keep this card for next time, add
              its brand and last 4 digits: it is saved to your payment methods and shown masked.
            </Text>
            <View style={styles.pair}>
              <View style={styles.cell}>
                <TextField
                  label="Card brand (optional)"
                  value={cardBrand}
                  onChange={text => { setCardBrand(text); setCardErrors(e => ({ ...e, brand: undefined })); }}
                  error={cardErrors.brand}
                  disabled={submitting}
                  placeholder="For example Visa"
                  maxLength={CARD_BRAND_MAX_LENGTH}
                />
              </View>
              <View style={styles.cell}>
                <TextField
                  label="Last 4 digits (optional)"
                  value={cardLastFour}
                  onChange={text => { setCardLastFour(text.replace(/\D/g, '').slice(0, 4)); setCardErrors(e => ({ ...e, lastFour: undefined })); }}
                  error={cardErrors.lastFour}
                  disabled={submitting}
                  inputMode="numeric"
                  maxLength={4}
                  hint="Never enter the full card number."
                />
              </View>
            </View>
          </View>
        ) : null}
        {newMethod === 'CARD' && !offerCardSave ? (
          <Text style={styles.meta}>Card details are confirmed with the payment provider, never entered here.</Text>
        ) : null}

        <TextField
          label={`Amount (${currency})`}
          control={control}
          name="amount"
          disabled={submitting}
          inputMode="decimal"
          hint={`Up to ${formatMoney(free, currency)}. You can pay part of the balance; leave it empty to pay all of ${formatMoney(free, currency)}.`}
        />
        {payingAmount !== null ? (
          <Text style={styles.body} testID="pay-amount-confirmation" aria-live="polite">
            You will pay {formatMoney(payingAmount, currency)}.
          </Text>
        ) : null}
        <Text style={styles.meta}>
          Accepted here: {online.map(m => paymentMethodLabel(m)).join(' and ')}.
        </Text>
      </View>
    );
    footer = (
      <FormActions
        submitLabel={saveCard.pending ? 'Saving card…' : 'Pay now'}
        submitIcon="card-outline"
        onSubmit={() => { void submit(); }}
        onCancel={close}
        submitting={submitting}
        disabled={!choice}
      />
    );
  }

  return (
    <Modal
      open
      title={title}
      onClose={close}
      dismissOnBackdrop={false}
      busy={submitting || simulate.pending}
      footer={footer}
    >
      {body}
    </Modal>
  );
}

const styles = StyleSheet.create({
  stack: { gap: space.md, minWidth: 0 },
  row:   { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  pair:  { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  cell:  { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0 },

  summary:     { gap: space.xs, padding: space.md, borderRadius: radius.md, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.bgSubtle },
  summaryHead: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
  amount:      { fontSize: fontSize.xl, fontWeight: '700', color: colors.text },
  body:        { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  meta:        { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },

  devBox:   { gap: space.sm, padding: space.md, borderRadius: radius.md, borderWidth: 1, borderStyle: 'dashed', borderColor: colors.borderInput },
  devTitle: { fontSize: fontSize.sm, fontWeight: '700', color: colors.text },
});
