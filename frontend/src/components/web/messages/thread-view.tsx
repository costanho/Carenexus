import { Ionicons } from '@expo/vector-icons';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  type NativeScrollEvent, type NativeSyntheticEvent, ScrollView, StyleSheet, Text, TouchableOpacity, View,
} from 'react-native';
import { apiErrorMessage } from '@/hooks/use-appointments';
import { usePolling } from '@/hooks/use-page-active';
import { type LocalMessage, useThreadMessages } from '@/hooks/use-thread-messages';
import { fetchThread, httpStatus } from '@/lib/communications/api';
import { threadDraftKey } from '@/lib/communications/drafts';
import { threadOutboxKey } from '@/lib/communications/outbox';
import { clockTime, dayHeading, dayKey, fullTime, threadTitle } from '@/lib/communications/format';
import { NO_THREAD_ACCESS_TEXT, type Message, type Thread } from '@/lib/communications/types';
import { isClosedSupportThread, isSupportThread, keepClosed, supportOpener, supportThreadTitle } from '@/lib/support/format';
import { SUPPORT_CLOSED_TEXT } from '@/lib/support/types';
import { showMessage } from '@/lib/ui/dialogs';
import { ariaLevel } from '@/components/web/ui/a11y';
import { Composer } from './composer';
import { MemberManager } from './member-manager';
import {
  BG, BORDER, Banner, Empty, ErrorBlock, GRAY, LIGHT, Loading, RED, RED_BG, TEXT, UrgentTag, WHITE, useTheme,
} from './ui';

interface Props {
  threadId:       number;
  initialThread:  Thread | null;
  mySystemId:     string | null;
  myRole:         string;
  /** Shown on narrow screens to return to the list. */
  onBack?:        () => void;
  onThreadChanged:(thread: Thread) => void;
  onRead:         (threadId: number, unreadCount: number) => void;
  /** One of my messages was confirmed by the server. */
  onSent?:        (threadId: number, message: Message) => void;
  onArchived:     (threadId: number, archived: boolean) => Promise<void>;
  /** The user left or lost the conversation; close it and refresh the list. */
  onClosed:       () => void;
  /**
   * The viewer is an administrator answering a support conversation: it cannot be archived, and it can be
   * closed. Resolves when the conversation was closed or the administrator changed their mind; rejects when
   * the request failed.
   */
  onCloseConversation?: (threadId: number) => Promise<void>;
}

const SUPPORT_THREAD_POLL_MS = 15_000;

export function ThreadView({
  threadId, initialThread, mySystemId, myRole, onBack, onThreadChanged, onRead, onSent, onArchived, onClosed,
  onCloseConversation,
}: Props) {
  const { accent } = useTheme();
  const [thread,        setThread]        = useState<Thread | null>(initialThread);
  const [threadError,   setThreadError]   = useState<string | null>(null);
  const [threadDenied,  setThreadDenied]  = useState(false);
  const [threadMissing, setThreadMissing] = useState(false);
  const [showMembers,   setShowMembers]   = useState(false);
  const [archiving,     setArchiving]     = useState(false);
  const [closing,       setClosing]       = useState(false);

  const outboxKey = mySystemId ? threadOutboxKey(mySystemId, threadId) : null;
  const msgs = useThreadMessages(threadId, { outboxKey, onRead, onSent });
  const forbidden = threadDenied || msgs.forbidden;
  const notFound  = threadMissing || msgs.notFound;

  // Thread details (members, type) — the list copy may be stale or missing (opened from a notification).
  useEffect(() => {
    let cancelled = false;
    setThread(initialThread);
    setThreadError(null);
    setThreadDenied(false);
    setThreadMissing(false);
    setShowMembers(false);
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

  // Archived is per user and can change from the list while this thread is open: follow the parent's copy.
  const parentArchived = initialThread?.threadId === threadId ? initialThread.archived : undefined;
  useEffect(() => {
    if (parentArchived === undefined) return;
    setThread(t => (t && t.archived !== parentArchived ? { ...t, archived: parentArchived } : t));
  }, [parentArchived]);

  // ── Scrolling: stick to the bottom for new messages, keep position when loading older ones ──
  const scrollRef      = useRef<ScrollView>(null);
  const nearBottomRef  = useRef(true);
  const contentHRef    = useRef(0);
  const offsetRef      = useRef(0);
  const prependRef     = useRef<{ height: number; offset: number } | null>(null);

  useEffect(() => { nearBottomRef.current = true; }, [threadId]);

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

  // ── Support conversations: no patient, no urgent flag, no member changes; an administrator can close them ──
  const adminView = !!onCloseConversation;
  const support   = isSupportThread(thread);
  // Closed is decided by an administrator and can change while this thread is open. It is final (a closed
  // conversation is never reopened), so it is enough that this copy or the parent's newer one says so.
  const parentClosed = initialThread?.threadId === threadId && isClosedSupportThread(initialThread);
  const closed    = support && (isClosedSupportThread(thread) || parentClosed);

  const onThreadChangedRef = useRef(onThreadChanged);
  onThreadChangedRef.current = onThreadChanged;
  /** Quietly reloads the details of a support conversation (closed state, administrators who joined). */
  const refreshSupportThread = useCallback(async () => {
    try {
      const t = await fetchThread(threadId);
      setThread(prev => keepClosed(prev, t));
      onThreadChangedRef.current(t);
    } catch {
      // The message history reports lost access and connection problems; this refresh only adds detail.
    }
  }, [threadId]);

  // The conversation list may not contain this thread (it is left out while a patient filter is on), so an
  // open support conversation checks for itself whether it was closed.
  usePolling(refreshSupportThread, SUPPORT_THREAD_POLL_MS, support && !closed && !forbidden && !notFound);

  // A send that the server refused because the conversation was closed meanwhile: show the closed state now.
  const refusedAsClosed = msgs.pending.some(p => p.status === 'failed' && p.error === SUPPORT_CLOSED_TEXT);
  useEffect(() => {
    if (refusedAsClosed && support && !closed) refreshSupportThread();
  }, [refusedAsClosed, support, closed, refreshSupportThread]);

  function send(body: string, urgent: boolean) {
    nearBottomRef.current = true;
    // The server ignores urgent in a support conversation; never send it.
    msgs.send(body, urgent && !support);
  }

  async function closeConversation() {
    if (!thread || !onCloseConversation) return;
    setClosing(true);
    try {
      await onCloseConversation(thread.threadId);
    } catch (e) {
      showMessage('Could not close conversation', apiErrorMessage(e, 'Please try again.'));
    } finally {
      setClosing(false);
    }
  }

  async function toggleArchive() {
    if (!thread) return;
    setArchiving(true);
    try {
      await onArchived(thread.threadId, !thread.archived);
      const next = { ...thread, archived: !thread.archived };
      setThread(next);
    } catch (e) {
      showMessage('Could not update conversation', apiErrorMessage(e, 'Please try again.'));
    } finally {
      setArchiving(false);
    }
  }

  const title = thread
    ? (support ? supportThreadTitle(thread, mySystemId) : threadTitle(thread, mySystemId))
    : 'Conversation';
  const opener = thread && support ? supportOpener(thread) : null;
  const subtitle = !thread ? '' : support
    ? [
        'Support',
        // Tell the administrator who is asking, unless the title already is that person's name.
        adminView && opener && opener.label !== title ? `Opened by ${opener.label}` : null,
        closed ? 'Closed' : null,
      ].filter(Boolean).join(' · ')
    : [thread.type === 'CARE_TEAM' ? 'Care team' : 'Private', `About ${thread.patientName}`, `${thread.members.length} members`].join(' · ');
  const canClose = adminView && support && !closed;
  const closedReason = adminView
    ? 'This support conversation is closed.'
    : 'This support conversation is closed. You can start a new one from Help & Support.';
  const draftKey = mySystemId ? threadDraftKey(mySystemId, threadId) : null;

  return (
    <View style={styles.wrap}>
      {/* Header */}
      <View style={styles.header}>
        {onBack && (
          <TouchableOpacity style={styles.iconBtn} onPress={onBack} accessibilityRole="button" accessibilityLabel="Back to conversations">
            <Ionicons aria-hidden name="arrow-back" size={20} color={TEXT} importantForAccessibility="no" />
          </TouchableOpacity>
        )}
        <View style={styles.headerBody}>
          {/* Beside the list (wide) the conversation is a sub-section of Messages; alone (narrow, with Back) it is the page. */}
          <Text style={styles.title} numberOfLines={1} accessibilityRole="header" {...ariaLevel(onBack ? 1 : 2)}>{title}</Text>
          {!!subtitle && <Text style={styles.subtitle} numberOfLines={1}>{subtitle}</Text>}
        </View>
        {thread && !forbidden && !notFound && (
          <>
            <TouchableOpacity
              style={styles.iconBtn}
              onPress={() => setShowMembers(s => !s)}
              accessibilityRole="button"
              accessibilityLabel={showMembers ? 'Hide members' : 'Show members'}
              accessibilityState={{ expanded: showMembers }}
              aria-expanded={showMembers}
            >
              <Ionicons aria-hidden name="people-outline" size={19} color={showMembers ? accent : GRAY} importantForAccessibility="no" />
            </TouchableOpacity>
            {/* Support conversations are closed by an administrator, never archived by one. */}
            {!adminView && (
              <TouchableOpacity
                style={styles.iconBtn}
                onPress={toggleArchive}
                disabled={archiving}
                accessibilityRole="button"
                accessibilityLabel={thread.archived ? 'Move conversation to inbox' : 'Archive conversation'}
                accessibilityState={{ busy: archiving, disabled: archiving }}
                aria-busy={archiving}
              >
                <Ionicons aria-hidden name={thread.archived ? 'arrow-undo-outline' : 'archive-outline'} size={19} color={GRAY} importantForAccessibility="no" />
              </TouchableOpacity>
            )}
            {canClose && (
              <TouchableOpacity
                style={[styles.closeBtn, { borderColor: accent }, closing && styles.closeBtnBusy]}
                onPress={closeConversation}
                disabled={closing}
                accessibilityRole="button"
                accessibilityLabel="Close conversation"
                accessibilityState={{ busy: closing, disabled: closing }}
                aria-busy={closing}
              >
                <Ionicons aria-hidden name="checkmark-done-outline" size={16} color={accent} importantForAccessibility="no" />
                {/* On a narrow screen (the Back button is shown) the icon alone keeps the title readable. */}
                {!onBack && <Text style={[styles.closeBtnText, { color: accent }]}>{closing ? 'Closing…' : 'Close conversation'}</Text>}
              </TouchableOpacity>
            )}
          </>
        )}
      </View>

      {forbidden && <Banner tone="danger" icon="lock-closed-outline" text={NO_THREAD_ACCESS_TEXT} />}
      {notFound && <Banner tone="danger" icon="help-circle-outline" text="This conversation could not be found." />}
      {threadError && !forbidden && !notFound && <Banner tone="info" icon="alert-circle-outline" text={threadError} />}
      {msgs.stale && !forbidden && !notFound && (
        <Banner tone="warning" icon="sync-outline" text="Not up to date. Could not reach CareNexus, retrying…" />
      )}

      {showMembers && thread && !forbidden && (
        <MemberManager
          headingLevel={onBack ? 2 : 3}
          thread={thread}
          mySystemId={mySystemId}
          myRole={myRole}
          onChanged={t => { if (t) { setThread(t); onThreadChanged(t); } }}
          onLeft={onClosed}
        />
      )}

      {/* Messages */}
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
          // Focusable so keyboard users can scroll the history; "log" announces new messages politely.
          focusable
          role="log"
          aria-label="Conversation"
          style={styles.flex}
          contentContainerStyle={styles.list}
          onScroll={onScroll}
          scrollEventThrottle={100}
          onContentSizeChange={onContentSizeChange}
        >
          {msgs.hasOlder && (
            <TouchableOpacity
              style={styles.olderBtn}
              onPress={loadOlder}
              disabled={msgs.loadingOlder}
              accessibilityRole="button"
              accessibilityLabel="Load earlier messages"
              accessibilityState={{ busy: msgs.loadingOlder, disabled: msgs.loadingOlder }}
              aria-busy={msgs.loadingOlder}
            >
              <Text style={[styles.olderText, { color: accent }]}>{msgs.loadingOlder ? 'Loading…' : 'Load earlier messages'}</Text>
            </TouchableOpacity>
          )}
          {msgs.error && <Text style={styles.inlineError} accessibilityRole="alert">{msgs.error}</Text>}

          {msgs.messages.length === 0 && msgs.pending.length === 0 ? (
            support && !adminView && !closed ? (
              <Empty
                icon="help-buoy-outline"
                title="How can we help?"
                sub="Write your question below and the support team will reply here. For questions about your health, message your care team instead."
              />
            ) : (
              <Empty icon="chatbubble-outline" title="No messages yet" sub={closed ? undefined : 'Send the first message below.'} />
            )
          ) : (
            <View accessibilityRole="list" accessibilityLabel="Messages">
              {msgs.messages.map((m, i) => (
                <View key={m.messageId} role="listitem">
                  {(i === 0 || dayKey(msgs.messages[i - 1].sentAt) !== dayKey(m.sentAt)) && (
                    <Text style={styles.day} accessibilityRole="header" {...ariaLevel(onBack ? 2 : 3)}>{dayHeading(m.sentAt)}</Text>
                  )}
                  <Bubble message={m} showSender={!m.mine} />
                </View>
              ))}
              {msgs.pending.map(p => (
                <View key={p.clientMessageId} role="listitem">
                  <PendingBubble local={p} onRetry={() => msgs.retry(p.clientMessageId)} onDiscard={() => msgs.discard(p.clientMessageId)} />
                </View>
              ))}
            </View>
          )}
        </ScrollView>
      )}

      <Composer
        draftKey={draftKey}
        disabled={forbidden || notFound || closed}
        disabledReason={forbidden ? NO_THREAD_ACCESS_TEXT : notFound ? 'This conversation is not available.' : closedReason}
        allowUrgent={!support}
        onSend={send}
      />
    </View>
  );
}

function Bubble({ message: m, showSender }: { message: Message; showSender: boolean }) {
  const { accent } = useTheme();
  const time = clockTime(m.sentAt);
  return (
    <View
      style={[styles.bubbleRow, m.mine ? styles.rowMine : styles.rowTheirs]}
      // A named group: who sent it and the full date and time; the message text inside is read as it is shown.
      role="group"
      accessibilityLabel={`${m.mine ? 'You' : m.senderLabel}, ${fullTime(m.sentAt)}${m.urgent ? ', urgent' : ''}`}
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
        <Text style={[styles.time, m.mine && !m.urgent && styles.timeMine]}>{time}</Text>
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
        role="group"
        accessibilityLabel={`Your message${local.urgent ? ', urgent' : ''}, ${failed ? 'not sent' : 'sending'}`}
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
  wrap:       { flex: 1, backgroundColor: BG, minWidth: 0 },
  flex:       { flex: 1 },
  header:     { flexDirection: 'row', alignItems: 'center', gap: 6, paddingHorizontal: 12, paddingVertical: 10, backgroundColor: WHITE, borderBottomWidth: 1, borderBottomColor: BORDER },
  headerBody: { flex: 1, minWidth: 0, paddingHorizontal: 4 },
  title:      { fontSize: 15, fontWeight: '700', color: TEXT },
  subtitle:   { fontSize: 12, color: GRAY, marginTop: 1 },
  iconBtn:    { padding: 8, borderRadius: 8 },
  closeBtn:   { flexDirection: 'row', alignItems: 'center', gap: 6, borderWidth: 1, borderRadius: 8, paddingHorizontal: 10, paddingVertical: 7, backgroundColor: WHITE },
  closeBtnBusy: { opacity: 0.6 },
  closeBtnText: { fontSize: 13, fontWeight: '600' },
  list:       { paddingHorizontal: 14, paddingVertical: 12, flexGrow: 1 },
  olderBtn:   { alignSelf: 'center', paddingVertical: 8, paddingHorizontal: 12 },
  olderText:  { fontSize: 13, fontWeight: '600' },
  inlineError:{ fontSize: 12, color: RED, textAlign: 'center', paddingVertical: 4 },
  day:        { alignSelf: 'center', fontSize: 11, color: LIGHT, fontWeight: '600', marginVertical: 10 },
  bubbleRow:  { marginVertical: 3, maxWidth: '100%' },
  rowMine:    { alignItems: 'flex-end' },
  rowTheirs:  { alignItems: 'flex-start' },
  bubble:     { maxWidth: '82%', borderRadius: 14, paddingHorizontal: 12, paddingVertical: 8, gap: 3 },
  bubbleMine: { borderBottomRightRadius: 4 },
  bubbleTheirs: { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER, borderBottomLeftRadius: 4 },
  bubbleUrgent: { backgroundColor: RED_BG, borderWidth: 1.5, borderColor: RED },
  bubbleFailed: { backgroundColor: WHITE, borderWidth: 1, borderColor: RED },
  bubbleHead: { flexDirection: 'row', alignItems: 'center', gap: 6, flexWrap: 'wrap' },
  sender:     { fontSize: 11, fontWeight: '700', color: GRAY, flexShrink: 1 },
  body:       { fontSize: 14, color: TEXT, lineHeight: 20 },
  bodyMine:   { color: WHITE },
  time:       { fontSize: 10, color: LIGHT, alignSelf: 'flex-end' },
  timeMine:   { color: WHITE },
  failRow:    { flexDirection: 'row', alignItems: 'center', gap: 4, marginTop: 3, flexWrap: 'wrap', justifyContent: 'flex-end', maxWidth: '90%' },
  failText:   { fontSize: 11, color: RED, flexShrink: 1 },
  failBtn:    { paddingHorizontal: 6, paddingVertical: 2 },
  failBtnText:{ fontSize: 12, fontWeight: '700' },
  backBtn:    { borderWidth: 1, borderRadius: 8, paddingHorizontal: 14, paddingVertical: 8, marginTop: 6 },
  backBtnText:{ fontSize: 13, fontWeight: '600' },
});
