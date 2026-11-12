import { useCallback, useMemo } from 'react';
import { CareTeamPanel } from '@/components/web/careteam';
import { Screen } from '@/components/web/ui';
import { useSectionNav } from '@/hooks/use-section-nav';
import { apiErrorMessage } from '@/lib/api/errors';
import { careTeamMemberName } from '@/lib/careteam/format';
import { OWN_CARE_TEAM, type CareTeamMember } from '@/lib/careteam/types';
import { createThread } from '@/lib/communications/api';
import type { SectionProps } from '@/lib/nav/sections';
import { showMessage } from '@/lib/ui/dialogs';

/**
 * My Care Team (patient section `care-team`): the doctors and other providers looking after the signed-in
 * patient. The patient can message a doctor, choose the primary member and remove a member; members are added
 * by a doctor. `?id=<careTeamId>` (the link of the "Care team updated" notification) highlights that member.
 */
export default function PatientWebCareTeam({ params, navigate, openThread, theme }: Partial<SectionProps>) {
  const nav = useSectionNav();
  const go = navigate ?? nav.navigate;
  const open = openThread ?? nav.openThread;
  const highlightId = (params ?? nav.params).id;

  // "Message Dr X": the API returns the existing private conversation of this pair or creates it, so pressing
  // it again never makes a duplicate.
  const messageMember = useCallback(async (member: CareTeamMember) => {
    if (!member.providerSystemId) return;
    try {
      const thread = await createThread({
        patientSystemId: member.patientSystemId,
        type:            'PRIVATE',
        memberSystemIds: [member.providerSystemId],
      });
      open(thread.threadId);
    } catch (e) {
      showMessage(
        'Could not open the conversation',
        apiErrorMessage(e, `We could not open a conversation with ${careTeamMemberName(member)}. You can start one from Messages.`),
      );
    }
  }, [open]);

  const canBook = nav.hasSection('book-appointment');
  const emptyAction = useMemo(
    () => (canBook
      ? { label: 'Book an appointment', icon: 'calendar-outline' as const, onPress: () => go('book-appointment') }
      : undefined),
    [canBook, go],
  );

  return (
    <Screen
      title="My Care Team"
      subtitle="The doctors and other providers looking after you. Your doctor adds members; you choose who is primary and can remove anyone you no longer want listed."
      theme={theme}
      role="patient"
    >
      <CareTeamPanel
        patientSystemId={OWN_CARE_TEAM}
        title="Members"
        canSetPrimary
        canRemove
        onMessage={messageMember}
        highlightId={highlightId}
        emptyAction={emptyAction}
      />
    </Screen>
  );
}
