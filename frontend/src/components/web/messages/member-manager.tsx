import { Ionicons } from '@expo/vector-icons';
import { useState } from 'react';
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { apiErrorMessage } from '@/hooks/use-appointments';
import { useMessageRecipients } from '@/hooks/use-message-recipients';
import { addThreadMembers, removeThreadMember } from '@/lib/communications/api';
import { initials, roleLabel } from '@/lib/communications/format';
import type { Thread, ThreadMember } from '@/lib/communications/types';
import { confirmAction } from '@/lib/ui/dialogs';
import { Avatar, BORDER, GRAY, LIGHT, Loading, RED, TEXT, WHITE, avatarColor, useTheme } from './ui';

interface Props {
  thread:     Thread;
  mySystemId: string | null;
  myRole:     string;
  onChanged:  (thread: Thread | null) => void;
  /** Called after the current user leaves the conversation. */
  onLeft:     () => void;
}

/** Members panel for a conversation: list, add (care team), remove, leave. */
export function MemberManager({ thread, mySystemId, myRole, onChanged, onLeft }: Props) {
  const { accent, accentBg } = useTheme();
  const careTeam  = thread.type === 'CARE_TEAM';
  const canManage = careTeam && thread.canManageMembers;
  const [adding,   setAdding]   = useState(false);
  const [selected, setSelected] = useState<string[]>([]);
  const [busy,     setBusy]     = useState(false);
  const [error,    setError]    = useState<string | null>(null);

  const recipients = useMessageRecipients(thread.patientSystemId, canManage && adding);
  const memberIds  = new Set(thread.members.map(m => m.systemId));
  const candidates = (recipients.data?.recipients ?? []).filter(r => r.canAddToCareTeam && !memberIds.has(r.systemId));

  async function add() {
    if (selected.length === 0) return;
    setBusy(true);
    setError(null);
    try {
      const updated = await addThreadMembers(thread.threadId, selected);
      setSelected([]);
      setAdding(false);
      onChanged(updated);
    } catch (e) {
      setError(apiErrorMessage(e, 'Could not add members.'));
    } finally {
      setBusy(false);
    }
  }

  async function remove(member: ThreadMember) {
    const self = member.systemId === mySystemId;
    const ok = await confirmAction(
      self ? 'Leave this conversation?' : `Remove ${member.label}?`,
      self
        ? 'You will stop receiving messages in this conversation. Someone in it can add you back later.'
        : `${member.label} will no longer see this conversation or new messages in it.`,
      self ? 'Leave' : 'Remove',
      true,
    );
    if (!ok) return;
    setBusy(true);
    setError(null);
    try {
      await removeThreadMember(thread.threadId, member.systemId);
      if (self) {
        onLeft();
        return;
      }
      onChanged({ ...thread, members: thread.members.filter(m => m.systemId !== member.systemId) });
    } catch (e) {
      setError(apiErrorMessage(e, self ? 'Could not leave the conversation.' : 'Could not remove this member.'));
    } finally {
      setBusy(false);
    }
  }

  function canRemove(m: ThreadMember): boolean {
    if (!careTeam || m.role === 'PATIENT') return false;
    if (m.systemId === mySystemId) return myRole === 'DOCTOR' || myRole === 'CAREGIVER';
    return canManage;
  }

  return (
    <View style={styles.wrap} accessibilityLabel="Conversation members">
      <Text style={styles.heading} accessibilityRole="header">Members ({thread.members.length})</Text>
      {thread.members.map(m => {
        const self = m.systemId === mySystemId;
        return (
          <View key={m.systemId} style={styles.memberRow}>
            <Avatar text={initials(m.name)} color={avatarColor(m.name)} size={28} />
            <View style={styles.memberBody}>
              <Text style={styles.memberName} numberOfLines={1}>{m.label}{self ? ' (you)' : ''}</Text>
              <Text style={styles.memberRole}>{roleLabel(m.role)}</Text>
            </View>
            {canRemove(m) && (
              <TouchableOpacity
                style={styles.removeBtn}
                onPress={() => remove(m)}
                disabled={busy}
                accessibilityRole="button"
                accessibilityLabel={self ? 'Leave conversation' : `Remove ${m.label}`}
              >
                <Text style={styles.removeText}>{self ? 'Leave' : 'Remove'}</Text>
              </TouchableOpacity>
            )}
          </View>
        );
      })}

      {canManage && !adding && (
        <TouchableOpacity
          style={styles.addLink}
          onPress={() => setAdding(true)}
          accessibilityRole="button"
          accessibilityLabel="Add people to this conversation"
        >
          <Ionicons name="person-add-outline" size={14} color={accent} importantForAccessibility="no" />
          <Text style={[styles.addLinkText, { color: accent }]}>Add people</Text>
        </TouchableOpacity>
      )}

      {canManage && adding && (
        <View style={styles.addBox}>
          {recipients.loading ? <Loading compact label="Loading people" /> : recipients.error ? (
            <Text style={styles.error}>{recipients.error}</Text>
          ) : candidates.length === 0 ? (
            <Text style={styles.note}>Everyone who can join this conversation is already in it.</Text>
          ) : (
            <View accessibilityRole="list">
              {candidates.map(r => {
                const on = selected.includes(r.systemId);
                return (
                  <View key={r.systemId} role="listitem">
                  <TouchableOpacity
                    style={[styles.pickRow, on && { backgroundColor: accentBg }]}
                    onPress={() => setSelected(s => (on ? s.filter(x => x !== r.systemId) : [...s, r.systemId]))}
                    accessibilityRole="checkbox"
                    accessibilityLabel={r.label}
                    accessibilityState={{ checked: on }}
                    aria-checked={on}
                  >
                    <Ionicons name={on ? 'checkbox' : 'square-outline'} size={18} color={on ? accent : LIGHT} importantForAccessibility="no" />
                    <Text style={styles.memberName} numberOfLines={1}>{r.label}</Text>
                  </TouchableOpacity>
                  </View>
                );
              })}
            </View>
          )}
          <Text style={styles.note}>People you add see messages sent from now on.</Text>
          <View style={styles.addActions}>
            <TouchableOpacity
              style={styles.cancelBtn}
              onPress={() => { setAdding(false); setSelected([]); }}
              accessibilityRole="button"
              accessibilityLabel="Cancel adding people"
            >
              <Text style={styles.cancelText}>Cancel</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[styles.primaryBtn, { backgroundColor: selected.length && !busy ? accent : BORDER }]}
              onPress={add}
              disabled={!selected.length || busy}
              accessibilityRole="button"
              accessibilityLabel="Add selected people"
              accessibilityState={{ disabled: !selected.length || busy, busy }}
              aria-busy={busy}
            >
              <Text style={styles.primaryText}>{busy ? 'Adding…' : 'Add'}</Text>
            </TouchableOpacity>
          </View>
        </View>
      )}

      {error && <Text style={styles.error} accessibilityRole="alert">{error}</Text>}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap:       { padding: 14, gap: 8, borderBottomWidth: 1, borderBottomColor: BORDER, backgroundColor: WHITE },
  heading:    { fontSize: 12, fontWeight: '700', color: GRAY, letterSpacing: 0.5 },
  memberRow:  { flexDirection: 'row', alignItems: 'center', gap: 10 },
  memberBody: { flex: 1, minWidth: 0 },
  memberName: { fontSize: 13, color: TEXT, fontWeight: '500', flexShrink: 1 },
  memberRole: { fontSize: 11, color: LIGHT },
  removeBtn:  { paddingHorizontal: 10, paddingVertical: 5, borderRadius: 6, borderWidth: 1, borderColor: '#FECACA' },
  removeText: { fontSize: 12, color: RED, fontWeight: '600' },
  addLink:    { flexDirection: 'row', alignItems: 'center', gap: 6, paddingVertical: 4, alignSelf: 'flex-start' },
  addLinkText:{ fontSize: 13, fontWeight: '600' },
  addBox:     { gap: 6, borderWidth: 1, borderColor: BORDER, borderRadius: 8, padding: 10 },
  pickRow:    { flexDirection: 'row', alignItems: 'center', gap: 8, paddingVertical: 7, paddingHorizontal: 6, borderRadius: 6 },
  note:       { fontSize: 11, color: GRAY },
  addActions: { flexDirection: 'row', justifyContent: 'flex-end', gap: 8 },
  cancelBtn:  { paddingHorizontal: 12, paddingVertical: 7 },
  cancelText: { fontSize: 13, color: GRAY, fontWeight: '600' },
  primaryBtn: { paddingHorizontal: 14, paddingVertical: 7, borderRadius: 7 },
  primaryText:{ fontSize: 13, color: WHITE, fontWeight: '600' },
  error:      { fontSize: 12, color: RED },
});
