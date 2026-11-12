import { Ionicons } from '@expo/vector-icons';
import { useCallback, useEffect, useState } from 'react';
import { BackHandler, KeyboardAvoidingView, Platform, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { CaregiverMessagingStatus } from '@/components/web/messages/caregiver-messaging-status';
import { NewConversation } from '@/components/web/messages/new-conversation';
import { type PatientOption, PatientFilter } from '@/components/web/messages/patient-filter';
import { BG, BORDER, DEFAULT_THEME, GRAY, type MessagesTheme, TEXT, ThemeContext, WHITE } from '@/components/web/messages/ui';
import { apiErrorMessage } from '@/hooks/use-appointments';
import { useCurrentUser } from '@/hooks/use-current-user';
import { refreshUnreadCounts } from '@/hooks/use-notifications';
import { useThreads } from '@/hooks/use-threads';
import { setThreadArchived } from '@/lib/communications/api';
import { clearAllDrafts } from '@/lib/communications/drafts';
import { clearAllOutboxes } from '@/lib/communications/outbox';
import type { Message, MessagingMode, Thread } from '@/lib/communications/types';
import { showMessage } from '@/lib/ui/dialogs';
import { MobileThreadList } from './mobile-thread-list';
import { MobileThreadView } from './mobile-thread-view';

export interface MobileMessagesScreenProps {
  mode:   MessagingMode;
  theme?: MessagesTheme;
  /** Open this thread (from a notification or a Message button). Cleared through `onOpenThreadHandled`. */
  openThreadId?:        number | null;
  onOpenThreadHandled?: () => void;
}

const SUBTITLES: Record<MessagingMode, string> = {
  PATIENT:   'Message your doctors and care team.',
  DOCTOR:    'Conversations with your patients and their caregivers.',
  CAREGIVER: "Message your loved ones' care teams.",
};

type Pane = { kind: 'list' } | { kind: 'thread'; id: number } | { kind: 'new' };

/** Messages for patient, doctor and caregiver on iOS / Android: inbox, then one screen per conversation. */
export function MobileMessagesScreen({ mode, theme = DEFAULT_THEME, openThreadId, onOpenThreadHandled }: MobileMessagesScreenProps) {
  const { user } = useCurrentUser();
  const insets = useSafeAreaInsets();
  const [archived, setArchived] = useState(false);
  const [pane,     setPane]     = useState<Pane>({ kind: 'list' });
  const [known,    setKnown]    = useState<Record<number, Thread>>({});
  const [patient,  setPatient]  = useState<PatientOption | null>(null);
  const [pulling,  setPulling]  = useState(false);

  const threads = useThreads({ archived, patientSystemId: patient?.systemId ?? null });
  const mySystemId = user?.system_id ?? null;
  const myRole = user?.role ?? mode;

  // Unsent text of anyone else who used this device is never shown and need not be kept.
  useEffect(() => {
    if (!mySystemId) return;
    clearAllDrafts(mySystemId);
    clearAllOutboxes(mySystemId);
  }, [mySystemId]);

  useEffect(() => {
    if (openThreadId == null) return;
    setArchived(false);
    setPatient(null);
    setPane({ kind: 'thread', id: openThreadId });
    onOpenThreadHandled?.();
  }, [openThreadId, onOpenThreadHandled]);

  // Android back: a conversation or the new-conversation form goes back to the list.
  useEffect(() => {
    if (pane.kind === 'list') return;
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      setPane({ kind: 'list' });
      threads.refetch();
      return true;
    });
    return () => sub.remove();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pane.kind]);

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

  const onSent = useCallback((threadId: number, m: Message) => {
    threads.bump(threadId, {
      lastMessage: {
        messageId: m.messageId, senderSystemId: m.senderSystemId, senderLabel: m.senderLabel,
        preview: Array.from(m.body).slice(0, 120).join(''), urgent: m.urgent, sentAt: m.sentAt,
      },
      lastMessageAt: m.sentAt,
    });
    threads.refetch();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [threads.bump, threads.refetch]);

  async function refresh() {
    setPulling(true);
    try {
      await Promise.all([threads.refetch(), refreshUnreadCounts()]);
    } finally {
      setPulling(false);
    }
  }

  async function archiveFromList(t: Thread) {
    try {
      await threads.archive(t.threadId, !archived);
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

  function backToList() {
    setPane({ kind: 'list' });
    threads.refetch();
  }

  function onCreated(t: Thread) {
    remember(t);
    setArchived(false);
    setPane({ kind: 'thread', id: t.threadId });
    threads.refetch();
  }

  let content;
  if (pane.kind === 'thread') {
    const selected = threads.threads.find(t => t.threadId === pane.id) ?? known[pane.id] ?? null;
    content = (
      <MobileThreadView
        key={pane.id}
        threadId={pane.id}
        initialThread={selected}
        mySystemId={mySystemId}
        myRole={myRole}
        onBack={backToList}
        onThreadChanged={remember}
        onRead={onRead}
        onSent={onSent}
        onArchived={archiveFromThread}
        onClosed={backToList}
      />
    );
  } else if (pane.kind === 'new') {
    content = (
      <KeyboardAvoidingView
        style={[styles.flex, { paddingTop: insets.top, backgroundColor: WHITE }]}
        behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      >
        <NewConversation mode={mode} mySystemId={mySystemId} onCancel={() => setPane({ kind: 'list' })} onCreated={onCreated} />
      </KeyboardAvoidingView>
    );
  } else {
    const header = (
      <View style={[styles.listHeader, { paddingTop: insets.top + 12 }]}>
        <View style={styles.titleRow}>
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
            <Ionicons name="create-outline" size={16} color={WHITE} importantForAccessibility="no" />
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
                accessibilityLabel={`${tab.label} conversations`}
              >
                <Text style={[styles.tabText, on && { color: theme.accent, fontWeight: '700' }]}>{tab.label}</Text>
              </TouchableOpacity>
            );
          })}
        </View>
        <PatientFilter mode={mode} value={patient?.systemId ?? null} onChange={setPatient} />
      </View>
    );
    content = (
      <MobileThreadList
        header={header}
        threads={threads.threads}
        loading={threads.loading}
        refreshing={pulling}
        error={threads.error}
        hasMore={threads.hasMore}
        loadingMore={threads.loadingMore}
        archived={archived}
        mode={mode}
        mySystemId={mySystemId}
        onSelect={t => { remember(t); setPane({ kind: 'thread', id: t.threadId }); }}
        onRefresh={refresh}
        onLoadMore={threads.loadMore}
        onArchive={archiveFromList}
        onNew={archived ? undefined : () => setPane({ kind: 'new' })}
        filterName={patient?.name ?? null}
        emptyExtra={mode === 'CAREGIVER' && !archived ? <CaregiverMessagingStatus patientSystemId={patient?.systemId ?? null} /> : null}
      />
    );
  }

  return (
    <ThemeContext.Provider value={theme}>
      <View style={styles.screen}>{content}</View>
    </ThemeContext.Provider>
  );
}

const styles = StyleSheet.create({
  screen:     { flex: 1, backgroundColor: BG },
  flex:       { flex: 1 },
  listHeader: { paddingHorizontal: 16, backgroundColor: WHITE, borderBottomWidth: 1, borderBottomColor: BORDER, gap: 10, paddingBottom: 8 },
  titleRow:   { flexDirection: 'row', alignItems: 'flex-start', gap: 10 },
  pageTitle:  { fontSize: 24, fontWeight: '700', color: TEXT },
  pageSub:    { fontSize: 13, color: GRAY, marginTop: 2 },
  newBtn:     { flexDirection: 'row', alignItems: 'center', gap: 5, borderRadius: 10, paddingHorizontal: 14, paddingVertical: 9, marginTop: 4 },
  newBtnText: { color: WHITE, fontSize: 14, fontWeight: '600' },
  tabs:       { flexDirection: 'row', gap: 20 },
  tab:        { paddingVertical: 8, borderBottomWidth: 2, borderBottomColor: 'transparent' },
  tabText:    { fontSize: 14, color: GRAY, fontWeight: '500' },
});
