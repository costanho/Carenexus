// Types mirroring the Calls API (CONTRACT.md section 8.3).

export type CallSessionStatus =
  | 'PREPARING'
  | 'READY'
  | 'FAILED'
  | 'LIVE'
  | 'ENDED'
  | 'CANCELLED'
  | 'NEEDS_TIME_CONFIRMATION';

export type CallRole        = 'DOCTOR' | 'PATIENT' | 'CAREGIVER' | 'ADMIN';
export type ParticipantRole = 'DOCTOR' | 'PATIENT' | 'CAREGIVER';
export type ParticipantStatus = 'REQUESTED' | 'ADMITTED' | 'DENIED' | 'LEFT' | 'REMOVED';

export interface CallParticipant {
  participantId:    number;
  role:             ParticipantRole;
  label:            string;
  actingForPatient: boolean;
  status:           ParticipantStatus;
  requestedAt:      string | null;
  admittedAt:       string | null;
  connected:        boolean;
  removalPending:   boolean;
  me:               boolean;
  /** Present on REMOVED rows when the backend includes it (e.g. ACCESS_REVOKED, CALL_ENDED). */
  removedReason?:   string | null;
}

export interface CallStatusResponse {
  appointmentId:      number;
  callStatus:         CallSessionStatus;
  scheduledStartUtc:  string | null;
  joinWindowOpensAt:  string | null;
  joinWindowClosesAt: string | null;
  serverTime:         string;
  joinWindowOpen:     boolean;
  scheduledAtLocal:   string | null;
  patientName:        string | null;
  doctorName:         string | null;
  myRole:             CallRole;
  myParticipant:      CallParticipant | null;
  participants:       CallParticipant[];
  canStart:           boolean;
  canEnd:             boolean;
  canRequestJoin:     boolean;
  canConfirmTime:     boolean;
  removalPending:     boolean;
}

export interface CallTokenResponse {
  serverUrl: string;
  token:     string;
  expiresAt: string;
  identity:  string;
}
