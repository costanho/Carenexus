import { Ionicons } from '@expo/vector-icons';
import { useState } from 'react';
import { ScrollView, StyleSheet, Switch, Text, TouchableOpacity, View } from 'react-native';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const PURPLE = '#7C3AED';
const GREEN  = '#10B981';
const RED    = '#EF4444';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

interface NotifSetting {
  key:     string;
  label:   string;
  sub:     string;
  icon:    IoniconsName;
  iconBg:  string;
  iconColor: string;
}

const CATEGORIES: { title: string; sub: string; items: NotifSetting[] }[] = [
  {
    title: 'Health Alerts',
    sub:   'Critical notifications about your linked patients\' health',
    items: [
      { key: 'critical_alerts',    label: 'Critical Health Alerts',    sub: 'Immediate alerts for critical results or emergencies',       icon: 'warning-outline',          iconBg: '#FEE2E2', iconColor: RED    },
      { key: 'lab_results',        label: 'Lab Results',               sub: 'When new lab results are uploaded or require review',        icon: 'flask-outline',            iconBg: '#FEF3C7', iconColor: '#F59E0B' },
      { key: 'medication_due',     label: 'Medication Reminders',      sub: 'Daily reminders for due medications',                       icon: 'medical-outline',          iconBg: '#EDE9FE', iconColor: PURPLE },
      { key: 'vitals_alert',       label: 'Vitals Outside Range',      sub: 'When BP, glucose or weight readings are abnormal',          icon: 'pulse-outline',            iconBg: '#FEE2E2', iconColor: RED    },
    ],
  },
  {
    title: 'Appointments',
    sub:   'Reminders and updates about scheduled visits',
    items: [
      { key: 'appt_reminder_24h',  label: '24-Hour Appointment Reminder', sub: 'Reminder the day before a scheduled appointment',       icon: 'calendar-outline',         iconBg: '#EDE9FE', iconColor: PURPLE },
      { key: 'appt_reminder_1h',   label: '1-Hour Appointment Reminder',  sub: 'Reminder 1 hour before the appointment',               icon: 'alarm-outline',            iconBg: '#D1FAE5', iconColor: GREEN  },
      { key: 'appt_changes',       label: 'Appointment Changes',          sub: 'When appointments are cancelled or rescheduled',        icon: 'calendar-clear-outline',   iconBg: '#FEF3C7', iconColor: '#F59E0B' },
      { key: 'video_call_ready',   label: 'Video Call Ready',             sub: 'When a video consultation is ready to join',            icon: 'videocam-outline',         iconBg: '#D1FAE5', iconColor: GREEN  },
    ],
  },
  {
    title: 'Care Team Messages',
    sub:   'Communication from doctors, nurses and pharmacists',
    items: [
      { key: 'new_message',        label: 'New Messages',              sub: 'When the care team sends you a message',                   icon: 'chatbox-outline',          iconBg: '#EDE9FE', iconColor: PURPLE },
      { key: 'prescription_ready', label: 'Prescription Ready',        sub: 'When a prescription is ready for collection',             icon: 'tablet-portrait-outline',  iconBg: '#D1FAE5', iconColor: GREEN  },
      { key: 'care_plan_update',   label: 'Care Plan Updates',         sub: 'Changes to treatment goals or care plans',                icon: 'document-text-outline',    iconBg: '#EDE9FE', iconColor: PURPLE },
      { key: 'referral',           label: 'Referrals',                 sub: 'When a patient is referred to a specialist',              icon: 'share-outline',            iconBg: '#FEF3C7', iconColor: '#F59E0B' },
    ],
  },
  {
    title: 'Account & Security',
    sub:   'Account activity and security alerts',
    items: [
      { key: 'login_alert',        label: 'New Login Alert',           sub: 'When your account is accessed from a new device',         icon: 'shield-outline',           iconBg: '#FEE2E2', iconColor: RED    },
      { key: 'access_change',      label: 'Proxy Access Changes',      sub: 'When your proxy permissions are modified',                icon: 'key-outline',              iconBg: '#FEF3C7', iconColor: '#F59E0B' },
      { key: 'weekly_summary',     label: 'Weekly Health Summary',     sub: 'A weekly digest of all patient activity',                 icon: 'stats-chart-outline',      iconBg: '#EDE9FE', iconColor: PURPLE },
    ],
  },
];

const DELIVERY_CHANNELS = [
  { key: 'push',  label: 'Push Notifications', icon: 'phone-portrait-outline' as IoniconsName },
  { key: 'email', label: 'Email',               icon: 'mail-outline'           as IoniconsName },
  { key: 'sms',   label: 'SMS',                 icon: 'chatbubble-outline'     as IoniconsName },
];

function ToggleRow({ setting, value, onChange }: {
  setting: NotifSetting;
  value: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <View style={styles.toggleRow}>
      <View style={[styles.iconWrap, { backgroundColor: setting.iconBg }]}>
        <Ionicons name={setting.icon} size={16} color={setting.iconColor} />
      </View>
      <View style={{ flex: 1 }}>
        <Text style={styles.toggleLabel}>{setting.label}</Text>
        <Text style={styles.toggleSub}>{setting.sub}</Text>
      </View>
      <Switch
        value={value}
        onValueChange={onChange}
        trackColor={{ false: BORDER, true: PURPLE + '60' }}
        thumbColor={value ? PURPLE : WHITE}
      />
    </View>
  );
}

const DEFAULT_STATE: Record<string, boolean> = {
  critical_alerts: true, lab_results: true, medication_due: true, vitals_alert: true,
  appt_reminder_24h: true, appt_reminder_1h: true, appt_changes: true, video_call_ready: true,
  new_message: true, prescription_ready: true, care_plan_update: false, referral: true,
  login_alert: true, access_change: true, weekly_summary: false,
};

export default function PatientProxyNotificationSettings() {
  const [settings, setSettings] = useState<Record<string, boolean>>(DEFAULT_STATE);
  const [channels, setChannels] = useState({ push: true, email: true, sms: false });
  const [saved, setSaved]       = useState(false);

  function toggle(key: string) {
    setSettings(s => ({ ...s, [key]: !s[key] }));
  }

  function save() {
    setSaved(true);
    setTimeout(() => setSaved(false), 2500);
  }

  const enabledCount = Object.values(settings).filter(Boolean).length;

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>
      <View style={styles.pageHeader}>
        <View>
          <Text style={styles.pageTitle}>Notification Settings</Text>
          <Text style={styles.pageSub}>{enabledCount} of {Object.keys(settings).length} notifications enabled</Text>
        </View>
        <TouchableOpacity style={styles.saveBtn} activeOpacity={0.8} onPress={save}>
          <Ionicons name={saved ? 'checkmark-outline' : 'save-outline'} size={15} color={WHITE} />
          <Text style={styles.saveBtnText}>{saved ? 'Saved!' : 'Save Changes'}</Text>
        </TouchableOpacity>
      </View>

      {/* Delivery channels */}
      <View style={styles.card}>
        <Text style={styles.cardTitle}>Delivery Channels</Text>
        <Text style={styles.cardSub}>Choose how you receive notifications</Text>
        <View style={styles.channelRow}>
          {DELIVERY_CHANNELS.map(ch => (
            <TouchableOpacity
              key={ch.key}
              style={[styles.channelBtn, channels[ch.key as keyof typeof channels] && styles.channelBtnActive]}
              activeOpacity={0.7}
              onPress={() => setChannels(c => ({ ...c, [ch.key]: !c[ch.key as keyof typeof channels] }))}
            >
              <Ionicons
                name={ch.icon}
                size={18}
                color={channels[ch.key as keyof typeof channels] ? WHITE : GRAY}
              />
              <Text style={[styles.channelText, channels[ch.key as keyof typeof channels] && styles.channelTextActive]}>
                {ch.label}
              </Text>
            </TouchableOpacity>
          ))}
        </View>
      </View>

      {/* Notification categories */}
      {CATEGORIES.map(cat => (
        <View key={cat.title} style={styles.card}>
          <Text style={styles.cardTitle}>{cat.title}</Text>
          <Text style={styles.cardSub}>{cat.sub}</Text>
          {cat.items.map((item, i) => (
            <View key={item.key}>
              {i > 0 && <View style={styles.sep} />}
              <ToggleRow
                setting={item}
                value={settings[item.key] ?? false}
                onChange={() => toggle(item.key)}
              />
            </View>
          ))}
        </View>
      ))}

      {/* Quiet hours */}
      <View style={styles.card}>
        <Text style={styles.cardTitle}>Quiet Hours</Text>
        <Text style={styles.cardSub}>Pause non-critical notifications during these hours</Text>
        <View style={styles.quietRow}>
          <View style={styles.quietBlock}>
            <Text style={styles.quietLabel}>From</Text>
            <View style={styles.quietTime}><Text style={styles.quietTimeText}>10:00 PM</Text></View>
          </View>
          <Text style={{ color: GRAY, fontSize: 14 }}>to</Text>
          <View style={styles.quietBlock}>
            <Text style={styles.quietLabel}>Until</Text>
            <View style={styles.quietTime}><Text style={styles.quietTimeText}>07:00 AM</Text></View>
          </View>
          <View style={{ flex: 1 }} />
          <Switch
            value={true}
            trackColor={{ false: BORDER, true: PURPLE + '60' }}
            thumbColor={PURPLE}
          />
        </View>
        <Text style={styles.quietNote}>Critical health alerts will still be delivered during quiet hours.</Text>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll:     { flex: 1, backgroundColor: BG },
  container:  { padding: 24, paddingBottom: 48, gap: 20 },
  pageHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start' },
  pageTitle:  { fontSize: 22, fontWeight: '700', color: TEXT },
  pageSub:    { fontSize: 13, color: GRAY, marginTop: 3 },
  saveBtn:    { flexDirection: 'row', alignItems: 'center', gap: 6, backgroundColor: PURPLE, borderRadius: 10, paddingHorizontal: 16, paddingVertical: 10 },
  saveBtnText:{ fontSize: 13, color: WHITE, fontWeight: '600' },

  card:      { backgroundColor: WHITE, borderRadius: 14, borderWidth: 1, borderColor: BORDER, padding: 20, gap: 0 },
  cardTitle: { fontSize: 16, fontWeight: '700', color: TEXT, marginBottom: 4 },
  cardSub:   { fontSize: 12, color: GRAY, marginBottom: 16 },

  channelRow: { flexDirection: 'row', gap: 10 },
  channelBtn: { flex: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8, borderWidth: 1, borderColor: BORDER, borderRadius: 10, paddingVertical: 12 },
  channelBtnActive: { backgroundColor: PURPLE, borderColor: PURPLE },
  channelText:      { fontSize: 13, color: GRAY, fontWeight: '500' },
  channelTextActive:{ color: WHITE },

  sep: { height: 1, backgroundColor: BORDER },

  toggleRow:   { flexDirection: 'row', alignItems: 'center', gap: 12, paddingVertical: 14 },
  iconWrap:    { width: 36, height: 36, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  toggleLabel: { fontSize: 14, fontWeight: '600', color: TEXT },
  toggleSub:   { fontSize: 12, color: GRAY, marginTop: 2 },

  quietRow:   { flexDirection: 'row', alignItems: 'center', gap: 16, paddingVertical: 8 },
  quietBlock: { gap: 4 },
  quietLabel: { fontSize: 11, color: GRAY },
  quietTime:  { backgroundColor: BG, borderRadius: 8, paddingHorizontal: 14, paddingVertical: 8, borderWidth: 1, borderColor: BORDER },
  quietTimeText:{ fontSize: 14, fontWeight: '600', color: TEXT },
  quietNote:  { fontSize: 11, color: GRAY, fontStyle: 'italic', marginTop: 10 },
});
