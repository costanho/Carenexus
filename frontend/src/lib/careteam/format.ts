import { doctorDisplayName } from '@/hooks/use-doctors';
import type { IoniconsName } from '@/lib/nav/sections';
import { CARE_TEAM_ROLES, DOCTOR_ACCOUNT_ROLES, type CareTeamMember, type CareTeamRole } from './types';

// Display rules for care-team rows (contract be-care-team, sections 3.1, 4 and 9).

const ROLE_LABELS: Record<CareTeamRole, string> = {
  DOCTOR:     'Doctor',
  NURSE:      'Nurse',
  LAB:        'Lab',
  PHARMACY:   'Pharmacy',
  SPECIALIST: 'Specialist',
  THERAPIST:  'Therapist',
};

const ROLE_ICONS: Record<CareTeamRole, IoniconsName> = {
  DOCTOR:     'medkit-outline',
  NURSE:      'pulse-outline',
  LAB:        'flask-outline',
  PHARMACY:   'bandage-outline',
  SPECIALIST: 'ribbon-outline',
  THERAPIST:  'fitness-outline',
};

export function isCareTeamRole(value: unknown): value is CareTeamRole {
  return typeof value === 'string' && (CARE_TEAM_ROLES as readonly string[]).includes(value);
}

/** "Doctor", "Pharmacy", ... A value this build does not know is shown in words rather than dropped. */
export function careTeamRoleLabel(role: string | null | undefined): string {
  if (isCareTeamRole(role)) return ROLE_LABELS[role];
  const words = (role ?? '').trim().replace(/_+/g, ' ').toLowerCase();
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : 'Care team member';
}

/** Decorative icon of a role (the role is always shown in words next to it). */
export function careTeamRoleIcon(role: string | null | undefined): IoniconsName {
  return isCareTeamRole(role) ? ROLE_ICONS[role] : 'person-outline';
}

/** True for a provider with a CareNexus account (always a doctor); false for an external provider. */
export function isAccountProvider(member: Pick<CareTeamMember, 'providerSystemId'>): boolean {
  return !!member.providerSystemId;
}

/** True for a stored membership, the only kind that can be changed or removed. */
export function isAssignedMember(member: CareTeamMember): member is CareTeamMember & { careTeamId: number } {
  return member.source === 'ASSIGNED' && typeof member.careTeamId === 'number';
}

/**
 * The name to show: "Dr. First Last" for a doctor account (the API sends the name without a title), the stored
 * name for an external provider. Never the system id.
 */
export function careTeamMemberName(member: Pick<CareTeamMember, 'providerSystemId' | 'providerName'>): string {
  const name = (member.providerName ?? '').replace(/\s+/g, ' ').trim();
  if (isAccountProvider(member)) return doctorDisplayName(name, '') || 'Doctor (name not set)';
  return name || 'Provider (name not set)';
}

/** Where the member works: the facility linked to the assignment, otherwise an external provider's organisation. */
export function careTeamAffiliation(member: Pick<CareTeamMember, 'facilityName' | 'providerOrganisation'>): string | null {
  const facility = (member.facilityName ?? '').trim();
  if (facility) return facility;
  const organisation = (member.providerOrganisation ?? '').trim();
  return organisation || null;
}

/** "Doctor, Cardiology" or just "Pharmacy". */
export function careTeamRoleLine(member: Pick<CareTeamMember, 'role' | 'specialization'>): string {
  const role = careTeamRoleLabel(member.role);
  const specialization = (member.specialization ?? '').trim();
  return specialization && specialization.toLowerCase() !== role.toLowerCase() ? `${role}, ${specialization}` : role;
}

/**
 * Roles offered for a member: a doctor account can only be Doctor or Specialist, an external provider can hold
 * any of the six (contract D4). The server answers 400 for anything else.
 */
export function careTeamRolesFor(accountProvider: boolean): readonly CareTeamRole[] {
  return accountProvider ? DOCTOR_ACCOUNT_ROLES : CARE_TEAM_ROLES;
}

export function careTeamRoleOptions(accountProvider: boolean): { value: CareTeamRole; label: string }[] {
  return careTeamRolesFor(accountProvider).map(role => ({ value: role, label: ROLE_LABELS[role] }));
}

/** A stable React key: the row id for a stored member, the doctor's id for a row derived from appointments. */
export function careTeamMemberKey(member: CareTeamMember): string {
  if (member.careTeamId != null) return `assigned-${member.careTeamId}`;
  return `derived-${member.providerSystemId ?? member.providerName}`;
}

/** Every member that reads as primary (legacy rows can produce more than one). */
export function primaryMembers(members: readonly CareTeamMember[]): CareTeamMember[] {
  return members.filter(m => m.isPrimary);
}
