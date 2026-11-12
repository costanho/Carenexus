import { Ionicons } from '@expo/vector-icons';
import { useCallback, useEffect, useRef, useState } from 'react';
import { type LayoutChangeEvent, Platform, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { apiErrorMessage } from '@/hooks/use-appointments';
import { useCurrentUser } from '@/hooks/use-current-user';
import { refreshUnreadCounts } from '@/hooks/use-notifications';
import { usePageActive } from '@/hooks/use-page-active';
import { useSupportThreads } from '@/hooks/use-support-threads';
import { useThreads } from '@/hooks/use-threads';
import { setThreadArchived } from '@/lib/communications/api';
import { clearAllDrafts } from '@/lib/communications/drafts';
import { clearAllOutboxes } from '@/lib/communications/outbox';
import type { Message, MessagingMode, Thread } from '@/lib/communications/types';
import { keepClosed, supportRowAsThread, withSupportRow } from '@/lib/support/format';
import type { SupportThread } from '@/lib/support/types';
import { confirmAction, showMessage } from '@/lib/ui/dialogs';
import { ariaLevel } from '@/components/web/ui/a11y';
import { CaregiverMessagingStatus } from './caregiver-messaging-status';
import { NewConversation } from './new-conversation';
import { type PatientOption, PatientFilter } from './patient-filter';
import { SupportThreadList } from './support-thread-list';
import { ThreadList } from './thread-list';
import { ThreadView } from './thread-view';
import { BG, BORDER, Banner, DEFAULT_THEME, Empty, GRAY, type MessagesTheme, TEXT, ThemeContext, WHITE } from './ui';

/**
 * Who is using the screen. The three member roles see their own conversations. `ADMIN` is the administrator
 * support inbox: only support conversations, listed from the admin endpoint, with a Close action and without
 * new conversations, the patient filter or archiving.
 */
export type MessagesMode = MessagingMode | 'ADMIN';

export interface MessagesScreenProps {
  mode:   MessagesMode;
  theme?: MessagesTheme;
  /** Open this thread (e.g. from a notification). Cleared through `onOpenThreadHandled`. */
  openThreadId?:        number | null;
  onOpenThreadHandled?: () => void;
}

const WIDE_MIN = 860;

const SUBTITLES: Record<MessagesMode, string> = {
  PATIENT:   'Message your doctors and care team.',
  DOCTOR:    'Conversations with your patients and their caregivers.',
  CAREGIVER: "Message your loved ones' care teams.",
  ADMIN:     'Conversations people opened from Help & Support.',
};

/** The two list tabs: inbox and archive for members, open and closed for the support inbox. */
const MEMBER_TABS = [{ key: false, label: 'Inbox' }, { key: true, label: 'Archived' }];
const ADMIN_TABS  = [{ key: false, label: 'Open' }, { key: true, label: 'Closed' }];

type Pane = { kind: 'none' } | { kind: 'thread'; id: number } | { kind: 'new' };

/** Messages experience shared by the patient, doctor and caregiver dashboards and the admin support inbox (web). */
export function MessagesScreen({ mode, theme = DEFAULT_THEME, openThreadId, onOpenThreadHandled }: MessagesScreenProps) {
  const { user } = useCurrentUser();
  const { online } = usePageActive();
  const [width,    setWidth]    = useState(0);
  const [archived, setArchived] = useState(false);
  const [pane,     setPane]     = useState<Pane>({ kind: 'none' });
  const [known,    setKnown]    = useState<Record<number, Thread>>({});
  const [patient,  setPatient]  = useState<PatientOption | null>(null);
  // Support inbox (admin mode) only: which tab is shown, which conversation is being closed, and the
  // conversations closed from this screen (a closed row leaves the Open list, but its thread may still be shown).
  const [showClosed, setShowClosed] = useState(false);
  const [closingId,  setClosingId]  = useState<number | null>(null);
  const [closedRows, setClosedRows] = useState<Record<number, SupportThread>>({});

  // The API role of the viewer: 'ADMIN' for the support inbox, otherwise the member role.
  const admin = mode === 'ADMIN';
  const memberMode: MessagingMode | null = mode === 'ADMIN' ? null : mode;

  // Administrators may not list conversations (403); their list is the support inbox.
  const threads = useThreads({ archived, patientSystemId: patient?.systemId ?? null, enabled: !admin });
  const support = useSupportThreads({ open: !showClosed, enabled: admin });
  const wide = width === 0 || width >= WIDE_MIN;
  const mySystemId = user?.system_id ?? null;
  // The member role, not the login role: sign-in stores 'doctor' / 'proxy', the members panel compares 'DOCTOR' / 'CAREGIVER'.
  const myRole = mode;

  // Unsent text of anyone else who used this tab is never shown and need not be kept.
  useEffect(() => {
    if (!mySystemId) return;
    clearAllDrafts(mySystemId);
    clearAllOutboxes(mySystemId);
  }, [mySystemId]);

  // Open a thread requested by the shell (notification click).
  useEffect(() => {
    if (openThreadId == null) return;
    setArchived(false);
    setPatient(null);
    setPane({ kind: 'thread', id: openThreadId });
    onOpenThreadHandled?.();
  }, [openThreadId, onOpenThreadHandled]);

  const selectedId = pane.kind === 'thread' ? pane.id : null;
  const selectedRow = admin && selectedId != null
    ? (closedRows[selectedId] ?? support.threads.find(r => r.threadId === selectedId) ?? null)
    : null;
  const selectedThread = selectedId == null ? null
    : admin
      // The inbox row knows the newest closed state; before the details arrive it also stands in for them.
      ? (known[selectedId] ? withSupportRow(known[selectedId], selectedRow) : (selectedRow ? supportRowAsThread(selectedRow) : null))
      : (threads.threads.find(t => t.threadId === selectedId) ?? known[selectedId] ?? null);

  const remember = useCallback((t: Thread) => {
    // A closed support conversation stays closed even if an older "open" copy arrives late.
    setKnown(k => ({ ...k, [t.threadId]: keepClosed(k[t.threadId], t) }));
    threads.patch(t.threadId, t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [threads.patch]);

  const onRead = useCallback((threadId: number, unreadCount: number) => {
    if (admin) support.patch(threadId, { unread: unreadCount > 0 });
    else threads.patch(threadId, unreadCount === 0 ? { unreadCount: 0, hasUrgentUnread: false } : { unreadCount });
    refreshUnreadCounts();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [admin, threads.patch, support.patch]);

  // My own send: show it as the newest preview at the top straight away, then confirm with the server.
  const onSent = useCallback((threadId: number, m: Message) => {
    const change = {
      lastMessage: {
        messageId: m.messageId, senderSystemId: m.senderSystemId, senderLabel: m.senderLabel,
        preview: Array.from(m.body).slice(0, 120).join(''), urgent: m.urgent, sentAt: m.sentAt,
      },
      lastMessageAt: m.sentAt,
    };
    if (admin) {
      support.bump(threadId, { ...change, unread: false });
      support.refetch();
    } else {
      threads.bump(threadId, change);
      threads.refetch();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [admin, threads.bump, threads.refetch, support.bump, support.refetch]);

  /**
   * Admin mode: closes a support conversation after the administrator confirms. Resolves when it was closed or
   * the administrator cancelled; rejects with the request error.
   */
  const closeConversation = useCallback(async (threadId: number) => {
    const ok = await confirmAction(
      'Close this support conversation?',
      'Nobody will be able to send new messages in it. The person who opened it can still read it and can start a new conversation from Help & Support.',
      'Close conversation',
    );
    if (!ok) return;
    setClosingId(threadId);
    try {
      const row = await support.close(threadId);
      // Closed is final, so this row stays the truth for the open thread whatever arrives later.
      if (!row.open) setClosedRows(rows => ({ ...rows, [threadId]: row }));
      support.refetch();
    } finally {
      setClosingId(id => (id === threadId ? null : id));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [support.close, support.refetch]);

  async function closeFromList(row: SupportThread) {
    try {
      await closeConversation(row.threadId);
    } catch (e) {
      showMessage('Could not close conversation', apiErrorMessage(e, 'Please try again.'));
    }
  }

  async function archiveFromList(t: Thread) {
    try {
      await threads.archive(t.threadId, !archived);
      // The open thread's header follows `known` (see ThreadView), so keep it in step.
      setKnown(k => ({ ...k, [t.threadId]: { ...(k[t.threadId] ?? t), archived: !archived } }));
      threads.refetch();
    } catch (e) {
      showMessage('Could not update conversation', apiErrorMessage(e, 'Please try again.'));
    }
  }

  async function archiveFromThread(threadId: number, value: boolean) {
    await setThreadArchived(threadId, value);
    setKnown(k => (k[threadId] ? { ...k, [threadId]: { ...k[threadId], archived: value } } : k));
    threads.patch(threadId, { archived: value });
    threads.refetch();
  }

  function closePane() {
    setPane({ kind: 'none' });
    if (admin) support.refetch();
    else threads.refetch();
  }

  function onCreated(t: Thread) {
    remember(t);
    setArchived(false);
    setPane({ kind: 'thread', id: t.threadId });
    threads.refetch();
  }

  function onLayout(e: LayoutChangeEvent) {
    const w = Math.round(e.nativeEvent.layout.width);
    if (w !== width) setWidth(w);
  }

  // Inbox / Archived tabs (Open / Closed in the support inbox): arrow keys (and Home/End) move between them,
  // one tab stop (roving tabindex).
  const tabs = admin ? ADMIN_TABS : MEMBER_TABS;
  const secondTab = admin ? showClosed : archived;
  const selectTab = admin ? setShowClosed : setArchived;
  const tabRefs = useRef<Record<string, any>>({});
  function onTabKey(e: any) {
    const key = e?.nativeEvent?.key ?? e?.key;
    let next: boolean | null = null;
    if (key === 'ArrowRight' || key === 'ArrowLeft') next = !secondTab;
    else if (key === 'Home') next = false;
    else if (key === 'End') next = true;
    if (next == null) return;
    e.preventDefault?.();
    selectTab(next);
    tabRefs.current[String(next)]?.focus?.();
  }

  const showList  = wide || pane.kind === 'none';
  const showRight = wide || pane.kind !== 'none';

  const list = (
    <View style={[styles.listPane, wide ? styles.listPaneWide : styles.flex]}>
      <View style={styles.listHeader}>
        <View style={styles.listTitleRow}>
          <View style={styles.flex}>
            <Text style={styles.pageTitle} accessibilityRole="header" {...ariaLevel(1)}>{admin ? 'Support messages' : 'Messages'}</Text>
            <Text style={styles.pageSub}>{SUBTITLES[mode]}</Text>
          </View>
          {/* Support conversations are opened by the person asking, from Help & Support: nothing to start here. */}
          {!admin && (
            <TouchableOpacity
              style={[styles.newBtn, { backgroundColor: theme.accent }]}
              onPress={() => setPane({ kind: 'new' })}
              activeOpacity={0.85}
              accessibilityRole="button"
              // Starts with the visible word "New" (WCAG 2.5.3).
              accessibilityLabel="New conversation"
            >
              <Ionicons aria-hidden name="create-outline" size={15} color={WHITE} importantForAccessibility="no" />
              <Text style={styles.newBtnText}>New</Text>
            </TouchableOpacity>
          )}
        </View>
        <View
          style={styles.tabs}
          accessibilityRole="tablist"
          aria-label={admin ? 'Support conversation status' : 'Conversation folders'}
          {...(Platform.OS === 'web' ? { onKeyDown: onTabKey } : {})}
        >
          {tabs.map(tab => {
            const on = secondTab === tab.key;
            return (
              <TouchableOpacity
                key={tab.label}
                ref={(r: any) => { tabRefs.current[String(tab.key)] = r; }}
                style={[styles.tab, on && { borderBottomColor: theme.accent }]}
                onPress={() => selectTab(tab.key)}
                {...({ tabIndex: on ? 0 : -1 } as object)}
                accessibilityRole="tab"
                accessibilityState={{ selected: on }}
                aria-selected={on}
                accessibilityLabel={`${tab.label} conversations`}
              >
                <Text style={[styles.tabText, on && { color: theme.accent, fontWeight: '700' }]}>{tab.label}</Text>
              </TouchableOpacity>
            );
          })}
        </View>
        {memberMode && <PatientFilter mode={memberMode} value={patient?.systemId ?? null} onChange={setPatient} />}
      </View>
      {memberMode ? (
        <ThreadList
          threads={threads.threads}
          loading={threads.loading}
          error={threads.error}
          hasMore={threads.hasMore}
          loadingMore={threads.loadingMore}
          archived={archived}
          mode={memberMode}
          mySystemId={mySystemId}
          selectedId={selectedId}
          onSelect={t => { remember(t); setPane({ kind: 'thread', id: t.threadId }); }}
          onRetry={threads.refetch}
          onLoadMore={threads.loadMore}
          onArchive={archiveFromList}
          onNew={archived ? undefined : () => setPane({ kind: 'new' })}
          filterName={patient?.name ?? null}
          emptyExtra={memberMode === 'CAREGIVER' && !archived ? <CaregiverMessagingStatus patientSystemId={patient?.systemId ?? null} /> : null}
        />
      ) : (
        <SupportThreadList
          threads={support.threads}
          loading={support.loading}
          error={support.error}
          denied={support.errorStatus === 403}
          hasMore={support.hasMore}
          loadingMore={support.loadingMore}
          closed={showClosed}
          mySystemId={mySystemId}
          selectedId={selectedId}
          closingId={closingId}
          onSelect={row => setPane({ kind: 'thread', id: row.threadId })}
          onRetry={support.refetch}
          onLoadMore={support.loadMore}
          onClose={closeFromList}
        />
      )}
    </View>
  );

  const right = pane.kind === 'thread' ? (
    <ThreadView
      key={pane.id}
      threadId={pane.id}
      initialThread={selectedThread}
      mySystemId={mySystemId}
      myRole={myRole}
      onBack={wide ? undefined : () => setPane({ kind: 'none' })}
      onThreadChanged={remember}
      onRead={onRead}
      onSent={onSent}
      onArchived={archiveFromThread}
      onClosed={closePane}
      onCloseConversation={admin ? closeConversation : undefined}
    />
  ) : pane.kind === 'new' && memberMode ? (
    <NewConversation
      mode={memberMode}
      mySystemId={mySystemId}
      onCancel={() => setPane({ kind: 'none' })}
      onCreated={onCreated}
      headingLevel={wide ? 2 : 1}
    />
  ) : (
    <View style={styles.placeholder}>
      <Empty
        icon="chatbubbles-outline"
        title="Select a conversation"
        sub={admin ? 'Choose a support conversation from the list to read and answer it.' : 'Choose a conversation from the list, or start a new one.'}
      />
    </View>
  );

  return (
    <ThemeContext.Provider value={theme}>
      <View style={styles.screen} onLayout={onLayout}>
        {!online && <Banner tone="warning" icon="cloud-offline-outline" text="You're offline. Messages will refresh when you reconnect." />}
        <View style={styles.panes}>
          {showList && list}
          {showRight && <View style={styles.rightPane}>{right}</View>}
        </View>
      </View>
    </ThemeContext.Provider>
  );
}

const styles = StyleSheet.create({
  screen:       { flex: 1, backgroundColor: BG, minWidth: 0 },
  panes:        { flex: 1, flexDirection: 'row', minHeight: 0 },
  flex:         { flex: 1 },
  listPane:     { backgroundColor: WHITE, minWidth: 0 },
  listPaneWide: { width: 340, borderRightWidth: 1, borderRightColor: BORDER },
  listHeader:   { paddingHorizontal: 16, paddingTop: 16, borderBottomWidth: 1, borderBottomColor: BORDER, gap: 10 },
  listTitleRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 10 },
  pageTitle:    { fontSize: 20, fontWeight: '700', color: TEXT },
  pageSub:      { fontSize: 12, color: GRAY, marginTop: 2 },
  newBtn:       { flexDirection: 'row', alignItems: 'center', gap: 5, borderRadius: 8, paddingHorizontal: 12, paddingVertical: 8 },
  newBtnText:   { color: WHITE, fontSize: 13, fontWeight: '600' },
  tabs:         { flexDirection: 'row', gap: 16 },
  tab:          { paddingVertical: 8, borderBottomWidth: 2, borderBottomColor: 'transparent' },
  tabText:      { fontSize: 13, color: GRAY, fontWeight: '500' },
  rightPane:    { flex: 1, minWidth: 0 },
  placeholder:  { flex: 1, backgroundColor: BG },
});
