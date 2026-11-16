import { zodResolver } from '@hookform/resolvers/zod';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import {
  ariaPressed, Banner, Button, colors, decorative, fontSize, FormError, FormField, Icon, InfoRow, type IoniconsName,
  LoadingView, radius, type RoleTheme, SectionCard, SelectField, space, TextField, UiThemeProvider, useUiTheme,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { useBookingRules } from '@/hooks/use-doctor-slots';
import type { Doctor } from '@/hooks/use-doctors';
import { FACILITY_SEARCH_MAX_LENGTH, useFacilities } from '@/hooks/use-facilities';
import { useResource } from '@/hooks/use-resource';
import { apiError } from '@/lib/api/errors';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { useIdempotencyKey } from '@/lib/api/idempotency';
import { createAppointment, getConsultationFees, getPatientPhoneOnFile } from '@/lib/appointments/api';
import {
  APPOINTMENT_TYPE_LABEL, bookingFailureKind, bookingWindow, dayClosedReason, durationOptions, formatLongDate, slotTime,
} from '@/lib/appointments/rules';
import type { AppointmentDto, AppointmentType, CreateAppointmentBody, Facility } from '@/lib/appointments/types';
import { useDoctorName } from '@/lib/doctors/names';
import { formatMoney } from '@/lib/format/money';
import { CLINIC_ZONE_LABEL, clinicToday } from '@/lib/time/clinic';
import {
  APPOINTMENT_LIMITS, BOOKING_FIELD_MAP, bookingFormSchema, type BookingFormValues, toCreateAppointmentBody,
} from '@/lib/validation/appointment';
import { CalendarPicker } from './calendar-picker';
import { DoctorChooser } from './doctor-chooser';
import { SlotPicker } from './slot-picker';

export interface BookingFormProps {
  /** The patient the appointment is for (PAT-...). */
  patientSystemId: string;
  /**
   * The patient's name when the form books for someone else (a caregiver's loved one, a doctor's patient).
   * Omit it when the signed-in patient books for themselves: the form then says "you".
   */
  patientName?: string | null;
  /** Books with this doctor and shows no doctor search (a doctor booking into their own schedule). */
  fixedDoctorSystemId?: string | null;
  /** Clinic day ("yyyy-MM-dd") the calendar starts on, when that day can be booked. */
  initialDate?: string | null;
  /** Called with the appointment the server created. */
  onBooked: (appointment: AppointmentDto) => void;
  /** Accent colours of the surrounding dashboard. Defaults to the theme already provided by the screen. */
  theme?: RoleTheme;
}

const TYPE_OPTIONS: { value: AppointmentType; icon: IoniconsName; description: string }[] = [
  { value: 'IN_PERSON', icon: 'business-outline', description: 'Visit the clinic' },
  { value: 'VIDEO',     icon: 'videocam-outline', description: 'Consult from anywhere' },
  { value: 'PHONE',     icon: 'call-outline',     description: 'Audio consultation' },
];

const VIDEO_UNAVAILABLE_NOTE =
  'Video appointments are not available at the moment. Please choose an in-person or a phone appointment.';

/** How often, and how long apart, a booking the server is still processing is asked for again. */
const IN_PROGRESS_RETRIES = 2;
const IN_PROGRESS_WAIT_MS = 1500;

const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

function wait(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function ChoiceCard({ label, description, icon, selected, disabled, onPress }: {
  label: string; description?: string; icon?: IoniconsName; selected: boolean; disabled: boolean; onPress: () => void;
}) {
  const { accent, accentBg } = useUiTheme();
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      accessibilityRole="button"
      accessibilityLabel={description ? `${label}, ${description}` : label}
      accessibilityState={{ selected, disabled }}
      {...ariaPressed(selected)}
      style={[styles.choice, selected && { borderColor: accent, backgroundColor: accentBg }]}
    >
      {icon ? <Icon name={icon} size={22} color={selected ? accent : colors.textMuted} /> : null}
      <Text style={[styles.choiceLabel, selected && { color: accent, fontWeight: '700' }]}>{label}</Text>
      {description ? <Text style={styles.choiceDescription} {...decorative}>{description}</Text> : null}
    </Pressable>
  );
}

function BookingFormBody({
  patientSystemId, patientName, fixedDoctorSystemId, initialDate, onBooked,
}: Omit<BookingFormProps, 'theme'>) {
  const fixedDoctor = (fixedDoctorSystemId ?? '').trim();
  const forSomeoneElse = (patientName ?? '').trim();
  const rulesState = useBookingRules();
  const { rules } = rulesState;
  const today = clinicToday();
  const bookable = bookingWindow(rules, today);

  const defaults = useCallback((): BookingFormValues => ({
    type:            'IN_PERSON',
    doctorSystemId:  fixedDoctor,
    date:            initialDate && DATE_ONLY.test(initialDate) && dayClosedReason(initialDate, rules) === null ? initialDate : '',
    durationMinutes: 30,
    slotStart:       '',
    facilityId:      null,
    contactPhone:    '',
    reasonForVisit:  '',
    notes:           '',
  }), [fixedDoctor, initialDate, rules]);

  const {
    control, handleSubmit, setValue, getValues, setError, clearErrors, watch, reset,
    formState: { errors, isSubmitted },
  } = useForm<BookingFormValues>({ resolver: zodResolver(bookingFormSchema), defaultValues: defaults() });

  const type        = watch('type');
  const doctorId    = watch('doctorSystemId');
  const date        = watch('date');
  const duration    = watch('durationMinutes');
  const slotStart   = watch('slotStart');
  const facilityId  = watch('facilityId');
  const contactPhone = watch('contactPhone');

  const [doctor, setDoctor] = useState<Doctor | null>(null);
  const [facility, setFacility] = useState<Facility | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  /** The server's refusal of the chosen time, shown by the times until another one is chosen. */
  const [slotError, setSlotError] = useState<string | null>(null);
  const [slotRefresh, setSlotRefresh] = useState(0);
  // Once a type was chosen its data stays loaded, so switching back and forth does not ask again.
  const [phoneWanted, setPhoneWanted] = useState(false);
  const phoneEdited = useRef(false);

  const fixedDoctorName = useDoctorName(fixedDoctor || null);
  const doctorName = fixedDoctor ? fixedDoctorName : doctor?.full_name ?? '';

  const touch = { shouldDirty: true, shouldValidate: isSubmitted } as const;

  const clearSlot = useCallback(() => {
    setValue('slotStart', '', { shouldDirty: true });
    clearErrors('slotStart');
    setSlotError(null);
  }, [setValue, clearErrors]);

  // ── Clinic rules ──────────────────────────────────────────────────────────

  const durations = useMemo(() => durationOptions(rules), [rules]);
  useEffect(() => {
    if (!durations.includes(duration)) {
      setValue('durationMinutes', durations.includes(30) ? 30 : durations[0]);
      clearSlot();
    }
  }, [durations, duration, setValue, clearSlot]);

  // A date that the clinic's rules (once loaded) do not allow is cleared rather than left to fail on submit.
  useEffect(() => {
    if (date && dayClosedReason(date, rules, today) !== null) {
      setValue('date', '');
      clearSlot();
    }
  }, [date, rules, today, setValue, clearSlot]);

  const videoAvailable = rules.videoCallsAvailable;
  useEffect(() => {
    if (!videoAvailable && type === 'VIDEO') setValue('type', 'IN_PERSON');
  }, [videoAvailable, type, setValue]);
  const typeOptions = TYPE_OPTIONS.filter(t => t.value !== 'VIDEO' || videoAvailable);

  // ── Phone: the number on file is offered, the patient can change it ───────

  const phoneOnFile = useResource(
    () => getPatientPhoneOnFile(patientSystemId),
    [patientSystemId],
    { enabled: phoneWanted && !!patientSystemId },
  );
  useEffect(() => {
    if (phoneOnFile.data && !phoneEdited.current && !getValues('contactPhone')) {
      setValue('contactPhone', phoneOnFile.data);
    }
  }, [phoneOnFile.data, getValues, setValue]);

  // ── In person: clinic locations ───────────────────────────────────────────

  const facilities = useFacilities();
  const facilityRequired = type === 'IN_PERSON' && !facilities.loading && !facilities.error && facilities.total > 0;
  const onlyFacility = facilities.total === 1 ? facilities.facilities[0] ?? null : null;
  useEffect(() => {
    if (onlyFacility && getValues('facilityId') === null) {
      setFacility(onlyFacility);
      setValue('facilityId', onlyFacility.facilityId);
    }
  }, [onlyFacility, getValues, setValue]);

  const facilityOptions = useMemo(() => {
    const listed = facilities.facilities.slice();
    // The chosen location stays in the list while a search no longer matches it.
    if (facility && !listed.some(f => f.facilityId === facility.facilityId)) listed.unshift(facility);
    return listed.map(f => ({ value: String(f.facilityId), label: f.address ? `${f.name}, ${f.address}` : f.name }));
  }, [facilities.facilities, facility]);

  function chooseFacility(id: string) {
    const chosen = facilities.facilities.find(f => String(f.facilityId) === id) ?? (facility && String(facility.facilityId) === id ? facility : null);
    setFacility(chosen);
    setValue('facilityId', chosen ? chosen.facilityId : null, touch);
    if (chosen) clearErrors('facilityId');
  }

  // ── Fee ───────────────────────────────────────────────────────────────────

  const fees = useResource(
    () => getConsultationFees(doctorId),
    [doctorId],
    { enabled: !!doctorId, errorMessage: 'Could not load the consultation fee.' },
  );
  const fee = fees.data?.find(f => f.appointmentType === type) ?? null;

  // ── Choices that reset the time ───────────────────────────────────────────

  function chooseType(next: AppointmentType) {
    setValue('type', next, touch);
    if (next === 'PHONE') setPhoneWanted(true);
    setFormError(null);
  }

  function chooseDoctor(next: Doctor) {
    if (next.doctor_system_id === doctorId) return;
    setDoctor(next);
    setValue('doctorSystemId', next.doctor_system_id, touch);
    clearSlot();
  }

  function chooseDate(next: string) {
    if (next === date) return;
    setValue('date', next, touch);
    clearSlot();
  }

  function chooseDuration(next: number) {
    if (next === duration) return;
    setValue('durationMinutes', next, touch);
    clearSlot();
  }

  function chooseSlot(start: string) {
    setValue('slotStart', start, touch);
    setSlotError(null);
    if (start) clearErrors('slotStart');
  }

  // ── Submit ────────────────────────────────────────────────────────────────

  // One key for this open form: a retried submit repeats it, so the server never books the same visit twice.
  const key = useIdempotencyKey();
  const book = useAction(async (body: CreateAppointmentBody) => {
    for (let attempt = 0; ; attempt++) {
      try {
        return await createAppointment(body, key.current());
      } catch (e) {
        // The first request is still running on the server: wait and ask again with the same key.
        if (attempt >= IN_PROGRESS_RETRIES || apiError(e).code !== 'IDEMPOTENCY_IN_PROGRESS') throw e;
        await wait(IN_PROGRESS_WAIT_MS);
      }
    }
  }, { errorMessage: 'Failed to book appointment. Please try again.' });

  const submit = handleSubmit(async values => {
    setFormError(null);
    setSlotError(null);
    if (facilityRequired && values.facilityId === null) {
      setError('facilityId', { type: 'required', message: 'Please choose a clinic location' });
      return;
    }

    const result = await book.run(toCreateAppointmentBody(values, patientSystemId));
    if (result.ok) {
      key.renew();
      phoneEdited.current = false;
      setDoctor(null);
      setFacility(null);
      reset(defaults());
      setSlotRefresh(n => n + 1);
      onBooked(result.data);
      return;
    }
    const failure = result.error;
    if (!failure) return;

    switch (bookingFailureKind(failure)) {
      case 'SLOT_UNAVAILABLE':
        // Taken in the meantime, or outside the doctor's hours: show why and offer the day's current times.
        setValue('slotStart', '');
        setSlotError(failure.message);
        setSlotRefresh(n => n + 1);
        break;
      case 'VIDEO_UNAVAILABLE':
        rulesState.refetch();
        setValue('type', 'IN_PERSON');
        setFormError(failure.message);
        break;
      case 'CONTACT_PHONE_REQUIRED':
        setError('contactPhone', { type: 'server', message: failure.message }, { shouldFocus: true });
        break;
      default:
        // A location that was deactivated meanwhile answers 404: the list is loaded again.
        if (failure.status === 404 && values.type === 'IN_PERSON' && values.facilityId !== null) facilities.refetch();
        setFormError(applyFieldErrors(failure, setError, BOOKING_FIELD_MAP));
    }
  });

  const busy = book.pending;
  const who = forSomeoneElse || 'you';
  const phoneShown = contactPhone.trim();

  return (
    <View style={styles.form}>
      {rulesState.error && !rulesState.loaded ? (
        <Banner
          tone="warning"
          text="Could not load the clinic's booking rules. Days and options the clinic does not allow are refused when you book."
          action={{ label: 'Retry', onPress: rulesState.refetch }}
        />
      ) : null}

      <SectionCard title="Appointment Type">
        <View style={styles.choiceRow} role="group" aria-label="Appointment type">
          {typeOptions.map(t => (
            <ChoiceCard
              key={t.value}
              label={APPOINTMENT_TYPE_LABEL[t.value]}
              description={t.description}
              icon={t.icon}
              selected={type === t.value}
              disabled={busy}
              onPress={() => chooseType(t.value)}
            />
          ))}
        </View>
        {!videoAvailable ? <Banner tone="info" text={VIDEO_UNAVAILABLE_NOTE} /> : null}
        <FormError message={errors.type?.message} />
      </SectionCard>

      <SectionCard title={fixedDoctor ? 'Doctor' : 'Select Doctor'}>
        {fixedDoctor ? (
          <InfoRow label="Doctor" value={doctorName} />
        ) : (
          <>
            <DoctorChooser selected={doctor} onSelect={chooseDoctor} disabled={busy} />
            <FormError message={errors.doctorSystemId?.message} />
          </>
        )}
      </SectionCard>

      <SectionCard title="Duration">
        <View style={styles.durationRow} role="group" aria-label="Duration">
          {durations.map(d => (
            <ChoiceCard
              key={d}
              label={`${d} min`}
              selected={duration === d}
              disabled={busy}
              onPress={() => chooseDuration(d)}
            />
          ))}
        </View>
        <FormError message={errors.durationMinutes?.message} />
      </SectionCard>

      <SectionCard
        title="Date & Time"
        subtitle={`Bookings open up to ${rules.maxAdvanceDays} days ahead${rules.allowWeekends ? '' : ', Monday to Friday'}.`}
      >
        <View style={styles.dateTime}>
          <View style={styles.dateColumn}>
            <FormField label="Select Date" required error={errors.date?.message}>
              <View style={[styles.pickerCard, !!errors.date && styles.pickerCardInvalid]}>
                <CalendarPicker
                  value={date}
                  onChange={chooseDate}
                  minDate={bookable.firstDay}
                  maxDate={bookable.lastDay}
                  disabledReason={d => dayClosedReason(d, rules, today)}
                  disabled={busy}
                />
              </View>
            </FormField>
          </View>
          <View style={styles.timeColumn}>
            <View style={[styles.pickerCard, !!(errors.slotStart || slotError) && styles.pickerCardInvalid]}>
              <SlotPicker
                label="Select Time"
                doctorSystemId={doctorId}
                date={date}
                durationMinutes={duration}
                value={slotStart}
                onChange={chooseSlot}
                refreshToken={slotRefresh}
                error={slotError ?? errors.slotStart?.message}
                disabled={busy}
              />
            </View>
          </View>
        </View>
      </SectionCard>

      {type === 'PHONE' ? (
        <SectionCard title="Contact Number">
          <TextField
            label="Contact number"
            value={contactPhone}
            onChange={text => {
              phoneEdited.current = true;
              setValue('contactPhone', text, touch);
            }}
            error={errors.contactPhone?.message}
            hint={phoneOnFile.loading
              ? 'Looking up the number on file…'
              : phoneShown
                ? `The doctor will call ${who} on this number at the appointment time.`
                : phoneOnFile.error
                  ? 'We could not look up the number on file. Enter a number, or leave this empty to use the one the clinic has on record.'
                  : 'Leave this empty to use the number the clinic has on record.'}
            placeholder="+263 77 123 4567"
            inputMode="tel"
            autoComplete="tel"
            maxLength={APPOINTMENT_LIMITS.contactPhoneMaxLength}
            disabled={busy}
          />
        </SectionCard>
      ) : null}

      {type === 'IN_PERSON' && (facilities.loading || facilities.error || facilities.total > 0) ? (
        <SectionCard title="Clinic Location">
          {facilities.loading ? (
            <LoadingView compact label="Loading clinic locations…" />
          ) : facilities.error && facilities.total === 0 ? (
            <>
              <Banner tone="danger" text={facilities.error} action={{ label: 'Retry', onPress: facilities.refetch }} />
              <Text style={styles.note}>You can still book without choosing a location.</Text>
            </>
          ) : (
            <>
              {facilities.searchable ? (
                <TextField
                  label="Search locations"
                  value={facilities.query}
                  onChange={facilities.setQuery}
                  placeholder="Search by name or address"
                  maxLength={FACILITY_SEARCH_MAX_LENGTH}
                  hint={facilities.searching ? 'Searching…'
                    : facilities.error ? facilities.error
                    : facilities.query.trim() && facilities.facilities.length === 0 ? 'No locations match your search.'
                    : undefined}
                  disabled={busy}
                />
              ) : null}
              <SelectField
                label="Clinic location"
                value={facilityId === null ? '' : String(facilityId)}
                onChange={chooseFacility}
                options={facilityOptions}
                placeholder="Select a clinic location"
                error={errors.facilityId?.message}
                required
                disabled={busy}
              />
            </>
          )}
        </SectionCard>
      ) : null}

      <SectionCard title="Visit Details">
        <TextField<BookingFormValues>
          control={control}
          name="reasonForVisit"
          label="Reason for visit"
          placeholder="Describe your symptoms or reason for the appointment..."
          multiline
          rows={4}
          maxLength={APPOINTMENT_LIMITS.reasonMaxLength}
          showCount
          required
          disabled={busy}
        />
        <TextField<BookingFormValues>
          control={control}
          name="notes"
          label="Additional notes (optional)"
          placeholder="Any additional information for the doctor..."
          multiline
          rows={3}
          maxLength={APPOINTMENT_LIMITS.notesMaxLength}
          showCount
          disabled={busy}
        />
      </SectionCard>

      <SectionCard title="Summary">
        {forSomeoneElse ? <InfoRow label="Patient" value={forSomeoneElse} /> : null}
        <InfoRow label="Doctor" value={doctorName} emptyText="Not chosen yet" />
        <InfoRow label="Type" value={APPOINTMENT_TYPE_LABEL[type]} />
        <InfoRow label="Date" value={date ? formatLongDate(date) : ''} emptyText="Not chosen yet" />
        <InfoRow label="Time" value={slotStart ? `${slotTime(slotStart)} ${CLINIC_ZONE_LABEL}` : ''} emptyText="Not chosen yet" />
        <InfoRow label="Duration" value={`${duration} minutes`} />
        {type === 'IN_PERSON' && facility ? (
          <InfoRow label="Location" value={facility.address ? `${facility.name}, ${facility.address}` : facility.name} />
        ) : null}
        {type === 'PHONE' ? (
          <InfoRow
            label="Phone call"
            value={phoneShown
              ? `The doctor will call ${who} on ${phoneShown}.`
              : `The doctor will call ${who} on the number the clinic has on record.`}
          />
        ) : null}
        <InfoRow label="Consultation fee">
          {!doctorId ? (
            <Text style={styles.summaryMuted}>Choose a doctor to see the fee</Text>
          ) : fees.loading ? (
            <Text style={styles.summaryMuted}>Loading…</Text>
          ) : fees.error ? (
            <View style={styles.feeError}>
              <Text style={styles.summaryMuted}>{fees.error}</Text>
              <Button label="Retry" variant="ghost" size="sm" onPress={fees.refetch} accessibilityLabel="Retry loading the consultation fee" />
            </View>
          ) : !fee || fee.source === 'NONE' ? (
            <Text style={styles.summaryMuted}>No fee has been set for this type of appointment</Text>
          ) : (
            <Text style={styles.summaryValue}>{formatMoney(fee.amount, fee.currency)}</Text>
          )}
        </InfoRow>
      </SectionCard>

      <FormError message={formError} />

      <Button
        label={busy ? 'Booking…' : 'Book Appointment'}
        icon="calendar-outline"
        onPress={submit}
        loading={busy}
        fullWidth
      />
    </View>
  );
}

/**
 * The booking form shared by the patient, caregiver and doctor screens: appointment type, doctor, duration,
 * date and one of the doctor's free times, then the details of the visit. The time is cleared whenever the
 * doctor, the date or the duration changes, because the offered times depend on all three.
 */
export function BookingForm({ theme, ...props }: BookingFormProps) {
  return (
    <UiThemeProvider theme={theme}>
      <BookingFormBody {...props} />
    </UiThemeProvider>
  );
}

const styles = StyleSheet.create({
  form: { gap: space.lg, minWidth: 0 },
  note: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },

  choiceRow:   { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  durationRow: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  choice: {
    flexGrow: 1, flexShrink: 1, flexBasis: 96, minWidth: 0, minHeight: 44, alignItems: 'center', justifyContent: 'center',
    gap: space.xs, paddingVertical: space.md, paddingHorizontal: space.sm,
    borderWidth: 1, borderColor: colors.borderInput, borderRadius: radius.lg, backgroundColor: colors.surface,
  },
  choiceLabel:       { fontSize: fontSize.sm, fontWeight: '600', color: colors.textMuted, textAlign: 'center' },
  choiceDescription: { fontSize: fontSize.xs, color: colors.textMuted, textAlign: 'center' },

  dateTime:    { flexDirection: 'row', flexWrap: 'wrap', gap: space.lg, alignItems: 'flex-start' },
  dateColumn:  { flexGrow: 1, flexShrink: 1, flexBasis: 280, minWidth: 0, maxWidth: '100%' },
  timeColumn:  { flexGrow: 1, flexShrink: 1, flexBasis: 280, minWidth: 0, maxWidth: '100%' },
  pickerCard:  { borderWidth: 1, borderColor: colors.border, borderRadius: radius.lg, padding: space.md, backgroundColor: colors.surface },
  pickerCardInvalid: { borderColor: colors.danger },

  summaryValue: { fontSize: fontSize.md, fontWeight: '600', color: colors.text, lineHeight: 20 },
  summaryMuted: { fontSize: fontSize.md, color: colors.textSubtle, lineHeight: 20 },
  feeError:     { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
});
