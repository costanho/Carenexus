import { Ionicons } from '@expo/vector-icons';
import { ScrollView, StyleSheet, Text, TouchableOpacity, TouchableWithoutFeedback, View } from 'react-native';
import { roleLabel, shortTime } from '@/lib/communications/format';
import { supportRowTitle } from '@/lib/support/format';
import type { SupportThread } from '@/lib/support/types';
import { Avatar, BORDER, Empty, ErrorBlock, GRAY, LIGHT, Loading, RED, TEXT, useTheme } from './ui';

interface Props {
  threads:     SupportThread[];
  loading:     boolean;
  error:       string | null;
  /** The list cannot be read at all (the user is not an administrator): no Retry is offered. */
  denied?:     boolean;
  hasMore:     boolean;
  loadingMore: boolean;
  /** The closed conversations are listed (empty-state wording, no Close action). */
  closed:      boolean;
  mySystemId:  string | null;
  selectedId:  number | null;
  /** Id of the conversation whose Close request is running, if any. */
  closingId?:  number | null;
  onSelect:    (thread: SupportThread) => void;
  onRetry:     () => void;
  onLoadMore:  () => void;
  onClose:     (thread: SupportThread) => void;
}

/** Administrator inbox: the support conversations people opened from Help & Support. */
export function SupportThreadList({
  threads, loading, error, denied, hasMore, loadingMore, closed, mySystemId, selectedId, closingId,
  onSelect, onRetry, onLoadMore, onClose,
}: Props) {
  const { accent } = useTheme();

  if (loading && threads.length === 0) return <Loading label="Loading support conversations" />;
  if (error && threads.length === 0) return <ErrorBlock message={error} onRetry={denied ? undefined : onRetry} />;
  if (threads.length === 0) {
    return closed ? (
      <Empty icon="checkmark-done-outline" title="No closed support conversations" sub="Conversations you or another administrator close are kept here." />
    ) : (
      <Empty
        icon="help-buoy-outline"
        title="No open support conversations"
        sub="When someone writes to support from Help & Support, the conversation appears here."
      />
    );
  }

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.content}>
      {error ? <Text style={styles.inlineError} accessibilityRole="alert">{error}</Text> : null}
      <View accessibilityRole="list" accessibilityLabel={closed ? 'Closed support conversations' : 'Open support conversations'}>
        {threads.map(t => (
          <SupportRow
            key={t.threadId}
            thread={t}
            mySystemId={mySystemId}
            selected={t.threadId === selectedId}
            closing={closingId === t.threadId}
            onPress={() => onSelect(t)}
            onClose={() => onClose(t)}
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

function SupportRow({ thread, mySystemId, selected, closing, onPress, onClose }: {
  thread: SupportThread; mySystemId: string | null; selected: boolean; closing: boolean;
  onPress: () => void; onClose: () => void;
}) {
  const { accent, accentBg } = useTheme();
  const title   = supportRowTitle(thread);
  const opener  = thread.openerName?.trim() || thread.openerSystemId;
  const last    = thread.lastMessage;
  const sender  = last ? (last.senderSystemId === mySystemId ? 'You' : last.senderLabel) : '';
  const time    = shortTime(thread.lastMessageAt ?? thread.createdAt);
  // With a subject as the title, the second line says who is asking; otherwise the title already does.
  const meta    = [
    thread.subject?.trim() ? opener : null,
    roleLabel(thread.openerRole),
    thread.openerSystemId,
    thread.open ? null : 'Closed',
  ].filter(Boolean).join(' · ');
  const preview = last ? `${sender}: ${last.preview}` : 'No messages yet';
  // Same order as the visible text, so the visible label is part of the accessible name (WCAG 2.5.3).
  const a11y = [title, time, meta, preview, thread.unread ? 'Unread' : null].filter(Boolean).join(', ');

  return (
    <View role="listitem" style={[styles.row, selected && { backgroundColor: accentBg }]}>
      {/* The avatar sits outside the button so it is not part of the button's visible label. */}
      <TouchableWithoutFeedback onPress={onPress} accessible={false} focusable={false}>
        <View style={styles.avatarCell}>
          <Avatar text="" icon="help-buoy-outline" color={accent} />
        </View>
      </TouchableWithoutFeedback>
      <TouchableOpacity
        style={styles.rowMain}
        onPress={onPress}
        activeOpacity={0.75}
        accessibilityRole="button"
        accessibilityLabel={a11y}
        accessibilityState={{ selected }}
        // react-native-web drops accessibilityState.selected; the open conversation is marked as current.
        {...(selected ? ({ 'aria-current': 'true' } as object) : null)}
      >
        <View style={styles.rowBody}>
          <View style={styles.rowTop}>
            {/* Trailing spaces keep words apart when the row's text nodes are read as one string. */}
            <Text style={[styles.title, thread.unread && styles.titleUnread]} numberOfLines={1}>{title}{' '}</Text>
            <Text style={styles.time}>{time}{' '}</Text>
          </View>
          <Text style={styles.meta} numberOfLines={1}>{meta}{' '}</Text>
          <View style={styles.rowTop}>
            <Text style={[styles.preview, thread.unread && styles.previewUnread]} numberOfLines={1}>{preview}{' '}</Text>
            {thread.unread && (
              <View style={styles.unreadTag}>
                <View style={[styles.unreadDot, { backgroundColor: accent }]} />
                <Text style={[styles.unreadText, { color: accent }]}>Unread</Text>
              </View>
            )}
          </View>
        </View>
      </TouchableOpacity>
      {thread.open && (
        <TouchableOpacity
          style={styles.closeBtn}
          onPress={onClose}
          disabled={closing}
          activeOpacity={0.7}
          accessibilityRole="button"
          accessibilityLabel={`Close conversation ${title}`}
          accessibilityState={{ busy: closing, disabled: closing }}
          aria-busy={closing}
        >
          <Ionicons aria-hidden name="checkmark-done-outline" size={17} color={closing ? BORDER : LIGHT} importantForAccessibility="no" />
        </TouchableOpacity>
      )}
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
  avatarCell:{ alignSelf: 'flex-start', paddingVertical: 12, paddingLeft: 13 },
  rowMain:   { flex: 1, flexDirection: 'row', gap: 12, paddingVertical: 12, paddingLeft: 12, paddingRight: 4, minWidth: 0 },
  rowBody:   { flex: 1, minWidth: 0, gap: 2 },
  rowTop:    { flexDirection: 'row', alignItems: 'center', gap: 8 },
  title:     { flex: 1, fontSize: 14, color: TEXT, fontWeight: '500' },
  titleUnread: { fontWeight: '700' },
  time:      { fontSize: 11, color: LIGHT },
  meta:      { fontSize: 11, color: GRAY },
  preview:   { flex: 1, fontSize: 13, color: GRAY },
  previewUnread: { color: TEXT, fontWeight: '500' },
  unreadTag: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  unreadDot: { width: 8, height: 8, borderRadius: 4 },
  unreadText:{ fontSize: 11, fontWeight: '700' },
  closeBtn:  { padding: 12 },
  moreBtn:   { alignItems: 'center', paddingVertical: 14 },
  moreText:  { fontSize: 13, fontWeight: '600' },
});
