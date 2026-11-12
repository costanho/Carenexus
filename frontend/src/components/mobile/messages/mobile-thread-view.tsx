import { Ionicons } from '@expo/vector-icons';
import { useEffect, useRef, useState } from 'react';
import {
  Keyboard, KeyboardAvoidingView, Modal, type NativeScrollEvent, type NativeSyntheticEvent, Platform, ScrollView,
  StyleSheet, Text, TouchableOpacity, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { MemberManager } from '@/components/web/messages/member-manager';
import {
  BG, BORDER, Banner, Empty, ErrorBlock, GRAY, LIGHT, Loading, RED, RED_BG, TEXT, UrgentTag, WHITE, useTheme,
} from '@/components/web/messages/ui';
import { apiErrorMessage } from '@/hooks/use-appointments';
import { type LocalMessage, useThreadMessages } from '@/hooks/use-thread-messages';
import { fetchThread, httpStatus } from '@/lib/communications/api';
import { threadDraftKey } from '@/lib/communications/drafts';
import { clockTime, dayHeading, dayKey, fullTime, threadTitle } from '@/lib/communications/format';
import { threadOutboxKey } from '@/lib/communications/outbox';
import { NO_THREAD_ACCESS_TEXT, type Message, type Thread } from '@/lib/communications/types';
import { showMessage } from '@/lib/ui/dialogs';
import { MobileComposer } from './mobile-composer';

interface Props {
  threadId:        number;
  initialThread:   Thread | null;
  mySystemId:      string | null;
  myRole:          string;
  onBack:          () => void;
  onThreadChanged: (thread: Thread) => void;
  onRead:          (threadId: number, unreadCount: number) => void;
  onSent?:         (threadId: number, message: Message) => void;
  onArchived:      (threadId: number, archived: boolean) => Promise<void>;
  /** The user left or lost the conversation; close it and refresh the list. */
  onClosed:        () => void;
}

/** One conversation, full screen on a phone. Same states and copy as the web thread view. */
export function MobileThreadView({
  threadId, initialThread, mySystemId, myRole, onBack, onThreadChanged, onRead, onSent, onArchived, onClosed,
}: Props) {
  const { accent } = useTheme();
  const insets = useSafeAreaInsets();
  const [thread,        setThread]        = useState<Thread | null>(initialThread);
  const [threadError,   setThreadError]   = useState<string | null>(null);
  const [threadDenied,  setThreadDenied]  = useState(false);
  const [threadMissing, setThreadMissing] = useState(false);
  const [showMembers,   setShowMembers]   = useState(false);
  const [archiving,     setArchiving]     = useState(false);

  const outboxKey = mySystemId ? threadOutboxKey(mySystemId, threadId) : null;
  const msgs = useThreadMessages(threadId, { outboxKey, onRead, onSent });
  const forbidden = threadDenied || msgs.forbidden;
  const notFound  = threadMissing || msgs.notFound;

  useEffect(() => {
    let cancelled = false;
    setThread(initialThread);
    setThreadError(null);
    setThreadDenied(false);
    setThreadMissing(false);
    fetchThread(threadId)
      .then(t => { if (!cancelled) { setThread(t); onThreadChanged(t); } })
      .catch(e => {
        if (cancelled) return;
        const s = httpStatus(e);
        if (s === 403) setThreadDenied(true);
        else if (s === 404) setThreadMissing(true);
        else setThreadError(apiErrorMessage(e, 'Failed to load this conversation.'));
      });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [threadId]);

  // Losing access while the members sheet is open closes it.
  useEffect(() => { if (forbidden || notFound) setShowMembers(false); }, [forbidden, notFound]);

  // ── Scrolling: stick to the bottom for new messages, keep position when loading older ones ──
  const scrollRef     = useRef<ScrollView>(null);
  const nearBottomRef = useRef(true);
  const contentHRef   = useRef(0);
  const offsetRef     = useRef(0);
  const prependRef    = useRef<{ height: number; offset: number } | null>(null);

  useEffect(() => {
    const sub = Keyboard.addListener(Platform.OS === 'ios' ? 'keyboardWillShow' : 'keyboardDidShow', () => {
      if (nearBottomRef.current) setTimeout(() => scrollRef.current?.scrollToEnd({ animated: true }), 50);
    });
    return () => sub.remove();
  }, []);

  function onScroll(e: NativeSyntheticEvent<NativeScrollEvent>) {
    const { contentOffset, contentSize, layoutMeasurement } = e.nativeEvent;
    offsetRef.current = contentOffset.y;
    nearBottomRef.current = contentOffset.y + layoutMeasurement.height >= contentSize.height - 80;
  }

  function onContentSizeChange(_w: number, h: number) {
    const prev = prependRef.current;
    if (prev) {
      prependRef.current = null;
      scrollRef.current?.scrollTo({ y: Math.max(0, h - prev.height + prev.offset), animated: false });
    } else if (nearBottomRef.current) {
      scrollRef.current?.scrollToEnd({ animated: false });
    }
    contentHRef.current = h;
  }

  function loadOlder() {
    prependRef.current = { height: contentHRef.current, offset: offsetRef.current };
    msgs.loadOlder();
  }

  function send(body: string, urgent: boolean) {
    nearBottomRef.current = true;
    msgs.send(body, urgent);
  }

  async function toggleArchive() {
    if (!thread) return;
    setArchiving(true);
    try {
      await onArchived(thread.threadId, !thread.archived);
      setThread({ ...thread, archived: !thread.archived });
    } catch (e) {
      showMessage('Could not update conversation', apiErrorMessage(e, 'Please try again.'));
    } finally {
      setArchiving(false);
    }
  }

  const title = thread ? threadTitle(thread, mySystemId) : 'Conversation';
  const subtitle = thread
    ? [thread.type === 'CARE_TEAM' ? 'Care team' : 'Private', `About ${thread.patientName}`, `${thread.members.length} members`].join(' · ')
    : '';
  const draftKey = mySystemId ? threadDraftKey(mySystemId, threadId) : null;

  return (
    <KeyboardAvoidingView style={styles.wrap} behavior={Platform.OS === 'ios' ? 'padding' : 'height'}>
      <View style={[styles.header, { paddingTop: insets.top + 8 }]}>
        <TouchableOpacity style={styles.iconBtn} onPress={onBack} accessibilityRole="button" accessibilityLabel="Back to conversations">
          <Ionicons name="chevron-back" size={24} color={TEXT} importantForAccessibility="no" />
        </TouchableOpacity>
        <View style={styles.headerBody}>
          <Text style={styles.title} numberOfLines={1} accessibilityRole="header">{title}</Text>
          {!!subtitle && <Text style={styles.subtitle} numberOfLines={1}>{subtitle}</Text>}
        </View>
        {thread && !forbidden && !notFound && (
          <>
            <TouchableOpacity
              style={styles.iconBtn}
              onPress={() => setShowMembers(true)}
              accessibilityRole="button"
              accessibilityLabel="Show members"
            >
              <Ionicons name="people-outline" size={21} color={GRAY} importantForAccessibility="no" />
            </TouchableOpacity>
            <TouchableOpacity
              style={styles.iconBtn}
              onPress={toggleArchive}
              disabled={archiving}
              accessibilityRole="button"
              accessibilityLabel={thread.archived ? 'Move conversation to inbox' : 'Archive conversation'}
              accessibilityState={{ busy: archiving, disabled: archiving }}
            >
              <Ionicons name={thread.archived ? 'arrow-undo-outline' : 'archive-outline'} size={21} color={GRAY} importantForAccessibility="no" />
            </TouchableOpacity>
          </>
        )}
      </View>

      {forbidden && <Banner tone="danger" icon="lock-closed-outline" text={NO_THREAD_ACCESS_TEXT} />}
      {notFound && <Banner tone="danger" icon="help-circle-outline" text="This conversation could not be found." />}
      {threadError && !forbidden && !notFound && <Banner tone="info" icon="alert-circle-outline" text={threadError} />}
      {msgs.stale && !forbidden && !notFound && (
        <Banner tone="warning" icon="sync-outline" text="Not up to date. Could not reach CareNexus, retrying…" />
      )}

      {forbidden || notFound ? (
        <View style={styles.flex}>
          <Empty
            icon={forbidden ? 'lock-closed-outline' : 'chatbubble-ellipses-outline'}
            title={forbidden ? NO_THREAD_ACCESS_TEXT : 'Conversation unavailable'}
            sub={forbidden ? 'You may have been removed, or your access to this patient changed.' : undefined}
          >
            <TouchableOpacity style={[styles.backBtn, { borderColor: accent }]} onPress={onClosed} accessibilityRole="button" accessibilityLabel="Back to conversations">
              <Text style={[styles.backBtnText, { color: accent }]}>Back to conversations</Text>
            </TouchableOpacity>
          </Empty>
        </View>
      ) : msgs.loading ? (
        <View style={styles.flex}><Loading label="Loading messages" /></View>
      ) : msgs.error && msgs.messages.length === 0 && msgs.pending.length === 0 ? (
        <View style={styles.flex}><ErrorBlock message={msgs.error} onRetry={msgs.reload} /></View>
      ) : (
        <ScrollView
          ref={scrollRef}
          style={styles.flex}
          contentContainerStyle={styles.list}
          onScroll={onScroll}
          scrollEventThrottle={100}
          onContentSizeChange={onContentSizeChange}
          keyboardDismissMode="interactive"
          keyboardShouldPersistTaps="handled"
        >
          {msgs.hasOlder && (
            <TouchableOpacity
              style={styles.olderBtn}
              onPress={loadOlder}
              disabled={msgs.loadingOlder}
              accessibilityRole="button"
              accessibilityLabel="Load earlier messages"
              accessibilityState={{ busy: msgs.loadingOlder, disabled: msgs.loadingOlder }}
            >
              <Text style={[styles.olderText, { color: accent }]}>{msgs.loadingOlder ? 'Loading…' : 'Load earlier messages'}</Text>
            </TouchableOpacity>
          )}
          {msgs.error && <Text style={styles.inlineError} accessibilityRole="alert">{msgs.error}</Text>}

          {msgs.messages.length === 0 && msgs.pending.length === 0 ? (
            <Empty icon="chatbubble-outline" title="No messages yet" sub="Send the first message below." />
          ) : (
            <View>
              {msgs.messages.map((m, i) => (
                <View key={m.messageId}>
                  {(i === 0 || dayKey(msgs.messages[i - 1].sentAt) !== dayKey(m.sentAt)) && (
                    <Text style={styles.day} accessibilityRole="header">{dayHeading(m.sentAt)}</Text>
                  )}
                  <Bubble message={m} showSender={!m.mine} />
                </View>
              ))}
              {msgs.pending.map(p => (
                <PendingBubble
                  key={p.clientMessageId}
                  local={p}
                  onRetry={() => msgs.retry(p.clientMessageId)}
                  onDiscard={() => msgs.discard(p.clientMessageId)}
                />
              ))}
            </View>
          )}
        </ScrollView>
      )}

      <MobileComposer
        draftKey={draftKey}
        disabled={forbidden || notFound}
        disabledReason={forbidden ? NO_THREAD_ACCESS_TEXT : 'This conversation is not available.'}
        onSend={send}
      />

      <Modal
        visible={showMembers && !!thread}
        animationType="slide"
        presentationStyle="pageSheet"
        onRequestClose={() => setShowMembers(false)}
      >
        <View style={styles.sheet}>
          <View style={styles.sheetHeader}>
            <Text style={styles.sheetTitle} accessibilityRole="header">Conversation members</Text>
            <TouchableOpacity onPress={() => setShowMembers(false)} style={styles.iconBtn} accessibilityRole="button" accessibilityLabel="Close members">
              <Text style={[styles.sheetDone, { color: accent }]}>Done</Text>
            </TouchableOpacity>
          </View>
          <ScrollView keyboardShouldPersistTaps="handled">
            {thread && (
              <MemberManager
                thread={thread}
                mySystemId={mySystemId}
                myRole={myRole}
                onChanged={t => { if (t) { setThread(t); onThreadChanged(t); } }}
                onLeft={() => { setShowMembers(false); onClosed(); }}
              />
            )}
          </ScrollView>
        </View>
      </Modal>
    </KeyboardAvoidingView>
  );
}

function Bubble({ message: m, showSender }: { message: Message; showSender: boolean }) {
  const { accent } = useTheme();
  return (
    <View
      style={[styles.bubbleRow, m.mine ? styles.rowMine : styles.rowTheirs]}
      accessible
      accessibilityLabel={`${m.mine ? 'You' : m.senderLabel}, ${fullTime(m.sentAt)}${m.urgent ? ', urgent' : ''}: ${m.body}`}
    >
      <View
        style={[
          styles.bubble,
          m.mine ? [styles.bubbleMine, { backgroundColor: accent }] : styles.bubbleTheirs,
          m.urgent && styles.bubbleUrgent,
        ]}
      >
        {(showSender || m.urgent) && (
          <View style={styles.bubbleHead}>
            {showSender && <Text style={styles.sender} numberOfLines={1}>{m.senderLabel}</Text>}
            {m.urgent && <UrgentTag />}
          </View>
        )}
        <Text style={[styles.body, m.mine && !m.urgent && styles.bodyMine]} selectable>{m.body}</Text>
        <Text style={[styles.time, m.mine && !m.urgent && styles.timeMine]}>{clockTime(m.sentAt)}</Text>
      </View>
    </View>
  );
}

function PendingBubble({ local, onRetry, onDiscard }: { local: LocalMessage; onRetry: () => void; onDiscard: () => void }) {
  const { accent } = useTheme();
  const failed = local.status === 'failed';
  return (
    <View style={[styles.bubbleRow, styles.rowMine]}>
      <View
        style={[
          styles.bubble, styles.bubbleMine, { backgroundColor: accent },
          local.urgent && styles.bubbleUrgent,
          { opacity: failed ? 1 : 0.7 },
          failed && styles.bubbleFailed,
        ]}
        accessible
        accessibilityLabel={`Your message${local.urgent ? ', urgent' : ''}, ${failed ? 'not sent' : 'sending'}: ${local.body}`}
      >
        {local.urgent && <View style={styles.bubbleHead}><UrgentTag /></View>}
        <Text style={[styles.body, !local.urgent && !failed && styles.bodyMine]}>{local.body}</Text>
        <Text style={[styles.time, !local.urgent && !failed && styles.timeMine]}>{failed ? 'Not sent' : 'Sending…'}</Text>
      </View>
      {failed && (
        <View style={styles.failRow}>
          <Text style={styles.failText} accessibilityRole="alert">{local.error ?? 'Message not sent.'}</Text>
          <TouchableOpacity onPress={onRetry} style={styles.failBtn} accessibilityRole="button" accessibilityLabel="Retry sending message">
            <Text style={[styles.failBtnText, { color: accent }]}>Retry</Text>
          </TouchableOpacity>
          <TouchableOpacity onPress={onDiscard} style={styles.failBtn} accessibilityRole="button" accessibilityLabel="Discard unsent message">
            <Text style={[styles.failBtnText, { color: GRAY }]}>Discard</Text>
          </TouchableOpacity>
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap:        { flex: 1, backgroundColor: BG },
  flex:        { flex: 1 },
  header:      { flexDirection: 'row', alignItems: 'center', gap: 2, paddingHorizontal: 6, paddingBottom: 8, backgroundColor: WHITE, borderBottomWidth: 1, borderBottomColor: BORDER },
  headerBody:  { flex: 1, minWidth: 0, paddingHorizontal: 4 },
  title:       { fontSize: 16, fontWeight: '700', color: TEXT },
  subtitle:    { fontSize: 12, color: GRAY, marginTop: 1 },
  iconBtn:     { padding: 8, borderRadius: 8 },
  list:        { paddingHorizontal: 12, paddingVertical: 12, flexGrow: 1 },
  olderBtn:    { alignSelf: 'center', paddingVertical: 8, paddingHorizontal: 12 },
  olderText:   { fontSize: 13, fontWeight: '600' },
  inlineError: { fontSize: 12, color: RED, textAlign: 'center', paddingVertical: 4 },
  day:         { alignSelf: 'center', fontSize: 11, color: LIGHT, fontWeight: '600', marginVertical: 10 },
  bubbleRow:   { marginVertical: 3, maxWidth: '100%' },
  rowMine:     { alignItems: 'flex-end' },
  rowTheirs:   { alignItems: 'flex-start' },
  bubble:      { maxWidth: '84%', borderRadius: 16, paddingHorizontal: 12, paddingVertical: 8, gap: 3 },
  bubbleMine:  { borderBottomRightRadius: 4 },
  bubbleTheirs:{ backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER, borderBottomLeftRadius: 4 },
  bubbleUrgent:{ backgroundColor: RED_BG, borderWidth: 1.5, borderColor: RED },
  bubbleFailed:{ backgroundColor: WHITE, borderWidth: 1, borderColor: RED },
  bubbleHead:  { flexDirection: 'row', alignItems: 'center', gap: 6, flexWrap: 'wrap' },
  sender:      { fontSize: 11, fontWeight: '700', color: GRAY, flexShrink: 1 },
  body:        { fontSize: 15, color: TEXT, lineHeight: 21 },
  bodyMine:    { color: WHITE },
  time:        { fontSize: 10, color: LIGHT, alignSelf: 'flex-end' },
  timeMine:    { color: WHITE },
  failRow:     { flexDirection: 'row', alignItems: 'center', gap: 4, marginTop: 3, flexWrap: 'wrap', justifyContent: 'flex-end', maxWidth: '90%' },
  failText:    { fontSize: 12, color: RED, flexShrink: 1 },
  failBtn:     { paddingHorizontal: 8, paddingVertical: 6 },
  failBtnText: { fontSize: 13, fontWeight: '700' },
  backBtn:     { borderWidth: 1, borderRadius: 8, paddingHorizontal: 14, paddingVertical: 10, marginTop: 6 },
  backBtnText: { fontSize: 14, fontWeight: '600' },
  sheet:       { flex: 1, backgroundColor: WHITE },
  sheetHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 16, paddingVertical: 10, borderBottomWidth: 1, borderBottomColor: BORDER },
  sheetTitle:  { fontSize: 16, fontWeight: '700', color: TEXT },
  sheetDone:   { fontSize: 15, fontWeight: '700' },
});
