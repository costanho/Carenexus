import { Ionicons } from '@expo/vector-icons';
import { useCallback, useEffect, useState } from 'react';
import { type LayoutChangeEvent, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { apiErrorMessage } from '@/hooks/use-appointments';
import { useCurrentUser } from '@/hooks/use-current-user';
import { refreshUnreadCounts } from '@/hooks/use-notifications';
import { usePageActive } from '@/hooks/use-page-active';
import { useThreads } from '@/hooks/use-threads';
import { setThreadArchived } from '@/lib/communications/api';
import type { MessagingMode, Thread } from '@/lib/communications/types';
import { showMessage } from '@/lib/ui/dialogs';
import { NewConversation } from './new-conversation';
import { ThreadList } from './thread-list';
import { ThreadView } from './thread-view';
import { BG, BORDER, Banner, DEFAULT_THEME, Empty, GRAY, type MessagesTheme, TEXT, ThemeContext, WHITE } from './ui';

export interface MessagesScreenProps {
  mode:   MessagingMode;
  theme?: MessagesTheme;
  /** Open this thread (e.g. from a notification). Cleared through `onOpenThreadHandled`. */
  openThreadId?:        number | null;
  onOpenThreadHandled?: () => void;
}

const WIDE_MIN = 860;

const SUBTITLES: Record<MessagingMode, string> = {
  PATIENT:   'Message your doctors and care team.',
  DOCTOR:    'Conversations with your patients and their caregivers.',
  CAREGIVER: "Message your loved ones' care teams.",
};

type Pane = { kind: 'none' } | { kind: 'thread'; id: number } | { kind: 'new' };

/** Messages experience shared by patient, doctor and caregiver dashboards (web). */
export function MessagesScreen({ mode, theme = DEFAULT_THEME, openThreadId, onOpenThreadHandled }: MessagesScreenProps) {
  const { user } = useCurrentUser();
  const { online } = usePageActive();
  const [width,    setWidth]    = useState(0);
  const [archived, setArchived] = useState(false);
  const [pane,     setPane]     = useState<Pane>({ kind: 'none' });
  const [known,    setKnown]    = useState<Record<number, Thread>>({});

  const threads = useThreads({ archived });
  const wide = width === 0 || width >= WIDE_MIN;
  const mySystemId = user?.system_id ?? null;
  const myRole = user?.role ?? mode;

  // Open a thread requested by the shell (notification click).
  useEffect(() => {
    if (openThreadId == null) return;
    setArchived(false);
    setPane({ kind: 'thread', id: openThreadId });
    onOpenThreadHandled?.();
  }, [openThreadId, onOpenThreadHandled]);

  const selectedId = pane.kind === 'thread' ? pane.id : null;
  const selectedThread = selectedId != null
    ? (threads.threads.find(t => t.threadId === selectedId) ?? known[selectedId] ?? null)
    : null;

  const remember = useCallback((t: Thread) => {
    setKnown(k => ({ ...k, [t.threadId]: t }));
    threads.patch(t.threadId, t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [threads.patch]);

  const onRead = useCallback((threadId: number, unreadCount: number) => {
    threads.patch(threadId, unreadCount === 0 ? { unreadCount: 0, hasUrgentUnread: false } : { unreadCount });
    refreshUnreadCounts();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [threads.patch]);

  async function archiveFromList(t: Thread) {
    try {
      await threads.archive(t.threadId, !archived);
      if (selectedId === t.threadId) setKnown(k => ({ ...k, [t.threadId]: { ...t, archived: !archived } }));
    } catch (e) {
      showMessage('Could not update conversation', apiErrorMessage(e, 'Please try again.'));
    }
  }

  async function archiveFromThread(threadId: number, value: boolean) {
    await setThreadArchived(threadId, value);
    threads.refetch();
  }

  function closePane() {
    setPane({ kind: 'none' });
    threads.refetch();
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

  const showList  = wide || pane.kind === 'none';
  const showRight = wide || pane.kind !== 'none';

  const list = (
    <View style={[styles.listPane, wide ? styles.listPaneWide : styles.flex]}>
      <View style={styles.listHeader}>
        <View style={styles.listTitleRow}>
          <View style={styles.flex}>
            <Text style={styles.pageTitle} accessibilityRole="header">Messages</Text>
            <Text style={styles.pageSub}>{SUBTITLES[mode]}</Text>
          </View>
          <TouchableOpacity
            style={[styles.newBtn, { backgroundColor: theme.accent }]}
            onPress={() => setPane({ kind: 'new' })}
            activeOpacity={0.85}
            accessibilityRole="button"
            accessibilityLabel="Start a new conversation"
          >
            <Ionicons name="create-outline" size={15} color={WHITE} importantForAccessibility="no" />
            <Text style={styles.newBtnText}>New</Text>
          </TouchableOpacity>
        </View>
        <View style={styles.tabs} accessibilityRole="tablist">
          {[{ key: false, label: 'Inbox' }, { key: true, label: 'Archived' }].map(tab => {
            const on = archived === tab.key;
            return (
              <TouchableOpacity
                key={tab.label}
                style={[styles.tab, on && { borderBottomColor: theme.accent }]}
                onPress={() => setArchived(tab.key)}
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
      </View>
      <ThreadList
        threads={threads.threads}
        loading={threads.loading}
        error={threads.error}
        hasMore={threads.hasMore}
        loadingMore={threads.loadingMore}
        archived={archived}
        mode={mode}
        mySystemId={mySystemId}
        selectedId={selectedId}
        onSelect={t => { remember(t); setPane({ kind: 'thread', id: t.threadId }); }}
        onRetry={threads.refetch}
        onLoadMore={threads.loadMore}
        onArchive={archiveFromList}
        onNew={archived ? undefined : () => setPane({ kind: 'new' })}
      />
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
      onArchived={archiveFromThread}
      onClosed={closePane}
    />
  ) : pane.kind === 'new' ? (
    <NewConversation mode={mode} mySystemId={mySystemId} onCancel={() => setPane({ kind: 'none' })} onCreated={onCreated} />
  ) : (
    <View style={styles.placeholder}>
      <Empty icon="chatbubbles-outline" title="Select a conversation" sub="Choose a conversation from the list, or start a new one." />
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
