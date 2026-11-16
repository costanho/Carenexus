import { z } from 'zod';
import { isCareTeamRole } from './format';
import { CARE_TEAM_LIMITS, DOCTOR_ACCOUNT_ROLES, type AssignCareTeamMemberBody, type CareTeamRole } from './types';

// Validation of the add-member form. It mirrors AssignCareTeamMemberRequest (contract be-care-team, 6.3), so a
// value the form accepts is a value the server accepts.

/** `ValidationPatterns.PHONE`: optional +, 7 to 15 digits; spaces, dots, dashes and brackets allowed. */
export const CARE_TEAM_PHONE_PATTERN = /^\+?(?:[ .()-]*\d){7,15}[ .()-]*$/;

/** `ValidationPatterns.SYSTEM_ID`. */
export const SYSTEM_ID_PATTERN = /^(PAT|DOC|CGV|ADM)-\d{5,}$/;

/** Who is being added: a doctor with a CareNexus account, or a provider entered by name. */
export type CareTeamProviderKind = 'DOCTOR_ACCOUNT' | 'EXTERNAL';

export const addCareTeamMemberSchema = z
  .object({
    kind:                 z.enum(['DOCTOR_ACCOUNT', 'EXTERNAL']),
    /** DOC-... id from the doctor picker; '' until chosen. */
    providerSystemId:     z.string(),
    providerName:         z.string(),
    providerPhone:        z.string(),
    providerOrganisation: z.string(),
    /** One of the six roles; '' until chosen. */
    role:                 z.string(),
    /** The facility select's value: '' for none, otherwise the facility id as text. */
    facilityId:           z.string(),
    isPrimary:            z.boolean(),
  })
  .superRefine((values, ctx) => {
    const issue = (path: string, message: string) => ctx.addIssue({ code: 'custom', path: [path], message });

    if (!values.role.trim()) {
      issue('role', 'Choose a role');
    } else if (!isCareTeamRole(values.role)) {
      issue('role', 'Choose one of the listed roles');
    }

    if (values.kind === 'DOCTOR_ACCOUNT') {
      const id = values.providerSystemId.trim();
      if (!id) issue('providerSystemId', 'Choose a doctor');
      else if (!SYSTEM_ID_PATTERN.test(id)) issue('providerSystemId', 'Choose a doctor from the list');
      if (isCareTeamRole(values.role) && !DOCTOR_ACCOUNT_ROLES.includes(values.role)) {
        issue('role', 'A CareNexus doctor can only be added as Doctor or Specialist');
      }
    } else {
      const name = values.providerName.trim();
      if (!name) issue('providerName', "Enter the provider's name");
      else if (name.length > CARE_TEAM_LIMITS.providerName) {
        issue('providerName', `Use at most ${CARE_TEAM_LIMITS.providerName} characters`);
      }

      const phone = values.providerPhone.trim();
      if (phone && (phone.length > CARE_TEAM_LIMITS.providerPhone || !CARE_TEAM_PHONE_PATTERN.test(phone))) {
        issue('providerPhone', 'Enter a valid phone number: 7 to 15 digits, at most 20 characters');
      }

      if (values.providerOrganisation.trim().length > CARE_TEAM_LIMITS.providerOrganisation) {
        issue('providerOrganisation', `Use at most ${CARE_TEAM_LIMITS.providerOrganisation} characters`);
      }
    }

    const facility = values.facilityId.trim();
    if (facility && !/^[1-9]\d*$/.test(facility)) issue('facilityId', 'Choose a facility from the list');
  });

export type AddCareTeamMemberValues = z.infer<typeof addCareTeamMemberSchema>;

/** Names of the form's fields, which are also the backend's field names (for `applyFieldErrors`). */
export const ADD_CARE_TEAM_MEMBER_FIELDS = [
  'providerSystemId', 'providerName', 'providerPhone', 'providerOrganisation', 'role', 'facilityId', 'isPrimary',
] as const;

export function emptyAddCareTeamMemberValues(
  preset: { providerSystemId?: string | null; role?: CareTeamRole } = {},
): AddCareTeamMemberValues {
  return {
    kind:                 'DOCTOR_ACCOUNT',
    providerSystemId:     preset.providerSystemId ?? '',
    providerName:         '',
    providerPhone:        '',
    providerOrganisation: '',
    role:                 preset.role ?? 'DOCTOR',
    facilityId:           '',
    isPrimary:            false,
  };
}

/**
 * The request body for validated form values. Only the fields of the chosen kind are sent, text is trimmed and
 * an empty optional field is left out (the server refuses '' for the id and the phone).
 */
export function toAssignCareTeamMemberBody(values: AddCareTeamMemberValues): AssignCareTeamMemberBody {
  const facility = values.facilityId.trim();
  const body: AssignCareTeamMemberBody = {
    role:      values.role.trim().toUpperCase() as CareTeamRole,
    isPrimary: values.isPrimary,
  };
  if (facility) body.facilityId = Number(facility);

  if (values.kind === 'DOCTOR_ACCOUNT') {
    body.providerSystemId = values.providerSystemId.trim();
    return body;
  }
  body.providerName = values.providerName.trim();
  const phone = values.providerPhone.trim();
  if (phone) body.providerPhone = phone;
  const organisation = values.providerOrganisation.trim();
  if (organisation) body.providerOrganisation = organisation;
  return body;
}
