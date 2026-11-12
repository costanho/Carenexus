import { z } from 'zod';
import type { CreateAppointmentBody } from '@/lib/appointments/types';
import { isAfterClinicNow } from '@/lib/time/clinic';

// Form rules for booking and rescheduling. They mirror the server's constraints on POST /api/appointments
// (CreateAppointmentRequest and the slot rules of AppointmentService) so a form the web accepts is not refused
// for its shape. Availability (taken times, the doctor's hours, time off) is decided by the server.

/** `ValidationPatterns.SYSTEM_ID`. */
export const SYSTEM_ID_PATTERN = /^(PAT|DOC|CGV|ADM)-\d{5,}$/;
/** `ValidationPatterns.PHONE`: an optional +, 7 to 15 digits; spaces, dashes, dots and brackets allowed. */
export const PHONE_PATTERN = /^\+?(?:[ .()-]*\d){7,15}[ .()-]*$/;
/** A clinic wall-clock start as the slots endpoint returns it and the booking endpoint expects it. */
export const SLOT_START_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:00$/;
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

export const APPOINTMENT_LIMITS = {
  minDurationMinutes:  5,
  maxDurationMinutes:  240,
  durationStepMinutes: 5,
  reasonMinLength:     5,
  reasonMaxLength:     2000,
  notesMaxLength:      2000,
  contactPhoneMaxLength: 20,
} as const;

export const APPOINTMENT_TYPES = ['IN_PERSON', 'VIDEO', 'PHONE'] as const;

const PAST_TIME_MESSAGE = 'This time has already passed. Please choose a later time.';

const slotStart = z.string()
  .min(1, 'Please select a time')
  .regex(SLOT_START_PATTERN, 'Please select a time');

export const bookingFormSchema = z
  .object({
    type:            z.enum(APPOINTMENT_TYPES, { error: 'Please select an appointment type' }),
    doctorSystemId:  z.string().min(1, 'Please select a doctor').regex(SYSTEM_ID_PATTERN, 'Please select a doctor'),
    date:            z.string().min(1, 'Please select a date').regex(DATE_PATTERN, 'Please select a date'),
    durationMinutes: z.number({ error: 'Please select a duration' })
      .int('Please select a duration')
      .min(APPOINTMENT_LIMITS.minDurationMinutes, 'Duration must be between 5 and 240 minutes')
      .max(APPOINTMENT_LIMITS.maxDurationMinutes, 'Duration must be between 5 and 240 minutes')
      .refine(v => v % APPOINTMENT_LIMITS.durationStepMinutes === 0, 'Duration must be in steps of 5 minutes'),
    slotStart,
    /** Chosen clinic location for an in-person visit; null when none is chosen or the clinic lists none. */
    facilityId:      z.number().int().positive('Please choose a clinic location').nullable(),
    /**
     * The number the doctor calls; used for PHONE only. It may be left empty: the server then uses the number on
     * file (users.phone, else patients.contact_phone) and answers CONTACT_PHONE_REQUIRED when there is none.
     */
    contactPhone:    z.string(),
    reasonForVisit:  z.string()
      .max(APPOINTMENT_LIMITS.reasonMaxLength, 'The reason must be at most 2000 characters')
      .refine(
        v => v.trim().length >= APPOINTMENT_LIMITS.reasonMinLength,
        'Please describe the reason for your visit (at least 5 characters)',
      ),
    notes:           z.string().max(APPOINTMENT_LIMITS.notesMaxLength, 'Notes must be at most 2000 characters'),
  })
  .superRefine((values, ctx) => {
    if (SLOT_START_PATTERN.test(values.slotStart)) {
      if (DATE_PATTERN.test(values.date) && values.slotStart.slice(0, 10) !== values.date) {
        ctx.addIssue({ code: 'custom', path: ['slotStart'], message: 'Please select a time on the chosen date' });
      } else if (!isAfterClinicNow(values.slotStart)) {
        ctx.addIssue({ code: 'custom', path: ['slotStart'], message: PAST_TIME_MESSAGE });
      }
    }
    if (values.type === 'PHONE') {
      const phone = values.contactPhone.trim();
      if (!phone) {
        // Empty is sent as null and the server falls back to the number on file.
      } else if (phone.length > APPOINTMENT_LIMITS.contactPhoneMaxLength) {
        ctx.addIssue({ code: 'custom', path: ['contactPhone'], message: 'The number must be at most 20 characters' });
      } else if (!PHONE_PATTERN.test(phone)) {
        ctx.addIssue({ code: 'custom', path: ['contactPhone'], message: 'Enter a valid phone number' });
      }
    }
  });

export type BookingFormValues = z.infer<typeof bookingFormSchema>;

/** The form's fields, for mapping the server's field errors onto them. */
export const BOOKING_FIELD_MAP = {
  doctorSystemId:  'doctorSystemId',
  scheduledAt:     'slotStart',
  durationMinutes: 'durationMinutes',
  type:            'type',
  facilityId:      'facilityId',
  contactPhone:    'contactPhone',
  reasonForVisit:  'reasonForVisit',
  notes:           'notes',
} as const satisfies Record<string, keyof BookingFormValues>;

/**
 * The POST /api/appointments body for a validated form. The location is sent for an in-person visit only and
 * the contact number for a phone appointment only; empty optional texts are sent as null, never as "".
 */
export function toCreateAppointmentBody(values: BookingFormValues, patientSystemId: string): CreateAppointmentBody {
  const notes = values.notes.trim();
  const phone = values.contactPhone.trim();
  return {
    patientSystemId,
    doctorSystemId:  values.doctorSystemId,
    facilityId:      values.type === 'IN_PERSON' ? values.facilityId : null,
    scheduledAt:     values.slotStart,
    durationMinutes: values.durationMinutes,
    type:            values.type,
    reasonForVisit:  values.reasonForVisit.trim(),
    notes:           notes || null,
    contactPhone:    values.type === 'PHONE' && phone ? phone : null,
  };
}

export const rescheduleFormSchema = z
  .object({
    date:      z.string().min(1, 'Please choose a new date').regex(DATE_PATTERN, 'Please choose a new date'),
    slotStart: z.string().min(1, 'Please choose a new time').regex(SLOT_START_PATTERN, 'Please choose a new time'),
  })
  .superRefine((values, ctx) => {
    if (!SLOT_START_PATTERN.test(values.slotStart)) return;
    if (DATE_PATTERN.test(values.date) && values.slotStart.slice(0, 10) !== values.date) {
      ctx.addIssue({ code: 'custom', path: ['slotStart'], message: 'Please choose a time on the chosen date' });
    } else if (!isAfterClinicNow(values.slotStart)) {
      ctx.addIssue({ code: 'custom', path: ['slotStart'], message: PAST_TIME_MESSAGE });
    }
  });

export type RescheduleFormValues = z.infer<typeof rescheduleFormSchema>;
