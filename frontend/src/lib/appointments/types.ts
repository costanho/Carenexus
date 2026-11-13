import type { CallSessionStatus } from '@/lib/calls/types';

// DTO types of the appointment, availability, facility and fee endpoints the booking screens call. Field names
// are the API's (camelCase); the snake_case view models of the older hooks are filled from these inside the hooks.

export type AppointmentType = 'IN_PERSON' | 'VIDEO' | 'PHONE';
export type AppointmentStatus = 'SCHEDULED' | 'RESCHEDULED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW';

/** `AppointmentResponse`. Date-times without an offset are clinic wall-clock values. */
export interface AppointmentDto {
  appointmentId:         number;
  patientSystemId:       string;
  patientName:           string | null;
  doctorSystemId:        string;
  /** "Dr. First Last"; null only when the doctor's account row is missing. */
  doctorName:            string | null;
  facilityId:            number | null;
  facilityName:          string | null;
  facilityAddress:       string | null;
  /** Clinic wall clock, `yyyy-MM-ddTHH:mm:ss`. */
  scheduledAt:           string;
  durationMinutes:       number;
  type:                  AppointmentType;
  status:                AppointmentStatus;
  reasonForVisit:        string | null;
  /** Legacy, always null. Calls use `callJoinRoute`. */
  videoConsultationLink: null;
  /** The number the doctor calls for a PHONE appointment; null for other types and for viewers who may not see it. */
  contactPhone:          string | null;
  reminderSent:          boolean;
  notes:                 string | null;
  createdAt:             string;
  updatedAt:             string;
  callStatus:            CallSessionStatus | null;
  callJoinRoute:         string | null;
  /** ISO instants with `Z`. */
  joinWindowOpensAt:     string | null;
  joinWindowClosesAt:    string | null;
}

/** `GET /api/appointments/booking-rules`: the clinic rules the server enforces for patient-side bookings. */
export interface BookingRules {
  timeZone:               string;
  /** First bookable hour (inclusive). */
  openHour:               number;
  /** Appointments must end by this hour (24 = end of day). */
  closeHour:              number;
  allowWeekends:          boolean;
  slotGranularityMinutes: number;
  minDurationMinutes:     number;
  maxDurationMinutes:     number;
  /** Booking horizon in days from the clinic's current time. */
  maxAdvanceDays:         number;
  /** False when no call provider is configured: VIDEO bookings are refused with 409 VIDEO_UNAVAILABLE. */
  videoCallsAvailable:    boolean;
}

export type AvailabilitySource = 'TEMPLATE' | 'CLINIC_HOURS';

export interface DoctorSlot {
  /** Clinic wall clock, `yyyy-MM-ddTHH:mm:ss`. Sent unchanged as `scheduledAt`. */
  start:     string;
  /** False when the time has passed, is beyond the horizon, inside time off or already taken. */
  available: boolean;
}

/** `GET /api/appointments/doctor/{doctorSystemId}/slots`. */
export interface DoctorSlots {
  doctorSystemId:  string;
  date:            string;
  timeZone:        string;
  source:          AvailabilitySource;
  slotMinutes:     number;
  durationMinutes: number;
  slots:           DoctorSlot[];
}

export interface DoctorSlotsQuery {
  durationMinutes?:      number;
  /** The appointment being moved, so it does not block its own neighbourhood. */
  excludeAppointmentId?: number | null;
}

/** `FacilityResponse`. `GET /api/facilities` lists active facilities only. */
export interface Facility {
  facilityId: number;
  name:       string;
  type:       string | null;
  address:    string | null;
  phone:      string | null;
  email:      string | null;
  isActive:   boolean;
  createdAt:  string;   // clinic wall-clock, no offset
  updatedAt:  string;   // clinic wall-clock, no offset
}

/** `FeeResponse`: one row per appointment type, always in the order VIDEO, IN_PERSON, PHONE. */
export interface ConsultationFee {
  appointmentType: AppointmentType;
  /** The fee that applies: the doctor's own, else the clinic default, else 0.00. */
  amount:          number;
  currency:        string;
  source:          'DOCTOR' | 'DEFAULT' | 'NONE';
  defaultAmount:   number;
  doctorSystemId:  string | null;
}

/** Body of `POST /api/appointments`. */
export interface CreateAppointmentBody {
  patientSystemId:  string;
  doctorSystemId:   string;
  /** Sent for IN_PERSON only; null otherwise and when the clinic has no facilities. */
  facilityId:       number | null;
  /** Clinic wall clock `yyyy-MM-ddTHH:mm:00`, never with an offset. */
  scheduledAt:      string;
  durationMinutes:  number;
  type:             AppointmentType;
  reasonForVisit:   string | null;
  notes:            string | null;
  /** PHONE only; null lets the server use the number on file. Never an empty string. */
  contactPhone:     string | null;
}

/** Filters of `GET /api/appointments`. */
export interface AppointmentListQuery {
  patientSystemId?: string;
  doctorSystemId?:  string;
  /** One or more statuses; sent comma-separated. */
  status?:          readonly AppointmentStatus[];
  /** Inclusive lower bound on `scheduledAt`, clinic wall clock. */
  from?:            string;
  /** Exclusive upper bound on `scheduledAt`, clinic wall clock. */
  to?:              string;
  /** Sort on `scheduledAt`; the server default is `desc`. */
  order?:           'asc' | 'desc';
}
