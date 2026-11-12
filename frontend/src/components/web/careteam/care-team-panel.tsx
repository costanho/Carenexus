import { type ReactNode, useCallback, useEffect, useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Banner, Button, colors, EmptyView, ErrorView, fontSize, LoadingView, NoAccessView, radius, space,
  UiThemeProvider, type EmptyAction, type RoleTheme,
} from '@/components/web/ui';
import { useCareTeam } from '@/hooks/use-care-team';
import { useCurrentUser } from '@/hooks/use-current-user';
import { apiError } from '@/lib/api/errors';
import {
  careTeamMemberKey, careTeamMemberName, careTeamRoleLabel, isAccountProvider, isAssignedMember, primaryMembers,
} from '@/lib/careteam/format';
import { OWN_CARE_TEAM, type CareTeamMember } from '@/lib/careteam/types';
import { confirmAction } from '@/lib/ui/dialogs';
import { AddCareTeamMemberDialog } from './add-care-team-member-dialog';
import { CareTeamMemberRow, type CareTeamRowAction } from './care-team-member-row';
import { ChangeCareTeamRoleDialog } from './change-care-team-role-dialog';

export interface CareTeamPanelProps {
  /**
   * Whose care team to show: the patient's PAT-... id (doctor, caregiver and administrator screens), or
   * `OWN_CARE_TEAM` on the signed-in patient's own screen. Nothing is loaded while it is empty.
   */
  patientSystemId: string | null | undefined;
  /** The viewer may add members and change roles: a related doctor or an administrator. Never a patient or a caregiver. */
  canManage?: boolean;
  /** The viewer may set and clear the primary member: the patient, a related doctor or an administrator. */
  canSetPrimary?: boolean;
  /**
   * The viewer may remove any stored member: the patient or an administrator. A doctor needs no flag: Remove is
   * offered on the row where the viewer is the provider (the only row a doctor may remove).
   */
  canRemove?: boolean;
  /**
   * Opens a conversation with a member. The Message shortcut is shown for members with a CareNexus account,
   * except on the viewer's own row. It may return a promise; the button shows a spinner until it settles.
   */
  onMessage?: (member: CareTeamMember) => void | Promise<void>;
  /** The dashboard's accent colours. Without it the panel uses the theme of the screen around it. */
  theme?: RoleTheme;
  /** The viewer's own system id. Default: the signed-in user's. */
  viewerSystemId?: string | null;
  /** `careTeamId` of the member a link or notification pointed at (`params.id`); ignored when it is not in the list. */
  highlightId?: number | string | null;
  /** The patient's name for dialogs and messages. Default: the name carried by the rows. */
  patientName?: string | null;
  /** Heading of the panel. Default "Care team". */
  title?: string;
  /** Heading level of the title. Default 2. */
  headingLevel?: 2 | 3;
  /** Draw the panel as a bordered card. Default true; pass false inside a card, tab or side panel that frames it. */
  framed?: boolean;
  /** What a viewer who cannot add members can do when the list is empty (for example "Book an appointment"). */
  emptyAction?: EmptyAction;
  /**
   * The viewer removed their own membership and the server now refuses the list (403): they are no longer on
   * this care team. A patient detail panel closes itself here.
   */
  onAccessLost?: () => void;
}

interface Notice { tone: 'success' | 'danger'; text: string }

/**
 * A patient's care team: assigned members with role, Primary badge, specialization and facility or organisation,
 * then doctors known only from appointments. Controls appear according to the flags: Message, Make primary /
 * Remove primary, Change role, Remove (each confirmed) and Add member. With no flags it is a read-only list
 * (the caregiver view). Renders its own loading, empty, error-with-retry and no-access states.
 */
export function CareTeamPanel({ theme, ...props }: CareTeamPanelProps) {
  return (
    <UiThemeProvider theme={theme}>
      <Panel {...props} />
    </UiThemeProvider>
  );
}

function Panel({
  patientSystemId, canManage = false, canSetPrimary = false, canRemove = false, onMessage,
  viewerSystemId, highlightId, patientName, title = 'Care team', headingLevel = 2, framed = true,
  emptyAction, onAccessLost,
}: Omit<CareTeamPanelProps, 'theme'>) {
  const id = (patientSystemId ?? '').trim();
  const own = id === OWN_CARE_TEAM;
  // Adding needs the patient's real id, and a patient cannot add members to their own team.
  const canAdd = canManage && !!id && !own;

  const { user } = useCurrentUser();
  const viewerId = viewerSystemId !== undefined ? viewerSystemId : user?.system_id ?? null;

  const team = useCareTeam(id);
  const { members, refetch } = team;

  const [notice, setNotice] = useState<Notice | null>(null);
  const [busy, setBusy] = useState<{ key: string; action: CareTeamRowAction } | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [addFor, setAddFor] = useState<CareTeamMember | null>(null);
  const [roleFor, setRoleFor] = useState<CareTeamMember | null>(null);
  const [removedSelf, setRemovedSelf] = useState(false);

  // One row action at a time; the ref guards against two presses in the same frame.
  const running = useRef(false);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  // Another patient: nothing of the previous one stays on screen.
  useEffect(() => {
    setNotice(null);
    setAddOpen(false);
    setAddFor(null);
    setRoleFor(null);
    setRemovedSelf(false);
  }, [id]);

  const lostAccess = team.noAccess && removedSelf;
  const accessLostRef = useRef(onAccessLost);
  accessLostRef.current = onAccessLost;
  useEffect(() => {
    if (lostAccess) accessLostRef.current?.();
  }, [lostAccess]);

  const name = (patientName ?? '').trim() || (members[0]?.patientName ?? '').trim();
  const whose = own ? 'your' : name ? `${name}'s` : "this patient's";

  const runRowAction = useCallback(async (
    member: CareTeamMember,
    action: CareTeamRowAction,
    work: () => Promise<string | null>,
    failure: string,
  ) => {
    if (running.current) return;
    running.current = true;
    setBusy({ key: careTeamMemberKey(member), action });
    setNotice(null);
    try {
      const done = await work();
      if (mounted.current && done) setNotice({ tone: 'success', text: done });
    } catch (e) {
      const error = apiError(e, failure);
      if (!mounted.current) return;
      setNotice({ tone: 'danger', text: error.message });
      // The row is gone or the viewer's access changed since the list was loaded: show the current state.
      if (error.status === 403 || error.status === 404) refetch();
    } finally {
      running.current = false;
      if (mounted.current) setBusy(null);
    }
  }, [refetch]);

  const handleMessage = (member: CareTeamMember) => {
    if (!onMessage) return;
    void runRowAction(member, 'message', async () => { await onMessage(member); return null; },
      'We could not open the conversation. Please try again.');
  };

  const handleTogglePrimary = async (member: CareTeamMember) => {
    if (running.current) return;
    const memberName = careTeamMemberName(member);
    const makePrimary = !member.isPrimary;
    const previous = primaryMembers(members).find(m => careTeamMemberKey(m) !== careTeamMemberKey(member));
    const confirmed = makePrimary
      ? await confirmAction(
          'Make primary',
          previous
            ? `${memberName} will replace ${careTeamMemberName(previous)} as the primary member of ${whose} care team.`
            : `${memberName} will become the primary member of ${whose} care team.`,
          'Make primary',
        )
      : await confirmAction(
          'Remove primary status',
          `${memberName} will stay on ${whose} care team but will no longer be the primary member. Nobody else becomes primary automatically.`,
          'Remove primary',
        );
    if (!confirmed) return;
    await runRowAction(member, 'primary', async () => {
      await team.update(member, { isPrimary: makePrimary });
      return makePrimary
        ? `${memberName} is now the primary member of ${whose} care team.`
        : `${memberName} is no longer the primary member of ${whose} care team.`;
    }, 'We could not change the primary member. Please try again.');
  };

  const handleRemove = async (member: CareTeamMember) => {
    if (running.current) return;
    const memberName = careTeamMemberName(member);
    const self = !!viewerId && member.providerSystemId === viewerId;
    const message = self
      ? `You will be removed from ${whose} care team. Unless you have an appointment or a consultation with this patient, you will no longer be able to open their records.`
      : isAccountProvider(member)
        ? `${memberName} will be removed from ${whose} care team. A doctor who still has an appointment or a consultation ${own ? 'with you' : 'with this patient'} stays listed as coming from appointments and keeps access to the records.`
        : `${memberName} will be removed from ${whose} care team.`;
    const confirmed = await confirmAction(self ? 'Leave this care team' : 'Remove from care team', message, self ? 'Leave' : 'Remove', true);
    if (!confirmed) return;
    await runRowAction(member, 'remove', async () => {
      await team.remove(member);
      if (self && mounted.current) setRemovedSelf(true);
      return self ? `You have left ${whose} care team.` : `${memberName} was removed from ${whose} care team.`;
    }, 'We could not remove this member. Please try again.');
  };

  const openAdd = (derived: CareTeamMember | null) => {
    setNotice(null);
    setAddFor(derived);
    setAddOpen(true);
  };
  const closeAdd = () => { setAddOpen(false); setAddFor(null); };

  const highlight = Number(highlightId);
  const anyDialog = addOpen || roleFor !== null;
  const assignedCount = members.filter(m => m.source === 'ASSIGNED').length;
  const derivedCount = members.length - assignedCount;

  let body: ReactNode;
  if (!id) {
    body = <EmptyView compact icon="people-outline" title="Choose a patient to see their care team" />;
  } else if (team.loading) {
    body = <LoadingView compact label="Loading the care team…" />;
  } else if (team.noAccess) {
    body = lostAccess
      ? <NoAccessView compact message={`You are no longer on ${whose} care team.`} sub="You left the care team, so its members are no longer shown to you." />
      : <NoAccessView compact message={own ? "You don't have access to a care team." : `You don't have access to ${whose} care team.`} sub={team.error ?? undefined} />;
  } else if (team.error && !team.loaded) {
    body = <ErrorView compact message={team.error} onRetry={team.refetch} />;
  } else {
    body = (
      <>
        {team.error ? (
          <Banner tone="danger" text={`The care team could not be refreshed. ${team.error}`} action={{ label: 'Retry', onPress: team.refetch }} />
        ) : null}
        {members.length === 0 ? (
          <EmptyView
            compact
            icon="people-outline"
            title={own ? 'Nobody is on your care team yet' : 'Nobody is on this care team yet'}
            sub={own
              ? 'Doctors you have appointments with appear here. Your doctor can also add other providers, such as a pharmacy or a lab.'
              : canAdd
                ? 'Add the doctors and other providers who look after this patient. Doctors with an appointment or a consultation appear here on their own.'
                : 'The doctors and other providers who look after this patient will be listed here.'}
            action={canAdd ? { label: 'Add member', icon: 'person-add-outline', onPress: () => openAdd(null) } : emptyAction}
          />
        ) : (
          <View role="list" aria-label={`${title}: ${members.length} ${members.length === 1 ? 'member' : 'members'}`} style={styles.list}>
            {members.map(member => {
              const key = careTeamMemberKey(member);
              const assigned = isAssignedMember(member);
              const account = isAccountProvider(member);
              const isViewer = !!viewerId && member.providerSystemId === viewerId;
              return (
                <CareTeamMemberRow
                  key={key}
                  member={member}
                  own={own}
                  highlighted={assigned && member.careTeamId === highlight}
                  busy={busy?.key === key ? busy.action : null}
                  disabled={(busy !== null && busy.key !== key) || anyDialog}
                  onMessage={onMessage && account && !isViewer ? handleMessage : undefined}
                  onTogglePrimary={canSetPrimary && assigned ? m => { void handleTogglePrimary(m); } : undefined}
                  onChangeRole={canManage && assigned ? m => { setNotice(null); setRoleFor(m); } : undefined}
                  onRemove={assigned && (canRemove || isViewer) ? m => { void handleRemove(m); } : undefined}
                  onAssign={canAdd && !assigned && account ? openAdd : undefined}
                />
              );
            })}
          </View>
        )}
      </>
    );
  }

  const summary = id && team.loaded && !team.noAccess && members.length > 0
    ? [
        `${assignedCount} ${assignedCount === 1 ? 'member' : 'members'}`,
        derivedCount > 0 ? `${derivedCount} from appointments` : null,
      ].filter(Boolean).join(', ')
    : null;

  return (
    <View style={[styles.panel, framed && styles.framed]}>
      <View style={styles.header}>
        <View style={styles.titles}>
          <Text style={styles.title} accessibilityRole="header" {...ariaLevel(headingLevel)}>{title}</Text>
          {summary ? <Text style={styles.summary}>{summary}</Text> : null}
        </View>
        {canAdd && team.loaded && !team.noAccess ? (
          <Button
            label="Add member"
            accessibilityLabel={name ? `Add member to ${name}'s care team` : 'Add member to the care team'}
            icon="person-add-outline"
            size="sm"
            onPress={() => openAdd(null)}
            disabled={busy !== null}
          />
        ) : null}
      </View>

      {notice ? <Banner tone={notice.tone} text={notice.text} onDismiss={() => setNotice(null)} /> : null}
      {body}

      {canAdd ? (
        <AddCareTeamMemberDialog
          open={addOpen}
          patientSystemId={id}
          patientName={name}
          members={members}
          fixedProvider={addFor}
          assign={team.assign}
          onConflict={team.refetch}
          onClose={closeAdd}
          onAdded={member => {
            closeAdd();
            setNotice({
              tone: 'success',
              text: `${careTeamMemberName(member)} was added to ${whose} care team as ${careTeamRoleLabel(member.role)}.`,
            });
          }}
        />
      ) : null}

      {canManage ? (
        <ChangeCareTeamRoleDialog
          member={roleFor}
          update={team.update}
          onClose={() => setRoleFor(null)}
          onChanged={member => {
            setRoleFor(null);
            setNotice({
              tone: 'success',
              text: `${careTeamMemberName(member)} is now on ${whose} care team as ${careTeamRoleLabel(member.role)}.`,
            });
          }}
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  panel:   { gap: space.md, minWidth: 0, maxWidth: '100%' },
  framed:  { padding: space.lg, backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderRadius: radius.lg },
  header:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  titles:  { flexGrow: 1, flexShrink: 1, flexBasis: 160, minWidth: 0, gap: 2 },
  title:   { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  summary: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  list:    { gap: space.sm, minWidth: 0 },
});
