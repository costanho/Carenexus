import { Ionicons } from '@expo/vector-icons';
import { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Image,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  useWindowDimensions,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { api } from '@/lib/auth/auth.interceptor';
import { type CaregiverDependent } from '@/hooks/use-caregiver-dependents';

const WHITE        = '#FFFFFF';
const TEXT         = '#111827';
const GRAY         = '#6B7280';
const GRAY2        = '#9CA3AF';
const BORDER       = '#E5E7EB';
const BG           = '#F9FAFB';
const PURPLE       = '#7C3AED';
const PURPLE_BG    = '#F5F3FF';
const PURPLE_LIGHT = '#EDE9FE';
const GREEN        = '#10B981';
const ORANGE       = '#F59E0B';
const RED          = '#EF4444';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type AccessLevel  = 'FULL_ACCESS' | 'VIEW_ONLY' | 'EDIT_ONLY' | 'CUSTOM';
type Gender       = 'MALE' | 'FEMALE' | 'OTHER';
type HealthStat   = 'STABLE' | 'MONITOR' | 'CRITICAL';
type BloodType    = 'A+' | 'A-' | 'B+' | 'B-' | 'O+' | 'O-' | 'AB+' | 'AB-';

interface PatientDetail {
  patient_id: number; user_id: number;
  date_of_birth: string; gender: string; blood_type: string;
  allergies: string[]; chronic_conditions: string[];
  health_status: string; created_at: string;
  first_name: string; last_name: string; full_name: string;
  email: string; phone: string; avatar_url: string | null;
}

// ─── Scale ────────────────────────────────────────────────────────────────────

function useScale() {
  const { width, height } = useWindowDimensions();
  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));
  return { s, sh };
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

const AVATAR_COLORS = ['#4F46E5','#0D9488','#F59E0B','#EF4444','#8B5CF6','#10B981','#0EA5E9'];
function avatarColor(name: string) {
  let h = 0;
  for (let i = 0; i < (name?.length ?? 0); i++) h = (h * 31 + name.charCodeAt(i)) & 0xffff;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}
function initials(name: string) {
  return (name ?? '?').split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase();
}
function calcAge(dob: string | null | undefined) {
  if (!dob) return '—';
  return `${Math.floor((Date.now() - new Date(dob).getTime()) / (365.25 * 24 * 3600 * 1000))} yrs`;
}
function formatDate(d: string | null | undefined) {
  if (!d) return '—';
  const dt = new Date(d);
  if (isNaN(dt.getTime())) return '—';
  return `${dt.getDate()} ${['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'][dt.getMonth()]} ${dt.getFullYear()}`;
}
function capitalize(s: string | null | undefined) {
  if (!s) return '—';
  return s.charAt(0).toUpperCase() + s.slice(1).toLowerCase();
}
function healthColors(status: string | null) {
  if (status === 'STABLE')   return { bg: '#D1FAE5', text: GREEN };
  if (status === 'MONITOR')  return { bg: '#FEF3C7', text: ORANGE };
  if (status === 'CRITICAL') return { bg: '#FEE2E2', text: RED };
  return { bg: '#F3F4F6', text: GRAY };
}
function dotColor(status: string | null) {
  if (status === 'STABLE')   return GREEN;
  if (status === 'MONITOR')  return ORANGE;
  if (status === 'CRITICAL') return RED;
  return GRAY2;
}
function accessColors(level: string) {
  if (level === 'FULL_ACCESS') return { bg: '#D1FAE5', text: GREEN };
  if (level === 'VIEW_ONLY')   return { bg: PURPLE_BG, text: PURPLE };
  if (level === 'EDIT_ONLY')   return { bg: '#FEF3C7', text: ORANGE };
  return { bg: '#F3F4F6', text: GRAY };
}
function accessLabel(level: string) {
  if (level === 'FULL_ACCESS') return 'Full Access';
  if (level === 'VIEW_ONLY')   return 'View Only';
  if (level === 'EDIT_ONLY')   return 'Edit Only';
  if (level === 'CUSTOM')      return 'Custom';
  return level;
}
function permissionsForLevel(level: AccessLevel) {
  if (level === 'FULL_ACCESS') return { can_view_records:1,can_view_appointments:1,can_view_medications:1,can_view_lab_results:1,can_view_imaging:1,can_message_care_team:1,can_view_consultations:1,can_schedule_appointments:1,can_manage_care_plan:1 };
  if (level === 'VIEW_ONLY')   return { can_view_records:1,can_view_appointments:1,can_view_medications:1,can_view_lab_results:1,can_view_imaging:0,can_message_care_team:0,can_view_consultations:1,can_schedule_appointments:0,can_manage_care_plan:0 };
  if (level === 'EDIT_ONLY')   return { can_view_records:1,can_view_appointments:1,can_view_medications:1,can_view_lab_results:1,can_view_imaging:1,can_message_care_team:1,can_view_consultations:1,can_schedule_appointments:1,can_manage_care_plan:0 };
  return {};
}

// ─── Main ─────────────────────────────────────────────────────────────────────

export default function PatientProxyMobileProfile({
  dependent,
  onBack,
}: {
  dependent: CaregiverDependent;
  onBack: () => void;
}) {
  const { s }  = useScale();
  const insets = useSafeAreaInsets();

  const [patient,    setPatient]    = useState<PatientDetail | null>(null);
  const [loading,    setLoading]    = useState(true);
  const [fetchError, setFetchError] = useState<string | null>(null);

  const [editingPersonal, setEditingPersonal] = useState(false);
  const [editingHealth,   setEditingHealth]   = useState(false);
  const [editingAccess,   setEditingAccess]   = useState(false);
  const [saving,          setSaving]          = useState(false);
  const [saveError,       setSaveError]       = useState('');

  const [pFirst, setPFirst] = useState('');
  const [pLast,  setPLast]  = useState('');
  const [pPhone, setPPhone] = useState('');

  const [hDob,        setHDob]        = useState('');
  const [hGender,     setHGender]     = useState<Gender>('MALE');
  const [hBlood,      setHBlood]      = useState<BloodType>('A+');
  const [hStatus,     setHStatus]     = useState<HealthStat>('STABLE');
  const [hAllergies,  setHAllergies]  = useState<string[]>([]);
  const [hConditions, setHConditions] = useState<string[]>([]);

  const [aLevel,  setALevel]  = useState<AccessLevel>('FULL_ACCESS');
  const [aExpires,setAExpires]= useState('');
  const [aPerms,  setAPerms]  = useState({
    can_view_records:1, can_view_appointments:1, can_view_medications:1,
    can_view_lab_results:1, can_view_imaging:1, can_message_care_team:1,
    can_view_consultations:1, can_schedule_appointments:1, can_manage_care_plan:1,
  });

  useEffect(() => {
    setLoading(true);
    api.get<PatientDetail>(`/api/patients/${dependent.patient_id}`)
      .then(({ data }) => {
        setPatient(data);
        setPFirst(data.first_name ?? '');
        setPLast(data.last_name ?? '');
        setPPhone(data.phone ?? '');
        setHDob(data.date_of_birth ?? '');
        setHGender((data.gender as Gender) ?? 'MALE');
        setHBlood((data.blood_type as BloodType) ?? 'A+');
        setHStatus((data.health_status as HealthStat) ?? 'STABLE');
        setHAllergies(data.allergies ?? []);
        setHConditions(data.chronic_conditions ?? []);
      })
      .catch(() => setFetchError('Could not load full details. Showing available data.'))
      .finally(() => setLoading(false));

    setALevel(dependent.access_level as AccessLevel);
    setAExpires(dependent.expires_at ?? '');
    setAPerms({
      can_view_records:          dependent.can_view_records,
      can_view_appointments:     dependent.can_view_appointments,
      can_view_medications:      dependent.can_view_medications,
      can_view_lab_results:      dependent.can_view_lab_results,
      can_view_imaging:          dependent.can_view_imaging,
      can_message_care_team:     dependent.can_message_care_team,
      can_view_consultations:    dependent.can_view_consultations,
      can_schedule_appointments: dependent.can_schedule_appointments,
      can_manage_care_plan:      dependent.can_manage_care_plan,
    });
  }, [dependent.patient_id]);

  function changeAccessLevel(level: AccessLevel) {
    setALevel(level);
    if (level !== 'CUSTOM') setAPerms(prev => ({ ...prev, ...permissionsForLevel(level) }));
  }

  async function savePersonal() {
    if (!patient) return;
    setSaving(true); setSaveError('');
    try {
      await api.patch(`/api/users/${patient.user_id}`, { first_name: pFirst.trim(), last_name: pLast.trim(), phone: pPhone.trim() });
      const { data } = await api.get<PatientDetail>(`/api/patients/${dependent.patient_id}`);
      setPatient(data);
      setEditingPersonal(false);
    } catch { setSaveError('Failed to save personal details.'); }
    finally { setSaving(false); }
  }

  async function saveHealth() {
    setSaving(true); setSaveError('');
    try {
      const { data } = await api.patch<PatientDetail>(`/api/patients/${dependent.patient_id}`, {
        date_of_birth: hDob, gender: hGender, blood_type: hBlood,
        health_status: hStatus, allergies: hAllergies, chronic_conditions: hConditions,
      });
      setPatient(data);
      setEditingHealth(false);
    } catch { setSaveError('Failed to save health profile.'); }
    finally { setSaving(false); }
  }

  async function saveAccess() {
    setSaving(true); setSaveError('');
    try {
      await api.patch(`/api/dependent-access/${dependent.access_id}`, {
        access_level:              aLevel,
        expires_at:                aExpires.trim() || null,
        can_view_records:          aPerms.can_view_records,
        can_view_appointments:     aPerms.can_view_appointments,
        can_view_medications:      aPerms.can_view_medications,
        can_view_lab_results:      aPerms.can_view_lab_results,
        can_view_imaging:          aPerms.can_view_imaging,
        can_message_care_team:     aPerms.can_message_care_team,
        can_view_consultations:    aPerms.can_view_consultations,
        can_schedule_appointments: aPerms.can_schedule_appointments,
        can_manage_care_plan:      aPerms.can_manage_care_plan,
      });
      setEditingAccess(false);
    } catch { setSaveError('Failed to save access settings.'); }
    finally { setSaving(false); }
  }

  const name       = patient?.full_name ?? dependent.patient_name ?? 'Unknown Patient';
  const avatarUri  = patient?.avatar_url ?? dependent.patient_avatar;
  const status     = patient?.health_status ?? dependent.patient_health_status ?? null;
  const dob        = patient?.date_of_birth ?? dependent.patient_date_of_birth;
  const gender     = patient?.gender ?? dependent.patient_gender;
  const bloodType  = patient?.blood_type ?? dependent.patient_blood_type;
  const email      = patient?.email ?? dependent.patient_email;
  const phone      = patient?.phone;
  const createdAt  = patient?.created_at;
  const allergies  = patient?.allergies ?? [];
  const conditions = patient?.chronic_conditions ?? [];

  const sc = healthColors(status);
  const dc = dotColor(status);
  const lc = accessColors(dependent.access_level);
  const alc = accessColors(aLevel);

  const PERMS: { key: keyof typeof aPerms; icon: IoniconsName; label: string }[] = [
    { key: 'can_view_records',          icon: 'document-text-outline', label: 'View Records'    },
    { key: 'can_view_appointments',     icon: 'calendar-outline',      label: 'Appointments'    },
    { key: 'can_view_medications',      icon: 'medical-outline',       label: 'Medications'     },
    { key: 'can_view_lab_results',      icon: 'flask-outline',         label: 'Lab Results'     },
    { key: 'can_view_imaging',          icon: 'scan-outline',          label: 'Imaging'         },
    { key: 'can_message_care_team',     icon: 'chatbox-outline',       label: 'Message Team'    },
    { key: 'can_view_consultations',    icon: 'people-outline',        label: 'Consultations'   },
    { key: 'can_schedule_appointments', icon: 'add-circle-outline',    label: 'Schedule'        },
    { key: 'can_manage_care_plan',      icon: 'clipboard-outline',     label: 'Care Plan'       },
  ];

  // ── reusable section shell ───────────────────────────────────────────────────
  function Card({ title, onEdit, children }: { title: string; onEdit?: () => void; children: React.ReactNode }) {
    return (
      <View style={[styles.card, { borderRadius: s(14), padding: s(14), marginBottom: s(12), gap: s(10) }]}>
        <View style={styles.cardHeader}>
          <Text style={[styles.cardTitle, { fontSize: s(14) }]}>{title}</Text>
          {onEdit && (
            <TouchableOpacity style={[styles.editBtn, { borderRadius: s(8), paddingHorizontal: s(10), paddingVertical: s(5), gap: s(4) }]} onPress={onEdit} activeOpacity={0.7}>
              <Ionicons name="pencil-outline" size={s(13)} color={PURPLE} />
              <Text style={[styles.editBtnText, { fontSize: s(12) }]}>Edit</Text>
            </TouchableOpacity>
          )}
        </View>
        {children}
      </View>
    );
  }

  function MInput({ value, onChange, placeholder, keyboardType }: {
    value: string; onChange: (v: string) => void; placeholder?: string; keyboardType?: any;
  }) {
    return (
      <TextInput
        style={[styles.input, { fontSize: s(14), borderRadius: s(10), paddingHorizontal: s(12), paddingVertical: s(10) }]}
        value={value} onChangeText={onChange} placeholder={placeholder}
        placeholderTextColor={GRAY2} keyboardType={keyboardType} autoCapitalize="none"
      />
    );
  }

  function MLabel({ text, required }: { text: string; required?: boolean }) {
    return <Text style={[styles.label, { fontSize: s(13), marginBottom: s(4) }]}>{text}{required && <Text style={{ color: RED }}> *</Text>}</Text>;
  }

  function MChipRow<T extends string>({ options, value, onChange }: {
    options: { value: T; label: string }[]; value: T; onChange: (v: T) => void;
  }) {
    return (
      <View style={[styles.chipRow, { gap: s(7) }]}>
        {options.map(o => (
          <TouchableOpacity key={o.value}
            style={[styles.chip, { borderRadius: s(8), paddingHorizontal: s(11), paddingVertical: s(7) }, value === o.value && styles.chipActive]}
            onPress={() => onChange(o.value)} activeOpacity={0.7}
          >
            <Text style={[styles.chipText, { fontSize: s(12) }, value === o.value && styles.chipTextActive]}>{o.label}</Text>
          </TouchableOpacity>
        ))}
      </View>
    );
  }

  function MTagInput({ tags, onAdd, onRemove, placeholder }: {
    tags: string[]; onAdd: (t: string) => void; onRemove: (i: number) => void; placeholder?: string;
  }) {
    const [text, setText] = useState('');
    function add() { const t = text.trim(); if (t && !tags.includes(t)) { onAdd(t); setText(''); } }
    return (
      <View style={{ gap: s(7) }}>
        <View style={[styles.chipRow, { gap: s(6) }]}>
          {tags.map((t, i) => (
            <View key={i} style={[styles.tagChip, { borderRadius: s(12), paddingHorizontal: s(8), paddingVertical: s(4), gap: s(5) }]}>
              <Text style={[styles.tagChipText, { fontSize: s(11) }]}>{t}</Text>
              <TouchableOpacity onPress={() => onRemove(i)} hitSlop={{ top:6,right:6,bottom:6,left:6 }}>
                <Ionicons name="close" size={s(11)} color={PURPLE} />
              </TouchableOpacity>
            </View>
          ))}
        </View>
        <View style={[styles.tagInputRow, { gap: s(7) }]}>
          <TextInput
            style={[styles.tagInput, { flex:1, fontSize:s(13), borderRadius:s(9), paddingHorizontal:s(11), paddingVertical:s(9) }]}
            value={text} onChangeText={setText} placeholder={placeholder ?? 'Type and press Add…'}
            placeholderTextColor={GRAY2} onSubmitEditing={add} returnKeyType="done" autoCapitalize="words"
          />
          <TouchableOpacity style={[styles.tagAddBtn, { borderRadius:s(9), paddingHorizontal:s(12), paddingVertical:s(9) }]} onPress={add} activeOpacity={0.7}>
            <Text style={[styles.tagAddBtnText, { fontSize:s(13) }]}>Add</Text>
          </TouchableOpacity>
        </View>
      </View>
    );
  }

  function MPermSwitch({ label, value, onChange }: { label: string; value: boolean; onChange: (v: boolean) => void }) {
    return (
      <TouchableOpacity style={[styles.permRow, { paddingVertical: s(10) }]} onPress={() => onChange(!value)} activeOpacity={0.7}>
        <Text style={[styles.permLabel, { fontSize: s(13) }]}>{label}</Text>
        <View style={[styles.toggle, { width: s(38), height: s(21), borderRadius: s(11) }, value && styles.toggleOn]}>
          <View style={[styles.toggleThumb, { width: s(17), height: s(17), borderRadius: s(9) }, value && styles.toggleThumbOn]} />
        </View>
      </TouchableOpacity>
    );
  }

  function SaveRow({ onSave, onCancel }: { onSave: () => void; onCancel: () => void }) {
    return (
      <View style={[styles.saveRow, { gap: s(10), marginTop: s(6) }]}>
        <TouchableOpacity style={[styles.cancelBtn, { borderRadius: s(10), paddingHorizontal: s(16), paddingVertical: s(11), flex: 1 }]} onPress={onCancel} activeOpacity={0.7}>
          <Text style={[styles.cancelBtnText, { fontSize: s(14) }]}>Cancel</Text>
        </TouchableOpacity>
        <TouchableOpacity style={[styles.saveBtn, { borderRadius: s(10), paddingHorizontal: s(16), paddingVertical: s(11), flex: 1 }, saving && { opacity: 0.5 }]} onPress={onSave} disabled={saving} activeOpacity={0.8}>
          {saving
            ? <ActivityIndicator size="small" color={WHITE} />
            : <Text style={[styles.saveBtnText, { fontSize: s(14) }]}>Save</Text>
          }
        </TouchableOpacity>
      </View>
    );
  }

  return (
    <ScrollView
      style={styles.scroll}
      contentContainerStyle={{ padding: s(14), paddingBottom: insets.bottom + s(24) }}
      showsVerticalScrollIndicator={false}
      keyboardShouldPersistTaps="handled"
    >
      {/* Back header */}
      <View style={[styles.backHeader, { marginBottom: s(14) }]}>
        <TouchableOpacity style={[styles.backBtn, { gap: s(5) }]} onPress={onBack} activeOpacity={0.7}>
          <Ionicons name="arrow-back-outline" size={s(18)} color={PURPLE} />
          <Text style={[styles.backText, { fontSize: s(14) }]}>Back</Text>
        </TouchableOpacity>
        <Text style={[styles.headerTitle, { fontSize: s(16) }]}>Patient Profile</Text>
        <View style={{ width: s(55) }} />
      </View>

      {/* Fetch error */}
      {fetchError && (
        <View style={[styles.errBanner, { borderRadius: s(10), padding: s(12), gap: s(7), marginBottom: s(12) }]}>
          <Ionicons name="alert-circle-outline" size={s(14)} color={RED} />
          <Text style={[styles.errBannerText, { fontSize: s(12) }]}>{fetchError}</Text>
        </View>
      )}
      {saveError ? (
        <View style={[styles.errBanner, { borderRadius: s(10), padding: s(12), gap: s(7), marginBottom: s(12) }]}>
          <Ionicons name="alert-circle-outline" size={s(14)} color={RED} />
          <Text style={[styles.errBannerText, { fontSize: s(12) }]}>{saveError}</Text>
        </View>
      ) : null}

      {/* Hero */}
      <View style={[styles.card, { borderRadius: s(14), padding: s(14), marginBottom: s(12) }]}>
        <View style={[styles.heroRow, { gap: s(12) }]}>
          <View>
            {avatarUri
              ? <Image source={{ uri: avatarUri }} style={{ width: s(68), height: s(68), borderRadius: s(34), backgroundColor: BORDER }} />
              : <View style={{ width: s(68), height: s(68), borderRadius: s(34), backgroundColor: avatarColor(name), alignItems: 'center', justifyContent: 'center' }}>
                  <Text style={{ color: WHITE, fontSize: s(20), fontWeight: '700' }}>{initials(name)}</Text>
                </View>
            }
            <View style={{ width: s(11), height: s(11), borderRadius: s(6), backgroundColor: dc, borderWidth: 2, borderColor: WHITE, position: 'absolute', bottom: s(2), right: s(2) }} />
          </View>
          <View style={{ flex: 1, gap: s(3) }}>
            <View style={[styles.heroTopRow, { gap: s(6) }]}>
              <Text style={[styles.heroName, { fontSize: s(17) }]} numberOfLines={1}>{name}</Text>
              <View style={[styles.healthPill, { backgroundColor: sc.bg, borderRadius: s(12), paddingHorizontal: s(8), paddingVertical: s(3) }]}>
                <Text style={{ fontSize: s(11), fontWeight: '600', color: sc.text }}>{capitalize(status)}</Text>
              </View>
            </View>
            <Text style={[styles.heroRel, { fontSize: s(12) }]}>{capitalize(dependent.relationship)}</Text>
            {loading
              ? <ActivityIndicator size="small" color={PURPLE} style={{ marginTop: s(4) }} />
              : <View style={{ gap: s(2), marginTop: s(2) }}>
                  {email ? <View style={[styles.metaRow, { gap: s(4) }]}><Ionicons name="mail-outline" size={s(11)} color={GRAY} /><Text style={[styles.metaText, { fontSize: s(11) }]} numberOfLines={1}>{email}</Text></View> : null}
                  {phone ? <View style={[styles.metaRow, { gap: s(4) }]}><Ionicons name="call-outline" size={s(11)} color={GRAY} /><Text style={[styles.metaText, { fontSize: s(11) }]}>{phone}</Text></View> : null}
                </View>
            }
          </View>
        </View>
      </View>

      {/* Overview 2×2 */}
      <View style={[styles.card, { borderRadius: s(14), padding: s(12), marginBottom: s(12) }]}>
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: s(8) }}>
          {[
            { label: 'Age',          value: calcAge(dob)       },
            { label: 'Gender',       value: capitalize(gender)  },
            { label: 'Blood Type',   value: bloodType ?? '—'   },
            { label: 'Member Since', value: createdAt ? String(new Date(createdAt).getFullYear()) : '—' },
          ].map(t => (
            <View key={t.label} style={[styles.overviewTile, { flex: 1, minWidth: '45%', borderRadius: s(10), padding: s(10) }]}>
              <Text style={[styles.overviewLabel, { fontSize: s(10) }]}>{t.label}</Text>
              <Text style={[styles.overviewValue, { fontSize: s(15) }]}>{t.value}</Text>
            </View>
          ))}
        </View>
      </View>

      {/* ── Personal Details ── */}
      <Card title="Personal Details" onEdit={!editingPersonal ? () => { setSaveError(''); setEditingPersonal(true); } : undefined}>
        {editingPersonal ? (
          <View style={{ gap: s(10) }}>
            <View style={{ flexDirection: 'row', gap: s(10) }}>
              <View style={{ flex: 1 }}>
                <MLabel text="First Name" required />
                <MInput value={pFirst} onChange={setPFirst} placeholder="First name" />
              </View>
              <View style={{ flex: 1 }}>
                <MLabel text="Last Name" required />
                <MInput value={pLast} onChange={setPLast} placeholder="Last name" />
              </View>
            </View>
            <MLabel text="Phone" />
            <MInput value={pPhone} onChange={setPPhone} placeholder="+263…" keyboardType="phone-pad" />
            <SaveRow onSave={savePersonal} onCancel={() => setEditingPersonal(false)} />
          </View>
        ) : (
          <View style={{ gap: s(8) }}>
            {[
              { label: 'Full Name',    value: name        },
              { label: 'Email',        value: email ?? '—' },
              { label: 'Phone',        value: phone ?? '—' },
              { label: 'Member Since', value: createdAt ? String(new Date(createdAt).getFullYear()) : '—' },
            ].map((row, i, arr) => (
              <View key={row.label}>
                <View style={styles.infoRow}>
                  <Text style={[styles.infoLabel, { fontSize: s(12) }]}>{row.label}</Text>
                  <Text style={[styles.infoVal, { fontSize: s(13), flex: 1.5, textAlign: 'right' }]}>{row.value}</Text>
                </View>
                {i < arr.length - 1 && <View style={[styles.sep, { marginTop: s(8) }]} />}
              </View>
            ))}
          </View>
        )}
      </Card>

      {/* ── Health Profile ── */}
      <Card title="Health Profile" onEdit={!editingHealth ? () => { setSaveError(''); setEditingHealth(true); } : undefined}>
        {editingHealth ? (
          <View style={{ gap: s(12) }}>
            <View>
              <MLabel text="Date of Birth" required />
              <MInput value={hDob} onChange={setHDob} placeholder="YYYY-MM-DD" />
            </View>
            <View>
              <MLabel text="Gender" required />
              <MChipRow<Gender> options={[{value:'MALE',label:'Male'},{value:'FEMALE',label:'Female'},{value:'OTHER',label:'Other'}]} value={hGender} onChange={setHGender} />
            </View>
            <View>
              <MLabel text="Blood Type" required />
              <MChipRow<BloodType> options={(['A+','A-','B+','B-','O+','O-','AB+','AB-'] as BloodType[]).map(v=>({value:v,label:v}))} value={hBlood} onChange={setHBlood} />
            </View>
            <View>
              <MLabel text="Health Status" />
              <MChipRow<HealthStat> options={[{value:'STABLE',label:'Stable'},{value:'MONITOR',label:'Monitor'},{value:'CRITICAL',label:'Critical'}]} value={hStatus} onChange={setHStatus} />
            </View>
            <View>
              <MLabel text="Known Allergies" />
              <MTagInput tags={hAllergies} onAdd={t=>setHAllergies(a=>[...a,t])} onRemove={i=>setHAllergies(a=>a.filter((_,idx)=>idx!==i))} placeholder="e.g. Penicillin…" />
            </View>
            <View>
              <MLabel text="Chronic Conditions" />
              <MTagInput tags={hConditions} onAdd={t=>setHConditions(c=>[...c,t])} onRemove={i=>setHConditions(c=>c.filter((_,idx)=>idx!==i))} placeholder="e.g. Hypertension…" />
            </View>
            <SaveRow onSave={saveHealth} onCancel={() => setEditingHealth(false)} />
          </View>
        ) : (
          <View style={{ gap: s(10) }}>
            <View style={[styles.metaRow, { gap: s(6) }]}>
              <Ionicons name="heart-outline" size={s(15)} color={dc} />
              <Text style={{ fontSize: s(13), fontWeight: '600', color: dc }}>{capitalize(status)}</Text>
            </View>
            <View style={styles.sep} />
            <Text style={[styles.subLabel, { fontSize: s(11) }]}>Known Allergies</Text>
            {allergies.length > 0
              ? <View style={[styles.chipRow, { gap: s(6) }]}>{allergies.map(a=><View key={a} style={[styles.allergyChip,{borderRadius:s(12),paddingHorizontal:s(8),paddingVertical:s(4)}]}><Text style={{fontSize:s(11),fontWeight:'600',color:PURPLE}}>{a}</Text></View>)}</View>
              : <Text style={[styles.noneText,{fontSize:s(12)}]}>None recorded</Text>
            }
            <View style={styles.sep} />
            <Text style={[styles.subLabel, { fontSize: s(11) }]}>Chronic Conditions</Text>
            {conditions.length > 0
              ? <View style={[styles.chipRow, { gap: s(6) }]}>{conditions.map(c=><View key={c} style={[styles.condChip,{borderRadius:s(12),paddingHorizontal:s(8),paddingVertical:s(4)}]}><Text style={{fontSize:s(11),fontWeight:'600',color:ORANGE}}>{c}</Text></View>)}</View>
              : <Text style={[styles.noneText,{fontSize:s(12)}]}>None recorded</Text>
            }
          </View>
        )}
      </Card>

      {/* ── Proxy Access ── */}
      <Card title="Your Access" onEdit={!editingAccess ? () => { setSaveError(''); setEditingAccess(true); } : undefined}>
        {editingAccess ? (
          <View style={{ gap: s(12) }}>
            <View>
              <MLabel text="Access Level" required />
              <View style={{ gap: s(8) }}>
                {([
                  { value:'FULL_ACCESS', label:'Full Access',  sub:'All permissions enabled',         color:GREEN  },
                  { value:'VIEW_ONLY',   label:'View Only',    sub:'Read records, no actions',        color:PURPLE },
                  { value:'EDIT_ONLY',   label:'Edit Only',    sub:'View + message + schedule',       color:ORANGE },
                  { value:'CUSTOM',      label:'Custom',        sub:'Set individual permissions below',color:GRAY  },
                ] as {value:AccessLevel;label:string;sub:string;color:string}[]).map(opt=>(
                  <TouchableOpacity
                    key={opt.value}
                    style={[styles.accessCard, { borderRadius:s(12), padding:s(12), gap:s(10) }, aLevel===opt.value && { borderColor:opt.color, borderWidth:2 }]}
                    onPress={() => changeAccessLevel(opt.value)} activeOpacity={0.7}
                  >
                    <View style={[styles.accessRadio, { width:s(18), height:s(18), borderRadius:s(9) }, aLevel===opt.value && { borderColor:opt.color }]}>
                      {aLevel===opt.value && <View style={[styles.accessRadioDot, { width:s(8), height:s(8), borderRadius:s(4), backgroundColor:opt.color }]} />}
                    </View>
                    <View style={{ flex: 1 }}>
                      <Text style={[styles.accessCardLabel, { fontSize:s(13) }, aLevel===opt.value && { color:opt.color }]}>{opt.label}</Text>
                      <Text style={[styles.accessCardSub, { fontSize:s(11) }]}>{opt.sub}</Text>
                    </View>
                  </TouchableOpacity>
                ))}
              </View>
            </View>

            {aLevel === 'CUSTOM' && (
              <View style={[styles.permBox, { borderRadius: s(12), padding: s(12) }]}>
                <Text style={[styles.permBoxTitle, { fontSize: s(13), marginBottom: s(8) }]}>Individual Permissions</Text>
                {PERMS.map(p => (
                  <MPermSwitch key={p.key} label={p.label} value={!!aPerms[p.key]} onChange={v => setAPerms(prev => ({ ...prev, [p.key]: v ? 1 : 0 }))} />
                ))}
              </View>
            )}

            <View>
              <MLabel text="Access Expiry (optional)" />
              <MInput value={aExpires} onChange={setAExpires} placeholder="YYYY-MM-DD" />
              <Text style={[styles.hint, { fontSize: s(11), marginTop: s(3) }]}>Leave blank for no expiry.</Text>
            </View>

            <SaveRow onSave={saveAccess} onCancel={() => setEditingAccess(false)} />
          </View>
        ) : (
          <View style={{ gap: s(8) }}>
            {[
              { label: 'Relationship',   value: capitalize(dependent.relationship) },
              { label: 'Authorized On',  value: formatDate(dependent.authorized_date) },
              { label: 'Expires',        value: dependent.expires_at ? formatDate(dependent.expires_at) : 'No expiry' },
              { label: 'Authorized By',  value: dependent.authorized_by_name ?? '—' },
            ].map((row, i, arr) => (
              <View key={row.label}>
                <View style={styles.infoRow}>
                  <Text style={[styles.infoLabel, { fontSize: s(12) }]}>{row.label}</Text>
                  <Text style={[styles.infoVal, { fontSize: s(12), flex: 1.5, textAlign: 'right' }]}>{row.value}</Text>
                </View>
                {i < arr.length - 1 && <View style={[styles.sep, { marginTop: s(8) }]} />}
              </View>
            ))}
            <View style={styles.sep} />
            <View style={styles.infoRow}>
              <Text style={[styles.infoLabel, { fontSize: s(12) }]}>Access Level</Text>
              <View style={[styles.badge, { backgroundColor: lc.bg, borderRadius: s(12), paddingHorizontal: s(8), paddingVertical: s(4) }]}>
                <Text style={{ fontSize: s(12), fontWeight: '600', color: lc.text }}>{accessLabel(dependent.access_level)}</Text>
              </View>
            </View>
            <View style={[styles.sep, { marginVertical: s(2) }]} />
            <Text style={[styles.subLabel, { fontSize: s(11) }]}>Permissions</Text>
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: s(6) }}>
              {PERMS.map(p => (
                <View key={p.key} style={{ width: '30%', alignItems: 'center', gap: s(4), padding: s(7), backgroundColor: BG, borderRadius: s(10), borderWidth: 1, borderColor: BORDER }}>
                  <View style={{ width: s(26), height: s(26), borderRadius: s(13), backgroundColor: !!dependent[p.key as keyof CaregiverDependent] ? '#D1FAE5' : '#F3F4F6', alignItems: 'center', justifyContent: 'center' }}>
                    <Ionicons name={!!dependent[p.key as keyof CaregiverDependent] ? 'checkmark-circle' : 'close-circle'} size={s(13)} color={!!dependent[p.key as keyof CaregiverDependent] ? GREEN : GRAY2} />
                  </View>
                  <Text style={{ fontSize: s(9), color: TEXT, fontWeight: '500', textAlign: 'center' }} numberOfLines={2}>{p.label}</Text>
                </View>
              ))}
            </View>
          </View>
        )}
      </Card>

      {/* Quick Actions */}
      {(!!dependent.can_message_care_team || !!dependent.can_schedule_appointments || !!dependent.can_view_records) && (
        <View style={[styles.card, { borderRadius: s(14), padding: s(14), gap: s(10) }]}>
          <Text style={[styles.cardTitle, { fontSize: s(14) }]}>Quick Actions</Text>
          <View style={{ gap: s(8) }}>
            {!!dependent.can_message_care_team    && <TouchableOpacity style={[styles.actionBtn, { borderRadius: s(10), paddingVertical: s(11), paddingHorizontal: s(14), gap: s(8) }]} activeOpacity={0.7}><Ionicons name="chatbox-outline" size={s(15)} color={PURPLE} /><Text style={[styles.actionBtnText, { fontSize: s(13) }]}>Message Care Team</Text></TouchableOpacity>}
            {!!dependent.can_schedule_appointments && <TouchableOpacity style={[styles.actionBtn, { borderRadius: s(10), paddingVertical: s(11), paddingHorizontal: s(14), gap: s(8) }]} activeOpacity={0.7}><Ionicons name="calendar-outline" size={s(15)} color={PURPLE} /><Text style={[styles.actionBtnText, { fontSize: s(13) }]}>Schedule Appointment</Text></TouchableOpacity>}
            {!!dependent.can_view_records          && <TouchableOpacity style={[styles.actionBtn, { borderRadius: s(10), paddingVertical: s(11), paddingHorizontal: s(14), gap: s(8) }]} activeOpacity={0.7}><Ionicons name="document-text-outline" size={s(15)} color={PURPLE} /><Text style={[styles.actionBtnText, { fontSize: s(13) }]}>View Records</Text></TouchableOpacity>}
          </View>
        </View>
      )}
    </ScrollView>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  scroll: { flex: 1, backgroundColor: BG },

  backHeader:  { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  backBtn:     { flexDirection: 'row', alignItems: 'center' },
  backText:    { color: PURPLE, fontWeight: '500' },
  headerTitle: { fontWeight: '700', color: TEXT },

  errBanner:     { flexDirection: 'row', alignItems: 'center', backgroundColor: '#FEE2E2', borderWidth: 1, borderColor: '#FECACA' },
  errBannerText: { color: RED, flex: 1 },

  card:       { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER },
  cardHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  cardTitle:  { fontWeight: '700', color: TEXT },
  editBtn:    { flexDirection: 'row', alignItems: 'center', borderWidth: 1, borderColor: BORDER },
  editBtnText:{ color: PURPLE, fontWeight: '600' },

  heroRow:    { flexDirection: 'row', alignItems: 'flex-start' },
  heroTopRow: { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap' },
  heroName:   { fontWeight: '700', color: TEXT, flex: 1 },
  heroRel:    { color: PURPLE, fontWeight: '500' },
  healthPill: {},
  metaRow:    { flexDirection: 'row', alignItems: 'center' },
  metaText:   { color: GRAY, flex: 1 },

  overviewTile:  { backgroundColor: BG, gap: 3 },
  overviewLabel: { color: GRAY2, fontWeight: '500' },
  overviewValue: { fontWeight: '700', color: TEXT },

  sep:      { height: StyleSheet.hairlineWidth, backgroundColor: BORDER },
  subLabel: { fontWeight: '600', color: GRAY, textTransform: 'uppercase', letterSpacing: 0.5 },
  noneText: { color: GRAY2, fontStyle: 'italic' },

  infoRow:   { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  infoLabel: { color: GRAY, fontWeight: '500', flex: 1 },
  infoVal:   { color: TEXT, fontWeight: '600' },
  badge:     {},

  allergyChip: { backgroundColor: PURPLE_BG },
  condChip:    { backgroundColor: '#FEF3C7' },
  chipRow:     { flexDirection: 'row', flexWrap: 'wrap' },

  label:         { fontWeight: '600', color: TEXT },
  input:         { borderWidth: 1, borderColor: BORDER, color: TEXT, backgroundColor: WHITE } as any,
  chip:          { borderWidth: 1, borderColor: BORDER },
  chipActive:    { borderColor: PURPLE, backgroundColor: PURPLE_BG },
  chipText:      { color: GRAY, fontWeight: '500' },
  chipTextActive:{ color: PURPLE, fontWeight: '700' },
  tagChip:       { flexDirection: 'row', alignItems: 'center', backgroundColor: PURPLE_BG },
  tagChipText:   { color: PURPLE, fontWeight: '500' },
  tagInputRow:   { flexDirection: 'row' },
  tagInput:      { borderWidth: 1, borderColor: BORDER, color: TEXT } as any,
  tagAddBtn:     { borderWidth: 1, borderColor: PURPLE, backgroundColor: PURPLE_BG },
  tagAddBtnText: { color: PURPLE, fontWeight: '600' },
  hint:          { color: GRAY2 },

  accessCard:      { flexDirection: 'row', alignItems: 'flex-start', borderWidth: 1, borderColor: BORDER, backgroundColor: WHITE },
  accessRadio:     { borderWidth: 2, borderColor: BORDER, alignItems: 'center', justifyContent: 'center', marginTop: 2 },
  accessRadioDot:  {},
  accessCardLabel: { fontWeight: '700', color: TEXT, marginBottom: 2 },
  accessCardSub:   { color: GRAY },

  permBox:      { backgroundColor: BG, borderWidth: 1, borderColor: BORDER },
  permBoxTitle: { fontWeight: '700', color: TEXT },
  permRow:      { flexDirection: 'row', alignItems: 'center', borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: BORDER },
  permLabel:    { flex: 1, color: TEXT },
  toggle:       { backgroundColor: BORDER, justifyContent: 'center', paddingHorizontal: 2 },
  toggleOn:     { backgroundColor: PURPLE },
  toggleThumb:  { backgroundColor: WHITE } as any,
  toggleThumbOn:{ alignSelf: 'flex-end' },

  saveRow:       { flexDirection: 'row', alignItems: 'center' },
  cancelBtn:     { alignItems: 'center', justifyContent: 'center', borderWidth: 1, borderColor: BORDER, backgroundColor: WHITE },
  cancelBtnText: { color: GRAY, fontWeight: '500' },
  saveBtn:       { alignItems: 'center', justifyContent: 'center', backgroundColor: PURPLE },
  saveBtnText:   { color: WHITE, fontWeight: '700' },

  actionBtn:     { flexDirection: 'row', alignItems: 'center', backgroundColor: PURPLE_BG, borderWidth: 1, borderColor: PURPLE_LIGHT },
  actionBtnText: { color: PURPLE, fontWeight: '600' },
});
