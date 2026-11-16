import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import {
  Banner, colors, ErrorView, fontSize, FormActions, FormError, LoadingView, SectionCard, space, StatusBadge, TextField,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { useResource } from '@/hooks/use-resource';
import { getFees, updateDoctorFees } from '@/lib/billing/api';
import { APPOINTMENT_TYPE_LABEL, FEE_SOURCE_LABEL } from '@/lib/billing/format';
import type { AppointmentType, Fee } from '@/lib/billing/types';
import { formatMoney } from '@/lib/format/money';
import {
  FEE_TYPES, feeFormSchema, type FeeFormValues, moneyInputValue, toUpdateFeesRequest,
} from '@/lib/validation/billing';

export interface FeeEditorProps {
  /** The signed-in doctor's own system id. Nothing loads until it is known; the server saves for the caller only. */
  doctorSystemId: string | null | undefined;
  /** Called with the doctor's resolved fees after a successful save. */
  onSaved?: (fees: Fee[]) => void;
}

const EMPTY_FORM: FeeFormValues = { VIDEO: '', IN_PERSON: '', PHONE: '' };

function feeOf(fees: readonly Fee[] | null, type: AppointmentType): Fee | null {
  return fees?.find(f => f.appointmentType === type) ?? null;
}

/** The form shows only the doctor's own overrides; a type that follows the clinic default is left blank. */
function formValues(fees: readonly Fee[] | null): FeeFormValues {
  const values = { ...EMPTY_FORM };
  for (const type of FEE_TYPES) {
    const fee = feeOf(fees, type);
    values[type] = fee && fee.source === 'DOCTOR' ? moneyInputValue(fee.amount) : '';
  }
  return values;
}

function hintFor(fee: Fee | null): string {
  if (!fee) return 'Leave blank to use the clinic default.';
  return `Leave blank to use the clinic default of ${formatMoney(fee.defaultAmount, fee.currency)}. Enter 0 for a free consultation.`;
}

/** Maps "fees[1].amount" from the server's validation errors to the form field of that row. */
function serverFieldErrors(fieldErrors: Record<string, string> | undefined): Partial<Record<AppointmentType, string>> {
  const out: Partial<Record<AppointmentType, string>> = {};
  for (const [name, message] of Object.entries(fieldErrors ?? {})) {
    const match = /^fees\[(\d+)]\.amount$/.exec(name);
    const type = match ? FEE_TYPES[Number(match[1])] : undefined;
    if (type) out[type] = message;
  }
  return out;
}

/**
 * A doctor's consultation fees, one per appointment type. A fee of the doctor's own replaces the clinic default;
 * leaving a field blank removes it so the default applies again. New invoices are priced from these fees.
 * Renders loading and failed (with Retry) states, validates amounts as the server does and fits a phone.
 */
export function FeeEditor({ doctorSystemId, onSaved }: FeeEditorProps) {
  const doctorId = doctorSystemId?.trim() || null;
  const fees = useResource<Fee[]>(
    () => getFees(doctorId),
    [doctorId],
    { enabled: doctorId !== null, errorMessage: 'We could not load your fees. Please try again.' },
  );

  const { control, handleSubmit, reset, setError, formState: { isDirty } } = useForm<FeeFormValues>({
    resolver:      zodResolver(feeFormSchema),
    defaultValues: EMPTY_FORM,
  });
  const [formError, setFormError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  const save = useAction(
    (values: FeeFormValues) => updateDoctorFees(toUpdateFeesRequest(values)),
    { errorMessage: 'We could not save your fees. Please try again.' },
  );

  // Fill the form when the fees arrive (and again after a reload).
  const loaded = fees.data;
  useEffect(() => {
    if (loaded) reset(formValues(loaded));
  }, [loaded, reset]);

  const submit = handleSubmit(async values => {
    setFormError(null);
    setSaved(false);
    const result = await save.run(values);
    if (result.ok) {
      fees.setData(result.data);
      reset(formValues(result.data));
      setSaved(true);
      onSaved?.(result.data);
      return;
    }
    if (!result.error) return;
    const byField = serverFieldErrors(result.error.fieldErrors);
    const types = Object.keys(byField) as AppointmentType[];
    types.forEach((type, index) => {
      setError(type, { type: 'server', message: byField[type] }, { shouldFocus: index === 0 });
    });
    setFormError(types.length > 0 ? null : result.error.message);
  });

  const discard = () => {
    setFormError(null);
    setSaved(false);
    reset(formValues(fees.data));
  };

  let body;
  if (doctorId === null || fees.loading) {
    body = <LoadingView compact label="Loading your fees" />;
  } else if (fees.data === null) {
    body = <ErrorView compact message={fees.error ?? 'We could not load your fees. Please try again.'} onRetry={fees.refetch} />;
  } else {
    const currency = fees.data[0]?.currency ?? null;
    body = (
      <View style={styles.form}>
        {fees.error ? <Banner tone="danger" text={fees.error} action={{ label: 'Retry', onPress: fees.refetch }} /> : null}
        {saved ? <Banner tone="success" text="Your fees were saved." onDismiss={() => setSaved(false)} /> : null}
        <View style={styles.rows}>
          {FEE_TYPES.map(type => {
            const fee = feeOf(fees.data, type);
            return (
              <View key={type} style={styles.row}>
                <View style={styles.field}>
                  <TextField
                    control={control}
                    name={type}
                    label={`${APPOINTMENT_TYPE_LABEL[type]} fee${currency ? ` (${currency})` : ''}`}
                    hint={hintFor(fee)}
                    inputMode="decimal"
                    maxLength={12}
                    disabled={save.pending}
                    onSubmit={submit}
                  />
                </View>
                {fee ? (
                  <View style={styles.current}>
                    <Text style={styles.currentLabel}>Charged now</Text>
                    <Text style={styles.currentAmount}>{formatMoney(fee.amount, fee.currency)}</Text>
                    <StatusBadge
                      label={FEE_SOURCE_LABEL[fee.source] ?? fee.source}
                      tone={fee.source === 'DOCTOR' ? 'info' : 'neutral'}
                      icon={null}
                    />
                  </View>
                ) : null}
              </View>
            );
          })}
        </View>
        <FormError message={formError} />
        <FormActions
          submitLabel="Save fees"
          onSubmit={submit}
          submitting={save.pending}
          disabled={!isDirty}
          onCancel={isDirty ? discard : undefined}
          cancelLabel="Discard changes"
        />
      </View>
    );
  }

  return (
    <SectionCard
      title="Consultation fees"
      subtitle="What a new invoice charges for each type of consultation. Invoices that already exist are not changed."
    >
      {body}
    </SectionCard>
  );
}

const styles = StyleSheet.create({
  form: { gap: space.lg, minWidth: 0 },
  rows: { gap: space.lg, minWidth: 0 },
  // The field and the current fee sit side by side on a wide screen and stack on a phone.
  row:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', columnGap: space.xl, rowGap: space.sm, minWidth: 0 },
  field: { flexGrow: 1, flexShrink: 1, flexBasis: 260, minWidth: 0, maxWidth: 420 },
  current:       { flexGrow: 0, flexShrink: 1, flexBasis: 160, minWidth: 0, gap: space.xs },
  currentLabel:  { fontSize: fontSize.sm, fontWeight: '600', color: colors.text },
  currentAmount: { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
});
