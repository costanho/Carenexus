import { zodResolver } from '@hookform/resolvers/zod';
import { useRouter } from 'expo-router';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { type Control, type Path, useFieldArray, useForm, type UseFormSetError, useWatch } from 'react-hook-form';
import { Platform, StyleSheet, Text, View } from 'react-native';
import { AmountRow, FeeEditor, InvoiceDetail, type InvoiceDetailContext, InvoiceList } from '@/components/web/billing';
import {
  ariaLevel, Banner, type BannerTone, Button, colors, ErrorView, fontSize, FormActions, FormError,
  IconButton, InfoRow, LoadingView, Modal, PagedList, radius, ROLE_THEME, Screen, SelectField, space, Tabs,
  type TabItem, TextField, useUiTheme,
} from '@/components/web/ui';
import { useCurrentUser } from '@/hooks/use-current-user';
import { patientNameFallback, useDoctorPatientOptions } from '@/hooks/use-doctor-consultations';
import {
  canCancelInvoice, canEditInvoice, canSendInvoice, type InvoiceVisit, isIssuedBy, useCancelDoctorInvoice,
  useConsultationInvoiceLookup, useCreateDoctorInvoice, useDoctorUninvoiced, useSendDoctorInvoice,
  useUpdateDoctorInvoice, visitFromUninvoiced,
} from '@/hooks/use-doctor-invoices';
import { useSectionNav } from '@/hooks/use-section-nav';
import type { ApiError } from '@/lib/api/errors';
import { type InvoiceFilterKey, parseInvoiceFilter } from '@/lib/billing/filters';
import { APPOINTMENT_TYPE_LABEL, formatBillingDateTime, invoiceStatusLabel } from '@/lib/billing/format';
import type { Invoice, UninvoicedConsultation } from '@/lib/billing/types';
import { formatMoney } from '@/lib/format/money';
import type { SectionParams, SectionParamsInput, SectionProps } from '@/lib/nav/sections';
import { confirmAction } from '@/lib/ui/dialogs';
import {
  cancelInvoiceSchema, type CancelInvoiceFormValues, INVOICE_CANCEL_REASON_MAX_LENGTH, INVOICE_DESCRIPTION_MAX_LENGTH,
  INVOICE_MAX_LINE_ITEMS, INVOICE_NOTES_MAX_LENGTH, invoiceFormSchema, invoiceFormSubtotal, type InvoiceFormValues,
  moneyInputValue, parseMoneyInput, toInvoiceLineItems, toUpdateInvoiceRequest,
} from '@/lib/validation/billing';

// Doctor dashboard, Billing section (api/be-billing-invoices.md).
//
// URL params (CONTRACTS 12.1 and 7.1):
//   tab=uninvoiced|fees           the Uninvoiced or Fees tab; absent = the invoice list;
//   status=<filter key>           the invoice list's status chip (draft, unpaid, overdue, paid, cancelled, refunded);
//   patient=<PAT id>              lists only that patient's invoices (server-side), shown as a removable chip;
//   id=<invoiceId>                opens one invoice (the BILLING notification link);
//   consultationId=<id>           opens the consultation's invoice, or starts "Create invoice" for it.
//
// Recording a payment is an administrator action: no control for it is shown here.

type BillingTab = 'invoices' | 'uninvoiced' | 'fees';

const TABS: readonly BillingTab[] = ['invoices', 'uninvoiced', 'fees'];
const PATIENT_ID = /^PAT-\d{5,}$/;

function tabOf(value: string | undefined): BillingTab {
  return TABS.includes(value as BillingTab) ? (value as BillingTab) : 'invoices';
}

/** A positive integer id from the URL, or null. */
function intParam(value: string | undefined): number | null {
  if (!value || !/^\d{1,9}$/.test(value)) return null;
  const n = Number(value);
  return n > 0 ? n : null;
}

/** The `id` param as the detail view takes it: an unusable value becomes NaN, which the detail reports as not available. */
function invoiceIdParam(value: string | undefined): number {
  return intParam(value) ?? Number.NaN;
}

function patientParam(value: string | undefined): string | null {
  const id = (value ?? '').trim();
  return PATIENT_ID.test(id) ? id : null;
}

/** The list filters carried by the URL, as params for opening the list again. */
function listParams(params: SectionParams): SectionParamsInput {
  const status = parseInvoiceFilter(params.status, 'doctor');
  return { patient: patientParam(params.patient) ?? undefined, status: status === 'all' ? undefined : status };
}

// ─── Back from an invoice to the list ─────────────────────────────────────────
//
// The list opens an invoice as a history entry of its own, so the in-app Back button and the browser's Back
// button leave the same trail. An invoice reached any other way (a notification link, the consultation editor,
// a reload) replaces its own entry with the list instead. Every history move mounts a fresh copy of the
// dashboard, so what the list opened is kept here.

const openedFromList = new Set<number>();

// ─── Small pieces ─────────────────────────────────────────────────────────────

interface Notice {
  /** The invoice the message is about; it is shown only while that invoice is open. Null: shown on the list. */
  invoiceId: number | null;
  tone:      BannerTone;
  text:      string;
}

function PatientChip({ name, onRemove }: { name: string; onRemove: () => void }) {
  const { accent, accentBg } = useUiTheme();
  return (
    <View style={[styles.chip, { backgroundColor: accentBg, borderColor: accent }]}>
      <Text style={styles.chipText} numberOfLines={1}>Patient: {name}</Text>
      <IconButton label={`Remove patient filter, ${name}`} icon="close" size="sm" onPress={onRemove} />
    </View>
  );
}

function visitLabel(visit: Pick<InvoiceVisit, 'appointmentType'>): string {
  return visit.appointmentType ? APPOINTMENT_TYPE_LABEL[visit.appointmentType] : 'Consultation';
}

// ─── Invoice form (create and edit) ───────────────────────────────────────────

type LineValues = InvoiceFormValues['lineItems'][number];

const EMPTY_LINE: LineValues = { description: '', quantity: '1', unitPrice: '' };

/** A new invoice starts with one line priced from the fee schedule. */
function createFormValues(visit: InvoiceVisit): InvoiceFormValues {
  return {
    lineItems: [{
      description: visitLabel(visit),
      quantity:    '1',
      unitPrice:   moneyInputValue(visit.suggestedFee),
    }],
    discount: '',
    notes:    '',
  };
}

function editFormValues(invoice: Invoice): InvoiceFormValues {
  const lines = invoice.lineItems.map<LineValues>(item => ({
    description: item.description,
    quantity:    String(item.quantity),
    unitPrice:   moneyInputValue(item.unitPrice),
  }));
  return {
    lineItems: lines.length > 0 ? lines : [{ ...EMPTY_LINE }],
    discount:  invoice.discount > 0 ? moneyInputValue(invoice.discount) : '',
    notes:     invoice.notes ?? '',
  };
}

/**
 * Puts the server's validation errors on the form: "lineItems[1].unitPrice" lands on that line's price field.
 * Returns the sentence for FormError, or null when every error found its field.
 */
function applyInvoiceFieldErrors(error: ApiError, setError: UseFormSetError<InvoiceFormValues>, lineCount: number): string | null {
  const entries = Object.entries(error.fieldErrors ?? {});
  if (entries.length === 0) return error.message;
  let unmatched = 0;
  let first = true;
  for (const [name, message] of entries) {
    const path = name.replace(/\[(\d+)]/g, '.$1');
    const line = /^lineItems\.(\d+)\.(description|quantity|unitPrice)$/.exec(path);
    const known = (line !== null && Number(line[1]) < lineCount) || path === 'discount' || path === 'notes' || path === 'lineItems';
    if (!known) { unmatched += 1; continue; }
    setError(path as Path<InvoiceFormValues>, { type: 'server', message }, { shouldFocus: first });
    first = false;
  }
  return unmatched > 0 ? error.message : null;
}

function LineRows({ control, currency, disabled, listError }: {
  control:   Control<InvoiceFormValues>;
  currency:  string | null;
  disabled:  boolean;
  listError?: string;
}) {
  const { fields, append, remove } = useFieldArray({ control, name: 'lineItems' });
  const lines = useWatch({ control, name: 'lineItems' });
  const full = fields.length >= INVOICE_MAX_LINE_ITEMS;
  const unit = currency ? ` (${currency})` : '';

  return (
    <View style={styles.formSection}>
      <Text style={styles.formHeading} accessibilityRole="header" {...ariaLevel(3)}>Items</Text>
      {fields.map((field, index) => {
        const line = lines?.[index];
        const amount = line ? invoiceFormSubtotal([line]) : 0;
        return (
          <View key={field.id} style={styles.line} role="group" aria-label={`Line ${index + 1}`}>
            <View style={styles.lineHead}>
              <Text style={styles.lineTitle}>Line {index + 1}</Text>
              {fields.length > 1 ? (
                <IconButton
                  label={`Remove line ${index + 1}`}
                  icon="trash-outline"
                  size="sm"
                  disabled={disabled}
                  onPress={() => remove(index)}
                />
              ) : null}
            </View>
            <TextField
              control={control}
              name={`lineItems.${index}.description`}
              label="Description"
              required
              maxLength={INVOICE_DESCRIPTION_MAX_LENGTH}
              disabled={disabled}
            />
            <View style={styles.lineNumbers}>
              <View style={styles.quantity}>
                <TextField
                  control={control}
                  name={`lineItems.${index}.quantity`}
                  label="Quantity"
                  required
                  inputMode="numeric"
                  maxLength={3}
                  hint="1 to 100"
                  disabled={disabled}
                />
              </View>
              <View style={styles.price}>
                <TextField
                  control={control}
                  name={`lineItems.${index}.unitPrice`}
                  label={`Unit price${unit}`}
                  required
                  inputMode="decimal"
                  maxLength={12}
                  hint="0 for no charge"
                  disabled={disabled}
                />
              </View>
              <View style={styles.lineAmount}>
                <Text style={styles.lineAmountLabel}>Amount</Text>
                <Text style={styles.lineAmountValue}>{formatMoney(amount, currency)}</Text>
              </View>
            </View>
          </View>
        );
      })}
      {listError ? <FormError message={listError} /> : null}
      {full ? (
        <Text style={styles.meta}>An invoice can have at most {INVOICE_MAX_LINE_ITEMS} lines.</Text>
      ) : (
        <View style={styles.addLine}>
          <Button
            label="Add line"
            icon="add"
            variant="secondary"
            size="sm"
            disabled={disabled}
            onPress={() => append({ ...EMPTY_LINE })}
          />
        </View>
      )}
    </View>
  );
}

function FormTotals({ control, currency }: { control: Control<InvoiceFormValues>; currency: string | null }) {
  const lines = useWatch({ control, name: 'lineItems' }) ?? [];
  const discountText = useWatch({ control, name: 'discount' });
  const subtotal = invoiceFormSubtotal(lines);
  const discount = parseMoneyInput(discountText) ?? 0;
  return (
    <View style={styles.totals}>
      <AmountRow label="Subtotal" value={formatMoney(subtotal, currency)} />
      {discount > 0 ? <AmountRow label="Discount" value={formatMoney(-discount, currency)} /> : null}
      <AmountRow label="Before tax" value={formatMoney(Math.max(0, subtotal - discount), currency)} strong />
      <Text style={styles.meta}>Tax at the clinic rate is added when the invoice is saved.</Text>
    </View>
  );
}

type InvoiceFormTarget =
  | { kind: 'create'; visit: InvoiceVisit }
  | { kind: 'edit'; invoice: Invoice };

function targetPatient(target: InvoiceFormTarget): { id: string; name: string | null } {
  return target.kind === 'create'
    ? { id: target.visit.patientSystemId, name: target.visit.patientName }
    : { id: target.invoice.patientSystemId, name: target.invoice.patientName };
}

/** A refusal that means what is on screen is out of date (the invoice or the visit changed meanwhile). */
function isStaleRefusal(error: ApiError | null): boolean {
  return !!error && (error.status === 404 || (error.status === 409 && error.code !== 'IDEMPOTENCY_IN_PROGRESS'));
}

/**
 * "Create invoice" for a completed consultation, or "Edit invoice" for a draft. One Idempotency-Key belongs to
 * each opening of the create form (the dialog mounts afresh every time it opens).
 */
function InvoiceFormDialog({ target, patientName, onClose, onCreated, onSaved, onStale }: {
  target:    InvoiceFormTarget;
  patientName: string;
  /** Called after a refusal that shows the lists or the invoice behind the dialog are out of date. */
  onStale:   () => void;
  onClose:   () => void;
  onCreated: (invoice: Invoice, created: boolean) => void;
  onSaved:   (invoice: Invoice) => void;
}) {
  const creating = target.kind === 'create';
  const currency = creating ? target.visit.currency : target.invoice.currency;
  const initial = useMemo(
    () => (target.kind === 'create' ? createFormValues(target.visit) : editFormValues(target.invoice)),
    // The form is filled once per opening.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );
  const { control, handleSubmit, setError, formState: { isDirty, errors } } = useForm<InvoiceFormValues>({
    resolver:      zodResolver(invoiceFormSchema),
    defaultValues: initial,
  });
  const [formError, setFormError] = useState<string | null>(null);

  const create = useCreateDoctorInvoice();
  const update = useUpdateDoctorInvoice();
  const pending = create.pending || update.pending;

  const submit = handleSubmit(async values => {
    setFormError(null);
    if (target.kind === 'create') {
      const body = toUpdateInvoiceRequest(values);
      const result = await create.run({
        consultationId: target.visit.consultationId,
        lineItems:      toInvoiceLineItems(values.lineItems),
        discount:       body.discount,
        notes:          body.notes,
      });
      if (result.ok) { onCreated(result.data.invoice, result.data.created); return; }
      if (result.error) setFormError(applyInvoiceFieldErrors(result.error, setError, values.lineItems.length));
      if (isStaleRefusal(result.error)) onStale();
      return;
    }
    const result = await update.run(target.invoice.invoiceId, toUpdateInvoiceRequest(values));
    if (result.ok) { onSaved(result.data); return; }
    if (result.error) setFormError(applyInvoiceFieldErrors(result.error, setError, values.lineItems.length));
    if (isStaleRefusal(result.error)) onStale();
  });

  const close = async () => {
    if (pending) return;
    if (isDirty && !(await confirmAction('Discard changes?', 'What you entered on this invoice will be lost.', 'Discard', true))) return;
    onClose();
  };

  // A changed price or quantity clears the form-level message about the totals.
  const lines = useWatch({ control, name: 'lineItems' });
  const lastLines = useRef(lines);
  useEffect(() => {
    if (lastLines.current !== lines && formError) setFormError(null);
    lastLines.current = lines;
  }, [lines, formError]);

  const listError = errors.lineItems?.message ?? errors.lineItems?.root?.message;

  return (
    <Modal
      open
      size="lg"
      title={creating ? 'Create invoice' : `Edit invoice ${target.invoice.invoiceNo}`}
      onClose={close}
      dismissOnBackdrop={false}
      busy={pending}
      footer={(
        <FormActions
          submitLabel={creating ? 'Create draft invoice' : 'Save changes'}
          submitIcon={creating ? 'add' : 'save-outline'}
          onSubmit={submit}
          submitting={pending}
          disabled={!creating && !isDirty}
          onCancel={close}
        />
      )}
    >
      <View style={styles.form}>
        <View>
          <InfoRow label="Patient" value={patientName} />
          {target.kind === 'create' ? (
            <>
              <InfoRow label="Visit" value={visitLabel(target.visit)} />
              <InfoRow
                label="Completed"
                value={formatBillingDateTime(target.visit.completedAt ?? target.visit.scheduledAt)}
                emptyText="Not recorded"
              />
            </>
          ) : (
            <InfoRow label="Status" value={invoiceStatusLabel(target.invoice.status)} />
          )}
        </View>
        <Text style={styles.body}>
          {creating
            ? 'The invoice is saved as a draft that only you can see. Review it, then send it to the patient.'
            : 'A draft can be changed until it is sent. The patient cannot see it yet.'}
        </Text>
        {creating && target.visit.suggestedFee === null ? (
          <Banner tone="info" text="The fee for this visit could not be found. Enter the price of the consultation." />
        ) : null}

        <LineRows control={control} currency={currency} disabled={pending} listError={listError} />

        <View style={styles.formSection}>
          <View style={styles.discount}>
            <TextField
              control={control}
              name="discount"
              label={`Discount${currency ? ` (${currency})` : ''}`}
              hint="Optional. Taken off the subtotal before tax."
              inputMode="decimal"
              maxLength={16}
              disabled={pending}
            />
          </View>
          <TextField
            control={control}
            name="notes"
            label="Note to the patient"
            hint="Optional. Shown on the invoice; do not add clinical details."
            multiline
            rows={3}
            maxLength={INVOICE_NOTES_MAX_LENGTH}
            showCount
            disabled={pending}
          />
        </View>

        <FormTotals control={control} currency={currency} />
        <FormError message={formError} />
      </View>
    </Modal>
  );
}

// ─── Send and cancel ──────────────────────────────────────────────────────────

function InvoiceSummary({ invoice }: { invoice: Invoice }) {
  return (
    <View>
      <InfoRow label="Invoice" value={invoice.invoiceNo} />
      <InfoRow label="Patient" value={invoice.patientName?.trim() || invoice.patientSystemId} />
      <InfoRow label="Total" value={formatMoney(invoice.total, invoice.currency)} />
      <InfoRow label="Status" value={invoiceStatusLabel(invoice.status)} />
    </View>
  );
}

function SendInvoiceDialog({ invoice, onClose, onSent, onStale }: {
  invoice: Invoice;
  onClose: () => void;
  onSent:  (invoice: Invoice) => void;
  onStale: () => void;
}) {
  const send = useSendDoctorInvoice();
  const confirm = async () => {
    const result = await send.run(invoice.invoiceId);
    if (result.ok) onSent(result.data);
    else if (isStaleRefusal(result.error)) onStale();
  };
  return (
    <Modal
      open
      title={`Send invoice ${invoice.invoiceNo}?`}
      onClose={onClose}
      busy={send.pending}
      footer={(
        <FormActions
          submitLabel="Send invoice"
          submitIcon="send-outline"
          onSubmit={confirm}
          submitting={send.pending}
          onCancel={onClose}
          cancelLabel="Not now"
        />
      )}
    >
      <View style={styles.form}>
        <InvoiceSummary invoice={invoice} />
        <Text style={styles.body}>
          The patient, and caregivers allowed to see their billing, will be able to see and pay this invoice. The
          due date is set from today. After it is sent the invoice can no longer be edited; you can still cancel it
          while nothing has been paid.
        </Text>
        <FormError message={send.error} />
      </View>
    </Modal>
  );
}

function CancelInvoiceDialog({ invoice, onClose, onCancelled, onStale }: {
  invoice:     Invoice;
  onClose:     () => void;
  onCancelled: (invoice: Invoice) => void;
  onStale:     () => void;
}) {
  const cancel = useCancelDoctorInvoice();
  const { control, handleSubmit, formState: { isDirty } } = useForm<CancelInvoiceFormValues>({
    resolver:      zodResolver(cancelInvoiceSchema),
    defaultValues: { reason: '' },
  });
  const submit = handleSubmit(async values => {
    const reason = values.reason.trim();
    const result = await cancel.run(invoice.invoiceId, { reason: reason || null });
    if (result.ok) onCancelled(result.data);
    else if (isStaleRefusal(result.error)) onStale();
  });
  const close = async () => {
    if (cancel.pending) return;
    if (isDirty && !(await confirmAction('Discard the reason?', 'The reason you typed will be lost.', 'Discard', true))) return;
    onClose();
  };
  const issued = invoice.status !== 'DRAFT';
  return (
    <Modal
      open
      title={`Cancel invoice ${invoice.invoiceNo}?`}
      onClose={close}
      dismissOnBackdrop={false}
      busy={cancel.pending}
      footer={(
        <FormActions
          submitLabel="Cancel invoice"
          submitIcon="close-circle-outline"
          destructive
          onSubmit={submit}
          submitting={cancel.pending}
          onCancel={close}
          cancelLabel="Keep invoice"
        />
      )}
    >
      <View style={styles.form}>
        <InvoiceSummary invoice={invoice} />
        <Text style={styles.body}>
          {issued
            ? 'The patient will see the invoice as cancelled and nothing will be owed on it. '
            : 'The draft is withdrawn; the patient never saw it. '}
          The consultation can be invoiced again afterwards. This cannot be undone.
        </Text>
        <TextField
          control={control}
          name="reason"
          label="Reason"
          hint={issued ? 'Optional. Shown to the patient on the cancelled invoice.' : 'Optional.'}
          multiline
          rows={3}
          maxLength={INVOICE_CANCEL_REASON_MAX_LENGTH}
          showCount
          disabled={cancel.pending}
        />
        <FormError message={cancel.error} />
      </View>
    </Modal>
  );
}

// ─── Uninvoiced consultations ─────────────────────────────────────────────────

function UninvoicedRow({ row, patientName, onCreate, onOpenConsultation }: {
  row:         UninvoicedConsultation;
  patientName: string;
  onCreate:    () => void;
  onOpenConsultation?: () => void;
}) {
  const type = APPOINTMENT_TYPE_LABEL[row.appointmentType] ?? 'Consultation';
  const when = row.completedAt
    ? `Completed ${formatBillingDateTime(row.completedAt)}`
    : `Visit ${formatBillingDateTime(row.scheduledAt, 'date not recorded')}`;
  const fee = formatMoney(row.suggestedFee, row.currency);
  return (
    <View style={styles.row}>
      <View style={styles.rowMain}>
        {/* Each row is a level-2 section under the page heading, named by its patient. */}
        <Text style={styles.rowTitle} numberOfLines={2} accessibilityRole="header" {...ariaLevel(2)}>{patientName}</Text>
        <Text style={styles.body}>{type}</Text>
        <Text style={styles.meta}>{when}</Text>
      </View>
      <View style={styles.rowSide}>
        <Text style={styles.meta}>Fee</Text>
        <Text style={styles.rowAmount}>{fee}</Text>
      </View>
      <View style={styles.rowActions}>
        {onOpenConsultation ? (
          <Button
            label="View consultation"
            icon="medical-outline"
            variant="ghost"
            size="sm"
            onPress={onOpenConsultation}
            accessibilityLabel={`View consultation with ${patientName}`}
          />
        ) : null}
        <Button
          label="Create invoice"
          icon="add"
          size="sm"
          onPress={onCreate}
          accessibilityLabel={`Create invoice for ${patientName}, ${type}, ${when}`}
        />
      </View>
    </View>
  );
}

// ─── Section ──────────────────────────────────────────────────────────────────

type Dialog =
  | { kind: 'form'; target: InvoiceFormTarget; /** The URL asked for this form (consultationId param). */ fromUrl: boolean }
  | { kind: 'send'; invoice: Invoice }
  | { kind: 'cancel'; invoice: Invoice };

export default function DoctorWebBilling(props: Partial<SectionProps>) {
  // The registry may render this screen with or without SectionProps; the shell's navigation fills the gaps.
  const sectionNav = useSectionNav();
  const params = props.params ?? sectionNav.params;
  const navigate = props.navigate ?? sectionNav.navigate;
  const theme = props.theme ?? ROLE_THEME.doctor;
  const { hasSection, setParams, replace } = sectionNav;
  const router = useRouter();

  const { user, error: userError, refetch: refetchUser } = useCurrentUser();
  const doctorId = user?.system_id ?? null;
  const patients = useDoctorPatientOptions();
  const nameOf = useCallback(
    (patientSystemId: string, given?: string | null) => given?.trim() || patients.nameOf(patientSystemId) || patientNameFallback(patientSystemId),
    [patients.nameOf],
  );

  const tab = tabOf(params.tab);
  const invoiceIdRaw = params.id;
  const showingInvoice = invoiceIdRaw !== undefined;
  const invoiceId = invoiceIdParam(invoiceIdRaw);
  const patient = patientParam(params.patient);
  const statusFilter = parseInvoiceFilter(params.status, 'doctor');
  const consultationId = showingInvoice ? null : intParam(params.consultationId);

  const [refreshKey, setRefreshKey] = useState(0);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const uninvoiced = useDoctorUninvoiced();
  const { refetch: refetchUninvoiced } = uninvoiced;

  const refreshAll = useCallback(() => {
    setRefreshKey(k => k + 1);
    refetchUninvoiced();
  }, [refetchUninvoiced]);

  // A patient param that is not a patient id is dropped rather than sent.
  useEffect(() => {
    if (params.patient !== undefined && patient === null) setParams({ patient: undefined });
  }, [params.patient, patient, setParams]);
  useEffect(() => {
    if (!showingInvoice && params.consultationId !== undefined && consultationId === null) setParams({ consultationId: undefined });
  }, [showingInvoice, params.consultationId, consultationId, setParams]);

  // ── Opening with consultationId: find its invoice or start "Create invoice" for it ──
  const lookup = useConsultationInvoiceLookup(consultationId, doctorId);
  const handledLookup = useRef<unknown>(null);
  useEffect(() => {
    const result = lookup.data;
    if (!result || handledLookup.current === result) return;
    handledLookup.current = result;
    if (result.kind === 'invoice') {
      setNotice({
        invoiceId: result.invoice.invoiceId,
        tone: 'info',
        text: `This consultation is billed on invoice ${result.invoice.invoiceNo}.`,
      });
      setParams({ consultationId: undefined, id: result.invoice.invoiceId });
    } else if (result.kind === 'create') {
      const visit = result.visit;
      setDialog({
        kind: 'form',
        fromUrl: true,
        target: { kind: 'create', visit },
      });
    }
  }, [lookup.data, setParams]);

  const dismissLookup = useCallback(() => setParams({ consultationId: undefined }), [setParams]);

  // ── Navigation between the list and one invoice ──
  const openInvoice = useCallback((id: number) => {
    setNotice(null);
    openedFromList.add(id);
    navigate('billing', { ...listParams(params), id });
  }, [navigate, params]);

  // The list is on screen again: nothing it opened earlier is still the entry before the current one.
  useEffect(() => {
    if (!showingInvoice) openedFromList.clear();
  }, [showingInvoice]);
  const backToList = useCallback(() => {
    setNotice(null);
    if (openedFromList.has(invoiceId) && Platform.OS === 'web' && router.canGoBack()) {
      openedFromList.delete(invoiceId);
      router.back();
      return;
    }
    replace('billing', listParams(params));
  }, [router, replace, params, invoiceId]);

  /** Shows an invoice in place (after creating it), without a history entry. */
  const showInvoice = useCallback((invoice: Invoice, message: Notice | null) => {
    setNotice(message);
    setParams({ consultationId: undefined, tab: undefined, id: invoice.invoiceId });
  }, [setParams]);

  // ── Dialog outcomes ──
  const closeDialog = useCallback(() => {
    if (dialog?.kind === 'form' && dialog.fromUrl) dismissLookup();
    setDialog(null);
  }, [dialog, dismissLookup]);

  const onCreated = useCallback((invoice: Invoice, created: boolean) => {
    setDialog(null);
    refreshAll();
    showInvoice(invoice, created
      ? { invoiceId: invoice.invoiceId, tone: 'success', text: 'Draft invoice created. Review it, then send it to the patient.' }
      : {
          invoiceId: invoice.invoiceId,
          tone: 'info',
          text: 'An invoice already existed for this visit, so no new invoice was created and your entries were not applied. It is shown here.',
        });
  }, [refreshAll, showInvoice]);

  const onSaved = useCallback((invoice: Invoice) => {
    setDialog(null);
    refreshAll();
    setNotice({ invoiceId: invoice.invoiceId, tone: 'success', text: 'The draft invoice was saved.' });
  }, [refreshAll]);

  const onSent = useCallback((invoice: Invoice) => {
    setDialog(null);
    refreshAll();
    setNotice({ invoiceId: invoice.invoiceId, tone: 'success', text: `Invoice ${invoice.invoiceNo} was sent to the patient.` });
  }, [refreshAll]);

  const onCancelled = useCallback((invoice: Invoice) => {
    setDialog(null);
    refreshAll();
    setNotice({
      invoiceId: invoice.invoiceId,
      tone: 'success',
      text: `Invoice ${invoice.invoiceNo} was cancelled. The consultation can be invoiced again from the Uninvoiced tab.`,
    });
  }, [refreshAll]);

  const startCreate = useCallback((row: UninvoicedConsultation) => {
    setDialog({
      kind: 'form',
      fromUrl: false,
      target: { kind: 'create', visit: visitFromUninvoiced(row) },
    });
  }, []);

  // ── Invoice actions (shown inside the detail for the issuing doctor) ──
  const invoiceActions = useCallback((ctx: InvoiceDetailContext) => {
    const invoice = ctx.invoice;
    if (!isIssuedBy(invoice, doctorId)) return null;
    const zeroDraft = invoice.status === 'DRAFT' && !canSendInvoice(invoice);
    return (
      <>
        {canEditInvoice(invoice) ? (
          <Button
            label="Edit"
            icon="create-outline"
            variant="secondary"
            onPress={() => setDialog({ kind: 'form', fromUrl: false, target: { kind: 'edit', invoice } })}
            accessibilityLabel={`Edit invoice ${invoice.invoiceNo}`}
          />
        ) : null}
        {canSendInvoice(invoice) ? (
          <Button
            label="Send to patient"
            icon="send-outline"
            onPress={() => setDialog({ kind: 'send', invoice })}
            accessibilityLabel={`Send to patient, invoice ${invoice.invoiceNo}`}
          />
        ) : null}
        {/* A payment that is still being confirmed would land on a cancelled invoice, so cancel waits for it. */}
        {canCancelInvoice(invoice) && !ctx.hasPendingPayment ? (
          <Button
            label="Cancel invoice"
            icon="close-circle-outline"
            variant="secondary"
            onPress={() => setDialog({ kind: 'cancel', invoice })}
            accessibilityLabel={`Cancel invoice ${invoice.invoiceNo}`}
          />
        ) : null}
        {zeroDraft ? <Text style={styles.meta}>Add a charge before sending: the total is zero.</Text> : null}
      </>
    );
  }, [doctorId]);

  // ── Tabs ──
  const uninvoicedCount = uninvoiced.loading || uninvoiced.error ? undefined : uninvoiced.total;
  const tabs = useMemo<TabItem<BillingTab>[]>(() => [
    { key: 'invoices',   label: 'Invoices' },
    { key: 'uninvoiced', label: 'Uninvoiced', badge: uninvoicedCount },
    { key: 'fees',       label: 'Fees' },
  ], [uninvoicedCount]);

  const patientOptions = useMemo(
    () => patients.options.map(o => ({ value: o.patientSystemId, label: o.fullName || o.patientSystemId })),
    [patients.options],
  );
  const consultationsAvailable = hasSection('consultations');
  const appointmentsAvailable = hasSection('appointments');

  // ── Status of a consultationId lookup, shown above the content ──
  let lookupBanner = null;
  if (consultationId !== null) {
    if (doctorId === null && userError) {
      lookupBanner = <Banner tone="danger" text={userError} action={{ label: 'Retry', onPress: refetchUser }} onDismiss={dismissLookup} />;
    } else if (doctorId === null || lookup.loading) {
      lookupBanner = <LoadingView compact label="Looking up the invoice for this consultation" />;
    } else if (lookup.error) {
      lookupBanner = <Banner tone="danger" text={lookup.error} action={{ label: 'Retry', onPress: lookup.refetch }} onDismiss={dismissLookup} />;
    } else if (lookup.data?.kind === 'unavailable') {
      lookupBanner = <Banner tone="warning" text={lookup.data.message} onDismiss={dismissLookup} />;
    }
  }

  // ── Content ──
  let content;
  if (showingInvoice) {
    const shownNotice = notice && notice.invoiceId === invoiceId ? notice : null;
    content = (
      <View style={styles.content}>
        {shownNotice ? <Banner tone={shownNotice.tone} text={shownNotice.text} onDismiss={() => setNotice(null)} /> : null}
        <InvoiceDetail
          invoiceId={invoiceId}
          viewer="doctor"
          actions={invoiceActions}
          onBack={backToList}
          backLabel="Back to invoices"
          refreshKey={refreshKey}
          onOpenConsultation={consultationsAvailable ? id => navigate('consultations', { id, mode: 'edit' }) : undefined}
          onOpenAppointment={appointmentsAvailable ? id => navigate('appointments', { id }) : undefined}
        />
      </View>
    );
  } else if (tab === 'uninvoiced') {
    content = (
      <View style={styles.content}>
        {patient ? (
          <Text style={styles.meta}>This list shows the completed consultations of all your patients.</Text>
        ) : null}
        <PagedList
          state={uninvoiced}
          keyOf={row => row.consultationId}
          label="Consultations without an invoice"
          noAccessMessage="You don't have access to these consultations."
          empty={{
            icon: 'checkmark-done-outline',
            title: 'Every completed consultation has an invoice',
            sub: 'Completed consultations that still need an invoice appear here.',
          }}
          renderItem={row => (
            <UninvoicedRow
              row={row}
              patientName={nameOf(row.patientSystemId, row.patientName)}
              onCreate={() => startCreate(row)}
              onOpenConsultation={consultationsAvailable
                ? () => navigate('consultations', { id: row.consultationId, mode: 'edit' })
                : undefined}
            />
          )}
        />
      </View>
    );
  } else if (tab === 'fees') {
    content = doctorId === null && userError ? (
      <ErrorView message={userError} onRetry={refetchUser} />
    ) : (
      <FeeEditor doctorSystemId={doctorId} onSaved={() => refetchUninvoiced()} />
    );
  } else {
    const listNotice = notice && notice.invoiceId === null ? notice : null;
    const patientName = patient ? nameOf(patient) : null;
    content = (
      <View style={styles.content}>
        {listNotice ? <Banner tone={listNotice.tone} text={listNotice.text} onDismiss={() => setNotice(null)} /> : null}
        <InvoiceList
          patientSystemId={patient}
          viewer="doctor"
          filter={statusFilter}
          onFilterChange={(key: InvoiceFilterKey) => setParams({ status: key === 'all' ? undefined : key })}
          onSelect={invoice => openInvoice(invoice.invoiceId)}
          refreshKey={refreshKey}
          emptyAction={patient ? undefined : {
            label: 'See consultations to invoice',
            icon: 'document-text-outline',
            onPress: () => setParams({ tab: 'uninvoiced' }),
          }}
          header={patient && patientName ? (
            <PatientChip name={patientName} onRemove={() => setParams({ patient: undefined })} />
          ) : patientOptions.length > 0 ? (
            <View style={styles.patientSelect}>
              <SelectField
                label="Patient"
                value=""
                onChange={value => setParams({ patient: value || undefined })}
                options={patientOptions}
                placeholder="All patients"
              />
            </View>
          ) : patients.error ? (
            <Button
              label="Retry loading the patient filter"
              icon="refresh-outline"
              variant="ghost"
              size="sm"
              onPress={patients.refetch}
            />
          ) : null}
        />
      </View>
    );
  }

  return (
    <Screen
      title="Billing"
      subtitle="Invoices for your consultations and the fees new invoices are priced from."
      theme={theme}
      actions={tab === 'fees' && !showingInvoice ? undefined : (
        <Button label="Refresh" icon="refresh-outline" variant="secondary" onPress={refreshAll} />
      )}
    >
      {lookupBanner}
      {showingInvoice ? null : (
        <Tabs
          tabs={tabs}
          active={tab}
          onChange={key => setParams({ tab: key === 'invoices' ? undefined : key })}
          label="Billing views"
        />
      )}
      {content}

      {dialog?.kind === 'form' ? (
        <InvoiceFormDialog
          target={dialog.target}
          patientName={nameOf(targetPatient(dialog.target).id, targetPatient(dialog.target).name)}
          onClose={closeDialog}
          onCreated={onCreated}
          onSaved={onSaved}
          onStale={refreshAll}
        />
      ) : null}
      {dialog?.kind === 'send' ? (
        <SendInvoiceDialog invoice={dialog.invoice} onClose={closeDialog} onSent={onSent} onStale={refreshAll} />
      ) : null}
      {dialog?.kind === 'cancel' ? (
        <CancelInvoiceDialog invoice={dialog.invoice} onClose={closeDialog} onCancelled={onCancelled} onStale={refreshAll} />
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: space.lg, minWidth: 0 },

  body: { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  meta: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },

  patientSelect: { maxWidth: 360, width: '100%' },
  chip: {
    flexDirection: 'row', alignItems: 'center', alignSelf: 'flex-start', gap: space.xs, maxWidth: '100%',
    paddingLeft: space.md, paddingRight: space.xs, paddingVertical: 2, borderWidth: 1, borderRadius: radius.pill,
  },
  chipText: { flexShrink: 1, minWidth: 0, fontSize: fontSize.sm, fontWeight: '600', color: colors.text },

  // Uninvoiced rows: the text, the fee and the buttons sit side by side on a wide screen and stack on a phone.
  row: {
    flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between',
    columnGap: space.lg, rowGap: space.sm, padding: space.lg, minWidth: 0,
    backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderRadius: radius.lg,
  },
  rowMain:    { flexGrow: 1, flexShrink: 1, flexBasis: 220, minWidth: 0, gap: space.xs },
  rowTitle:   { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  rowSide:    { flexGrow: 0, flexShrink: 1, minWidth: 0, gap: 2 },
  rowAmount:  { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  rowActions: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, flexBasis: '100%', justifyContent: 'flex-end' },

  form:        { gap: space.lg, minWidth: 0 },
  formSection: { gap: space.md, minWidth: 0 },
  formHeading: { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  line: {
    gap: space.sm, padding: space.md, minWidth: 0,
    borderWidth: 1, borderColor: colors.border, borderRadius: radius.md,
  },
  lineHead:    { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space.sm, minHeight: 32 },
  lineTitle:   { fontSize: fontSize.md, fontWeight: '600', color: colors.text },
  lineNumbers: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', columnGap: space.md, rowGap: space.sm, minWidth: 0 },
  quantity:    { flexGrow: 0, flexShrink: 1, flexBasis: 110, minWidth: 0 },
  price:       { flexGrow: 1, flexShrink: 1, flexBasis: 160, minWidth: 0, maxWidth: 260 },
  lineAmount:  { flexGrow: 1, flexShrink: 1, flexBasis: 120, minWidth: 0, gap: space.xs, alignItems: 'flex-end' },
  lineAmountLabel: { fontSize: fontSize.sm, fontWeight: '600', color: colors.text },
  lineAmountValue: { fontSize: fontSize.md, fontWeight: '700', color: colors.text, paddingTop: space.sm },
  addLine:     { flexDirection: 'row' },
  discount:    { maxWidth: 260, width: '100%' },
  totals:      { gap: space.xs, paddingTop: space.md, borderTopWidth: 1, borderTopColor: colors.border },
});
