import { Ionicons } from '@expo/vector-icons';
import { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { api } from '@/lib/auth/auth.interceptor';
import { caregiverErrorMessage, caregiverSaveErrorMessage, parseList, type CaregiverDependent } from '@/hooks/use-caregiver-dependents';

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
  userId: number;
  firstName: string; lastName: string; phone: string; email: string;
  role: string; isActive: boolean;
  patientSystemId: string;
  dateOfBirth: string; gender: string; bloodType: string;
  allergies: string; chronicConditions: string;
  healthStatus: string; createdAt: string; updatedAt: string;
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
// Messaging and video calls are NOT part of an access level: only the patient can turn them on (see CommStatusRow).
function permissionsForLevel(level: AccessLevel) {
  if (level === 'FULL_ACCESS') return { canViewRecords:true,canViewAppointments:true,canViewMedications:true,canViewLabResults:true,canViewImaging:true,canViewConsultations:true,canScheduleAppointments:true,canManageCarePlan:true };
  if (level === 'VIEW_ONLY')   return { canViewRecords:true,canViewAppointments:true,canViewMedications:true,canViewLabResults:true,canViewImaging:false,canViewConsultations:true,canScheduleAppointments:false,canManageCarePlan:false };
  if (level === 'EDIT_ONLY')   return { canViewRecords:true,canViewAppointments:true,canViewMedications:true,canViewLabResults:true,canViewImaging:true,canViewConsultations:true,canScheduleAppointments:true,canManageCarePlan:false };
  return {};
}

type CommState = 'ALLOWED' | 'WAITING' | 'NOT_ALLOWED';

/** Messaging / joining calls for the caregiver: effective now, requested but not approved yet, or off. */
function commState(requested: boolean, allowed: boolean): CommState {
  if (allowed) return 'ALLOWED';
  if (requested) return 'WAITING';
  return 'NOT_ALLOWED';
}

function commLabel(state: CommState, patientFirstName: string) {
  if (state === 'ALLOWED') return 'Allowed';
  if (state === 'WAITING') return `Waiting for ${patientFirstName} to approve`;
  return 'Not allowed';
}

function CommStatusRow({ icon, label, state, patientFirstName }: {
  icon: IoniconsName; label: string; state: CommState; patientFirstName: string;
}) {
  const color = state === 'ALLOWED' ? GREEN : state === 'WAITING' ? ORANGE : GRAY;
  const bg    = state === 'ALLOWED' ? '#D1FAE5' : state === 'WAITING' ? '#FEF3C7' : '#F3F4F6';
  return (
    <View style={styles.commRow} accessible accessibilityLabel={`${label}: ${commLabel(state, patientFirstName)}`}>
      <Ionicons name={icon} size={16} color={GRAY} importantForAccessibility="no" />
      <Text style={styles.commRowLabel}>{label}</Text>
      <View style={[styles.badge, { backgroundColor: bg }]}>
        <Text style={[styles.badgeText, { color }]}>{commLabel(state, patientFirstName)}</Text>
      </View>
    </View>
  );
}

/** Off-only switch: the caregiver can turn a communication permission off, never on. */
function ECommSwitch({ label, value, canTurnOn, onChange }: {
  label: string; value: boolean; canTurnOn: boolean; onChange: (v: boolean) => void;
}) {
  const disabled = !value && !canTurnOn;
  return (
    <TouchableOpacity
      style={[styles.permRow, disabled && { opacity: 0.5 }]}
      onPress={() => { if (!disabled) onChange(!value); }}
      disabled={disabled}
      activeOpacity={0.7}
      accessibilityRole="switch"
      accessibilityLabel={label}
      accessibilityState={{ checked: value, disabled }}
    >
      <Text style={styles.permLabel}>{label}</Text>
      <View style={[styles.toggle, value && styles.toggleOn]}>
        <View style={[styles.toggleThumb, value && styles.toggleThumbOn]} />
      </View>
    </TouchableOpacity>
  );
}

// ─── Small edit components ────────────────────────────────────────────────────

function ELabel({ text, required }: { text: string; required?: boolean }) {
  return <Text style={styles.eLabel}>{text}{required && <Text style={{ color: RED }}> *</Text>}</Text>;
}

function EInput({ value, onChange, placeholder, keyboardType }: {
  value: string; onChange: (v: string) => void; placeholder?: string; keyboardType?: any;
}) {
  return (
    <TextInput
      style={styles.eInput}
      value={value}
      onChangeText={onChange}
      placeholder={placeholder}
      placeholderTextColor={GRAY2}
      keyboardType={keyboardType}
      autoCapitalize="none"
    />
  );
}

function EChipRow<T extends string>({ options, value, onChange }: {
  options: { value: T; label: string }[]; value: T; onChange: (v: T) => void;
}) {
  return (
    <View style={styles.chipRow}>
      {options.map(o => (
        <TouchableOpacity
          key={o.value}
          style={[styles.eChip, value === o.value && styles.eChipActive]}
          onPress={() => onChange(o.value)}
          activeOpacity={0.7}
        >
          <Text style={[styles.eChipText, value === o.value && styles.eChipTextActive]}>{o.label}</Text>
        </TouchableOpacity>
      ))}
    </View>
  );
}

function ETagInput({ tags, onAdd, onRemove, placeholder }: {
  tags: string[]; onAdd: (t: string) => void; onRemove: (i: number) => void; placeholder?: string;
}) {
  const [text, setText] = useState('');
  function add() { const t = text.trim(); if (t && !tags.includes(t)) { onAdd(t); setText(''); } }
  return (
    <View style={{ gap: 8 }}>
      <View style={styles.chipRow}>
        {tags.map((t, i) => (
          <View key={i} style={styles.tagChip}>
            <Text style={styles.tagChipText}>{t}</Text>
            <TouchableOpacity onPress={() => onRemove(i)} hitSlop={{ top:6,right:6,bottom:6,left:6 }} accessibilityRole="button" accessibilityLabel={`Remove ${t}`}>
              <Ionicons name="close" size={11} color={PURPLE} />
            </TouchableOpacity>
          </View>
        ))}
      </View>
      <View style={styles.tagInputRow}>
        <TextInput style={styles.tagInput} value={text} onChangeText={setText}
          placeholder={placeholder ?? 'Type and press Add…'} placeholderTextColor={GRAY2}
          onSubmitEditing={add} returnKeyType="done" autoCapitalize="words" />
        <TouchableOpacity style={styles.tagAddBtn} onPress={add} activeOpacity={0.7}>
          <Text style={styles.tagAddBtnText}>Add</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

function EPermSwitch({ label, value, onChange }: { label: string; value: boolean; onChange: (v: boolean) => void }) {
  return (
    <TouchableOpacity
      style={styles.permRow}
      onPress={() => onChange(!value)}
      activeOpacity={0.7}
      accessibilityRole="switch"
      accessibilityLabel={label}
      accessibilityState={{ checked: value }}
    >
      <Text style={styles.permLabel}>{label}</Text>
      <View style={[styles.toggle, value && styles.toggleOn]}>
        <View style={[styles.toggleThumb, value && styles.toggleThumbOn]} />
      </View>
    </TouchableOpacity>
  );
}

function SectionCard({ title, children, editAction }: { title: string; children: React.ReactNode; editAction?: React.ReactNode }) {
  return (
    <View style={styles.sectionCard}>
      <View style={styles.sectionHeader}>
        <Text style={styles.sectionTitle}>{title}</Text>
        {editAction}
      </View>
      {children}
    </View>
  );
}

function InfoRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <View style={styles.infoRow}>
      <Text style={styles.infoLabel}>{label}</Text>
      <View style={styles.infoValue}>{children}</View>
    </View>
  );
}

function PermTile({ icon, label, granted }: { icon: IoniconsName; label: string; granted: boolean }) {
  return (
    <View style={styles.permTile}>
      <View style={[styles.permIconWrap, { backgroundColor: granted ? '#D1FAE5' : '#F3F4F6' }]}>
        <Ionicons name={granted ? 'checkmark-circle' : 'close-circle'} size={16} color={granted ? GREEN : GRAY2} />
      </View>
      <Text style={styles.permTileLabel} numberOfLines={2}>{label}</Text>
    </View>
  );
}

// ─── Main ─────────────────────────────────────────────────────────────────────

export default function PatientProxyWebProfile({
  dependent: initialDependent,
  onBack,
  onNavigate,
}: {
  dependent: CaregiverDependent;
  onBack: () => void;
  onNavigate?: (key: string) => void;
}) {
  // Local copy so a successful access PATCH is reflected immediately.
  const [dependent,  setDependent]  = useState<CaregiverDependent>(initialDependent);
  const [patient,    setPatient]    = useState<PatientDetail | null>(null);
  const [loading,    setLoading]    = useState(true);
  const [fetchError, setFetchError] = useState<string | null>(null);

  // edit states
  const [editingPersonal, setEditingPersonal] = useState(false);
  const [editingHealth,   setEditingHealth]   = useState(false);
  const [editingAccess,   setEditingAccess]   = useState(false);
  const [saving,          setSaving]          = useState(false);
  const [saveError,       setSaveError]       = useState('');

  // personal edit form
  const [pFirst, setPFirst] = useState('');
  const [pLast,  setPLast]  = useState('');
  const [pPhone, setPPhone] = useState('');

  // health edit form
  const [hDob,        setHDob]        = useState('');
  const [hGender,     setHGender]     = useState<Gender>('MALE');
  const [hBlood,      setHBlood]      = useState<BloodType>('A+');
  const [hStatus,     setHStatus]     = useState<HealthStat>('STABLE');
  const [hAllergies,  setHAllergies]  = useState<string[]>([]);
  const [hConditions, setHConditions] = useState<string[]>([]);

  // access edit form
  const [aLevel,    setALevel]    = useState<AccessLevel>('FULL_ACCESS');
  const [aExpires,  setAExpires]  = useState('');
  const [aPerms, setAPerms] = useState({
    canViewRecords:true, canViewAppointments:true, canViewMedications:true,
    canViewLabResults:true, canViewImaging:true,
    canViewConsultations:true, canScheduleAppointments:true, canManageCarePlan:true,
  });
  // Communication requests: can only be turned off here
  const [aComm, setAComm] = useState({ canMessageCareTeam: false, canJoinCalls: false });

  useEffect(() => {
    setDependent(initialDependent);
  }, [initialDependent]);

  useEffect(() => {
    setLoading(true);
    setFetchError(null);
    api.get<PatientDetail>(`/api/profile/user/${dependent.patientSystemId}`)
      .then(({ data }) => {
        setPatient(data);
        setPFirst(data.firstName ?? '');
        setPLast(data.lastName ?? '');
        setPPhone(data.phone ?? '');
        setHDob(data.dateOfBirth ?? '');
        setHGender((data.gender as Gender) ?? 'MALE');
        setHBlood((data.bloodType as BloodType) ?? 'A+');
        setHStatus((data.healthStatus as HealthStat) ?? 'STABLE');
        setHAllergies(parseList(data.allergies));
        setHConditions(parseList(data.chronicConditions));
      })
      .catch(err => setFetchError(caregiverErrorMessage(err, 'Could not load full patient details. Showing available data.')))
      .finally(() => setLoading(false));

    setALevel(dependent.accessLevel as AccessLevel);
    setAExpires(dependent.expiresAt ?? '');
    setAPerms({
      canViewRecords:          dependent.canViewRecords,
      canViewAppointments:     dependent.canViewAppointments,
      canViewMedications:      dependent.canViewMedications,
      canViewLabResults:       dependent.canViewLabResults,
      canViewImaging:          dependent.canViewImaging,
      canViewConsultations:    dependent.canViewConsultations,
      canScheduleAppointments: dependent.canScheduleAppointments,
      canManageCarePlan:       dependent.canManageCarePlan,
    });
    setAComm({
      canMessageCareTeam: !!dependent.canMessageCareTeam,
      canJoinCalls:       !!dependent.canJoinCalls,
    });
  }, [dependent.patientSystemId]);

  function startEditingAccess() {
    setSaveError('');
    // Always start from the saved values so an off-only switch never re-sends a stale "on"
    setAComm({ canMessageCareTeam: !!dependent.canMessageCareTeam, canJoinCalls: !!dependent.canJoinCalls });
    setEditingAccess(true);
  }

  function changeAccessLevel(level: AccessLevel) {
    setALevel(level);
    if (level !== 'CUSTOM') setAPerms(prev => ({ ...prev, ...permissionsForLevel(level) }));
  }

  async function savePersonal() {
    if (!patient) return;
    setSaving(true); setSaveError('');
    try {
      await api.put(`/api/profile/user/${dependent.patientSystemId}`, { firstName: pFirst.trim(), lastName: pLast.trim(), phone: pPhone.trim() });
      const { data } = await api.get<PatientDetail>(`/api/profile/user/${dependent.patientSystemId}`);
      setPatient(data);
      setEditingPersonal(false);
    } catch (err) { setSaveError(caregiverSaveErrorMessage(err, 'Failed to save personal details.')); }
    finally { setSaving(false); }
  }

  async function saveHealth() {
    setSaving(true); setSaveError('');
    try {
      const { data } = await api.put<PatientDetail>(`/api/profile/user/${dependent.patientSystemId}`, {
        dateOfBirth: hDob, gender: hGender, bloodType: hBlood,
        healthStatus: hStatus, allergies: JSON.stringify(hAllergies), chronicConditions: JSON.stringify(hConditions),
      });
      setPatient(data);
      setEditingHealth(false);
    } catch (err) { setSaveError(caregiverSaveErrorMessage(err, 'Failed to save health profile.')); }
    finally { setSaving(false); }
  }

  async function saveAccess() {
    setSaving(true); setSaveError('');
    try {
      const { data } = await api.patch<CaregiverDependent>(`/api/caregiver/dependents/${dependent.accessId}`, {
        accessLevel:              aLevel,
        expiresAt:                aExpires.trim() || null,
        canViewRecords:           aPerms.canViewRecords,
        canViewAppointments:      aPerms.canViewAppointments,
        canViewMedications:       aPerms.canViewMedications,
        canViewLabResults:        aPerms.canViewLabResults,
        canViewImaging:           aPerms.canViewImaging,
        // Only ever narrowed here: a value can be true only if it already was
        canMessageCareTeam:       aComm.canMessageCareTeam && !!dependent.canMessageCareTeam,
        canJoinCalls:             aComm.canJoinCalls && !!dependent.canJoinCalls,
        canViewConsultations:     aPerms.canViewConsultations,
        canScheduleAppointments:  aPerms.canScheduleAppointments,
        canManageCarePlan:        aPerms.canManageCarePlan,
      });
      if (data) setDependent(prev => ({ ...prev, ...data }));
      setEditingAccess(false);
    } catch (err) { setSaveError(caregiverSaveErrorMessage(err, 'Failed to save access settings.')); }
    finally { setSaving(false); }
  }

  const name       = patient ? `${patient.firstName} ${patient.lastName}` : dependent.patientName ?? 'Unknown Patient';
  const status     = patient?.healthStatus ?? dependent.patientHealthStatus ?? null;
  const dob        = patient?.dateOfBirth ?? dependent.patientDateOfBirth;
  const gender     = patient?.gender ?? dependent.patientGender;
  const bloodType  = patient?.bloodType ?? dependent.patientBloodType;
  const email      = patient?.email ?? dependent.patientEmail;
  const phone      = patient?.phone;
  const createdAt  = patient?.createdAt;
  const allergies  = parseList(patient?.allergies);
  const conditions = parseList(patient?.chronicConditions);

  const sc  = healthColors(status);
  const dc  = dotColor(status);
  const lc  = accessColors(dependent.accessLevel);
  const currentALevel = aLevel;
  const patientFirstName = (patient?.firstName ?? dependent.patientName?.split(' ')[0] ?? '').trim() || 'the patient';
  const messagingState = commState(!!dependent.canMessageCareTeam, !!dependent.messagingAllowed);
  const callsState     = commState(!!dependent.canJoinCalls, !!dependent.joinCallsAllowed);
  const currentLc     = accessColors(currentALevel);

  const PERMS: { key: keyof typeof aPerms; icon: IoniconsName; label: string }[] = [
    { key: 'canViewRecords',          icon: 'document-text-outline', label: 'View Records'      },
    { key: 'canViewAppointments',     icon: 'calendar-outline',      label: 'Appointments'      },
    { key: 'canViewMedications',      icon: 'medical-outline',       label: 'Medications'       },
    { key: 'canViewLabResults',       icon: 'flask-outline',         label: 'Lab Results'       },
    { key: 'canViewImaging',          icon: 'scan-outline',          label: 'Imaging'           },
    { key: 'canViewConsultations',    icon: 'people-outline',        label: 'Consultations'     },
    { key: 'canScheduleAppointments', icon: 'add-circle-outline',    label: 'Schedule'          },
    { key: 'canManageCarePlan',       icon: 'clipboard-outline',     label: 'Manage Care Plan'  },
  ];

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>

      {/* Back + title */}
      <View style={styles.pageHeader}>
        <TouchableOpacity style={styles.backBtn} onPress={onBack} activeOpacity={0.7} accessibilityRole="button">
          <Ionicons name="arrow-back-outline" size={18} color={PURPLE} importantForAccessibility="no" />
          <Text style={styles.backText}>Back to Dashboard</Text>
        </TouchableOpacity>
        <Text style={styles.pageTitle}>Patient Profile</Text>
        <Text style={styles.pageSub}>Proxy access overview for this patient</Text>
      </View>

      {/* Fetch error */}
      {fetchError && (
        <View style={styles.errBanner}>
          <Ionicons name="alert-circle-outline" size={15} color={RED} />
          <Text style={styles.errBannerText}>{fetchError}</Text>
        </View>
      )}

      {/* Save error */}
      {saveError ? (
        <View style={styles.errBanner}>
          <Ionicons name="alert-circle-outline" size={15} color={RED} />
          <Text style={styles.errBannerText}>{saveError}</Text>
        </View>
      ) : null}

      {/* ── Hero card ── */}
      <View style={styles.heroCard}>
        <View style={[styles.heroPill, { backgroundColor: sc.bg }]}>
          <View style={[styles.heroPillDot, { backgroundColor: sc.text }]} />
          <Text style={[styles.heroPillText, { color: sc.text }]}>{capitalize(status) || 'Unknown'}</Text>
        </View>
        <View style={styles.heroBody}>
          <View style={styles.heroAvatarWrap}>
            <View style={[styles.heroAvatar, { backgroundColor: avatarColor(name), alignItems:'center', justifyContent:'center' }]}>
              <Text style={styles.heroInitials}>{initials(name)}</Text>
            </View>
            <View style={[styles.heroDot, { backgroundColor: dc }]} />
          </View>
          <View style={styles.heroInfo}>
            <Text style={styles.heroName}>{name}</Text>
            <Text style={styles.heroRel}>{capitalize(dependent.relationship)}</Text>
            {loading
              ? <ActivityIndicator size="small" color={PURPLE} style={{ marginTop: 8 }} />
              : <View style={{ gap: 4, marginTop: 6 }}>
                  {email ? <View style={styles.metaRow}><Ionicons name="mail-outline" size={13} color={GRAY} /><Text style={styles.metaText}>{email}</Text></View> : null}
                  {phone ? <View style={styles.metaRow}><Ionicons name="call-outline" size={13} color={GRAY} /><Text style={styles.metaText}>{phone}</Text></View> : null}
                </View>
            }
          </View>
        </View>
      </View>

      {/* ── Overview strip ── */}
      <View style={styles.overviewCard}>
        {[
          { label: 'Age',          value: calcAge(dob)      },
          { label: 'Gender',       value: capitalize(gender) },
          { label: 'Blood Type',   value: bloodType ?? '—'  },
          { label: 'Member Since', value: createdAt ? String(new Date(createdAt).getFullYear()) : '—' },
        ].map((t, idx, arr) => (
          <View key={t.label} style={[styles.overviewTile, idx < arr.length - 1 && styles.overviewTileBorder]}>
            <Text style={styles.overviewLabel}>{t.label}</Text>
            <Text style={styles.overviewValue}>{t.value}</Text>
          </View>
        ))}
      </View>

      {/* ── Personal Details ── */}
      <SectionCard
        title="Personal Details"
        editAction={
          !editingPersonal
            ? <TouchableOpacity style={styles.editBtn} onPress={() => { setSaveError(''); setEditingPersonal(true); }} activeOpacity={0.7} accessibilityRole="button" accessibilityLabel="Edit personal details">
                <Ionicons name="pencil-outline" size={14} color={PURPLE} />
                <Text style={styles.editBtnText}>Edit</Text>
              </TouchableOpacity>
            : null
        }
      >
        {editingPersonal ? (
          <View style={{ gap: 12 }}>
            <View style={styles.row2}>
              <View style={{ flex: 1 }}>
                <ELabel text="First Name" required />
                <EInput value={pFirst} onChange={setPFirst} placeholder="First name" />
              </View>
              <View style={{ flex: 1 }}>
                <ELabel text="Last Name" required />
                <EInput value={pLast} onChange={setPLast} placeholder="Last name" />
              </View>
            </View>
            <ELabel text="Phone" />
            <EInput value={pPhone} onChange={setPPhone} placeholder="+263…" keyboardType="phone-pad" />
            <View style={styles.saveRow}>
              <TouchableOpacity style={styles.cancelBtn} onPress={() => setEditingPersonal(false)} activeOpacity={0.7}>
                <Text style={styles.cancelBtnText}>Cancel</Text>
              </TouchableOpacity>
              <TouchableOpacity style={[styles.saveBtn, saving && styles.saveBtnDisabled]} onPress={savePersonal} disabled={saving} activeOpacity={0.8}>
                {saving ? <ActivityIndicator size="small" color={WHITE} /> : <Text style={styles.saveBtnText}>Save Changes</Text>}
              </TouchableOpacity>
            </View>
          </View>
        ) : (
          <View style={{ gap: 10 }}>
            <InfoRow label="Full Name"><Text style={styles.infoVal}>{name}</Text></InfoRow>
            <View style={styles.sep} />
            <InfoRow label="Email"><Text style={styles.infoVal}>{email ?? '—'}</Text></InfoRow>
            <View style={styles.sep} />
            <InfoRow label="Phone"><Text style={styles.infoVal}>{phone ?? '—'}</Text></InfoRow>
            <View style={styles.sep} />
            <InfoRow label="Member Since"><Text style={styles.infoVal}>{createdAt ? String(new Date(createdAt).getFullYear()) : '—'}</Text></InfoRow>
          </View>
        )}
      </SectionCard>

      {/* ── Health Profile ── */}
      <SectionCard
        title="Health Profile"
        editAction={
          !editingHealth
            ? <TouchableOpacity style={styles.editBtn} onPress={() => { setSaveError(''); setEditingHealth(true); }} activeOpacity={0.7} accessibilityRole="button" accessibilityLabel="Edit health profile">
                <Ionicons name="pencil-outline" size={14} color={PURPLE} />
                <Text style={styles.editBtnText}>Edit</Text>
              </TouchableOpacity>
            : null
        }
      >
        {editingHealth ? (
          <View style={{ gap: 14 }}>
            <View>
              <ELabel text="Date of Birth" required />
              <EInput value={hDob} onChange={setHDob} placeholder="YYYY-MM-DD" />
            </View>
            <View>
              <ELabel text="Gender" required />
              <EChipRow<Gender>
                options={[{value:'MALE',label:'Male'},{value:'FEMALE',label:'Female'},{value:'OTHER',label:'Other'}]}
                value={hGender} onChange={setHGender}
              />
            </View>
            <View>
              <ELabel text="Blood Type" required />
              <EChipRow<BloodType>
                options={(['A+','A-','B+','B-','O+','O-','AB+','AB-'] as BloodType[]).map(v=>({value:v,label:v}))}
                value={hBlood} onChange={setHBlood}
              />
            </View>
            <View>
              <ELabel text="Health Status" />
              <EChipRow<HealthStat>
                options={[{value:'STABLE',label:'Stable'},{value:'MONITOR',label:'Monitor'},{value:'CRITICAL',label:'Critical'}]}
                value={hStatus} onChange={setHStatus}
              />
            </View>
            <View>
              <ELabel text="Known Allergies" />
              <ETagInput tags={hAllergies} onAdd={t=>setHAllergies(a=>[...a,t])} onRemove={i=>setHAllergies(a=>a.filter((_,idx)=>idx!==i))} placeholder="e.g. Penicillin…" />
            </View>
            <View>
              <ELabel text="Chronic Conditions" />
              <ETagInput tags={hConditions} onAdd={t=>setHConditions(c=>[...c,t])} onRemove={i=>setHConditions(c=>c.filter((_,idx)=>idx!==i))} placeholder="e.g. Hypertension…" />
            </View>
            <View style={styles.saveRow}>
              <TouchableOpacity style={styles.cancelBtn} onPress={() => setEditingHealth(false)} activeOpacity={0.7}>
                <Text style={styles.cancelBtnText}>Cancel</Text>
              </TouchableOpacity>
              <TouchableOpacity style={[styles.saveBtn, saving && styles.saveBtnDisabled]} onPress={saveHealth} disabled={saving} activeOpacity={0.8}>
                {saving ? <ActivityIndicator size="small" color={WHITE} /> : <Text style={styles.saveBtnText}>Save Changes</Text>}
              </TouchableOpacity>
            </View>
          </View>
        ) : (
          <View style={{ gap: 12 }}>
            <View style={styles.healthStatusRow}>
              <Ionicons name="heart-outline" size={16} color={dc} />
              <Text style={[styles.healthStatusText, { color: dc }]}>{capitalize(status)}</Text>
            </View>
            <View style={styles.sep} />
            <Text style={styles.subLabel}>Known Allergies</Text>
            {allergies.length > 0
              ? <View style={styles.chipRow}>{allergies.map(a => <View key={a} style={styles.allergyChip}><Text style={styles.allergyChipText}>{a}</Text></View>)}</View>
              : <Text style={styles.noneText}>None recorded</Text>
            }
            <View style={styles.sep} />
            <Text style={styles.subLabel}>Chronic Conditions</Text>
            {conditions.length > 0
              ? <View style={styles.chipRow}>{conditions.map(c => <View key={c} style={styles.condChip}><Text style={styles.condChipText}>{c}</Text></View>)}</View>
              : <Text style={styles.noneText}>None recorded</Text>
            }
          </View>
        )}
      </SectionCard>

      {/* ── Proxy Access ── */}
      <SectionCard
        title="Your Access"
        editAction={
          !editingAccess
            ? <TouchableOpacity style={styles.editBtn} onPress={startEditingAccess} activeOpacity={0.7} accessibilityRole="button" accessibilityLabel="Edit your access">
                <Ionicons name="pencil-outline" size={14} color={PURPLE} />
                <Text style={styles.editBtnText}>Edit</Text>
              </TouchableOpacity>
            : null
        }
      >
        {editingAccess ? (
          <View style={{ gap: 14 }}>
            <View>
              <ELabel text="Access Level" required />
              <View style={styles.accessGrid}>
                {([
                  { value:'FULL_ACCESS', label:'Full Access',  sub:'All permissions enabled',         color:GREEN  },
                  { value:'VIEW_ONLY',   label:'View Only',    sub:'Read records, no actions',        color:PURPLE },
                  { value:'EDIT_ONLY',   label:'Edit Only',    sub:'View + schedule',                 color:ORANGE },
                  { value:'CUSTOM',      label:'Custom',        sub:'Set individual permissions below',color:GRAY  },
                ] as {value:AccessLevel;label:string;sub:string;color:string}[]).map(opt => (
                  <TouchableOpacity
                    key={opt.value}
                    style={[styles.accessCard, currentALevel === opt.value && { borderColor: opt.color, borderWidth: 2 }]}
                    onPress={() => changeAccessLevel(opt.value)}
                    activeOpacity={0.7}
                  >
                    <View style={[styles.accessRadio, currentALevel === opt.value && { borderColor: opt.color }]}>
                      {currentALevel === opt.value && <View style={[styles.accessRadioDot, { backgroundColor: opt.color }]} />}
                    </View>
                    <View style={{ flex: 1 }}>
                      <Text style={[styles.accessCardLabel, currentALevel === opt.value && { color: opt.color }]}>{opt.label}</Text>
                      <Text style={styles.accessCardSub}>{opt.sub}</Text>
                    </View>
                  </TouchableOpacity>
                ))}
              </View>
            </View>

            {currentALevel === 'CUSTOM' && (
              <View style={styles.permBox}>
                <Text style={styles.permBoxTitle}>Individual Permissions</Text>
                {PERMS.map(p => (
                  <EPermSwitch
                    key={p.key}
                    label={p.label}
                    value={!!aPerms[p.key]}
                    onChange={v => setAPerms(prev => ({ ...prev, [p.key]: v }))}
                  />
                ))}
              </View>
            )}

            <View style={styles.permBox}>
              <Text style={styles.permBoxTitle}>Messaging and video calls</Text>
              <ECommSwitch
                label="Message the care team"
                value={aComm.canMessageCareTeam}
                canTurnOn={false}
                onChange={v => setAComm(prev => ({ ...prev, canMessageCareTeam: v }))}
              />
              <ECommSwitch
                label="Join video calls"
                value={aComm.canJoinCalls}
                canTurnOn={false}
                onChange={v => setAComm(prev => ({ ...prev, canJoinCalls: v }))}
              />
              <Text style={styles.hint}>
                You can turn these off. Only {patientFirstName} can allow messaging or video calls.
              </Text>
            </View>

            <View>
              <ELabel text="Access Expiry Date (optional)" />
              <EInput value={aExpires} onChange={setAExpires} placeholder="YYYY-MM-DD (leave blank for no expiry)" />
              <Text style={styles.hint}>Leave blank if this proxy access should not expire.</Text>
            </View>

            <View style={styles.saveRow}>
              <TouchableOpacity style={styles.cancelBtn} onPress={() => setEditingAccess(false)} activeOpacity={0.7}>
                <Text style={styles.cancelBtnText}>Cancel</Text>
              </TouchableOpacity>
              <TouchableOpacity style={[styles.saveBtn, saving && styles.saveBtnDisabled]} onPress={saveAccess} disabled={saving} activeOpacity={0.8}>
                {saving ? <ActivityIndicator size="small" color={WHITE} /> : <Text style={styles.saveBtnText}>Save Changes</Text>}
              </TouchableOpacity>
            </View>
          </View>
        ) : (
          <View style={{ gap: 10 }}>
            <InfoRow label="Relationship"><Text style={styles.infoVal}>{capitalize(dependent.relationship)}</Text></InfoRow>
            <View style={styles.sep} />
            <InfoRow label="Access Level">
              <View style={[styles.badge, { backgroundColor: lc.bg }]}>
                <Text style={[styles.badgeText, { color: lc.text }]}>{accessLabel(dependent.accessLevel)}</Text>
              </View>
            </InfoRow>
            <View style={styles.sep} />
            <InfoRow label="Authorized On"><Text style={styles.infoVal}>{formatDate(dependent.authorizedDate)}</Text></InfoRow>
            <View style={styles.sep} />
            <InfoRow label="Expires"><Text style={styles.infoVal}>{dependent.expiresAt ? formatDate(dependent.expiresAt) : 'No expiry'}</Text></InfoRow>
            <View style={styles.sep} />
            <InfoRow label="Authorized By"><Text style={styles.infoVal}>{dependent.authorizedBySystemId ?? '—'}</Text></InfoRow>
            <View style={[styles.sep, { marginVertical: 4 }]} />
            <Text style={styles.subLabel}>Permissions</Text>
            <View style={styles.permGrid}>
              {PERMS.map(p => <PermTile key={p.key} icon={p.icon} label={p.label} granted={!!dependent[p.key as keyof CaregiverDependent]} />)}
            </View>
            <View style={[styles.sep, { marginVertical: 4 }]} />
            <Text style={styles.subLabel}>Messaging and video calls</Text>
            <CommStatusRow icon="chatbox-outline" label="Message the care team" state={messagingState} patientFirstName={patientFirstName} />
            <CommStatusRow icon="videocam-outline" label="Join video calls" state={callsState} patientFirstName={patientFirstName} />
          </View>
        )}
      </SectionCard>

      {/* ── Quick Actions ── */}
      {onNavigate && (!!dependent.canViewAppointments || !!dependent.canViewLabResults || !!dependent.canViewMedications) && (
        <SectionCard title="Quick Actions">
          <View style={styles.actionsRow}>
            {!!dependent.canViewAppointments && (
              <TouchableOpacity style={styles.actionBtn} activeOpacity={0.7} onPress={() => onNavigate('appointments')} accessibilityRole="button">
                <Ionicons name="calendar-outline" size={15} color={PURPLE} importantForAccessibility="no" />
                <Text style={styles.actionBtnText}>View Appointments</Text>
              </TouchableOpacity>
            )}
            {!!dependent.canViewMedications && (
              <TouchableOpacity style={styles.actionBtn} activeOpacity={0.7} onPress={() => onNavigate('medications')} accessibilityRole="button">
                <Ionicons name="medical-outline" size={15} color={PURPLE} importantForAccessibility="no" />
                <Text style={styles.actionBtnText}>View Medications</Text>
              </TouchableOpacity>
            )}
            {!!dependent.canViewLabResults && (
              <TouchableOpacity style={styles.actionBtn} activeOpacity={0.7} onPress={() => onNavigate('documents-results')} accessibilityRole="button">
                <Ionicons name="flask-outline" size={15} color={PURPLE} importantForAccessibility="no" />
                <Text style={styles.actionBtnText}>View Lab Results</Text>
              </TouchableOpacity>
            )}
          </View>
        </SectionCard>
      )}

      <View style={{ height: 12 }} />
    </ScrollView>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  scroll:     { flex: 1, backgroundColor: BG },
  container:  { alignSelf:'center', width:'100%', maxWidth:860, padding:28, gap:20, paddingBottom:48 } as any,

  pageHeader: { gap: 4 },
  backBtn:    { flexDirection:'row', alignItems:'center', gap:6, alignSelf:'flex-start', marginBottom:6 },
  backText:   { fontSize:14, color:PURPLE, fontWeight:'500' },
  pageTitle:  { fontSize:24, fontWeight:'700', color:TEXT },
  pageSub:    { fontSize:13, color:GRAY },

  errBanner:     { flexDirection:'row', alignItems:'center', gap:8, backgroundColor:'#FEE2E2', borderRadius:10, padding:12, borderWidth:1, borderColor:'#FECACA' },
  errBannerText: { fontSize:13, color:RED, flex:1 },

  heroCard:       { backgroundColor:WHITE, borderWidth:1, borderColor:BORDER, borderRadius:16, padding:24 },
  heroPill:       { position:'absolute', top:20, right:20, flexDirection:'row', alignItems:'center', gap:5, paddingHorizontal:10, paddingVertical:5, borderRadius:20 },
  heroPillDot:    { width:6, height:6, borderRadius:3 },
  heroPillText:   { fontSize:12, fontWeight:'600' },
  heroBody:       { flexDirection:'row', alignItems:'flex-start', gap:20 },
  heroAvatarWrap: { position:'relative' },
  heroAvatar:     { width:80, height:80, borderRadius:40, backgroundColor:BORDER },
  heroInitials:   { color:WHITE, fontSize:26, fontWeight:'700' },
  heroDot:        { width:11, height:11, borderRadius:6, borderWidth:2, borderColor:WHITE, position:'absolute', bottom:3, right:3 },
  heroInfo:       { flex:1, gap:4 },
  heroName:       { fontSize:22, fontWeight:'700', color:TEXT },
  heroRel:        { fontSize:14, color:PURPLE, fontWeight:'500' },
  metaRow:        { flexDirection:'row', alignItems:'center', gap:5 },
  metaText:       { fontSize:13, color:GRAY },

  overviewCard:       { backgroundColor:WHITE, borderWidth:1, borderColor:BORDER, borderRadius:14, flexDirection:'row' },
  overviewTile:       { flex:1, padding:18, alignItems:'center', gap:4 },
  overviewTileBorder: { borderRightWidth:1, borderRightColor:BORDER },
  overviewLabel:      { fontSize:11, color:GRAY2, fontWeight:'500' },
  overviewValue:      { fontSize:16, fontWeight:'700', color:TEXT },

  sectionCard:   { backgroundColor:WHITE, borderWidth:1, borderColor:BORDER, borderRadius:14, padding:20, gap:12 },
  sectionHeader: { flexDirection:'row', alignItems:'center', justifyContent:'space-between', marginBottom:4 },
  sectionTitle:  { fontSize:16, fontWeight:'700', color:TEXT },
  editBtn:       { flexDirection:'row', alignItems:'center', gap:5, borderWidth:1, borderColor:BORDER, borderRadius:8, paddingHorizontal:10, paddingVertical:5 },
  editBtnText:   { fontSize:12, color:PURPLE, fontWeight:'600' },

  sep:         { height:1, backgroundColor:BORDER },
  subLabel:    { fontSize:12, fontWeight:'600', color:GRAY, textTransform:'uppercase', letterSpacing:0.5 },
  noneText:    { fontSize:13, color:GRAY2, fontStyle:'italic' },

  infoRow:   { flexDirection:'row', alignItems:'center', justifyContent:'space-between', paddingVertical:2 },
  infoLabel: { fontSize:13, color:GRAY, fontWeight:'500', flex:1 },
  infoValue: { flex:2, alignItems:'flex-start' },
  infoVal:   { fontSize:13, color:TEXT, fontWeight:'600' },

  badge:     { paddingHorizontal:10, paddingVertical:4, borderRadius:20 },
  badgeText: { fontSize:12, fontWeight:'600' },

  healthStatusRow:  { flexDirection:'row', alignItems:'center', gap:6 },
  healthStatusText: { fontSize:14, fontWeight:'600' },

  chipRow:         { flexDirection:'row', flexWrap:'wrap', gap:8 },
  allergyChip:     { paddingHorizontal:10, paddingVertical:5, borderRadius:20, backgroundColor:PURPLE_BG },
  allergyChipText: { fontSize:12, fontWeight:'600', color:PURPLE },
  condChip:        { paddingHorizontal:10, paddingVertical:5, borderRadius:20, backgroundColor:'#FEF3C7' },
  condChipText:    { fontSize:12, fontWeight:'600', color:ORANGE },

  permGrid:      { flexDirection:'row', flexWrap:'wrap', gap:10, marginTop:4 },
  permTile:      { width:'30%', alignItems:'center', gap:6, padding:10, backgroundColor:BG, borderRadius:10, borderWidth:1, borderColor:BORDER },
  permIconWrap:  { width:32, height:32, borderRadius:16, alignItems:'center', justifyContent:'center' },
  permTileLabel: { fontSize:11, color:TEXT, fontWeight:'500', textAlign:'center' },

  actionsRow:    { flexDirection:'row', flexWrap:'wrap', gap:10 },
  actionBtn:     { flexDirection:'row', alignItems:'center', gap:6, backgroundColor:PURPLE_BG, borderWidth:1, borderColor:PURPLE_LIGHT, borderRadius:20, paddingHorizontal:16, paddingVertical:10 },
  actionBtnText: { fontSize:13, color:PURPLE, fontWeight:'600' },

  // Edit form styles
  row2:    { flexDirection:'row', gap:14 },
  eLabel:  { fontSize:13, fontWeight:'600', color:TEXT, marginBottom:5 },
  eInput:  { borderWidth:1, borderColor:BORDER, borderRadius:10, paddingHorizontal:14, paddingVertical:11, fontSize:14, color:TEXT, backgroundColor:WHITE } as any,
  eChip:         { borderWidth:1, borderColor:BORDER, borderRadius:8, paddingHorizontal:14, paddingVertical:8 },
  eChipActive:   { borderColor:PURPLE, backgroundColor:PURPLE_BG },
  eChipText:     { fontSize:13, color:GRAY, fontWeight:'500' },
  eChipTextActive:{ color:PURPLE, fontWeight:'700' },
  tagChip:       { flexDirection:'row', alignItems:'center', gap:5, backgroundColor:PURPLE_BG, borderRadius:20, paddingHorizontal:10, paddingVertical:4 },
  tagChipText:   { fontSize:12, color:PURPLE, fontWeight:'500' },
  tagInputRow:   { flexDirection:'row', gap:8 },
  tagInput:      { flex:1, borderWidth:1, borderColor:BORDER, borderRadius:10, paddingHorizontal:12, paddingVertical:9, fontSize:13, color:TEXT } as any,
  tagAddBtn:     { borderWidth:1, borderColor:PURPLE, borderRadius:10, paddingHorizontal:14, paddingVertical:9, backgroundColor:PURPLE_BG },
  tagAddBtnText: { fontSize:13, color:PURPLE, fontWeight:'600' },
  hint:          { fontSize:11, color:GRAY2, marginTop:4 },

  accessGrid:      { flexDirection:'row', flexWrap:'wrap', gap:10, marginTop:6 },
  accessCard:      { flex:1, minWidth:160, flexDirection:'row', alignItems:'flex-start', gap:10, borderWidth:1, borderColor:BORDER, borderRadius:12, padding:14 },
  accessRadio:     { width:18, height:18, borderRadius:9, borderWidth:2, borderColor:BORDER, alignItems:'center', justifyContent:'center', marginTop:2 },
  accessRadioDot:  { width:8, height:8, borderRadius:4 },
  accessCardLabel: { fontSize:13, fontWeight:'700', color:TEXT, marginBottom:2 },
  accessCardSub:   { fontSize:11, color:GRAY },

  commRow:      { flexDirection:'row', alignItems:'center', gap:8, flexWrap:'wrap', paddingVertical:4 },
  commRowLabel: { flex:1, minWidth:140, fontSize:13, color:TEXT, fontWeight:'500' },

  permBox:      { backgroundColor:BG, borderRadius:12, borderWidth:1, borderColor:BORDER, padding:16 },
  permBoxTitle: { fontSize:13, fontWeight:'700', color:TEXT, marginBottom:10 },
  permRow:      { flexDirection:'row', alignItems:'center', paddingVertical:10, borderBottomWidth:1, borderBottomColor:BORDER },
  permLabel:    { flex:1, fontSize:13, color:TEXT },
  toggle:       { width:40, height:22, borderRadius:11, backgroundColor:BORDER, justifyContent:'center', paddingHorizontal:2 },
  toggleOn:     { backgroundColor:PURPLE },
  toggleThumb:  { width:18, height:18, borderRadius:9, backgroundColor:WHITE } as any,
  toggleThumbOn:{ alignSelf:'flex-end' },

  saveRow:          { flexDirection:'row', justifyContent:'flex-end', gap:10, marginTop:4 },
  cancelBtn:        { borderWidth:1, borderColor:BORDER, borderRadius:10, paddingHorizontal:18, paddingVertical:10 },
  cancelBtnText:    { fontSize:14, color:GRAY, fontWeight:'500' },
  saveBtn:          { backgroundColor:PURPLE, borderRadius:10, paddingHorizontal:22, paddingVertical:10 },
  saveBtnDisabled:  { opacity:0.5 },
  saveBtnText:      { fontSize:14, color:WHITE, fontWeight:'700' },
});
