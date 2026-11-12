// DTO types of the account endpoints under /api/users/me (password, email change, two-factor authentication,
// sessions, data rights, legal acceptances). Field names are exactly what the API sends (camelCase).
// Every timestamp here is a UTC instant with `Z`: parse with `new Date(value)`, never with the clinic helpers.

export interface MessageResponse { message: string }

// ─── Password ────────────────────────────────────────────────────────────────

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword:     string;
  /** The session that survives the change (`getSessionId()`); every other session of the account is ended. */
  keepSessionId?:  string;
}

// ─── Email change ────────────────────────────────────────────────────────────

export interface EmailChangeRequest { currentPassword: string; newEmail: string }

/** The pending request: nothing changes on the account until the link sent to `newEmail` is opened. */
export interface EmailChangeResponse { newEmail: string; requestedAt: string; expiresAt: string }

// ─── Two-factor authentication ───────────────────────────────────────────────

export interface MfaStatus {
  enabled:  boolean;
  /** The caller's role must keep two-factor authentication on; turning it off is refused. */
  required: boolean;
  /** Unused recovery codes, 0 to 10. */
  recoveryCodesRemaining: number;
  enabledAt: string | null;
}

export interface MfaSetup {
  /** Base32 secret for manual entry (32 characters). */
  secret:     string;
  /** otpauth:// URI to show as a QR code. */
  otpauthUri: string;
}

/** Exactly ten one-time codes. The server keeps only hashes, so they can be shown once. */
export interface MfaRecoveryCodes { recoveryCodes: string[] }

/** Turning two-factor authentication off needs the password and either a code from the app or a recovery code. */
export type MfaDisableRequest =
  | { password: string; code: string }
  | { password: string; recoveryCode: string };

// ─── Sessions ────────────────────────────────────────────────────────────────

export interface SessionResponse {
  /** UUID, stable across token refreshes. */
  sessionId:  string;
  deviceType: string | null;
  deviceInfo: string | null;
  createdAt:  string;
  /** Sign-in or the most recent token refresh; lags real activity by up to 15 minutes. */
  lastUsedAt: string;
  current:    boolean;
}

export interface RevokeOtherSessionsRequest { keepSessionId: string }
export interface RevokeOtherSessionsResponse { revokedCount: number }

// ─── Data rights ─────────────────────────────────────────────────────────────

export type AccountRequestType = 'EXPORT' | 'CLOSURE' | 'DELETION';
export type AccountRequestStatus = 'REQUESTED' | 'PROCESSING' | 'READY' | 'COMPLETED' | 'REJECTED' | 'CANCELLED';

export interface AccountRequest {
  requestId:      number;
  type:           AccountRequestType;
  status:         AccountRequestStatus;
  /** The user's own reason (closure, deletion). */
  reason:         string | null;
  /** The administrator's reason when a deletion request was declined. */
  decisionReason: string | null;
  requestedAt:    string;
  processedAt:    string | null;
  expiresAt:      string | null;
  /** Decided by the server; never compare `expiresAt` with the device clock. */
  downloadable:   boolean;
  fileName:       string | null;
}

export interface AccountClosure {
  requestId: number;
  closedAt:  string;
  clinicalRecordsRetained: boolean;
  /** Shown as received. */
  message:   string;
}

export interface PasswordConfirmRequest { password: string }
export interface PasswordReasonRequest { password: string; reason?: string }

// ─── Legal ───────────────────────────────────────────────────────────────────

export type LegalDocType = 'TERMS' | 'PRIVACY';

export interface LegalDocumentSummary {
  docType:     LegalDocType;
  version:     string;
  title:       string;
  effectiveAt: string;
  /** True while the text is placeholder copy; the warning is shown from this flag, never by reading the text. */
  pendingLegalReview: boolean;
}

export interface LegalDocument extends LegalDocumentSummary {
  /** Restricted Markdown: #, ##, ### headings, paragraphs, "- " bullets, **bold**, [text](https://...) links. */
  contentMd: string;
}

export interface LegalAcceptance {
  acceptanceId: number;
  docType:      LegalDocType;
  version:      string;
  acceptedAt:   string;
  /** True when `version` is the current version of `docType`. */
  current:      boolean;
}

export interface LegalStatus {
  /** True when `pending` is not empty; the same value as the session flag `legalActionRequired`. */
  actionRequired: boolean;
  accepted:       LegalAcceptance[];
  pending:        LegalDocumentSummary[];
}

export interface AcceptLegalRequest { docType: LegalDocType; version: string }
