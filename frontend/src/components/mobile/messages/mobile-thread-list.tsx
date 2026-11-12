import { Ionicons } from '@expo/vector-icons';
import type { ReactElement } from 'react';
import { ActivityIndicator, FlatList, RefreshControl, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import {
  Avatar, BORDER, CountBadge, Empty, ErrorBlock, GRAY, LIGHT, Loading, RED, RED_BG, TEXT, UrgentTag, WHITE,
  avatarColor, useTheme,
} from '@/components/web/messages/ui';
import { initials, shortTime, threadTitle } from '@/lib/communications/format';
import type { MessagingMode, Thread } from '@/lib/communications/types';

interface Props {
  threads:     Thread[];
  loading:     boolean;
  refreshing:  boolean;
  error:       string | null;
  hasMore:     boolean;
  loadingMore: boolean;
  archived:    boolean;
  mode:        MessagingMode;
  mySystemId:  string | null;
  onSelect:    (thread: Thread) => void;
  onRefresh:   () => void;
  onLoadMore:  () => void;
  onArchive:   (thread: Thread) => void;
  onNew?:      () => void;
  filterName?: string | null;
  /** Extra content for the empty inbox (a caregiver's messaging access per loved one). */
  emptyExtra?: ReactElement | null;
  /** Header rendered above the rows (tabs and filter), so the whole screen scrolls and pulls to refresh. */
  header:      ReactElement;
}

/** Conversation list for a phone: pull to refresh, load more, archive per row. Same copy as web. */
export function MobileThreadList({
  threads, loading, refreshing, error, hasMore, loadingMore, archived, mode, mySystemId,
  onSelect, onRefresh, onLoadMore, onArchive, onNew, filterName, emptyExtra, header,
}: Props) {
  const { accent } = useTheme();

  function empty() {
    if (loading) return <Loading label="Loading conversations" />;
    if (error) return <ErrorBlock message={error} onRetry={onRefresh} />;
    const about = filterName ? ` about ${filterName}` : '';
    return archived ? (
      <Empty icon="archive-outline" title={`No archived conversations${about}`} sub="Conversations you archive appear here. A new message moves a conversation back to your inbox." />
    ) : (
      <Empty icon="chatbubbles-outline" title={filterName ? `No conversations${about}` : 'No conversations yet'} sub="Start a conversation with your care team. Messages are saved here.">
        {emptyExtra}
        {onNew && (
          <TouchableOpacity
            style={[styles.newBtn, { backgroundColor: accent }]}
            onPress={onNew}
            activeOpacity={0.85}
            accessibilityRole="button"
            accessibilityLabel="Start a new conversation"
          >
            <Ionicons name="create-outline" size={16} color={WHITE} importantForAccessibility="no" />
            <Text style={styles.newBtnText}>New conversation</Text>
          </TouchableOpacity>
        )}
      </Empty>
    );
  }

  return (
    <FlatList
      style={styles.flex}
      contentContainerStyle={threads.length === 0 ? styles.emptyContent : styles.content}
      data={threads}
      keyExtractor={t => String(t.threadId)}
      ListHeaderComponent={
        <>
          {header}
          {error && threads.length > 0 ? <Text style={styles.inlineError} accessibilityRole="alert">{error}</Text> : null}
        </>
      }
      ListEmptyComponent={empty()}
      renderItem={({ item }) => (
        <ThreadRow
          thread={item}
          mode={mode}
          mySystemId={mySystemId}
          archived={archived}
          onPress={() => onSelect(item)}
          onArchive={() => onArchive(item)}
        />
      )}
      onEndReachedThreshold={0.4}
      onEndReached={() => { if (hasMore && !loadingMore) onLoadMore(); }}
      ListFooterComponent={loadingMore ? <ActivityIndicator style={styles.more} color={accent} accessibilityLabel="Loading more conversations" /> : null}
      refreshControl={<RefreshControl refreshing={refreshing && !loading} onRefresh={onRefresh} tintColor={accent} colors={[accent]} />}
      keyboardShouldPersistTaps="handled"
    />
  );
}

function ThreadRow({ thread, mode, mySystemId, archived, onPress, onArchive }: {
  thread: Thread; mode: MessagingMode; mySystemId: string | null; archived: boolean;
  onPress: () => void; onArchive: () => void;
}) {
  const { accent } = useTheme();
  const title   = threadTitle(thread, mySystemId);
  const unread  = thread.unreadCount > 0;
  const urgent  = thread.hasUrgentUnread;
  const last    = thread.lastMessage;
  const sender  = last ? (last.senderSystemId === mySystemId ? 'You' : last.senderLabel) : '';
  const typeLbl = thread.type === 'CARE_TEAM' ? 'Care team' : 'Private';
  const time    = shortTime(thread.lastMessageAt ?? thread.createdAt);
  const meta    = `${typeLbl}${mode !== 'PATIENT' ? ` · ${thread.patientName}` : ''}`;
  const preview = last ? `${sender}: ${last.preview}` : 'No messages yet';
  const a11y = [
    title, time, meta, urgent ? 'Urgent' : null, preview,
    unread ? `${thread.unreadCount > 99 ? '99+' : thread.unreadCount} unread` : null,
  ].filter(Boolean).join(', ');

  return (
    <View style={[styles.row, urgent && styles.rowUrgent]}>
      <TouchableOpacity
        style={styles.rowMain}
        onPress={onPress}
        activeOpacity={0.7}
        accessibilityRole="button"
        accessibilityLabel={a11y}
      >
        <Avatar
          text={thread.type === 'CARE_TEAM' ? 'CT' : initials(title)}
          color={thread.type === 'CARE_TEAM' ? accent : avatarColor(title)}
          size={42}
        />
        <View style={styles.rowBody}>
          <View style={styles.rowTop}>
            <Text style={[styles.title, unread && styles.titleUnread]} numberOfLines={1}>{title}</Text>
            <Text style={[styles.time, unread && { color: accent, fontWeight: '700' }]}>{time}</Text>
          </View>
          <View style={styles.metaRow}>
            <Text style={styles.meta} numberOfLines={1}>{meta}</Text>
            {urgent && <UrgentTag />}
          </View>
          <View style={styles.rowTop}>
            <Text style={[styles.preview, unread && styles.previewUnread]} numberOfLines={2}>{preview}</Text>
            <CountBadge count={thread.unreadCount} color={urgent ? RED : accent} label={`${thread.unreadCount} unread messages`} />
          </View>
        </View>
      </TouchableOpacity>
      <TouchableOpacity
        style={styles.archiveBtn}
        onPress={onArchive}
        activeOpacity={0.6}
        hitSlop={8}
        accessibilityRole="button"
        accessibilityLabel={archived ? `Move ${title} to inbox` : `Archive ${title}`}
      >
        <Ionicons name={archived ? 'arrow-undo-outline' : 'archive-outline'} size={19} color={LIGHT} importantForAccessibility="no" />
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  flex:         { flex: 1 },
  content:      { paddingBottom: 24 },
  emptyContent: { flexGrow: 1 },
  inlineError:  { color: RED, fontSize: 12, paddingHorizontal: 16, paddingVertical: 8 },
  row: {
    flexDirection: 'row', alignItems: 'center', backgroundColor: WHITE,
    borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: BORDER,
    borderLeftWidth: 3, borderLeftColor: 'transparent',
  },
  rowUrgent:    { borderLeftColor: RED, backgroundColor: RED_BG },
  rowMain:      { flex: 1, flexDirection: 'row', alignItems: 'flex-start', gap: 12, paddingVertical: 12, paddingLeft: 13, minWidth: 0 },
  rowBody:      { flex: 1, minWidth: 0, gap: 2 },
  rowTop:       { flexDirection: 'row', alignItems: 'center', gap: 8 },
  metaRow:      { flexDirection: 'row', alignItems: 'center', gap: 6 },
  title:        { flex: 1, fontSize: 15, color: TEXT, fontWeight: '500' },
  titleUnread:  { fontWeight: '700' },
  time:         { fontSize: 12, color: LIGHT },
  meta:         { fontSize: 12, color: GRAY, flexShrink: 1 },
  preview:      { flex: 1, fontSize: 13, color: GRAY, lineHeight: 18 },
  previewUnread:{ color: TEXT, fontWeight: '500' },
  archiveBtn:   { paddingHorizontal: 14, paddingVertical: 16, alignSelf: 'stretch', justifyContent: 'center' },
  more:         { paddingVertical: 16 },
  newBtn:       { flexDirection: 'row', alignItems: 'center', gap: 6, borderRadius: 10, paddingHorizontal: 16, paddingVertical: 11, marginTop: 6 },
  newBtnText:   { color: WHITE, fontSize: 14, fontWeight: '600' },
});
