import { Ionicons } from '@expo/vector-icons';
import { useEffect, useMemo, useRef, useState } from 'react';
import { ScrollView, StyleSheet, Switch, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { apiErrorMessage } from '@/hooks/use-appointments';
import { useCaregiverDependents } from '@/hooks/use-caregiver-dependents';
import { caregiverMessagingState } from '@/hooks/use-caregiver-messaging';
import { useDoctorPatients } from '@/hooks/use-doctor-patients';
import { useMessageRecipients } from '@/hooks/use-message-recipients';
import { usePageActive } from '@/hooks/use-page-active';
import { createThread, newClientId, sendMessage } from '@/lib/communications/api';
import { clearDraft, loadDraft, newConversationDraftKey, saveDraft, threadDraftKey } from '@/lib/communications/drafts';
import { roleLabel } from '@/lib/communications/format';
import { onMessagingReset } from '@/lib/communications/session';
import {
  MAX_MESSAGE_LENGTH, MAX_SUBJECT_LENGTH, OFFLINE_SEND_TEXT, URGENT_HELPER_TEXT,
  type ConversationType, type MessagingMode, type Thread,
} from '@/lib/communications/types';
import { showMessage } from '@/lib/ui/dialogs';
import { BORDER, Banner, ErrorBlock, GRAY, LIGHT, Loading, RED, TEXT, WHITE, useTheme } from './ui';

interface Props {
  mode:       MessagingMode;
  mySystemId: string | null;
  onCancel:   () => void;
  onCreated:  (thread: Thread) => void;
}

const TYPE_SUB: Record<ConversationType, string> = {
  PRIVATE:   'Between a doctor and the patient or one caregiver',
  CARE_TEAM: 'The patient, their doctors and approved caregivers',
};

/** Start a private or care-team conversation about one patient. */
export function NewConversation({ mode, mySystemId, onCancel, onCreated }: Props) {
  const { accent, accentBg } = useTheme();
  const { online } = usePageActive();
  const [patientId, setPatientId] = useState<string | null>(null);
  const [type,      setType]      = useState<ConversationType>('PRIVATE');
  const [selected,  setSelected]  = useState<string[]>([]);
  const [subject,   setSubject]   = useState('');
  const [body,      setBody]      = useState('');
  const [urgent,    setUrgent]    = useState(false);
  const [busy,      setBusy]      = useState(false);
  const [error,     setError]     = useState<string | null>(null);

  // Retry-safe ids: reused if the user presses Start again after a failure with the same choices.
  // A new patient, type or set of people is a different conversation, and a changed first message
  // is a different message, so those get fresh ids (a lost-response retry must not land elsewhere).
  const clientThreadId  = useRef(newClientId());
  const clientMessageId = useRef(newClientId());

  const isPatient = mode === 'PATIENT';
  const recipients = useMessageRecipients(isPatient ? null : patientId, isPatient || !!patientId);
  const effectivePatient = isPatient ? (recipients.data?.patientSystemId ?? mySystemId) : patientId;
  const draftKey = mySystemId && effectivePatient ? newConversationDraftKey(mySystemId, effectivePatient) : null;

  const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  /** The debounced save that has not run yet, written straight away when the form closes or the patient changes. */
  const unsaved   = useRef<{ key: string; value: string } | null>(null);
  function flushDraft() {
    if (saveTimer.current) clearTimeout(saveTimer.current);
    saveTimer.current = null;
    const p = unsaved.current;
    unsaved.current = null;
    if (p) saveDraft(p.key, p.value);
  }

  // Restore the first-message draft for this patient (the previous patient's is saved first).
  useEffect(() => {
    setBody(draftKey ? loadDraft(draftKey) : '');
    return flushDraft;
  }, [draftKey]);

  // Sign-out drops every draft; a save still waiting must not write this one back on unmount.
  useEffect(() => onMessagingReset(() => {
    if (saveTimer.current) clearTimeout(saveTimer.current);
    saveTimer.current = null;
    unsaved.current = null;
  }), []);

  function changeBody(v: string) {
    setBody(v);
    if (!draftKey) return;
    if (saveTimer.current) clearTimeout(saveTimer.current);
    unsaved.current = { key: draftKey, value: v };
    saveTimer.current = setTimeout(flushDraft, 400);
  }

  // Reset recipient choice when the patient or type changes.
  useEffect(() => { setSelected([]); setError(null); }, [patientId, type]);

  const selectionKey = selected.slice().sort().join(',');
  useEffect(() => { clientThreadId.current = newClientId(); }, [effectivePatient, type, selectionKey]);
  useEffect(() => { clientMessageId.current = newClientId(); }, [effectivePatient, type, selectionKey, body, urgent]);

  const list = recipients.data?.recipients ?? [];
  const options = useMemo(
    () => list.filter(r => (type === 'PRIVATE' ? r.canStartPrivate : r.canAddToCareTeam)),
    [list, type],
  );
  const privateAvailable = list.some(r => r.canStartPrivate);
  const careTeamAvailable = list.some(r => r.canAddToCareTeam);

  useEffect(() => {
    // A caregiver or patient may have no private option; fall back to care team.
    if (recipients.data && type === 'PRIVATE' && !privateAvailable && careTeamAvailable) setType('CARE_TEAM');
  }, [recipients.data, privateAvailable, careTeamAvailable, type]);

  function toggle(id: string) {
    if (type === 'PRIVATE') setSelected([id]);
    else setSelected(s => (s.includes(id) ? s.filter(x => x !== id) : [...s, id]));
  }

  const hasDoctor = mode === 'DOCTOR' || list.some(r => r.role === 'DOCTOR' && selected.includes(r.systemId));
  const trimmedBody = body.trim();
  const subjectTooLong = subject.trim().length > MAX_SUBJECT_LENGTH;
  const bodyTooLong = trimmedBody.length > MAX_MESSAGE_LENGTH;

  function validate(): string | null {
    if (!effectivePatient) return 'Choose who this conversation is about.';
    if (type === 'PRIVATE' && selected.length !== 1) return 'Choose who to message.';
    if (type === 'CARE_TEAM' && selected.length === 0) return 'Choose at least one person to add.';
    if (type === 'CARE_TEAM' && !hasDoctor) return 'A care team conversation needs at least one doctor';
    if (subjectTooLong) return `Subject must be at most ${MAX_SUBJECT_LENGTH} characters`;
    if (bodyTooLong) return `Messages can be at most ${MAX_MESSAGE_LENGTH} characters`;
    return null;
  }

  async function submit() {
    const problem = validate();
    if (problem) { setError(problem); return; }
    if (!online) { setError(OFFLINE_SEND_TEXT); return; }
    setBusy(true);
    setError(null);
    let thread: Thread;
    try {
      thread = await createThread({
        patientSystemId: effectivePatient as string,
        type,
        subject: subject.trim() || undefined,
        memberSystemIds: selected,
        clientThreadId: clientThreadId.current,
      });
    } catch (e) {
      setError(apiErrorMessage(e, 'Could not start the conversation.'));
      setBusy(false);
      return;
    }
    if (trimmedBody) {
      try {
        await sendMessage(thread.threadId, { clientMessageId: clientMessageId.current, body: trimmedBody, urgent });
      } catch (e) {
        // Keep the text as the thread's draft so nothing is lost.
        if (mySystemId) saveDraft(threadDraftKey(mySystemId, thread.threadId), trimmedBody);
        showMessage('Message not sent', `${apiErrorMessage(e, 'Your conversation was started, but the message was not sent.')} Your text is kept in the message box.`);
      }
    }
    if (saveTimer.current) clearTimeout(saveTimer.current);
    unsaved.current = null;
    if (draftKey) clearDraft(draftKey);
    setBusy(false);
    onCreated(thread);
  }

  return (
    <View style={styles.wrap}>
      <View style={styles.header}>
        <TouchableOpacity style={styles.iconBtn} onPress={onCancel} accessibilityRole="button" accessibilityLabel="Cancel new conversation">
          <Ionicons name="close" size={20} color={TEXT} importantForAccessibility="no" />
        </TouchableOpacity>
        <Text style={styles.title} accessibilityRole="header">New conversation</Text>
      </View>
      {!online && <Banner tone="warning" icon="cloud-offline-outline" text="You're offline. Reconnect to start a conversation." />}

      <ScrollView style={styles.flex} contentContainerStyle={styles.form} keyboardShouldPersistTaps="handled">
        {/* Patient context */}
        {mode === 'CAREGIVER' && <CaregiverPatientPicker value={patientId} onChange={setPatientId} />}
        {mode === 'DOCTOR' && <DoctorPatientPicker value={patientId} onChange={setPatientId} />}
        {isPatient && recipients.data && (
          <Text style={styles.note}>This conversation is about your care.</Text>
        )}

        {effectivePatient && (
          <>
            {/* Type */}
            <Text style={styles.label}>Conversation type</Text>
            <View style={styles.typeRow} accessibilityRole="radiogroup">
              {(['PRIVATE', 'CARE_TEAM'] as ConversationType[]).map(t => {
                const on = type === t;
                const available = t === 'PRIVATE' ? privateAvailable : careTeamAvailable;
                return (
                  <TouchableOpacity
                    key={t}
                    style={[styles.typeOpt, on && { borderColor: accent, backgroundColor: accentBg }, !available && styles.optDisabled]}
                    onPress={() => setType(t)}
                    disabled={!available}
                    accessibilityRole="radio"
                    accessibilityState={{ checked: on, disabled: !available }}
                    aria-checked={on}
                    // Same words as the visible title and description (WCAG 2.5.3, label in name).
                    accessibilityLabel={t === 'PRIVATE'
                      ? `Private conversation, ${TYPE_SUB.PRIVATE.toLowerCase()}`
                      : `Care team conversation, ${TYPE_SUB.CARE_TEAM.toLowerCase()}`}
                  >
                    <Ionicons name={t === 'PRIVATE' ? 'person-outline' : 'people-outline'} size={16} color={on ? accent : GRAY} importantForAccessibility="no" />
                    <View style={styles.flex}>
                      <Text style={[styles.typeTitle, on && { color: accent }]}>{t === 'PRIVATE' ? 'Private' : 'Care team'}</Text>
                      <Text style={styles.typeSub}>{TYPE_SUB[t]}</Text>
                    </View>
                  </TouchableOpacity>
                );
              })}
            </View>

            {/* Recipients */}
            <Text style={styles.label}>{type === 'PRIVATE' ? 'Send to' : 'Add people'}</Text>
            {recipients.loading ? <Loading compact label="Loading people you can message" /> : recipients.error ? (
              <ErrorBlock compact message={recipients.error} onRetry={recipients.forbidden ? undefined : recipients.refetch} />
            ) : options.length === 0 ? (
              <Text style={styles.note}>
                {list.length === 0
                  ? `No one is available to message about ${recipients.data?.patientName ?? 'this patient'} yet.`
                  : 'No one can be added for this conversation type.'}
              </Text>
            ) : (
              <View accessibilityRole={type === 'PRIVATE' ? 'radiogroup' : 'list'}>
                {options.map(r => {
                  const on = selected.includes(r.systemId);
                  return (
                    <TouchableOpacity
                      key={r.systemId}
                      style={[styles.pickRow, on && { backgroundColor: accentBg, borderColor: accent }]}
                      onPress={() => toggle(r.systemId)}
                      accessibilityRole={type === 'PRIVATE' ? 'radio' : 'checkbox'}
                      accessibilityState={{ checked: on }}
                      aria-checked={on}
                      accessibilityLabel={r.label}
                    >
                      <Ionicons
                        name={type === 'PRIVATE' ? (on ? 'radio-button-on' : 'radio-button-off') : (on ? 'checkbox' : 'square-outline')}
                        size={18} color={on ? accent : LIGHT} importantForAccessibility="no"
                      />
                      <View style={styles.flex}>
                        <Text style={styles.pickName} numberOfLines={1}>{r.label}</Text>
                        <Text style={styles.pickRole}>{roleLabel(r.role)}</Text>
                      </View>
                    </TouchableOpacity>
                  );
                })}
              </View>
            )}
            {type === 'CARE_TEAM' && (
              <Text style={styles.note}>{mode === 'PATIENT' ? 'You are included automatically.' : 'You and the patient are included automatically.'}</Text>
            )}

            {/* Subject + first message */}
            <Text style={styles.label} nativeID="nc-subject">Subject (optional)</Text>
            <TextInput
              style={styles.input}
              value={subject}
              onChangeText={setSubject}
              placeholder="e.g. Question about my medication"
              placeholderTextColor={LIGHT}
              maxLength={MAX_SUBJECT_LENGTH + 20}
              accessibilityLabel="Subject, optional"
            />
            {subjectTooLong && <Text style={styles.error}>Subject must be at most {MAX_SUBJECT_LENGTH} characters</Text>}

            <Text style={styles.label}>Message (optional)</Text>
            <TextInput
              style={[styles.input, styles.textarea]}
              value={body}
              onChangeText={changeBody}
              placeholder="Write your first message…"
              placeholderTextColor={LIGHT}
              multiline
              maxLength={MAX_MESSAGE_LENGTH + 200}
              accessibilityLabel="First message, optional"
            />
            {bodyTooLong && <Text style={styles.error}>Messages can be at most {MAX_MESSAGE_LENGTH} characters</Text>}
            <View style={styles.urgentRow}>
              <Switch
                value={urgent}
                onValueChange={setUrgent}
                trackColor={{ false: BORDER, true: '#FCA5A5' }}
                thumbColor={urgent ? RED : WHITE}
                accessibilityLabel="Mark first message as urgent"
                accessibilityHint={URGENT_HELPER_TEXT}
              />
              <Text style={[styles.urgentLabel, urgent && { color: RED }]}>Mark as urgent</Text>
            </View>
            {/* Always mounted so screen readers announce the helper when the switch turns on. */}
            <View accessibilityLiveRegion="polite">
              {urgent && <Text style={styles.note}>{URGENT_HELPER_TEXT}</Text>}
            </View>
          </>
        )}

        {error && <Text style={styles.error} accessibilityRole="alert">{error}</Text>}
      </ScrollView>

      <View style={styles.actions}>
        <TouchableOpacity style={styles.cancelBtn} onPress={onCancel} accessibilityRole="button" accessibilityLabel="Cancel">
          <Text style={styles.cancelText}>Cancel</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.primaryBtn, { backgroundColor: busy || !effectivePatient ? BORDER : accent }]}
          onPress={submit}
          disabled={busy || !effectivePatient}
          accessibilityRole="button"
          accessibilityLabel="Start conversation"
          accessibilityState={{ busy, disabled: busy || !effectivePatient }}
          aria-busy={busy}
        >
          <Text style={styles.primaryText}>{busy ? 'Starting…' : 'Start conversation'}</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

function CaregiverPatientPicker({ value, onChange }: { value: string | null; onChange: (id: string) => void }) {
  const { accent, accentBg } = useTheme();
  const { dependents, loading, error, refetch } = useCaregiverDependents();
  const active = dependents.filter(d => d.isActive && d.accessStatus !== 'REVOKED');

  // Pre-select when there is exactly one dependent who allows messaging.
  useEffect(() => {
    const allowed = active.filter(d => d.messagingAllowed);
    if (!value && allowed.length === 1) onChange(allowed[0].patientSystemId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dependents]);

  return (
    <View style={styles.section}>
      <Text style={styles.label}>Who is this about?</Text>
      {loading ? <Loading compact label="Loading your loved ones" /> : error ? (
        <ErrorBlock compact message={error} onRetry={refetch} />
      ) : active.length === 0 ? (
        <Text style={styles.note}>You are not linked to anyone yet.</Text>
      ) : (
        <View accessibilityRole="radiogroup">
          {active.map(d => {
            const name = d.patientName ?? 'Your loved one';
            const on = value === d.patientSystemId;
            // Same rule as every other caregiver screen: "waiting" needs a pending request, not just the flag.
            const state = caregiverMessagingState(d);
            const allowed = state.status === 'ALLOWED';
            const reason = state.status === 'WAITING' ? `Waiting for ${name} to approve messaging` : state.reason;
            return (
              <TouchableOpacity
                key={d.accessId}
                style={[styles.pickRow, on && { backgroundColor: accentBg, borderColor: accent }, !allowed && styles.optDisabled]}
                onPress={() => onChange(d.patientSystemId)}
                disabled={!allowed}
                accessibilityRole="radio"
                accessibilityState={{ checked: on, disabled: !allowed }}
                aria-checked={on}
                accessibilityLabel={reason ? `${name}. ${reason}` : name}
              >
                <Ionicons name={on ? 'radio-button-on' : 'radio-button-off'} size={18} color={on ? accent : LIGHT} importantForAccessibility="no" />
                <View style={styles.flex}>
                  <Text style={styles.pickName} numberOfLines={1}>{name}</Text>
                  {reason && <Text style={styles.pickRole}>{reason}</Text>}
                </View>
              </TouchableOpacity>
            );
          })}
        </View>
      )}
    </View>
  );
}

function DoctorPatientPicker({ value, onChange }: { value: string | null; onChange: (id: string) => void }) {
  const { accent, accentBg } = useTheme();
  const { patients, loading, error, refetch } = useDoctorPatients();
  const [query, setQuery] = useState('');
  const q = query.trim().toLowerCase();
  const filtered = q
    ? patients.filter(p => p.full_name.toLowerCase().includes(q) || p.patient_system_id.toLowerCase().includes(q))
    : patients;
  const shown = filtered.slice(0, 8);
  const chosen = patients.find(p => p.patient_system_id === value) ?? null;

  return (
    <View style={styles.section}>
      <Text style={styles.label}>Patient</Text>
      {loading ? <Loading compact label="Loading your patients" /> : error ? (
        <ErrorBlock compact message={error} onRetry={refetch} />
      ) : patients.length === 0 ? (
        <Text style={styles.note}>You have no patients yet.</Text>
      ) : (
        <>
          {chosen && <Text style={styles.note}>Selected: {chosen.full_name}</Text>}
          <TextInput
            style={styles.input}
            value={query}
            onChangeText={setQuery}
            placeholder="Search patients by name"
            placeholderTextColor={LIGHT}
            accessibilityLabel="Search patients"
            accessibilityRole="search"
          />
          <View accessibilityRole="radiogroup">
            {shown.map(p => {
              const on = value === p.patient_system_id;
              return (
                <TouchableOpacity
                  key={p.patient_system_id}
                  style={[styles.pickRow, on && { backgroundColor: accentBg, borderColor: accent }]}
                  onPress={() => onChange(p.patient_system_id)}
                  accessibilityRole="radio"
                  accessibilityState={{ checked: on }}
                  aria-checked={on}
                  accessibilityLabel={p.full_name}
                >
                  <Ionicons name={on ? 'radio-button-on' : 'radio-button-off'} size={18} color={on ? accent : LIGHT} importantForAccessibility="no" />
                  <Text style={[styles.pickName, styles.flex]} numberOfLines={1}>{p.full_name}</Text>
                </TouchableOpacity>
              );
            })}
            {filtered.length === 0 && <Text style={styles.note}>No patients match "{query}".</Text>}
            {filtered.length > shown.length && <Text style={styles.note}>Keep typing to narrow {filtered.length - shown.length} more.</Text>}
          </View>
        </>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap:     { flex: 1, backgroundColor: WHITE, minWidth: 0 },
  flex:     { flex: 1 },
  header:   { flexDirection: 'row', alignItems: 'center', gap: 6, paddingHorizontal: 12, paddingVertical: 10, borderBottomWidth: 1, borderBottomColor: BORDER },
  iconBtn:  { padding: 8, borderRadius: 8 },
  title:    { fontSize: 15, fontWeight: '700', color: TEXT },
  form:     { padding: 16, gap: 8, maxWidth: 640, width: '100%' },
  section:  { gap: 6 },
  label:    { fontSize: 12, fontWeight: '700', color: GRAY, marginTop: 8, letterSpacing: 0.3 },
  note:     { fontSize: 12, color: GRAY, lineHeight: 17 },
  error:    { fontSize: 12, color: RED },
  typeRow:  { flexDirection: 'row', gap: 8, flexWrap: 'wrap' },
  typeOpt:  { flexGrow: 1, flexBasis: 220, flexDirection: 'row', alignItems: 'flex-start', gap: 8, borderWidth: 1, borderColor: BORDER, borderRadius: 10, padding: 10 },
  typeTitle:{ fontSize: 13, fontWeight: '700', color: TEXT },
  typeSub:  { fontSize: 11, color: GRAY, marginTop: 2 },
  optDisabled: { opacity: 0.5 },
  pickRow:  { flexDirection: 'row', alignItems: 'center', gap: 10, borderWidth: 1, borderColor: 'transparent', borderRadius: 8, paddingVertical: 8, paddingHorizontal: 8 },
  pickName: { fontSize: 13, color: TEXT, fontWeight: '500' },
  pickRole: { fontSize: 11, color: GRAY },
  input:    { borderWidth: 1, borderColor: BORDER, borderRadius: 8, paddingHorizontal: 12, paddingVertical: 9, fontSize: 14, color: TEXT },
  textarea: { minHeight: 90, textAlignVertical: 'top' },
  urgentRow:{ flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 4 },
  urgentLabel: { fontSize: 12, color: GRAY, fontWeight: '600' },
  actions:  { flexDirection: 'row', justifyContent: 'flex-end', gap: 8, padding: 12, borderTopWidth: 1, borderTopColor: BORDER },
  cancelBtn:{ paddingHorizontal: 14, paddingVertical: 9 },
  cancelText:{ fontSize: 13, color: GRAY, fontWeight: '600' },
  primaryBtn:{ paddingHorizontal: 16, paddingVertical: 9, borderRadius: 8 },
  primaryText:{ fontSize: 13, color: WHITE, fontWeight: '700' },
});
