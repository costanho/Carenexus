import { Ionicons } from '@expo/vector-icons';
import { useEffect, useRef, useState } from 'react';
import { Platform, StyleSheet, Switch, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { clearDraft, loadDraft, saveDraft } from '@/lib/communications/drafts';
import { MAX_MESSAGE_LENGTH, URGENT_HELPER_TEXT } from '@/lib/communications/types';
import { BORDER, GRAY, LIGHT, RED, TEXT, WHITE, useTheme } from './ui';

interface Props {
  /** sessionStorage key for this thread's draft; null disables drafts. */
  draftKey:  string | null;
  disabled?: boolean;
  disabledReason?: string;
  onSend:    (body: string, urgent: boolean) => void;
}

const DRAFT_DEBOUNCE_MS = 400;

export function Composer({ draftKey, disabled, disabledReason, onSend }: Props) {
  const { accent } = useTheme();
  const [text,   setText]   = useState(() => (draftKey ? loadDraft(draftKey) : ''));
  const [urgent, setUrgent] = useState(false);
  const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const keyRef    = useRef(draftKey);
  const inputRef  = useRef<TextInput>(null);

  // Restore the draft when switching threads.
  useEffect(() => {
    keyRef.current = draftKey;
    setText(draftKey ? loadDraft(draftKey) : '');
    setUrgent(false);
  }, [draftKey]);

  useEffect(() => () => { if (saveTimer.current) clearTimeout(saveTimer.current); }, []);

  function change(value: string) {
    setText(value);
    const key = keyRef.current;
    if (!key) return;
    if (saveTimer.current) clearTimeout(saveTimer.current);
    saveTimer.current = setTimeout(() => saveDraft(key, value), DRAFT_DEBOUNCE_MS);
  }

  const trimmed  = text.trim();
  const tooLong  = trimmed.length > MAX_MESSAGE_LENGTH;
  const canSend  = !disabled && trimmed.length > 0 && !tooLong;

  function submit() {
    if (!canSend) return;
    onSend(trimmed, urgent);
    setText('');
    setUrgent(false);
    if (saveTimer.current) clearTimeout(saveTimer.current);
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
        <Ionicons name="lock-closed-outline" size={14} color={GRAY} importantForAccessibility="no" />
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
          <Ionicons name="send" size={16} color={WHITE} importantForAccessibility="no" />
        </TouchableOpacity>
      </View>

      <View style={styles.footer}>
        <View style={styles.urgentRow}>
          <Switch
            value={urgent}
            onValueChange={setUrgent}
            trackColor={{ false: BORDER, true: '#FCA5A5' }}
            thumbColor={urgent ? RED : WHITE}
            accessibilityLabel="Mark as urgent"
            accessibilityHint={URGENT_HELPER_TEXT}
          />
          <Text style={[styles.urgentLabel, urgent && { color: RED }]}>Mark as urgent</Text>
        </View>
        <Text style={[styles.counter, tooLong && { color: RED }]} accessibilityLiveRegion={tooLong ? 'polite' : 'none'}>
          {tooLong ? `Messages can be at most ${MAX_MESSAGE_LENGTH} characters` : `${trimmed.length}/${MAX_MESSAGE_LENGTH}`}
        </Text>
      </View>
      {urgent && <Text style={styles.helper}>{URGENT_HELPER_TEXT}</Text>}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap:      { borderTopWidth: 1, borderTopColor: BORDER, backgroundColor: WHITE, paddingHorizontal: 12, paddingTop: 10, paddingBottom: 10, gap: 6 },
  inputRow:  { flexDirection: 'row', alignItems: 'flex-end', gap: 8 },
  input: {
    flex: 1, minHeight: 42, maxHeight: 140,
    borderWidth: 1, borderColor: BORDER, borderRadius: 10,
    paddingHorizontal: 12, paddingVertical: 10,
    fontSize: 14, color: TEXT, backgroundColor: '#FFFFFF',
  },
  sendBtn:   { width: 42, height: 42, borderRadius: 21, alignItems: 'center', justifyContent: 'center' },
  footer:    { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8, flexWrap: 'wrap' },
  urgentRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  urgentLabel: { fontSize: 12, color: GRAY, fontWeight: '600' },
  counter:   { fontSize: 11, color: LIGHT },
  helper:    { fontSize: 11, color: GRAY, lineHeight: 16 },
  disabledBox: { flexDirection: 'row', alignItems: 'center', gap: 8, borderTopWidth: 1, borderTopColor: BORDER, backgroundColor: '#F3F4F6', paddingHorizontal: 16, paddingVertical: 14 },
  disabledText: { fontSize: 13, color: GRAY, flex: 1 },
});
