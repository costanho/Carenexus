import { useAction, type ActionState } from '@/hooks/use-action';
import { usePaged, type PagedState } from '@/hooks/use-paged';
import { useResource } from '@/hooks/use-resource';
import {
  listAdminDoctors, listPendingDoctors, rejectAdminDoctor, updateAdminDoctorLicence, verifyAdminDoctor,
} from '@/lib/admin/api';
import type { AdminDoctor, LicenceRequest, VerificationStatus } from '@/lib/admin/types';

const keyOfDoctor = (doctor: AdminDoctor) => doctor.doctorSystemId;

/**
 * Clinicians by verification status (GET /api/admin/doctors), oldest registration first.
 * `status` null lists every status.
 */
export function useAdminDoctors(
  status: VerificationStatus | null, options: { pageSize?: number; enabled?: boolean } = {},
): PagedState<AdminDoctor> {
  return usePaged<AdminDoctor>(
    (page, size) => listAdminDoctors(status, page, size),
    [status],
    { pageSize: options.pageSize, enabled: options.enabled, keyOf: keyOfDoctor, errorMessage: 'The clinicians could not be loaded.' },
  );
}

export interface PendingDoctorCount {
  /** Clinicians waiting for verification; null until the first answer (and after a failed first load). */
  count:   number | null;
  loading: boolean;
  error:   string | null;
  refetch: () => void;
}

/** Length of the verification queue: `totalElements` of GET /api/admin/doctors/pending?size=1. */
export function usePendingDoctorCount(options: { enabled?: boolean } = {}): PendingDoctorCount {
  const { data, loading, error, refetch } = useResource<number>(
    async () => (await listPendingDoctors(0, 1)).totalElements,
    [],
    { enabled: options.enabled, errorMessage: 'The number of pending verifications could not be loaded.' },
  );
  return { count: data, loading, error, refetch };
}

export interface DoctorVerificationActions {
  /** Approves a clinician (from PENDING, REJECTED or SUSPENDED). Fails with code LICENCE_REQUIRED without a licence number. */
  approve:     ActionState<[string], AdminDoctor>;
  /** `(systemId, reason)`: rejects a pending registration. The reason is emailed to the clinician. */
  reject:      ActionState<[string, string], AdminDoctor>;
  /** `(systemId, licence)`: replaces the licence details. Fails with 409 when another doctor has the number. */
  saveLicence: ActionState<[string, LicenceRequest], AdminDoctor>;
}

/** The verification decisions; each resolves with the clinician record after the change. */
export function useDoctorVerificationActions(): DoctorVerificationActions {
  const approve = useAction(verifyAdminDoctor, { errorMessage: 'The clinician could not be approved.' });
  const reject = useAction(rejectAdminDoctor, { errorMessage: 'The registration could not be rejected.' });
  const saveLicence = useAction(updateAdminDoctorLicence, { errorMessage: 'The licence details could not be saved.' });
  return { approve, reject, saveLicence };
}
