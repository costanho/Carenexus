import { Ionicons } from '@expo/vector-icons';
import { useState } from 'react';
import { Pressable, StyleSheet, Switch, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { BORDER, GRAY, LIGHT, RED, TEXT, WHITE, useTheme } from '@/components/web/messages/ui';
import { useDraft } from '@/hooks/use-draft';
import { MAX_MESSAGE_LENGTH, URGENT_HELPER_TEXT } from '@/lib/communications/types';

const MIN_INPUT = 40;
const MAX_INPUT = 120;

interface Props {
  /** Stored draft key for this thread; null disables drafts. */
  draftKey:        string | null;
  disabled?:       boolean;
  disabledReason?: string;
  onSend:          (body: string, urgent: boolean) => void;
}

/** Phone composer: message box, send, urgent switch with the required helper text (same rules as web). */
export function MobileComposer({ draftKey, disabled, disabledReason, onSend }: Props) {
  const { accent } = useTheme();
  const { text, setText, clear } = useDraft(draftKey);
  const [urgent, setUrgent] = useState(false);
  // The box grows with its text up to 120 pt; set explicitly so it shrinks back after a send (iOS keeps
  // a multiline input at its tallest size otherwise).
  const [inputHeight, setInputHeight] = useState(MIN_INPUT);

  const trimmed = text.trim();
  const tooLong = trimmed.length > MAX_MESSAGE_LENGTH;
  const canSend = !disabled && trimmed.length > 0 && !tooLong;

  function submit() {
    if (!canSend) return;
    // The text is safe once handed over: unconfirmed sends are kept in the thread's outbox.
    onSend(trimmed, urgent);
    clear();
    setUrgent(false);
    setInputHeight(MIN_INPUT);
  }

  if (disabled) {
    return (
      <View style={styles.disabledBox} accessibilityRole="text">
        <Ionicons name="lock-closed-outline" size={14} color={GRAY} importantForAccessibility="no" />
        <Text style={styles.disabledText}>{disabledReason ?? 'You cannot send messages in this conversation.'}</Text>
      </View>
    );
  }

  return (
    <View style={styles.wrap}>
      {urgent && <Text style={styles.helper} accessibilityLiveRegion="polite">{URGENT_HELPER_TEXT}</Text>}
      <View style={styles.inputRow}>
        <TextInput
          style={[styles.input, { height: inputHeight }]}
          value={text}
          onChangeText={setText}
          onContentSizeChange={e => {
            const h = Math.min(MAX_INPUT, Math.max(MIN_INPUT, Math.ceil(e.nativeEvent.contentSize.height) + 20));
            setInputHeight(h);
          }}
          placeholder="Write a message…"
          placeholderTextColor={LIGHT}
          multiline
          maxLength={MAX_MESSAGE_LENGTH + 200}
          accessibilityLabel="Message"
        />
        <TouchableOpacity
          style={[styles.sendBtn, { backgroundColor: canSend ? (urgent ? RED : accent) : BORDER }]}
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
        {/* The whole row toggles (a bigger target than the switch alone). */}
        <Pressable
          style={styles.urgentRow}
          onPress={() => setUrgent(u => !u)}
          hitSlop={6}
          accessibilityRole="switch"
          accessibilityLabel="Mark as urgent"
          accessibilityHint={URGENT_HELPER_TEXT}
          accessibilityState={{ checked: urgent }}
        >
          <Switch
            value={urgent}
            onValueChange={setUrgent}
            trackColor={{ false: BORDER, true: '#FCA5A5' }}
            thumbColor={urgent ? RED : WHITE}
            ios_backgroundColor={BORDER}
            importantForAccessibility="no-hide-descendants"
            accessibilityElementsHidden
          />
          <Text style={[styles.urgentLabel, urgent && { color: RED }]}>Mark as urgent</Text>
        </Pressable>
        {(tooLong || trimmed.length > MAX_MESSAGE_LENGTH - 500) && (
          <Text style={[styles.counter, tooLong && { color: RED }]} accessibilityLiveRegion="polite">
            {tooLong ? `Messages can be at most ${MAX_MESSAGE_LENGTH} characters` : `${trimmed.length}/${MAX_MESSAGE_LENGTH}`}
          </Text>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap:        { borderTopWidth: 1, borderTopColor: BORDER, backgroundColor: WHITE, paddingHorizontal: 12, paddingTop: 8, paddingBottom: 8, gap: 6 },
  inputRow:    { flexDirection: 'row', alignItems: 'flex-end', gap: 8 },
  input: {
    flex: 1,
    borderWidth: 1, borderColor: BORDER, borderRadius: 20,
    paddingHorizontal: 14, paddingTop: 10, paddingBottom: 10,
    fontSize: 15, color: TEXT, backgroundColor: WHITE,
  },
  sendBtn:     { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center' },
  footer:      { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8 },
  urgentRow:   { flexDirection: 'row', alignItems: 'center', gap: 8, paddingVertical: 2, paddingRight: 8 },
  urgentLabel: { fontSize: 12, color: GRAY, fontWeight: '600' },
  counter:     { fontSize: 11, color: LIGHT, flexShrink: 1, textAlign: 'right' },
  helper:      { fontSize: 11, color: RED, lineHeight: 16 },
  disabledBox: { flexDirection: 'row', alignItems: 'center', gap: 8, borderTopWidth: 1, borderTopColor: BORDER, backgroundColor: '#F3F4F6', paddingHorizontal: 16, paddingVertical: 14 },
  disabledText:{ fontSize: 13, color: GRAY, flex: 1 },
});
