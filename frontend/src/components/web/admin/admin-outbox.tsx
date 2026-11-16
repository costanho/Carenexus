import { useState } from 'react';
import { Linking, StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Banner, Button, Card, colors, EmptyView, fontSize, InfoRow, PagedList, radius, Screen, SelectField,
  space, StatusBadge, statusTone, STATUS_TONES, TextField, type IoniconsName, type SelectOption,
} from '@/components/web/ui';
import { useDebouncedValue } from '@/hooks/use-admin-ops-debounced';
import { useAdminOutbox } from '@/hooks/use-admin-ops-outbox';
import { useSectionNav } from '@/hooks/use-section-nav';
import {
  DELIVERY_CHANNEL_LABEL, DELIVERY_CHANNELS, firstLink, formatInstant, isDeliveryChannel, OUTBOUND_STATUS_LABEL,
  OUTBOX_TEMPLATE_MAX, OUTBOX_TO_MAX, type DeliveryChannel, type OutboundMessage,
} from '@/lib/admin/ops-types';
import { ROLE_THEME, type SectionProps } from '@/lib/nav/sections';

// Admin section `outbox` (development installations only): the email, SMS and push messages the server queued,
// newest first, so a password-reset or verification link can be opened locally. The endpoint answers 404 on
// every other installation; the admin registry then does not list the section, and this screen says so if it
// is opened by URL. Nothing here can be edited, resent or deleted.

type ChannelChoice = DeliveryChannel | 'ALL';

const CHANNEL_OPTIONS: readonly SelectOption<ChannelChoice>[] = [
  { value: 'ALL', label: 'All channels' },
  ...DELIVERY_CHANNELS.map(channel => ({ value: channel, label: DELIVERY_CHANNEL_LABEL[channel] })),
];

const CHANNEL_ICON: Record<DeliveryChannel, IoniconsName> = {
  EMAIL: 'mail-outline',
  SMS:   'chatbox-outline',
  PUSH:  'notifications-outline',
};

function MessageRow({ message }: { message: OutboundMessage }) {
  const [open, setOpen] = useState(false);
  const link = firstLink(message.body);
  const channelLabel = DELIVERY_CHANNEL_LABEL[message.channel] ?? message.channel;
  const title = message.subject?.trim() || message.templateId || `${channelLabel} message`;
  const recipient = [message.recipientAddress, message.recipientSystemId].filter(Boolean).join(' · ') || 'No recipient';
  const data = message.data ? Object.entries(message.data) : [];

  return (
    <Card style={styles.row}>
      <View style={styles.rowHead}>
        <View style={styles.rowTitles}>
          <Text style={styles.rowTitle} accessibilityRole="header" {...ariaLevel(2)}>{title}</Text>
          <Text style={styles.rowSub} selectable>{`To ${recipient}`}</Text>
        </View>
        <View style={styles.badges}>
          <StatusBadge label={channelLabel} tone="neutral" icon={CHANNEL_ICON[message.channel] ?? 'paper-plane-outline'} />
          <StatusBadge
            label={OUTBOUND_STATUS_LABEL[message.status] ?? message.status}
            tone={statusTone('delivery', message.status)}
          />
        </View>
      </View>

      <Text style={styles.meta}>
        {[
          `Queued ${formatInstant(message.createdAt, 'at an unknown time')}`,
          message.templateId ? `Template ${message.templateId}` : null,
          `Attempt ${message.attempts} of ${message.maxAttempts}`,
          message.provider ? `Provider ${message.provider}` : null,
        ].filter(Boolean).join(' · ')}
      </Text>
      {message.lastError ? <Text style={styles.error}>{`Last error: ${message.lastError}`}</Text> : null}

      {link ? (
        <View style={styles.linkRow}>
          <Text style={styles.link} selectable>{link}</Text>
          <Button
            label="Open link"
            icon="open-outline"
            size="sm"
            onPress={() => { void Linking.openURL(link); }}
            accessibilityLabel={`Open link from the message to ${recipient}`}
          />
        </View>
      ) : null}

      <View style={styles.rowActions}>
        <Button
          label={open ? 'Hide message' : 'Show message'}
          icon={open ? 'chevron-up' : 'chevron-down'}
          variant="secondary"
          size="sm"
          onPress={() => setOpen(o => !o)}
          accessibilityLabel={`${open ? 'Hide message' : 'Show message'} ${message.messageId}: ${title}`}
        />
      </View>

      {open ? (
        <View style={styles.detail}>
          <View style={styles.body}>
            <Text style={styles.bodyText} selectable>{message.body}</Text>
          </View>
          <View>
            <InfoRow label="Message ID" value={message.messageId} />
            {data.map(([name, value]) => <InfoRow key={name} label={`Data: ${name}`} value={value} />)}
            <InfoRow label="Sent or skipped" value={formatInstant(message.sentAt)} emptyText="Not yet" />
            {message.status === 'PENDING' ? (
              <InfoRow label="Next attempt" value={formatInstant(message.nextAttemptAt)} emptyText="Unknown" />
            ) : null}
            <InfoRow label="Provider message ID" value={message.providerMessageId} emptyText="None" />
            <InfoRow label="Notification ID" value={message.notificationId} emptyText="None (account message)" />
            <InfoRow label="Dedupe key" value={message.dedupeKey} emptyText="None" />
          </View>
        </View>
      ) : null}
    </Card>
  );
}

export default function AdminOutbox({ theme, params }: Partial<SectionProps>) {
  const nav = useSectionNav();
  const look = theme ?? ROLE_THEME.admin;
  const urlParams = params ?? nav.params;
  const channel = isDeliveryChannel(urlParams.channel) ? urlParams.channel : undefined;

  const [to, setTo] = useState('');
  const [template, setTemplate] = useState('');
  const toQuery = useDebouncedValue(to.trim());
  const templateQuery = useDebouncedValue(template.trim());
  const list = useAdminOutbox({ channel, to: toQuery, template: templateQuery });

  // 404 = this is not a development installation: the outbox does not exist here.
  const unavailable = !list.loading && list.items.length === 0 && list.errorStatus === 404;
  const filtered = !!channel || toQuery !== '' || templateQuery !== '';

  if (unavailable) {
    return (
      <Screen title="Outbox" theme={look}>
        <EmptyView
          icon="mail-unread-outline"
          title="The outbox is not available here"
          sub="It exists only on development installations, where it lists the messages the server queued."
        />
      </Screen>
    );
  }

  return (
    <Screen
      title="Outbox"
      subtitle="Development installations only. Every email, SMS and push message the server queued, newest first, so reset and verification links can be opened on this machine."
      theme={look}
      actions={(
        <Button
          label="Refresh" icon="refresh-outline" variant="secondary" onPress={list.refetch}
          loading={list.refreshing} disabled={list.loading}
        />
      )}
    >
      <Banner
        tone="info"
        text="A message appears as soon as the request that caused it has finished; its text and link are final from then on, whatever its status. Messages cannot be edited, resent or deleted here."
      />

      <PagedList
        state={list}
        label="Outbound messages"
        keyOf={m => m.messageId}
        header={(
          <View style={styles.filters}>
            <View style={styles.filter}>
              <SelectField<ChannelChoice>
                label="Channel"
                value={channel ?? 'ALL'}
                options={CHANNEL_OPTIONS}
                onChange={value => nav.setParams({ channel: value === 'ALL' ? undefined : value })}
              />
            </View>
            <View style={styles.filter}>
              <TextField
                label="Recipient"
                value={to}
                onChange={setTo}
                maxLength={OUTBOX_TO_MAX}
                autoCapitalize="none"
                hint="Part of an email address, phone number or account ID."
              />
            </View>
            <View style={styles.filter}>
              <TextField
                label="Template"
                value={template}
                onChange={setTemplate}
                maxLength={OUTBOX_TEMPLATE_MAX}
                autoCapitalize="none"
                hint="The exact template id, for example account-password-reset."
              />
            </View>
          </View>
        )}
        renderItem={message => <MessageRow message={message} />}
        empty={filtered
          ? { icon: 'search-outline', title: 'No messages match', sub: 'Change the channel, recipient or template filter.' }
          : { icon: 'mail-outline', title: 'No messages yet', sub: 'Messages appear here when the server queues an email, SMS or push notification.' }}
      />
    </Screen>
  );
}

const styles = StyleSheet.create({
  filters: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: space.md },
  filter:  { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0, maxWidth: 360 },

  row:        { gap: space.sm },
  rowHead:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', gap: space.sm },
  rowTitles:  { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0, gap: 2 },
  rowTitle:   { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  rowSub:     { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  rowActions: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  badges:     { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs, maxWidth: '100%' },
  meta:       { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  error:      { fontSize: fontSize.sm, fontWeight: '500', color: STATUS_TONES.danger.fg, lineHeight: 19 },

  linkRow: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
  link:    { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0, fontSize: fontSize.sm, color: colors.text, lineHeight: 19 },

  detail:   { gap: space.sm, borderTopWidth: 1, borderTopColor: colors.border, paddingTop: space.md },
  body:     { borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, backgroundColor: colors.bg, padding: space.md, minWidth: 0 },
  bodyText: { fontSize: fontSize.sm, color: colors.text, lineHeight: 20 },
});
