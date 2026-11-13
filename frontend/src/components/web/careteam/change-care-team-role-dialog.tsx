import { useState } from 'react';
import { StyleSheet, Text } from 'react-native';
import { colors, fontSize, FormActions, FormError, Modal, SelectField } from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { careTeamMemberName, careTeamRoleLabel, careTeamRoleOptions, isAccountProvider } from '@/lib/careteam/format';
import type { CareTeamMember, CareTeamRole, UpdateCareTeamMemberBody } from '@/lib/careteam/types';

export interface ChangeCareTeamRoleDialogProps {
  /** The stored member whose role is being changed; null keeps the dialog closed. */
  member: CareTeamMember | null;
  /** Sends the change (`useCareTeam().update`). */
  update: (member: CareTeamMember, body: UpdateCareTeamMemberBody) => Promise<CareTeamMember>;
  /** Called with the updated member after a successful save. */
  onChanged: (member: CareTeamMember) => void;
  onClose: () => void;
}

/**
 * Changes a member's role (a related doctor or an administrator). The options follow the add form: a CareNexus
 * doctor can be Doctor or Specialist, an external provider any of the six roles.
 */
export function ChangeCareTeamRoleDialog({ member, ...rest }: ChangeCareTeamRoleDialogProps) {
  if (!member) return null;
  // Keyed by member so the selection and any error start fresh for each row.
  return <RoleDialog key={member.careTeamId ?? member.providerName} member={member} {...rest} />;
}

function RoleDialog({ member, update, onChanged, onClose }: ChangeCareTeamRoleDialogProps & { member: CareTeamMember }) {
  const [role, setRole] = useState<CareTeamRole | ''>(member.role);
  const options = careTeamRoleOptions(isAccountProvider(member));
  const save = useAction(
    (next: CareTeamRole) => update(member, { role: next }),
    { errorMessage: 'We could not change the role. Please try again.' },
  );
  const name = careTeamMemberName(member);
  const selectable = role !== '' && options.some(o => o.value === role);

  const submit = async () => {
    if (!selectable || role === member.role) return;
    const result = await save.run(role);
    if (result.ok) onChanged(result.data);
  };

  return (
    <Modal
      open
      size="sm"
      title="Change role"
      onClose={onClose}
      dismissOnBackdrop={false}
      busy={save.pending}
      footer={(
        <FormActions
          submitLabel="Save role"
          onSubmit={() => { void submit(); }}
          onCancel={onClose}
          submitting={save.pending}
          disabled={!selectable || role === member.role}
        />
      )}
    >
      <Text style={styles.intro}>{`${name} is on the care team as ${careTeamRoleLabel(member.role)}.`}</Text>
      <SelectField<CareTeamRole>
        label="Role"
        value={role}
        onChange={next => { setRole(next); save.reset(); }}
        options={options}
        placeholder="Select a role"
        required
        disabled={save.pending}
        hint={isAccountProvider(member)
          ? 'A CareNexus doctor can be Doctor or Specialist.'
          : undefined}
      />
      <FormError message={save.error} />
    </Modal>
  );
}

const styles = StyleSheet.create({
  intro: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
});
