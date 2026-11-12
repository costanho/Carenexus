import { Ionicons } from '@expo/vector-icons';
import type { ReactNode } from 'react';
import { ScrollView, StyleSheet, Text, TouchableOpacity, TouchableWithoutFeedback, View } from 'react-native';
import { initials, shortTime, threadTitle } from '@/lib/communications/format';
import type { MessagingMode, Thread } from '@/lib/communications/types';
import {
  Avatar, BORDER, CountBadge, Empty, ErrorBlock, GRAY, LIGHT, Loading, RED, RED_BG, TEXT, UrgentTag, WHITE,
  avatarColor, useTheme,
} from './ui';

interface Props {
  threads:      Thread[];
  loading:      boolean;
  error:        string | null;
  hasMore:      boolean;
  loadingMore:  boolean;
  archived:     boolean;
  mode:         MessagingMode;
  mySystemId:   string | null;
  selectedId:   number | null;
  onSelect:     (thread: Thread) => void;
  onRetry:      () => void;
  onLoadMore:   () => void;
  onArchive:    (thread: Thread) => void;
  onNew?:       () => void;
  /** Name of the patient the list is filtered to, if any (empty-state wording). */
  filterName?:  string | null;
  /** Extra content for the empty inbox (e.g. a caregiver's messaging access per loved one). */
  emptyExtra?:  ReactNode;
}

export function ThreadList({
  threads, loading, error, hasMore, loadingMore, archived, mode, mySystemId, selectedId,
  onSelect, onRetry, onLoadMore, onArchive, onNew, filterName, emptyExtra,
}: Props) {
  const { accent } = useTheme();

  if (loading && threads.length === 0) return <Loading label="Loading conversations" />;
  if (error && threads.length === 0) return <ErrorBlock message={error} onRetry={onRetry} />;
  if (threads.length === 0) {
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
            <Ionicons name="create-outline" size={15} color={WHITE} importantForAccessibility="no" />
            <Text style={styles.newBtnText}>New conversation</Text>
          </TouchableOpacity>
        )}
      </Empty>
    );
  }

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.content}>
      {error ? <Text style={styles.inlineError} accessibilityRole="alert">{error}</Text> : null}
      <View accessibilityRole="list" accessibilityLabel={archived ? 'Archived conversations' : 'Conversations'}>
        {threads.map(t => (
          <ThreadRow
            key={t.threadId}
            thread={t}
            mode={mode}
            mySystemId={mySystemId}
            selected={t.threadId === selectedId}
            archived={archived}
            onPress={() => onSelect(t)}
            onArchive={() => onArchive(t)}
          />
        ))}
      </View>
      {hasMore && (
        <TouchableOpacity
          style={styles.moreBtn}
          onPress={onLoadMore}
          disabled={loadingMore}
          accessibilityRole="button"
          accessibilityLabel="Load more conversations"
          accessibilityState={{ busy: loadingMore, disabled: loadingMore }}
          aria-busy={loadingMore}
        >
          <Text style={[styles.moreText, { color: accent }]}>{loadingMore ? 'Loading…' : 'Load more'}</Text>
        </TouchableOpacity>
      )}
    </ScrollView>
  );
}

function ThreadRow({ thread, mode, mySystemId, selected, archived, onPress, onArchive }: {
  thread: Thread; mode: MessagingMode; mySystemId: string | null; selected: boolean; archived: boolean;
  onPress: () => void; onArchive: () => void;
}) {
  const { accent, accentBg } = useTheme();
  const title   = threadTitle(thread, mySystemId);
  const unread  = thread.unreadCount > 0;
  const urgent  = thread.hasUrgentUnread;
  const last    = thread.lastMessage;
  const sender  = last ? (last.senderSystemId === mySystemId ? 'You' : last.senderLabel) : '';
  const typeLbl = thread.type === 'CARE_TEAM' ? 'Care team' : 'Private';
  const showPatient = mode !== 'PATIENT';

  const time    = shortTime(thread.lastMessageAt ?? thread.createdAt);
  const meta    = `${typeLbl}${showPatient ? ` · ${thread.patientName}` : ''}`;
  const preview = last ? `${sender}: ${last.preview}` : 'No messages yet';
  // Same order as the visible text, so the visible label is part of the accessible name
  // (WCAG 2.5.3); the unread count is spelled out at the end.
  const a11y = [
    title,
    time,
    meta,
    urgent ? 'Urgent' : null,
    preview,
    unread ? `${thread.unreadCount > 99 ? '99+' : thread.unreadCount} unread` : null,
  ].filter(Boolean).join(', ');

  return (
    <View
      role="listitem"
      style={[
        styles.row,
        selected && { backgroundColor: accentBg },
        urgent && styles.rowUrgent,
      ]}
    >
      {/* The avatar sits outside the button so its initials are not part of the button's visible label. */}
      <TouchableWithoutFeedback onPress={onPress} accessible={false} focusable={false}>
        <View style={styles.avatarCell}>
          <Avatar text={thread.type === 'CARE_TEAM' ? 'CT' : initials(title)} color={thread.type === 'CARE_TEAM' ? accent : avatarColor(title)} />
        </View>
      </TouchableWithoutFeedback>
      <TouchableOpacity
        style={styles.rowMain}
        onPress={onPress}
        activeOpacity={0.75}
        accessibilityRole="button"
        accessibilityLabel={a11y}
        accessibilityState={{ selected }}
      >
        <View style={styles.rowBody}>
          <View style={styles.rowTop}>
            {/* Trailing spaces keep words apart when the row's text nodes are read as one string. */}
            <Text style={[styles.title, unread && styles.titleUnread]} numberOfLines={1}>{title}{' '}</Text>
            <Text style={styles.time}>{time}{' '}</Text>
          </View>
          <View style={styles.metaRow}>
            <Text style={styles.meta} numberOfLines={1}>{meta}{' '}</Text>
            {urgent && <UrgentTag />}
          </View>
          <View style={styles.rowTop}>
            <Text style={[styles.preview, unread && styles.previewUnread]} numberOfLines={1}>{preview}{' '}</Text>
            <CountBadge count={thread.unreadCount} color={urgent ? RED : accent} label={`${thread.unreadCount} unread messages`} />
          </View>
        </View>
      </TouchableOpacity>
      <TouchableOpacity
        style={styles.archiveBtn}
        onPress={onArchive}
        activeOpacity={0.7}
        accessibilityRole="button"
        accessibilityLabel={archived ? `Move ${title} to inbox` : `Archive ${title}`}
      >
        <Ionicons name={archived ? 'arrow-undo-outline' : 'archive-outline'} size={16} color={LIGHT} importantForAccessibility="no" />
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  scroll:   { flex: 1 },
  content:  { paddingBottom: 24 },
  inlineError: { color: RED, fontSize: 12, paddingHorizontal: 16, paddingVertical: 8 },
  row: {
    flexDirection: 'row', alignItems: 'center',
    borderBottomWidth: 1, borderBottomColor: BORDER,
    borderLeftWidth: 3, borderLeftColor: 'transparent',
  },
  rowUrgent: { borderLeftColor: RED, backgroundColor: RED_BG },
  avatarCell:{ alignSelf: 'flex-start', paddingVertical: 12, paddingLeft: 13 },
  rowMain:   { flex: 1, flexDirection: 'row', gap: 12, paddingVertical: 12, paddingLeft: 12, paddingRight: 4, minWidth: 0 },
  rowBody:   { flex: 1, minWidth: 0, gap: 2 },
  rowTop:    { flexDirection: 'row', alignItems: 'center', gap: 8 },
  metaRow:   { flexDirection: 'row', alignItems: 'center', gap: 6 },
  title:     { flex: 1, fontSize: 14, color: TEXT, fontWeight: '500' },
  titleUnread: { fontWeight: '700' },
  time:      { fontSize: 11, color: LIGHT },
  meta:      { fontSize: 11, color: GRAY, flexShrink: 1 },
  preview:   { flex: 1, fontSize: 13, color: GRAY },
  previewUnread: { color: TEXT, fontWeight: '500' },
  archiveBtn: { padding: 12 },
  moreBtn:   { alignItems: 'center', paddingVertical: 14 },
  moreText:  { fontSize: 13, fontWeight: '600' },
  newBtn:    { flexDirection: 'row', alignItems: 'center', gap: 6, borderRadius: 8, paddingHorizontal: 14, paddingVertical: 9, marginTop: 6 },
  newBtnText:{ color: WHITE, fontSize: 13, fontWeight: '600' },
});
