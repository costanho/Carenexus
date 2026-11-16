import { Ionicons } from '@expo/vector-icons';
import { useEffect, useRef, useState } from 'react';
import { Platform, StyleSheet, Switch, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { clearDraft, loadDraft, saveDraft } from '@/lib/communications/drafts';
import { onMessagingReset } from '@/lib/communications/session';
import { MAX_MESSAGE_LENGTH, URGENT_HELPER_TEXT } from '@/lib/communications/types';
import { BORDER, GRAY, LIGHT, RED, TEXT, WHITE, useTheme } from './ui';

interface Props {
  /** sessionStorage key for this thread's draft; null disables drafts. */
  draftKey:  string | null;
  disabled?: boolean;
  disabledReason?: string;
  /** Default true. False hides the urgent switch and always sends a normal message (support conversations). */
  allowUrgent?: boolean;
  onSend:    (body: string, urgent: boolean) => void;
}

const DRAFT_DEBOUNCE_MS = 400;

export function Composer({ draftKey, disabled, disabledReason, allowUrgent = true, onSend }: Props) {
  const { accent } = useTheme();
  const [text,      setText]      = useState(() => (draftKey ? loadDraft(draftKey) : ''));
  const [urgentOn,  setUrgent]    = useState(false);
  // The switch can disappear while it is on (the conversation turned out to be a support one).
  const urgent = allowUrgent && urgentOn;
  const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  /** The debounced save that has not run yet, so it can be written straight away on a switch. */
  const unsaved   = useRef<{ key: string; value: string } | null>(null);
  const keyRef    = useRef(draftKey);
  const inputRef  = useRef<TextInput>(null);

  function flushDraft() {
    if (saveTimer.current) clearTimeout(saveTimer.current);
    saveTimer.current = null;
    const p = unsaved.current;
    unsaved.current = null;
    if (p) saveDraft(p.key, p.value);
  }

  // Restore the draft when switching threads; the previous thread's last keystrokes are saved first.
  useEffect(() => {
    keyRef.current = draftKey;
    setText(draftKey ? loadDraft(draftKey) : '');
    setUrgent(false);
    return flushDraft;
  }, [draftKey]);

  // Sign-out drops every draft; a save still waiting must not write this one back on unmount.
  useEffect(() => onMessagingReset(() => {
    if (saveTimer.current) clearTimeout(saveTimer.current);
    saveTimer.current = null;
    unsaved.current = null;
  }), []);

  function change(value: string) {
    setText(value);
    const key = keyRef.current;
    if (!key) return;
    if (saveTimer.current) clearTimeout(saveTimer.current);
    unsaved.current = { key, value };
    saveTimer.current = setTimeout(flushDraft, DRAFT_DEBOUNCE_MS);
  }

  const trimmed  = text.trim();
  const tooLong  = trimmed.length > MAX_MESSAGE_LENGTH;
  const canSend  = !disabled && trimmed.length > 0 && !tooLong;

  function submit() {
    if (!canSend) return;
    // The text is safe once handed over: unconfirmed sends are kept in the thread's outbox.
    onSend(trimmed, urgent);
    setText('');
    setUrgent(false);
    if (saveTimer.current) clearTimeout(saveTimer.current);
    unsaved.current = null;
    if (keyRef.current) clearDraft(keyRef.current);
    // The send button turns disabled once the box is empty, which would drop keyboard focus to the
    // page body; keep the user in the message box instead.
    inputRef.current?.focus();
  }

  function onKeyPress(e: any) {
    // Web: Enter sends, Shift+Enter adds a new line.
    if (Platform.OS !== 'web') return;
    const ne = e?.nativeEvent ?? {};
    if (ne.key === 'Enter' && !ne.shiftKey && !ne.isComposing) {
      e.preventDefault?.();
      submit();
    }
  }

  if (disabled) {
    return (
      <View style={styles.disabledBox}>
        <Ionicons aria-hidden name="lock-closed-outline" size={14} color={GRAY} importantForAccessibility="no" />
        <Text style={styles.disabledText}>{disabledReason ?? 'You cannot send messages in this conversation.'}</Text>
      </View>
    );
  }

  return (
    <View style={styles.wrap}>
      <View style={styles.inputRow}>
        <TextInput
          ref={inputRef}
          style={styles.input}
          value={text}
          onChangeText={change}
          onKeyPress={onKeyPress}
          placeholder="Write a message…"
          placeholderTextColor={LIGHT}
          multiline
          maxLength={MAX_MESSAGE_LENGTH + 200}
          accessibilityLabel="Message"
          accessibilityHint="Press Enter to send, Shift and Enter for a new line"
        />
        <TouchableOpacity
          style={[styles.sendBtn, { backgroundColor: canSend ? accent : BORDER }]}
          onPress={submit}
          disabled={!canSend}
          activeOpacity={0.85}
          accessibilityRole="button"
          accessibilityLabel={urgent ? 'Send urgent message' : 'Send message'}
          accessibilityState={{ disabled: !canSend }}
        >
          <Ionicons aria-hidden name="send" size={16} color={WHITE} importantForAccessibility="no" />
        </TouchableOpacity>
      </View>

      <View style={[styles.footer, !allowUrgent && styles.footerEnd]}>
        {allowUrgent && (
          <View style={styles.urgentRow}>
            <Switch
              value={urgent}
              onValueChange={setUrgent}
              trackColor={{ false: INPUT_BORDER, true: '#FCA5A5' }}
              thumbColor={urgent ? RED : WHITE}
              accessibilityLabel="Mark as urgent"
              accessibilityHint={URGENT_HELPER_TEXT}
            />
            <Text style={[styles.urgentLabel, urgent && { color: RED }]}>Mark as urgent</Text>
          </View>
        )}
        <Text style={[styles.counter, tooLong && { color: RED }]} accessibilityLiveRegion={tooLong ? 'polite' : 'none'}>
          {tooLong ? `Messages can be at most ${MAX_MESSAGE_LENGTH} characters` : `${trimmed.length}/${MAX_MESSAGE_LENGTH}`}
        </Text>
      </View>
      {/* Always mounted so screen readers announce the helper when the switch turns on. */}
      {allowUrgent && (
        <View accessibilityLiveRegion="polite">
          {urgent && <Text style={styles.helper}>{URGENT_HELPER_TEXT}</Text>}
        </View>
      )}
    </View>
  );
}

/** Outline of the message box and the switch track when off: 4.83:1 on white (WCAG 1.4.11 asks 3:1). */
const INPUT_BORDER = '#6B7280';

const styles = StyleSheet.create({
  wrap:      { borderTopWidth: 1, borderTopColor: BORDER, backgroundColor: WHITE, paddingHorizontal: 12, paddingTop: 10, paddingBottom: 10, gap: 6 },
  inputRow:  { flexDirection: 'row', alignItems: 'flex-end', gap: 8 },
  input: {
    flex: 1, minHeight: 42, maxHeight: 140,
    borderWidth: 1, borderColor: INPUT_BORDER, borderRadius: 10,
    paddingHorizontal: 12, paddingVertical: 10,
    fontSize: 14, color: TEXT, backgroundColor: '#FFFFFF',
  },
  sendBtn:   { width: 42, height: 42, borderRadius: 21, alignItems: 'center', justifyContent: 'center' },
  footer:    { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8, flexWrap: 'wrap' },
  footerEnd: { justifyContent: 'flex-end' },
  urgentRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  urgentLabel: { fontSize: 12, color: GRAY, fontWeight: '600' },
  counter:   { fontSize: 11, color: LIGHT },
  helper:    { fontSize: 11, color: GRAY, lineHeight: 16 },
  disabledBox: { flexDirection: 'row', alignItems: 'center', gap: 8, borderTopWidth: 1, borderTopColor: BORDER, backgroundColor: '#F3F4F6', paddingHorizontal: 16, paddingVertical: 14 },
  disabledText: { fontSize: 13, color: GRAY, flex: 1 },
});
