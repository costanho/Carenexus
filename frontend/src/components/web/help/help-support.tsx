import { Ionicons } from '@expo/vector-icons';
import { ActivityIndicator, Linking, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useAction } from '@/hooks/use-action';
import { usePublicSettings } from '@/hooks/use-public-settings';
import { useSectionNav } from '@/hooks/use-section-nav';
import { ROLE_THEME, type AppRole, type IoniconsName, type SectionProps } from '@/lib/nav/sections';
import { openSupportThread } from '@/lib/support/api';
import { clinicHoursText, clinicTimeZoneText } from '@/lib/support/format';

const TEXT   = '#111827';
const GRAY   = '#4B5563';
const WHITE  = '#FFFFFF';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const AMBER_BG     = '#FFFBEB';
const AMBER_BORDER = '#FCD34D';
const AMBER_TEXT   = '#92400E';
const RED          = '#B91C1C';

const HIDDEN = { 'aria-hidden': true } as object;
const H1 = { 'aria-level': 1 } as object;
const H2 = { 'aria-level': 2 } as object;
const H3 = { 'aria-level': 3 } as object;

/**
 * How to reach support. A value given here replaces the one from the clinic settings; a value that is missing
 * from both is simply not shown.
 */
export interface SupportContact {
  email?:      string | null;
  phone?:      string | null;
  clinicName?: string | null;
}

interface Topic {
  icon:  IoniconsName;
  title: string;
  body:  string;
  /** Section this topic is about. The "Open ..." link is shown only when the dashboard has that section. */
  section?:   string;
  linkLabel?: string;
}

const TOPICS: Record<AppRole, Topic[]> = {
  patient: [
    {
      icon: 'calendar-outline', title: 'Book and manage appointments',
      body: 'Open Book Appointment, choose a doctor, a date and a time, then confirm. Your bookings are listed under My Appointments, where you can reschedule or cancel an upcoming visit.',
      section: 'my-appointments', linkLabel: 'Open My Appointments',
    },
    {
      icon: 'videocam-outline', title: 'Join a video consultation',
      body: 'Video visits are joined from My Appointments. The join button appears on the appointment when the call can be opened. Allow your browser to use the camera and microphone when it asks.',
      section: 'my-appointments', linkLabel: 'Open My Appointments',
    },
    {
      icon: 'folder-open-outline', title: 'Find your health records',
      body: 'Consultation History, Medical Records, Prescriptions, Lab Results and Imaging Results show what your care team has recorded for you. If something looks wrong, message the doctor who recorded it.',
      section: 'medical-records', linkLabel: 'Open Medical Records',
    },
    {
      icon: 'people-outline', title: 'Give a caregiver access',
      body: 'My Proxies (Caregivers) lists the people who can act for you and what each of them can see. You can add a caregiver, change their access, approve their requests or remove them at any time.',
      section: 'my-proxies', linkLabel: 'Open My Proxies (Caregivers)',
    },
  ],
  doctor: [
    {
      icon: 'calendar-outline', title: 'Your schedule and appointments',
      body: 'Schedule shows your day and what is coming up. Appointments lists every booking for your patients; open one to reschedule it, record its outcome or message the patient.',
      section: 'schedule', linkLabel: 'Open Schedule',
    },
    {
      icon: 'videocam-outline', title: 'Video consultations',
      body: 'A video appointment shows a join button on your schedule when the call can be opened. Allow your browser to use the camera and microphone when it asks.',
      section: 'schedule', linkLabel: 'Open Schedule',
    },
    {
      icon: 'people-outline', title: 'Patients and their records',
      body: 'Patients lists the people who have had appointments with you. Medical Records, Prescriptions, Consultations and Labs & Results show what is on file for them.',
      section: 'patients', linkLabel: 'Open Patients',
    },
  ],
  proxy: [
    {
      icon: 'heart-outline', title: 'The people you care for',
      body: 'Your linked patients are listed under My Loved Ones in the menu. Select one to see their profile and what you are allowed to view or do. Use Add Another Loved One to link someone else.',
      section: 'profile-settings', linkLabel: 'Open Profile & Settings',
    },
    {
      icon: 'lock-closed-outline', title: 'What you can see',
      body: 'Each patient decides what their caregiver can see and do. If a screen says you do not have access to some information, ask the patient to change your access from their own account.',
    },
    {
      icon: 'calendar-outline', title: 'Appointments, medications and results',
      body: 'Appointments, Medications, Documents & Results and Health Summary bring together the information of every patient who has given you access to it.',
      section: 'appointments', linkLabel: 'Open Appointments',
    },
    {
      icon: 'options-outline', title: 'Choose your notifications',
      body: 'Notification Settings controls which updates about your loved ones you are told about.',
      section: 'notification-settings', linkLabel: 'Open Notification Settings',
    },
  ],
  admin: [
    {
      icon: 'grid-outline', title: 'Finding your way around',
      body: 'Administration tools are listed in the menu on the left (behind the menu button on a phone). Each tool opens in this area, and the Back button of your browser returns to the previous one.',
    },
    {
      icon: 'help-buoy-outline', title: 'Answering support messages',
      body: 'Patients, caregivers and doctors can write to support from their own Help & Support page. Their conversations are listed under Messages: reply there, and close a conversation once the question is settled.',
      section: 'messages', linkLabel: 'Open Messages',
    },
    {
      icon: 'shield-checkmark-outline', title: 'Keeping the administrator account safe',
      body: 'Administrator accounts can only be used on the web. Sign out when you leave a shared computer, and never share your password.',
    },
  ],
};

const MESSAGES_HELP: Record<AppRole, string> = {
  patient: 'Use Messages to write to your doctors and care team about anything that is not urgent. You will be notified when they reply.',
  doctor:  'Use Messages to write to your patients and, where the patient has approved it, their caregivers.',
  proxy:   "Use Messages to write to a loved one's care team. The patient has to approve messaging for you first.",
  admin:   '',
};

function Card({ children }: { children: React.ReactNode }) {
  return <View style={styles.card}>{children}</View>;
}

function DetailRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <View style={styles.detailRow}>
      <Text style={styles.detailLabel}>{label}{' '}</Text>
      <View style={styles.detailValue}>{children}</View>
    </View>
  );
}

/**
 * Help & Support: short how-to text for the signed-in role, a link to Messages, the clinic's support contact
 * and hours, and a Contact support button that opens the user's conversation with the support team.
 *
 * Clinic name, support email, phone and hours come from the clinic settings (`usePublicSettings`). When they
 * cannot be loaded, or no support email is configured, the email from EXPO_PUBLIC_SUPPORT_EMAIL is shown.
 * Administrators answer support conversations, so they are not offered the button.
 */
export default function HelpSupport({ role, navigate, openThread, theme, contact }: Partial<SectionProps> & { contact?: SupportContact }) {
  const nav = useSectionNav();
  const activeRole: AppRole = role ?? nav.role;
  const accent = (theme ?? ROLE_THEME[activeRole]).accent;
  const go = navigate ?? nav.navigate;
  const showThread = openThread ?? nav.openThread;

  const clinic = usePublicSettings();
  const email = (contact?.email ?? clinic.supportEmail ?? '').trim();
  const phone = (contact?.phone ?? clinic.settings?.supportPhone ?? '').trim();
  const clinicName = (contact?.clinicName ?? clinic.settings?.clinicName ?? '').trim();
  const hours = clinicHoursText(clinic.settings);
  const timeZone = clinicTimeZoneText(clinic.settings?.clinicTimeZone);
  const closedOnWeekends = clinic.settings?.allowWeekends === false;
  const hasDetails = !!(clinicName || email || phone || hours);

  const hasMessages = nav.hasSection('messages') && !!MESSAGES_HELP[activeRole];
  const topics = TOPICS[activeRole];

  // Support conversations are read in Messages; administrators answer them and cannot open one.
  const canContactSupport = activeRole !== 'admin' && nav.hasSection('messages');
  const contactSupport = useAction(() => openSupportThread(), {
    errorMessage: 'The support conversation could not be opened. Please try again.',
  });
  async function onContactSupport() {
    const result = await contactSupport.run();
    if (result.ok) showThread(result.data.threadId);
  }

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>
      <View style={styles.pageHeader}>
        <Text style={styles.pageTitle} accessibilityRole="header" {...H1}>Help & Support</Text>
        <Text style={styles.pageSub}>How to use CareNexus and where to get help</Text>
      </View>

      <View style={styles.notice} role="note">
        <Ionicons name="warning-outline" size={18} color={AMBER_TEXT} importantForAccessibility="no" {...HIDDEN} />
        <Text style={styles.noticeText}>
          CareNexus is not an emergency service. If you think someone needs urgent medical help, call your local emergency number.
        </Text>
      </View>

      <Text style={styles.sectionTitle} accessibilityRole="header" {...H2}>How to</Text>
      <View style={styles.grid}>
        {topics.map(topic => {
          const linked = !!topic.section && nav.hasSection(topic.section);
          return (
            <View key={topic.title} style={styles.gridItem}>
              <Card>
                <View style={styles.topicHead}>
                  <View style={[styles.topicIcon, { backgroundColor: (theme ?? ROLE_THEME[activeRole]).accentBg }]} importantForAccessibility="no-hide-descendants" {...HIDDEN}>
                    <Ionicons aria-hidden name={topic.icon} size={18} color={accent} />
                  </View>
                  <Text style={styles.topicTitle} accessibilityRole="header" {...H3}>{topic.title}</Text>
                </View>
                <Text style={styles.body}>{topic.body}</Text>
                {linked && (
                  <TouchableOpacity
                    style={styles.link}
                    onPress={() => go(topic.section!)}
                    activeOpacity={0.7}
                    accessibilityRole="button"
                  >
                    <Text style={[styles.linkText, { color: accent }]}>{topic.linkLabel ?? 'Open'}</Text>
                    <Ionicons name="arrow-forward" size={14} color={accent} importantForAccessibility="no" {...HIDDEN} />
                  </TouchableOpacity>
                )}
              </Card>
            </View>
          );
        })}
      </View>

      {hasMessages && (
        <>
          <Text style={styles.sectionTitle} accessibilityRole="header" {...H2}>Talk to your care team</Text>
          <Card>
            <Text style={styles.body}>{MESSAGES_HELP[activeRole]}</Text>
            <TouchableOpacity
              style={[styles.primaryBtn, { backgroundColor: accent }]}
              onPress={() => go('messages')}
              activeOpacity={0.8}
              accessibilityRole="button"
            >
              <Ionicons name="chatbubble-outline" size={16} color={WHITE} importantForAccessibility="no" {...HIDDEN} />
              <Text style={styles.primaryBtnText}>Open Messages</Text>
            </TouchableOpacity>
          </Card>
        </>
      )}

      <Text style={styles.sectionTitle} accessibilityRole="header" {...H2}>Contact support</Text>
      <Card>
        {canContactSupport ? (
          <>
            <Text style={styles.body}>
              For problems with your account or with CareNexus itself, write to {clinicName ? `${clinicName} support` : 'the support team'}.
              Your conversation opens in Messages, and you get a notification when support replies.
              For questions about your health, message your care team instead.
            </Text>
            <TouchableOpacity
              style={[styles.primaryBtn, { backgroundColor: accent }, contactSupport.pending && styles.btnBusy]}
              onPress={onContactSupport}
              disabled={contactSupport.pending}
              activeOpacity={0.8}
              accessibilityRole="button"
              accessibilityState={{ busy: contactSupport.pending, disabled: contactSupport.pending }}
              aria-busy={contactSupport.pending}
            >
              <Ionicons name="help-buoy-outline" size={16} color={WHITE} importantForAccessibility="no" {...HIDDEN} />
              <Text style={styles.primaryBtnText}>{contactSupport.pending ? 'Opening…' : 'Contact support'}</Text>
            </TouchableOpacity>
            {/* Always mounted so a failure is announced when it appears. */}
            <View accessibilityLiveRegion="polite">
              {!!contactSupport.error && <Text style={styles.errorText} accessibilityRole="alert">{contactSupport.error}</Text>}
            </View>
          </>
        ) : activeRole === 'admin' ? (
          <>
            <Text style={styles.body}>
              These are the clinic details people see on their own Help & Support page, next to the button that opens a conversation with support.
            </Text>
            {nav.hasSection('system-settings') && (
              <TouchableOpacity
                style={styles.link}
                onPress={() => go('system-settings')}
                activeOpacity={0.7}
                accessibilityRole="button"
              >
                <Text style={[styles.linkText, { color: accent }]}>Open System Settings</Text>
                <Ionicons name="arrow-forward" size={14} color={accent} importantForAccessibility="no" {...HIDDEN} />
              </TouchableOpacity>
            )}
          </>
        ) : (
          <Text style={styles.body}>
            For problems with your account or with CareNexus itself, contact {clinicName ? `${clinicName} support` : 'support'}.
          </Text>
        )}

        {clinic.loading ? (
          <View style={styles.detailsState}>
            <ActivityIndicator size="small" color={accent} accessibilityLabel="Loading clinic details" />
          </View>
        ) : (
          <>
            {!!clinic.error && (
              <View style={styles.detailsError} accessibilityRole="alert">
                <Ionicons name="alert-circle-outline" size={18} color={RED} importantForAccessibility="no" {...HIDDEN} />
                <Text style={styles.detailsErrorText}>The clinic's contact details and hours could not be loaded.</Text>
                <TouchableOpacity
                  style={[styles.retryBtn, { borderColor: accent }]}
                  onPress={clinic.refetch}
                  activeOpacity={0.8}
                  accessibilityRole="button"
                  accessibilityLabel="Retry loading clinic details"
                >
                  <Text style={[styles.retryText, { color: accent }]}>Retry</Text>
                </TouchableOpacity>
              </View>
            )}
            {hasDetails ? (
              <View style={styles.details}>
                {!!clinicName && (
                  <DetailRow label="Clinic"><Text style={styles.detailText}>{clinicName}</Text></DetailRow>
                )}
                {!!email && (
                  <DetailRow label="Email">
                    <TouchableOpacity
                      style={styles.contactLink}
                      onPress={() => { Linking.openURL(`mailto:${email}`).catch(() => {}); }}
                      activeOpacity={0.7}
                      // Named by the visible address; the row's "Email" label says what it is.
                      accessibilityRole="link"
                      accessibilityLabel={`${email}, send an email`}
                    >
                      <Ionicons name="mail-outline" size={16} color={accent} importantForAccessibility="no" {...HIDDEN} />
                      <Text style={[styles.contactValue, { color: accent }]}>{email}</Text>
                    </TouchableOpacity>
                    <Text style={styles.detailNote}>Do not include medical details in an email.</Text>
                  </DetailRow>
                )}
                {!!phone && (
                  <DetailRow label="Phone">
                    <TouchableOpacity
                      style={styles.contactLink}
                      onPress={() => { Linking.openURL(`tel:${phone.replace(/[^\d+]/g, '')}`).catch(() => {}); }}
                      activeOpacity={0.7}
                      accessibilityRole="link"
                      accessibilityLabel={`${phone}, call`}
                    >
                      <Ionicons name="call-outline" size={16} color={accent} importantForAccessibility="no" {...HIDDEN} />
                      <Text style={[styles.contactValue, { color: accent }]}>{phone}</Text>
                    </TouchableOpacity>
                  </DetailRow>
                )}
                {!!hours && (
                  <DetailRow label="Clinic hours">
                    <Text style={styles.detailText}>{hours}{closedOnWeekends ? '. Closed on weekends' : ''}</Text>
                    {!!timeZone && <Text style={styles.detailNote}>Times are in clinic time ({timeZone}).</Text>}
                  </DetailRow>
                )}
              </View>
            ) : !clinic.error && !canContactSupport && activeRole !== 'admin' ? (
              <Text style={[styles.body, styles.detailsEmpty]}>
                No support contact has been set up yet. Contact the clinic or organisation that gave you access to CareNexus.
              </Text>
            ) : null}
            {!clinic.error && !email && !phone && activeRole === 'admin' && (
              <Text style={[styles.body, styles.detailsEmpty]}>No support email or phone number has been set yet.</Text>
            )}
          </>
        )}
      </Card>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll:    { flex: 1, backgroundColor: BG },
  container: { padding: 24, paddingBottom: 48, maxWidth: 960, width: '100%', alignSelf: 'center' },

  pageHeader: { marginBottom: 16 },
  pageTitle:  { fontSize: 22, fontWeight: '700', color: TEXT },
  pageSub:    { fontSize: 14, color: GRAY, marginTop: 4 },

  notice: {
    flexDirection: 'row', alignItems: 'flex-start', gap: 10,
    backgroundColor: AMBER_BG, borderWidth: 1, borderColor: AMBER_BORDER, borderRadius: 10,
    paddingVertical: 12, paddingHorizontal: 14,
  },
  noticeText: { flex: 1, minWidth: 0, fontSize: 13, lineHeight: 19, color: AMBER_TEXT, fontWeight: '500' },

  sectionTitle: { fontSize: 16, fontWeight: '700', color: TEXT, marginTop: 24, marginBottom: 10 },

  grid:     { flexDirection: 'row', flexWrap: 'wrap', gap: 12 },
  gridItem: { flexGrow: 1, flexShrink: 1, flexBasis: 280, minWidth: 0 },

  card: {
    flex: 1, backgroundColor: WHITE, borderRadius: 12, borderWidth: 1, borderColor: BORDER,
    padding: 16, minWidth: 0,
  },
  topicHead:  { flexDirection: 'row', alignItems: 'center', gap: 10, marginBottom: 8 },
  topicIcon:  { width: 34, height: 34, borderRadius: 17, alignItems: 'center', justifyContent: 'center' },
  topicTitle: { flex: 1, minWidth: 0, fontSize: 15, fontWeight: '600', color: TEXT },
  body:       { fontSize: 14, lineHeight: 21, color: GRAY },

  link:     { flexDirection: 'row', alignItems: 'center', gap: 6, marginTop: 12, alignSelf: 'flex-start', paddingVertical: 4 },
  linkText: { fontSize: 14, fontWeight: '600' },

  primaryBtn: {
    flexDirection: 'row', alignItems: 'center', gap: 8, alignSelf: 'flex-start',
    marginTop: 14, paddingVertical: 10, paddingHorizontal: 16, borderRadius: 8, minHeight: 40,
  },
  primaryBtnText: { fontSize: 14, fontWeight: '600', color: WHITE },
  btnBusy:        { opacity: 0.7 },
  errorText:      { fontSize: 13, lineHeight: 19, color: RED, marginTop: 8 },

  detailsState: { alignItems: 'flex-start', marginTop: 16, paddingVertical: 4 },
  detailsError: { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: 8, marginTop: 16 },
  detailsErrorText: { flexShrink: 1, minWidth: 0, fontSize: 13, lineHeight: 19, color: GRAY },
  detailsEmpty: { marginTop: 12 },
  retryBtn:     { borderWidth: 1, borderRadius: 8, paddingVertical: 6, paddingHorizontal: 12, minHeight: 32, justifyContent: 'center' },
  retryText:    { fontSize: 13, fontWeight: '600' },

  details:     { marginTop: 16, borderTopWidth: 1, borderTopColor: BORDER },
  detailRow:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: 8, paddingVertical: 10, borderBottomWidth: 1, borderBottomColor: BORDER },
  detailLabel: { width: 110, fontSize: 13, lineHeight: 20, color: GRAY, fontWeight: '600' },
  detailValue: { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0, gap: 2 },
  detailText:  { fontSize: 14, lineHeight: 20, color: TEXT },
  detailNote:  { fontSize: 12, lineHeight: 17, color: GRAY },
  contactLink: { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: 6, alignSelf: 'flex-start', paddingVertical: 2 },
  contactValue: { fontSize: 14, fontWeight: '600' },
});
