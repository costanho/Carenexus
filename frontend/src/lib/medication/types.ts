// Medication schedule DTOs, straight from the API (camelCase): /api/medication-schedules and
// /api/prescriptions/{id}/schedule. A dose is one medication at one time of one clinic day.

/** Wire status of a dose. PENDING is shown as "Due". */
export type DoseStatus = 'PENDING' | 'TAKEN' | 'MISSED';

export interface MedicationDose {
  /** Dose id; not stable for future doses across a schedule edit, so it is never kept across a reload. */
  scheduleId:       number;
  prescriptionId:   number;
  patientSystemId:  string;
  medicationName:   string;
  dosage:           string;
  /** Clinic calendar day, "yyyy-MM-dd". */
  scheduleDate:     string;
  /** Clinic wall-clock time of day, "HH:mm" (24-hour). */
  scheduledTime:    string;
  status:           DoseStatus;
  /** Clinic wall-clock date-time of the moment the dose was marked taken; null unless TAKEN. */
  takenAt:          string | null;
  /** Who set the current status (PAT-... or CGV-...). Null while PENDING and for a dose the server marked missed. */
  markedBySystemId: string | null;
  /** True when the dose is of the clinic's today or yesterday, the only days on which it can be updated. */
  markable:         boolean;
}

export interface MedicationAdherence {
  patientSystemId: string;
  /** Length of the window in days (1 to 90). */
  days:            number;
  from:            string;
  to:              string;
  taken:           number;
  missed:          number;
  pending:         number;
  total:           number;
  /** Whole percentage of taken among taken + missed; null when nothing was taken or missed yet. */
  adherenceRate:   number | null;
}

/** The daily times of one prescription. */
export interface MedicationSchedulePlan {
  prescriptionId:    number;
  patientSystemId:   string;
  /** "HH:mm" times, distinct and ascending; empty when the prescription has no scheduled doses. */
  times:             string[];
  updatedBySystemId: string | null;
  updatedAt:         string | null;
}

/** What a person can do to a dose: PATCH /api/medication-schedules/{id}/taken | missed | undo. */
export type DoseAction = 'taken' | 'missed' | 'undo';
