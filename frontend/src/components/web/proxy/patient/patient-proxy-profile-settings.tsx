import { Ionicons } from '@expo/vector-icons';
import { Image, ScrollView, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { useCurrentUser } from '@/hooks/use-current-user';
import { useCaregiverDependents } from '@/hooks/use-caregiver-dependents';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const PURPLE = '#7C3AED';
const PURPLE_BG  = '#F5F3FF';
const GREEN  = '#10B981';
const RED    = '#EF4444';
const ORANGE = '#F59E0B';

const AVATAR_COLORS = ['#4F46E5', '#0D9488', '#F59E0B', '#EF4444', '#8B5CF6', '#10B981', '#0EA5E9'];
function avatarColor(name: string) {
  let h = 0;
  for (let i = 0; i < (name?.length ?? 0); i++) h = (h * 31 + name.charCodeAt(i)) & 0xffff;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}
function initials(name: string) {
  return (name ?? '?').split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase();
}
function healthDotColor(s: string | null) {
  if (s === 'STABLE')   return GREEN;
  if (s === 'MONITOR')  return ORANGE;
  if (s === 'CRITICAL') return RED;
  return '#9CA3AF';
}
function accessLabel(level: string) {
  if (level === 'FULL_ACCESS') return 'Full Access';
  if (level === 'VIEW_ONLY')   return 'View Only';
  if (level === 'EDIT_ONLY')   return 'Edit Only';
  return 'Custom';
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.infoRow}>
      <Text style={styles.infoLabel}>{label}</Text>
      <Text style={styles.infoValue}>{value}</Text>
    </View>
  );
}

export default function PatientProxyProfileSettings() {
  const { user }       = useCurrentUser();
  const { dependents } = useCaregiverDependents();

  const fullName = user ? `${user.first_name} ${user.last_name}` : '...';
  const bg       = user ? avatarColor(fullName) : '#7C3AED';
  const ini      = user ? initials(fullName) : '??';

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>
      {/* Hero */}
      <View style={styles.hero}>
        <View style={styles.heroOverlay}>
          <View style={styles.avatarWrap}>
            {user?.avatar_url ? (
              <Image source={{ uri: user.avatar_url }} style={styles.avatar} />
            ) : (
              <View style={[styles.avatar, { backgroundColor: bg, alignItems: 'center', justifyContent: 'center' }]}>
                <Text style={styles.avatarText}>{ini}</Text>
              </View>
            )}
            <TouchableOpacity style={styles.avatarEdit} activeOpacity={0.8}>
              <Ionicons name="camera-outline" size={14} color={WHITE} />
            </TouchableOpacity>
          </View>
          <View>
            <Text style={styles.heroName}>{fullName}</Text>
            <Text style={styles.heroRole}>Proxy / Caregiver</Text>
            <View style={styles.verifiedRow}>
              <Ionicons name="shield-checkmark-outline" size={14} color={GREEN} />
              <Text style={styles.verifiedText}>Verified Caregiver</Text>
            </View>
          </View>
        </View>
      </View>

      <View style={styles.body}>
        {/* Personal info */}
        <View style={styles.card}>
          <View style={styles.cardHead}>
            <Text style={styles.cardTitle}>Personal Information</Text>
            <TouchableOpacity style={styles.editBtn} activeOpacity={0.7}>
              <Ionicons name="pencil-outline" size={14} color={PURPLE} />
              <Text style={styles.editBtnText}>Edit</Text>
            </TouchableOpacity>
          </View>
          <InfoRow label="First Name"    value={user?.first_name ?? '—'} />
          <View style={styles.sep} />
          <InfoRow label="Last Name"     value={user?.last_name  ?? '—'} />
          <View style={styles.sep} />
          <InfoRow label="Email Address" value={user?.email       ?? '—'} />
          <View style={styles.sep} />
          <InfoRow label="Phone Number"  value={user?.phone       ?? '—'} />
          <View style={styles.sep} />
          <InfoRow label="Role"          value="Proxy / Caregiver" />
          <View style={styles.sep} />
          <InfoRow label="Account Status" value={user?.is_active ? 'Active' : 'Inactive'} />
        </View>

        {/* Linked patients */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Linked Patients</Text>
          <Text style={styles.cardSub}>Patients you have been authorised to manage as proxy</Text>

          {dependents.length === 0 ? (
            <Text style={styles.emptyText}>No linked patients yet.</Text>
          ) : (
            dependents.map((dep, i) => {
              const name      = dep.patient_name ?? 'Unknown';
              const dotColor  = healthDotColor(dep.patient_health_status);
              const label     = accessLabel(dep.access_level);
              const bg2       = avatarColor(name);
              const rel       = dep.relationship
                ? dep.relationship.charAt(0).toUpperCase() + dep.relationship.slice(1)
                : '';
              const expiry = dep.expires_at
                ? `Expires ${new Date(dep.expires_at).toLocaleDateString('en-ZW', { day: 'numeric', month: 'short', year: 'numeric' })}`
                : 'No expiry';

              return (
                <View key={dep.access_id}>
                  {i > 0 && <View style={styles.sep} />}
                  <View style={styles.depRow}>
                    <View style={styles.depAvatarWrap}>
                      {dep.patient_avatar ? (
                        <Image source={{ uri: dep.patient_avatar }} style={styles.depAvatar} />
                      ) : (
                        <View style={[styles.depAvatar, { backgroundColor: bg2, alignItems: 'center', justifyContent: 'center' }]}>
                          <Text style={{ color: WHITE, fontSize: 14, fontWeight: '700' }}>{initials(name)}</Text>
                        </View>
                      )}
                      <View style={[styles.depDot, { backgroundColor: dotColor }]} />
                    </View>
                    <View style={{ flex: 1 }}>
                      <Text style={styles.depName}>{name}</Text>
                      <Text style={styles.depRel}>{rel}</Text>
                      <View style={styles.depMetaRow}>
                        <View style={styles.accessBadge}>
                          <Text style={styles.accessText}>{label}</Text>
                        </View>
                        <Text style={styles.depExpiry}>{expiry}</Text>
                      </View>
                    </View>
                    <TouchableOpacity style={styles.manageBtn} activeOpacity={0.8}>
                      <Text style={styles.manageBtnText}>Manage</Text>
                    </TouchableOpacity>
                  </View>
                </View>
              );
            })
          )}

          <TouchableOpacity style={styles.addBtn} activeOpacity={0.8}>
            <Ionicons name="add" size={16} color={PURPLE} />
            <Text style={styles.addBtnText}>Add Linked Patient</Text>
          </TouchableOpacity>
        </View>

        {/* Security */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Security</Text>
          <View style={styles.secRow}>
            <View style={{ flex: 1 }}>
              <Text style={styles.secLabel}>Password</Text>
              <Text style={styles.secSub}>Last changed 90 days ago</Text>
            </View>
            <TouchableOpacity style={styles.secBtn} activeOpacity={0.8}>
              <Text style={styles.secBtnText}>Change Password</Text>
            </TouchableOpacity>
          </View>
          <View style={styles.sep} />
          <View style={styles.secRow}>
            <View style={{ flex: 1 }}>
              <Text style={styles.secLabel}>Two-Factor Authentication</Text>
              <Text style={styles.secSub}>Not enabled</Text>
            </View>
            <TouchableOpacity style={[styles.secBtn, { backgroundColor: '#D1FAE5', borderColor: GREEN }]} activeOpacity={0.8}>
              <Text style={[styles.secBtnText, { color: GREEN }]}>Enable 2FA</Text>
            </TouchableOpacity>
          </View>
        </View>

        {/* Change password form */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Change Password</Text>
          <View style={styles.formGroup}>
            <Text style={styles.formLabel}>Current Password</Text>
            <TextInput style={styles.input} secureTextEntry placeholder="••••••••" placeholderTextColor="#9CA3AF" />
          </View>
          <View style={styles.formGroup}>
            <Text style={styles.formLabel}>New Password</Text>
            <TextInput style={styles.input} secureTextEntry placeholder="••••••••" placeholderTextColor="#9CA3AF" />
          </View>
          <View style={styles.formGroup}>
            <Text style={styles.formLabel}>Confirm New Password</Text>
            <TextInput style={styles.input} secureTextEntry placeholder="••••••••" placeholderTextColor="#9CA3AF" />
          </View>
          <TouchableOpacity style={styles.saveBtn} activeOpacity={0.8}>
            <Text style={styles.saveBtnText}>Update Password</Text>
          </TouchableOpacity>
        </View>

        {/* Danger zone */}
        <View style={[styles.card, styles.dangerCard]}>
          <Text style={[styles.cardTitle, { color: RED }]}>Danger Zone</Text>
          <Text style={styles.dangerText}>These actions are irreversible. Proceed with caution.</Text>
          <TouchableOpacity style={styles.dangerBtn} activeOpacity={0.8}>
            <Ionicons name="trash-outline" size={15} color={RED} />
            <Text style={styles.dangerBtnText}>Deactivate Account</Text>
          </TouchableOpacity>
        </View>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll:     { flex: 1, backgroundColor: BG },
  container:  { paddingBottom: 48 },

  hero:        { backgroundColor: '#0D1B2E', padding: 32, paddingBottom: 28 },
  heroOverlay: { flexDirection: 'row', alignItems: 'center', gap: 20 },
  avatarWrap:  { position: 'relative' },
  avatar:      { width: 80, height: 80, borderRadius: 40, borderWidth: 3, borderColor: WHITE },
  avatarText:  { color: WHITE, fontSize: 26, fontWeight: '700' },
  avatarEdit:  {
    position: 'absolute', bottom: 0, right: 0,
    width: 26, height: 26, borderRadius: 13,
    backgroundColor: PURPLE, borderWidth: 2, borderColor: WHITE,
    alignItems: 'center', justifyContent: 'center',
  },
  heroName:    { fontSize: 22, fontWeight: '700', color: WHITE },
  heroRole:    { fontSize: 13, color: '#94A3B8', marginTop: 4 },
  verifiedRow: { flexDirection: 'row', alignItems: 'center', gap: 5, marginTop: 6 },
  verifiedText:{ fontSize: 12, color: GREEN, fontWeight: '600' },

  body:      { padding: 24, gap: 20 },
  card:      { backgroundColor: WHITE, borderRadius: 14, borderWidth: 1, borderColor: BORDER, padding: 20, gap: 0 },
  cardHead:  { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 },
  cardTitle: { fontSize: 16, fontWeight: '700', color: TEXT },
  cardSub:   { fontSize: 12, color: GRAY, marginTop: 2, marginBottom: 16 },
  editBtn:   { flexDirection: 'row', alignItems: 'center', gap: 4, borderWidth: 1, borderColor: BORDER, borderRadius: 8, paddingHorizontal: 10, paddingVertical: 5 },
  editBtnText:{ fontSize: 12, color: PURPLE, fontWeight: '500' },

  infoRow:   { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingVertical: 12 },
  infoLabel: { fontSize: 13, color: GRAY },
  infoValue: { fontSize: 13, fontWeight: '600', color: TEXT },
  sep:       { height: 1, backgroundColor: BORDER },

  depRow:      { flexDirection: 'row', alignItems: 'center', gap: 12, paddingVertical: 12 },
  depAvatarWrap:{ position: 'relative' },
  depAvatar:   { width: 48, height: 48, borderRadius: 24, backgroundColor: BORDER },
  depDot:      { width: 11, height: 11, borderRadius: 6, borderWidth: 2, borderColor: WHITE, position: 'absolute', bottom: 1, right: 1 },
  depName:     { fontSize: 14, fontWeight: '700', color: TEXT },
  depRel:      { fontSize: 12, color: PURPLE, fontWeight: '500', marginTop: 2 },
  depMetaRow:  { flexDirection: 'row', alignItems: 'center', gap: 10, marginTop: 4 },
  accessBadge: { backgroundColor: PURPLE_BG, borderRadius: 20, paddingHorizontal: 8, paddingVertical: 2 },
  accessText:  { fontSize: 11, color: PURPLE, fontWeight: '600' },
  depExpiry:   { fontSize: 11, color: GRAY },
  manageBtn:   { borderWidth: 1, borderColor: BORDER, borderRadius: 8, paddingHorizontal: 12, paddingVertical: 6 },
  manageBtnText:{ fontSize: 12, color: TEXT, fontWeight: '500' },
  addBtn:      { flexDirection: 'row', alignItems: 'center', gap: 6, justifyContent: 'center', marginTop: 16, borderWidth: 1, borderColor: PURPLE, borderRadius: 10, paddingVertical: 10 },
  addBtnText:  { fontSize: 13, color: PURPLE, fontWeight: '600' },
  emptyText:   { fontSize: 13, color: GRAY, paddingVertical: 8 },

  secRow:      { flexDirection: 'row', alignItems: 'center', paddingVertical: 12 },
  secLabel:    { fontSize: 14, fontWeight: '600', color: TEXT },
  secSub:      { fontSize: 12, color: GRAY, marginTop: 2 },
  secBtn:      { borderWidth: 1, borderColor: BORDER, borderRadius: 8, paddingHorizontal: 14, paddingVertical: 7 },
  secBtnText:  { fontSize: 12, color: TEXT, fontWeight: '500' },

  formGroup:  { gap: 6, marginTop: 14 },
  formLabel:  { fontSize: 13, fontWeight: '600', color: TEXT },
  input:      { borderWidth: 1, borderColor: BORDER, borderRadius: 10, paddingHorizontal: 12, paddingVertical: 10, fontSize: 14, color: TEXT } as any,
  saveBtn:    { backgroundColor: PURPLE, borderRadius: 10, paddingVertical: 12, alignItems: 'center', marginTop: 16 },
  saveBtnText:{ fontSize: 14, color: WHITE, fontWeight: '700' },

  dangerCard: { borderColor: '#FECACA' },
  dangerText: { fontSize: 13, color: GRAY, marginBottom: 14 },
  dangerBtn:  { flexDirection: 'row', alignItems: 'center', gap: 6, borderWidth: 1, borderColor: RED, borderRadius: 10, paddingVertical: 10, paddingHorizontal: 14, alignSelf: 'flex-start' },
  dangerBtnText:{ fontSize: 13, color: RED, fontWeight: '600' },
});
