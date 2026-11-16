// Shared API envelope types (CONTRACTS 3.1 and 3.2). Domain DTO types live in lib/<domain>/types.ts.

/** The backend pagination envelope (`com.carenexus.api.common.dto.PageResponse`). `page` is zero-based. */
export interface PageResponse<T> {
  content:       T[];
  page:          number;
  size:          number;
  totalElements: number;
  totalPages:    number;
}

/** One entry of `errors` in the backend error envelope. */
export interface ApiFieldError {
  field:   string;
  message: string;
}

/** The backend error envelope (`ErrorResponse`). `code` and `errors` are optional. */
export interface ApiErrorBody {
  timestamp?: string;
  status?:    number;
  error?:     string;
  message?:   string;
  path?:      string;
  code?:      string;
  errors?:    ApiFieldError[];
}

/** Error codes the backend sends in `code` (CONTRACTS 3.1). Other values may appear; treat the list as known cases. */
export type ApiErrorCode =
  | 'VALIDATION_FAILED'
  | 'RATE_LIMITED'
  | 'EMAIL_NOT_VERIFIED'
  | 'ACCOUNT_PENDING_VERIFICATION'
  | 'ACCOUNT_REJECTED'
  | 'ACCOUNT_CLOSED'
  | 'MFA_REQUIRED'
  | 'MFA_SETUP_REQUIRED'
  | 'LEGAL_ACCEPTANCE_REQUIRED'
  | 'VIDEO_UNAVAILABLE'
  | 'IDEMPOTENCY_IN_PROGRESS'
  | 'PROVIDER_UNAVAILABLE'
  | 'FILE_REJECTED';
