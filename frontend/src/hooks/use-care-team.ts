import { useCallback, useMemo } from 'react';
import { usePaged, type PagedState } from '@/hooks/use-paged';
import { useResource } from '@/hooks/use-resource';
import {
  assignCareTeamMember, getDoctorCareTeamAssignments, getMyCareTeam, getPatientCareTeam,
  removeCareTeamMember, updateCareTeamMember,
} from '@/lib/careteam/api';
import {
  OWN_CARE_TEAM,
  type AssignCareTeamMemberBody, type CareTeamMember, type UpdateCareTeamMemberBody,
} from '@/lib/careteam/types';

// A patient's care team: the list plus assign, update and remove (contract be-care-team, section 6).
// The list is small and not paged, so it is a `useResource`; every successful write reloads it, because a
// primary swap changes a second row and a removed doctor can come back as a row derived from appointments.

const EMPTY: CareTeamMember[] = [];

export interface CareTeamOptions {
  /** When false nothing is requested. Default true. */
  enabled?: boolean;
}

export interface CareTeamState {
  /** Assigned members first (primary on top), then doctors known only from appointments. */
  members:     CareTeamMember[];
  /** True until the first result for this patient arrives. */
  loading:     boolean;
  /** True once a list for this patient has arrived (it may be empty); stays true when a later reload fails. */
  loaded:      boolean;
  /** True while a reload runs with the list already on screen. */
  refreshing:  boolean;
  /** A sentence to show, or null. After a failed reload the previous members are kept alongside it. */
  error:       string | null;
  /** HTTP status of the failed load (0 = no response), or null. */
  errorStatus: number | null;
  /** The server refused the read (403): show the no-access state, never the previous members. */
  noAccess:    boolean;
  refetch:     () => void;
  /**
   * Adds a member (related doctor or administrator). `idempotencyKey` is the add form's key. Rejects with the
   * request error (409 when the provider is already on the team).
   */
  assign: (body: AssignCareTeamMemberBody, idempotencyKey?: string) => Promise<CareTeamMember>;
  /** Changes a stored member's role and/or primary flag. A patient may only send `isPrimary`. */
  update: (member: CareTeamMember, body: UpdateCareTeamMemberBody) => Promise<CareTeamMember>;
  /** Removes a stored member: the patient, the doctor on the row, or an administrator. */
  remove: (member: CareTeamMember) => Promise<void>;
}

function requireRowId(member: CareTeamMember): number {
  if (member.source !== 'ASSIGNED' || typeof member.careTeamId !== 'number') {
    throw new Error('Only an assigned care team member can be changed.');
  }
  return member.careTeamId;
}

/**
 * `const team = useCareTeam(patientSystemId)` for a doctor, caregiver or administrator, or
 * `useCareTeam(OWN_CARE_TEAM)` on the signed-in patient's own screen. A missing id loads nothing.
 */
export function useCareTeam(patientSystemId: string | null | undefined, options: CareTeamOptions = {}): CareTeamState {
  const id = (patientSystemId ?? '').trim();
  const enabled = (options.enabled ?? true) && id.length > 0;

  const { data, loading, refreshing, error, errorStatus, refetch, setData } = useResource<CareTeamMember[]>(
    () => (id === OWN_CARE_TEAM ? getMyCareTeam() : getPatientCareTeam(id)),
    [id],
    { enabled, errorMessage: 'We could not load the care team. Please try again.' },
  );

  const assign = useCallback(async (body: AssignCareTeamMemberBody, idempotencyKey?: string) => {
    if (!id || id === OWN_CARE_TEAM) throw new Error('A care team member can only be added for a named patient.');
    const created = await assignCareTeamMember(id, body, idempotencyKey);
    refetch();
    return created;
  }, [id, refetch]);

  const update = useCallback(async (member: CareTeamMember, body: UpdateCareTeamMemberBody) => {
    const careTeamId = requireRowId(member);
    const updated = await updateCareTeamMember(member.patientSystemId, careTeamId, body);
    // Show the result at once; the reload that follows brings the server's order and any demoted member.
    setData(prev => (prev ?? EMPTY).map(m => {
      if (m.careTeamId === careTeamId) return updated;
      return updated.isPrimary && m.isPrimary ? { ...m, isPrimary: false } : m;
    }));
    refetch();
    return updated;
  }, [refetch, setData]);

  const remove = useCallback(async (member: CareTeamMember) => {
    const careTeamId = requireRowId(member);
    await removeCareTeamMember(member.patientSystemId, careTeamId);
    setData(prev => (prev ?? EMPTY).filter(m => m.careTeamId !== careTeamId));
    refetch();
  }, [refetch, setData]);

  const noAccess = errorStatus === 403;
  const members = useMemo(() => (noAccess ? EMPTY : data ?? EMPTY), [data, noAccess]);

  return {
    members, loading, loaded: data !== null, refreshing, error, errorStatus, noAccess, refetch, assign, update, remove,
  };
}

/**
 * The signed-in doctor's own assignments across patients, newest first (GET /api/doctors/care-team), page by
 * page. Render it with `PagedList`. DOCTOR only: any other role gets a 403 state.
 */
export function useDoctorCareTeamAssignments(options: { enabled?: boolean; pageSize?: number } = {}): PagedState<CareTeamMember> {
  return usePaged<CareTeamMember>(
    (page, size) => getDoctorCareTeamAssignments(page, size),
    [],
    {
      enabled:      options.enabled,
      pageSize:     options.pageSize,
      errorMessage: 'We could not load your care team assignments. Please try again.',
      keyOf:        member => member.careTeamId ?? `${member.patientSystemId}:${member.providerSystemId ?? member.providerName}`,
    },
  );
}
