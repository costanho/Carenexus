import { Ionicons } from '@expo/vector-icons';
import { useState } from 'react';
import { ScrollView, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { useCaregiverDependents } from '@/hooks/use-caregiver-dependents';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const GRAY2  = '#9CA3AF';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const PURPLE = '#7C3AED';
const PURPLE_BG = '#F5F3FF';

interface Message {
  id:      number;
  from:    string;
  role:    string;
  avatar:  number;
  time:    string;
  subject: string;
  body:    string;
  unread:  boolean;
  patient: string;
}

const MOCK_MESSAGES: Message[] = [
  {
    id: 1, from: 'Dr. James Banda', role: 'General Practitioner', avatar: 12,
    time: '2 hrs ago', subject: 'Blood Pressure Update — Thomas Dube',
    body: 'Thomas\'s latest BP readings are showing improvement with Amlodipine. Please ensure he continues taking it at the same time each morning. His next in-person review is scheduled for 10 August. Let me know if you notice any ankle swelling or dizziness.',
    unread: true, patient: 'Thomas Dube',
  },
  {
    id: 2, from: 'Dr. Emeka Eze', role: 'Internal Medicine', avatar: 22,
    time: '1 day ago', subject: 'HbA1c Results — Action Required',
    body: 'Thomas\'s HbA1c has come in at 8.2% — above the target of 7.0%. We discussed intensifying his Metformin dosage during the video consultation. Please monitor his blood glucose at home and report any hypoglycaemic episodes immediately.',
    unread: true, patient: 'Thomas Dube',
  },
  {
    id: 3, from: 'Dr. Emeka Eze', role: 'Internal Medicine', avatar: 22,
    time: '3 days ago', subject: 'URGENT: Agnes Zimba — Kidney Function Results',
    body: 'Agnes\'s latest kidney panel shows declining eGFR (32 mL/min/1.73m²), which represents CKD Stage 3b. I am flagging this as urgent. Her appointment on 5 August is critical — please ensure she attends. She must adhere strictly to fluid and dietary protein restrictions in the meantime.',
    unread: false, patient: 'Agnes Zimba',
  },
  {
    id: 4, from: 'Care Nurse — Miriam Choto', role: 'Community Care Nurse', avatar: 32,
    time: '5 days ago', subject: 'Home Visit Summary — Agnes Zimba',
    body: 'Completed home visit for Agnes on 25 July. Vital signs stable. Weight up 1.2kg since last week — likely fluid retention. Reminded her to take Furosemide each morning and to report daily weight changes. Pain levels 4/10 with Tramadol — manageable. Arranged physiotherapy session for next week.',
    unread: false, patient: 'Agnes Zimba',
  },
  {
    id: 5, from: 'Pharmacy Support', role: 'Pharmacist', avatar: 55,
    time: '1 week ago', subject: 'Prescription Ready for Collection',
    body: 'The following prescriptions for Thomas Dube are ready for collection at our facility: Amlodipine 5mg (30 tabs), Metformin 500mg (60 tabs), Atorvastatin 10mg (30 tabs). Please collect within 7 days. Contact us on +263773000999 for any queries.',
    unread: false, patient: 'Thomas Dube',
  },
];

export default function PatientProxyMessages() {
  const { dependents } = useCaregiverDependents();
  const [selected, setSelected] = useState<Message | null>(MOCK_MESSAGES[0]);
  const [reply, setReply]       = useState('');
  const [search, setSearch]     = useState('');

  const filtered = MOCK_MESSAGES.filter(m =>
    m.subject.toLowerCase().includes(search.toLowerCase()) ||
    m.from.toLowerCase().includes(search.toLowerCase()) ||
    m.patient.toLowerCase().includes(search.toLowerCase())
  );

  const unreadCount = MOCK_MESSAGES.filter(m => m.unread).length;

  return (
    <View style={styles.outer}>
      {/* Sidebar list */}
      <View style={styles.list}>
        {/* List header */}
        <View style={styles.listHeader}>
          <View>
            <Text style={styles.listTitle}>Messages</Text>
            {unreadCount > 0 && (
              <Text style={styles.unreadBadge}>{unreadCount} unread</Text>
            )}
          </View>
          <TouchableOpacity style={styles.composeBtn} activeOpacity={0.8}>
            <Ionicons name="create-outline" size={16} color={PURPLE} />
          </TouchableOpacity>
        </View>

        {/* Search */}
        <View style={styles.searchBox}>
          <Ionicons name="search-outline" size={15} color={GRAY2} />
          <TextInput
            style={styles.searchInput}
            placeholder="Search messages..."
            placeholderTextColor={GRAY2}
            value={search}
            onChangeText={setSearch}
          />
        </View>

        {/* Message rows */}
        <ScrollView showsVerticalScrollIndicator={false}>
          {filtered.map(msg => (
            <TouchableOpacity
              key={msg.id}
              style={[styles.msgRow, selected?.id === msg.id && styles.msgRowActive]}
              activeOpacity={0.7}
              onPress={() => setSelected(msg)}
            >
              {msg.unread && <View style={styles.unreadDot} />}
              <View style={[styles.senderAvatar, { backgroundColor: PURPLE }]}>
                <Text style={styles.senderInitials}>
                  {msg.from.split(' ').slice(-1)[0][0]}
                </Text>
              </View>
              <View style={{ flex: 1 }}>
                <View style={styles.msgMeta}>
                  <Text style={[styles.senderName, msg.unread && { fontWeight: '700' }]} numberOfLines={1}>
                    {msg.from}
                  </Text>
                  <Text style={styles.msgTime}>{msg.time}</Text>
                </View>
                <Text style={[styles.msgSubject, msg.unread && { fontWeight: '600', color: TEXT }]} numberOfLines={1}>
                  {msg.subject}
                </Text>
                <Text style={styles.msgSnippet} numberOfLines={1}>{msg.body}</Text>
                <View style={styles.patientTag}>
                  <Ionicons name="person-outline" size={10} color={PURPLE} />
                  <Text style={styles.patientTagText}>{msg.patient}</Text>
                </View>
              </View>
            </TouchableOpacity>
          ))}
        </ScrollView>
      </View>

      {/* Detail panel */}
      {selected ? (
        <View style={styles.detail}>
          <View style={styles.detailHeader}>
            <Text style={styles.detailSubject}>{selected.subject}</Text>
            <View style={styles.detailMeta}>
              <View style={[styles.senderAvatar, { backgroundColor: PURPLE }]}>
                <Text style={styles.senderInitials}>{selected.from.split(' ').slice(-1)[0][0]}</Text>
              </View>
              <View style={{ flex: 1 }}>
                <Text style={styles.detailFrom}>{selected.from}</Text>
                <Text style={styles.detailRole}>{selected.role} · Re: {selected.patient}</Text>
              </View>
              <Text style={styles.detailTime}>{selected.time}</Text>
            </View>
          </View>

          <ScrollView style={styles.detailBody} showsVerticalScrollIndicator={false}>
            <Text style={styles.detailBodyText}>{selected.body}</Text>
          </ScrollView>

          {/* Reply box */}
          <View style={styles.replyBox}>
            <TextInput
              style={styles.replyInput}
              multiline
              numberOfLines={3}
              placeholder={`Reply to ${selected.from}...`}
              placeholderTextColor={GRAY2}
              value={reply}
              onChangeText={setReply}
              textAlignVertical="top"
            />
            <View style={styles.replyActions}>
              <TouchableOpacity activeOpacity={0.7}>
                <Ionicons name="attach-outline" size={20} color={GRAY} />
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.sendBtn, !reply.trim() && styles.sendBtnDisabled]}
                activeOpacity={0.8}
                disabled={!reply.trim()}
                onPress={() => setReply('')}
              >
                <Ionicons name="send-outline" size={15} color={WHITE} />
                <Text style={styles.sendBtnText}>Send</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      ) : (
        <View style={styles.empty}>
          <Ionicons name="chatbox-outline" size={48} color={BORDER} />
          <Text style={styles.emptyText}>Select a message to read</Text>
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  outer: { flex: 1, flexDirection: 'row', backgroundColor: BG },

  list:       { width: 320, backgroundColor: WHITE, borderRightWidth: 1, borderRightColor: BORDER, flexDirection: 'column' },
  listHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', padding: 20, paddingBottom: 12 },
  listTitle:  { fontSize: 18, fontWeight: '700', color: TEXT },
  unreadBadge:{ fontSize: 12, color: PURPLE, fontWeight: '600', marginTop: 2 },
  composeBtn: { width: 34, height: 34, borderRadius: 8, borderWidth: 1, borderColor: BORDER, alignItems: 'center', justifyContent: 'center' },

  searchBox:   { flexDirection: 'row', alignItems: 'center', gap: 8, margin: 12, marginTop: 0, backgroundColor: BG, borderRadius: 8, paddingHorizontal: 10, paddingVertical: 8, borderWidth: 1, borderColor: BORDER },
  searchInput: { flex: 1, fontSize: 13, color: TEXT, outlineStyle: 'none' } as any,

  msgRow:       { flexDirection: 'row', alignItems: 'flex-start', gap: 10, paddingHorizontal: 16, paddingVertical: 12, borderBottomWidth: 1, borderBottomColor: BORDER, position: 'relative' },
  msgRowActive: { backgroundColor: PURPLE_BG },
  unreadDot:    { width: 8, height: 8, borderRadius: 4, backgroundColor: PURPLE, position: 'absolute', left: 6, top: 16 },
  senderAvatar: { width: 38, height: 38, borderRadius: 19, alignItems: 'center', justifyContent: 'center', flexShrink: 0 },
  senderInitials:{ color: WHITE, fontSize: 14, fontWeight: '700' },
  msgMeta:      { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 2 },
  senderName:   { fontSize: 13, fontWeight: '500', color: TEXT, flex: 1 },
  msgTime:      { fontSize: 11, color: GRAY2 },
  msgSubject:   { fontSize: 13, color: GRAY, marginBottom: 2 },
  msgSnippet:   { fontSize: 11, color: GRAY2, marginBottom: 4 },
  patientTag:   { flexDirection: 'row', alignItems: 'center', gap: 3 },
  patientTagText:{ fontSize: 10, color: PURPLE, fontWeight: '500' },

  detail:       { flex: 1, flexDirection: 'column' },
  detailHeader: { padding: 24, borderBottomWidth: 1, borderBottomColor: BORDER, gap: 12, backgroundColor: WHITE },
  detailSubject:{ fontSize: 18, fontWeight: '700', color: TEXT },
  detailMeta:   { flexDirection: 'row', alignItems: 'center', gap: 12 },
  detailFrom:   { fontSize: 14, fontWeight: '600', color: TEXT },
  detailRole:   { fontSize: 12, color: GRAY, marginTop: 2 },
  detailTime:   { fontSize: 12, color: GRAY2 },

  detailBody:     { flex: 1, padding: 24 },
  detailBodyText: { fontSize: 14, color: TEXT, lineHeight: 24 },

  replyBox:     { padding: 16, borderTopWidth: 1, borderTopColor: BORDER, backgroundColor: WHITE, gap: 10 },
  replyInput:   { borderWidth: 1, borderColor: BORDER, borderRadius: 10, padding: 12, fontSize: 13, color: TEXT, minHeight: 72 } as any,
  replyActions: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  sendBtn:         { flexDirection: 'row', alignItems: 'center', gap: 6, backgroundColor: PURPLE, borderRadius: 8, paddingHorizontal: 16, paddingVertical: 8 },
  sendBtnDisabled: { opacity: 0.4 },
  sendBtnText:     { fontSize: 13, color: WHITE, fontWeight: '600' },

  empty:     { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 12 },
  emptyText: { fontSize: 14, color: GRAY },
});
