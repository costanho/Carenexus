import { withIdempotencyKey } from '@/lib/api/idempotency';
import { fetchPage } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import { api } from '@/lib/auth/auth.interceptor';
import type {
  AppointmentDto, AppointmentListQuery, BookingRules, ConsultationFee, CreateAppointmentBody, DoctorSlots,
  DoctorSlotsQuery, Facility,
} from './types';

// Typed client for the endpoints the booking and appointment screens call. Every function rejects with the
// axios error; show it with `apiError` / `apiErrorMessage` from '@/lib/api/errors'.

/** GET /api/appointments/booking-rules */
export async function getBookingRules(): Promise<BookingRules> {
  const { data } = await api.get<BookingRules>('/api/appointments/booking-rules');
  return data;
}

/** GET /api/appointments/doctor/{doctorSystemId}/slots?date=yyyy-MM-dd */
export async function getDoctorSlots(
  doctorSystemId: string,
  date: string,
  query: DoctorSlotsQuery = {},
): Promise<DoctorSlots> {
  const params: Record<string, string | number> = { date };
  if (query.durationMinutes) params.durationMinutes = query.durationMinutes;
  if (query.excludeAppointmentId) params.excludeAppointmentId = query.excludeAppointmentId;
  const { data } = await api.get<DoctorSlots>(
    `/api/appointments/doctor/${encodeURIComponent(doctorSystemId)}/slots`, { params },
  );
  return { ...data, slots: Array.isArray(data?.slots) ? data.slots : [] };
}

/** GET /api/appointments: the canonical paged list (newest first unless `order` is `asc`). */
export function listAppointments(
  query: AppointmentListQuery,
  page: number,
  size: number,
): Promise<PageResponse<AppointmentDto>> {
  return fetchPage<AppointmentDto>('/api/appointments', page, size, {
    patientSystemId: query.patientSystemId,
    doctorSystemId:  query.doctorSystemId,
    status:          query.status && query.status.length > 0 ? query.status.join(',') : undefined,
    from:            query.from,
    to:              query.to,
    order:           query.order,
  });
}

/** GET /api/appointments/{id} */
export async function getAppointment(appointmentId: number): Promise<AppointmentDto> {
  const { data } = await api.get<AppointmentDto>(`/api/appointments/${appointmentId}`);
  return data;
}

/**
 * POST /api/appointments. `idempotencyKey` is the key of the open form: the same key is sent again when the
 * submit is retried, so a repeat returns the stored appointment instead of booking twice.
 */
export async function createAppointment(body: CreateAppointmentBody, idempotencyKey: string): Promise<AppointmentDto> {
  const { data } = await api.post<AppointmentDto>('/api/appointments', body, withIdempotencyKey(idempotencyKey));
  return data;
}

/** PUT /api/appointments/{id} with a new start time (`slot.start`, clinic wall clock). Returns the updated row. */
export async function moveAppointment(appointmentId: number, scheduledAt: string): Promise<AppointmentDto> {
  const { data } = await api.put<AppointmentDto>(`/api/appointments/${appointmentId}`, { scheduledAt });
  return data;
}

/** DELETE /api/appointments/{id}: soft cancel (status becomes CANCELLED). Answers 204 with no body. */
export async function deleteAppointment(appointmentId: number): Promise<void> {
  await api.delete(`/api/appointments/${appointmentId}`);
}

/** GET /api/facilities: active facilities, by name. `query` matches the name or the address. */
export function listFacilities(page: number, size: number, query?: string): Promise<PageResponse<Facility>> {
  return fetchPage<Facility>('/api/facilities', page, size, { query: query?.trim() || undefined });
}

/** GET /api/billing/fees: three rows (VIDEO, IN_PERSON, PHONE) resolved for the doctor, or the clinic defaults. */
export async function getConsultationFees(doctorSystemId?: string | null): Promise<ConsultationFee[]> {
  const { data } = await api.get<ConsultationFee[]>('/api/billing/fees', {
    params: doctorSystemId ? { doctorSystemId } : undefined,
  });
  return Array.isArray(data) ? data : [];
}

/**
 * The phone number on the patient's account (GET /api/profile/user/{systemId}), used to prefill the contact
 * number of a phone appointment. Null when the account has none.
 */
export async function getPatientPhoneOnFile(patientSystemId: string): Promise<string | null> {
  const { data } = await api.get<{ phone?: string | null }>(
    `/api/profile/user/${encodeURIComponent(patientSystemId)}`,
  );
  const phone = typeof data?.phone === 'string' ? data.phone.trim() : '';
  return phone || null;
}
