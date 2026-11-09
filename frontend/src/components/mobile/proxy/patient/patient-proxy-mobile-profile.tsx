import { Ionicons } from '@expo/vector-icons';
import { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  useWindowDimensions,
  View,
} from 'react-native';
import { api } from '@/lib/auth/auth.interceptor';
import { caregiverErrorMessage, caregiverSaveErrorMessage, parseList, type CaregiverDependent } from '@/hooks/use-caregiver-dependents';
import { MobileMessageCareTeamButton } from '../../messages/messaging-actions';
import { useCurrentUser } from '@/hooks/use-current-user';
import { ageFromDateOnly, dateOnlyParts, daysUntilDateOnly, formatDateOnly, isValidDateOnly } from '@/lib/ui/date-only';

const WHITE        = '#FFFFFF';
const TEXT         = '#111827';
const GRAY         = '#6B7280';
const GRAY2        = '#6B7280';
const BORDER       = '#E5E7EB';
const BG           = '#F9FAFB';
const PURPLE       = '#7C3AED';
const PURPLE_BG    = '#F5F3FF';
const GREEN        = '#047857';
const ORANGE       = '#B45309';
const RED          = '#B91C1C';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type AccessLevel  = 'FULL_ACCESS' | 'VIEW_ONLY' | 'EDIT_ONLY' | 'CUSTOM';
type Gender       = 'MALE' | 'FEMALE' | 'OTHER';
type HealthStat   = 'STABLE' | 'MONITOR' | 'CRITICAL';
type BloodType    = 'A+' | 'A-' | 'B+' | 'B-' | 'O+' | 'O-' | 'AB+' | 'AB-';

const GENDERS:     Gender[]     = ['MALE', 'FEMALE', 'OTHER'];
const BLOOD_TYPES: BloodType[]  = ['A+', 'A-', 'B+', 'B-', 'O+', 'O-', 'AB+', 'AB-'];
const STATUSES:    HealthStat[] = ['STABLE', 'MONITOR', 'CRITICAL'];

interface PatientDetail {
  userId: number;
  firstName: string; lastName: string; phone: string; email: string;
  role: string; isActive: boolean;
  patientSystemId: string;
  // The health fields are null when the caregiver may not view records (the backend redacts them).
  dateOfBirth: string | null; gender: string | null; bloodType: string | null;
  allergies: string | null; chronicConditions: string | null;
  healthStatus: string | null; createdAt: string; updatedAt: string;
}

// ─── Scale ────────────────────────────────────────────────────────────────────

function useScale() {
  const { width, height } = useWindowDimensions();
  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));
  return { s, sh };
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

const AVATAR_COLORS = ['#4F46E5','#0F766E','#B45309','#B91C1C','#6D28D9','#047857','#0369A1'];
function avatarColor(name: string) {
  let h = 0;
  for (let i = 0; i < (name?.length ?? 0); i++) h = (h * 31 + name.charCodeAt(i)) & 0xffff;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}
function initials(name: string) {
  return (name ?? '?').split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase();
}
function calcAge(dob: string | null | undefined) {
  const age = ageFromDateOnly(dob);
  return age === null ? '—' : `${age} yrs`;
}
function capitalize(s: string | null | undefined) {
  if (!s) return '—';
  return s.charAt(0).toUpperCase() + s.slice(1).toLowerCase();
}
function oneOf<T extends string>(value: string | null | undefined, options: readonly T[]): T | '' {
  return value && (options as readonly string[]).includes(value) ? (value as T) : '';
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
// Messaging is not part of an access level: only the patient can turn it on, so it is never sent from here.
function permissionsForLevel(level: AccessLevel) {
  if (level === 'FULL_ACCESS') return { canViewRecords:true,canViewAppointments:true,canViewMedications:true,canViewLabResults:true,canViewImaging:true,canViewConsultations:true,canScheduleAppointments:true,canManageCarePlan:true };
  if (level === 'VIEW_ONLY')   return { canViewRecords:true,canViewAppointments:true,canViewMedications:true,canViewLabResults:true,canViewImaging:false,canViewConsultations:true,canScheduleAppointments:false,canManageCarePlan:false };
  if (level === 'EDIT_ONLY')   return { canViewRecords:true,canViewAppointments:true,canViewMedications:true,canViewLabResults:true,canViewImaging:true,canViewConsultations:true,canScheduleAppointments:true,canManageCarePlan:false };
  return {};
}

type Perms = ReturnType<typeof permsFrom>;
function permsFrom(d: CaregiverDependent) {
  return {
    canViewRecords:          !!d.canViewRecords,
    canViewAppointments:     !!d.canViewAppointments,
    canViewMedications:      !!d.canViewMedications,
    canViewLabResults:       !!d.canViewLabResults,
    canViewImaging:          !!d.canViewImaging,
    canViewConsultations:    !!d.canViewConsultations,
    canScheduleAppointments: !!d.canScheduleAppointments,
    canManageCarePlan:       !!d.canManageCarePlan,
  };
}

const PERMS: { key: keyof Perms; icon: IoniconsName; label: string }[] = [
  { key: 'canViewRecords',          icon: 'document-text-outline', label: 'View Records'    },
  { key: 'canViewAppointments',     icon: 'calendar-outline',      label: 'Appointments'    },
  { key: 'canViewMedications',      icon: 'medical-outline',       label: 'Medications'     },
  { key: 'canViewLabResults',       icon: 'flask-outline',         label: 'Lab Results'     },
  { key: 'canViewImaging',          icon: 'scan-outline',          label: 'Imaging'         },
  { key: 'canViewConsultations',    icon: 'people-outline',        label: 'Consultations'   },
  { key: 'canScheduleAppointments', icon: 'add-circle-outline',    label: 'Schedule'        },
  { key: 'canManageCarePlan',       icon: 'clipboard-outline',     label: 'Care Plan'       },
];

const LEVEL_OPTIONS: { value: AccessLevel; label: string; sub: string; color: string }[] = [
  { value:'FULL_ACCESS', label:'Full Access',  sub:'All permissions enabled',          color:GREEN  },
  { value:'VIEW_ONLY',   label:'View Only',    sub:'Read records, no actions',         color:PURPLE },
  { value:'EDIT_ONLY',   label:'Edit Only',    sub:'View + schedule',                  color:ORANGE },
  { value:'CUSTOM',      label:'Custom',       sub:'Set individual permissions below', color:GRAY   },
];

// ─── Field components (module scope so inputs keep focus and state across re-renders) ─────────────

function Card({ title, onEdit, children }: { title: string; onEdit?: () => void; children: React.ReactNode }) {
  const { s } = useScale();
  return (
    <View style={[styles.card, { borderRadius: s(14), padding: s(14), marginBottom: s(12), gap: s(10) }]}>
      <View style={[styles.cardHeader, { gap: s(8) }]}>
        <Text style={[styles.cardTitle, { fontSize: s(14), flexShrink: 1 }]} accessibilityRole="header" numberOfLines={1}>{title}</Text>
        {onEdit && (
          <TouchableOpacity
            style={[styles.editBtn, { borderRadius: s(8), paddingHorizontal: s(10), paddingVertical: s(5), gap: s(4) }]}
            onPress={onEdit}
            activeOpacity={0.7}
            accessibilityRole="button"
            accessibilityLabel={`Edit ${title.toLowerCase()}`}
          >
            <Ionicons name="pencil-outline" size={s(13)} color={PURPLE} importantForAccessibility="no" />
            <Text style={[styles.editBtnText, { fontSize: s(12) }]}>Edit</Text>
          </TouchableOpacity>
        )}
      </View>
      {children}
    </View>
  );
}

function MInput({ value, onChange, placeholder, keyboardType, label, error, autoCapitalize = 'none' }: {
  value: string; onChange: (v: string) => void; placeholder?: string; keyboardType?: any;
  /** Spoken name of the field. */
  label?: string; error?: string; autoCapitalize?: 'none' | 'words';
}) {
  const { s } = useScale();
  return (
    <View>
      <TextInput
        style={[styles.input, { fontSize: s(14), borderRadius: s(10), paddingHorizontal: s(12), paddingVertical: s(10) }, !!error && { borderColor: RED }]}
        value={value} onChangeText={onChange} placeholder={placeholder}
        placeholderTextColor={GRAY2} keyboardType={keyboardType} autoCapitalize={autoCapitalize}
        accessibilityLabel={label}
        accessibilityHint={error || undefined}
      />
      {error ? <Text style={[styles.errorText, { fontSize: s(11), marginTop: s(3) }]} accessibilityLiveRegion="polite">{error}</Text> : null}
    </View>
  );
}

function MLabel({ text, required }: { text: string; required?: boolean }) {
  const { s } = useScale();
  return <Text style={[styles.label, { fontSize: s(13), marginBottom: s(4) }]}>{text}{required && <Text style={{ color: RED }}> *</Text>}</Text>;
}

function MChipRow<T extends string>({ options, value, onChange, label }: {
  options: { value: T; label: string }[]; value: T | ''; onChange: (v: T) => void; label: string;
}) {
  const { s } = useScale();
  return (
    <View style={[styles.chipRow, { gap: s(7) }]} accessibilityRole="radiogroup" accessibilityLabel={label}>
      {options.map(o => (
        <TouchableOpacity key={o.value}
          style={[styles.chip, { borderRadius: s(8), paddingHorizontal: s(11), paddingVertical: s(7) }, value === o.value && styles.chipActive]}
          onPress={() => onChange(o.value)} activeOpacity={0.7}
          accessibilityRole="radio"
          accessibilityLabel={`${label}: ${o.label}`}
          accessibilityState={{ selected: value === o.value, checked: value === o.value }}
        >
          <Text style={[styles.chipText, { fontSize: s(12) }, value === o.value && styles.chipTextActive]}>{o.label}</Text>
        </TouchableOpacity>
      ))}
    </View>
  );
}

function MTagInput({ tags, onAdd, onRemove, placeholder, label }: {
  tags: string[]; onAdd: (t: string) => void; onRemove: (i: number) => void; placeholder?: string; label: string;
}) {
  const { s } = useScale();
  const [text, setText] = useState('');
  function add() { const t = text.trim(); if (t && !tags.includes(t)) { onAdd(t); setText(''); } }
  return (
    <View style={{ gap: s(7) }}>
      <View style={[styles.chipRow, { gap: s(6) }]}>
        {tags.map((t, i) => (
          <View key={`${t}-${i}`} style={[styles.tagChip, { borderRadius: s(12), paddingHorizontal: s(8), paddingVertical: s(4), gap: s(5) }]}>
            <Text style={[styles.tagChipText, { fontSize: s(11), flexShrink: 1 }]}>{t}</Text>
            <TouchableOpacity onPress={() => onRemove(i)} hitSlop={{ top:6,right:6,bottom:6,left:6 }} accessibilityRole="button" accessibilityLabel={`Remove ${t}`}>
              <Ionicons name="close" size={s(11)} color={PURPLE} importantForAccessibility="no" />
            </TouchableOpacity>
          </View>
        ))}
      </View>
      <View style={[styles.tagInputRow, { gap: s(7) }]}>
        <TextInput
          style={[styles.tagInput, { flex:1, fontSize:s(13), borderRadius:s(9), paddingHorizontal:s(11), paddingVertical:s(9) }]}
          value={text} onChangeText={setText} placeholder={placeholder ?? 'Type and press Add…'}
          placeholderTextColor={GRAY2} onSubmitEditing={add} returnKeyType="done" autoCapitalize="words"
          blurOnSubmit={false}
          accessibilityLabel={`Add to ${label.toLowerCase()}`}
        />
        <TouchableOpacity
          style={[styles.tagAddBtn, { borderRadius:s(9), paddingHorizontal:s(12), paddingVertical:s(9) }]}
          onPress={add} activeOpacity={0.7}
          accessibilityRole="button" accessibilityLabel={`Add to ${label.toLowerCase()}`}
        >
          <Text style={[styles.tagAddBtnText, { fontSize:s(13) }]}>Add</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

function MPermSwitch({ label, value, onChange }: { label: string; value: boolean; onChange: (v: boolean) => void }) {
  const { s } = useScale();
  return (
    <TouchableOpacity
      style={[styles.permRow, { paddingVertical: s(10) }]}
      onPress={() => onChange(!value)}
      activeOpacity={0.7}
      accessibilityRole="switch"
      accessibilityLabel={label}
      accessibilityState={{ checked: value }}
    >
      <Text style={[styles.permLabel, { fontSize: s(13) }]}>{label}</Text>
      <View style={[styles.toggle, { width: s(38), height: s(21), borderRadius: s(11) }, value && styles.toggleOn]}>
        <View style={[styles.toggleThumb, { width: s(17), height: s(17), borderRadius: s(9) }, value && styles.toggleThumbOn]} />
      </View>
    </TouchableOpacity>
  );
}

function SaveRow({ onSave, onCancel, saving }: { onSave: () => void; onCancel: () => void; saving: boolean }) {
  const { s } = useScale();
  return (
    <View style={[styles.saveRow, { gap: s(10), marginTop: s(6) }]}>
      <TouchableOpacity
        style={[styles.cancelBtn, { borderRadius: s(10), paddingHorizontal: s(16), paddingVertical: s(11), flex: 1 }]}
        onPress={onCancel} activeOpacity={0.7}
        accessibilityRole="button"
      >
        <Text style={[styles.cancelBtnText, { fontSize: s(14) }]}>Cancel</Text>
      </TouchableOpacity>
      <TouchableOpacity
        style={[styles.saveBtn, { borderRadius: s(10), paddingHorizontal: s(16), paddingVertical: s(11), flex: 1 }, saving && { opacity: 0.5 }]}
        onPress={onSave} disabled={saving} activeOpacity={0.8}
        accessibilityRole="button"
        accessibilityLabel={saving ? 'Saving' : 'Save'}
        accessibilityState={{ disabled: saving, busy: saving }}
      >
        {saving
          ? <ActivityIndicator size="small" color={WHITE} />
          : <Text style={[styles.saveBtnText, { fontSize: s(14) }]}>Save</Text>
        }
      </TouchableOpacity>
    </View>
  );
}

function ErrorBanner({ message }: { message: string }) {
  const { s } = useScale();
  return (
    <View
      style={[styles.errBanner, { borderRadius: s(10), padding: s(12), gap: s(7), marginBottom: s(12) }]}
      accessibilityRole="alert"
      accessibilityLiveRegion="assertive"
    >
      <Ionicons name="alert-circle-outline" size={s(14)} color={RED} importantForAccessibility="no" />
      <Text style={[styles.errBannerText, { fontSize: s(12) }]}>{message}</Text>
    </View>
  );
}

function InfoRow({ label, value }: { label: string; value: string }) {
  const { s } = useScale();
  return (
    <View style={[styles.infoRow, { gap: s(8) }]}>
      <Text style={[styles.infoLabel, { fontSize: s(12) }]} numberOfLines={1}>{label}</Text>
      <Text style={[styles.infoVal, { fontSize: s(13), flex: 1.5, textAlign: 'right' }]} numberOfLines={2}>{value}</Text>
    </View>
  );
}

export type ProxyRecordKey = 'schedule' | 'medications' | 'documents' | 'health-summary';

// Record screens reachable from this profile; `flag` is the permission the loved one must share (none: always shown).
const QUICK_ACTIONS: { key: ProxyRecordKey; label: string; icon: IoniconsName; flag?: keyof Perms }[] = [
  { key: 'schedule',       label: 'View Appointments', icon: 'calendar-outline',   flag: 'canViewAppointments' },
  { key: 'medications',    label: 'View Medications',  icon: 'medical-outline',    flag: 'canViewMedications' },
  { key: 'documents',      label: 'View Lab Results',  icon: 'flask-outline',      flag: 'canViewLabResults' },
  { key: 'health-summary', label: 'Health Summary',    icon: 'heart-outline' },
];

// ─── Main ─────────────────────────────────────────────────────────────────────

export default function PatientProxyMobileProfile({
  dependent: initialDependent,
  onBack,
  onOpenRecord,
}: {
  dependent: CaregiverDependent;
  onBack: () => void;
  /** Opens one of this loved one's record screens (filtered to them). */
  onOpenRecord?: (key: ProxyRecordKey) => void;
}) {
  const { s }    = useScale();
  const { user } = useCurrentUser();

  // Local copy so a successful access PATCH is reflected immediately.
  const [dependent, setDependent] = useState<CaregiverDependent>(initialDependent);
  useEffect(() => { setDependent(initialDependent); }, [initialDependent]);

  const [patient,    setPatient]    = useState<PatientDetail | null>(null);
  const [loading,    setLoading]    = useState(true);
  const [fetchError, setFetchError] = useState<string | null>(null);

  const [editingPersonal, setEditingPersonal] = useState(false);
  const [editingHealth,   setEditingHealth]   = useState(false);
  const [editingAccess,   setEditingAccess]   = useState(false);
  const [saving,          setSaving]          = useState(false);
  const [saveError,       setSaveError]       = useState('');
  const [fieldErrors,     setFieldErrors]     = useState<Record<string, string>>({});

  const [pFirst, setPFirst] = useState('');
  const [pLast,  setPLast]  = useState('');
  const [pPhone, setPPhone] = useState('');

  const [hDob,        setHDob]        = useState('');
  const [hGender,     setHGender]     = useState<Gender | ''>('');
  const [hBlood,      setHBlood]      = useState<BloodType | ''>('');
  const [hStatus,     setHStatus]     = useState<HealthStat | ''>('');
  const [hAllergies,  setHAllergies]  = useState<string[]>([]);
  const [hConditions, setHConditions] = useState<string[]>([]);

  const [aLevel,  setALevel]  = useState<AccessLevel>(initialDependent.accessLevel);
  const [aExpires,setAExpires]= useState('');
  const [aPerms,  setAPerms]  = useState<Perms>(() => permsFrom(initialDependent));

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setFetchError(null);
    setPatient(null);
    api.get<PatientDetail>(`/api/profile/user/${dependent.patientSystemId}`)
      .then(({ data }) => { if (!cancelled) setPatient(data); })
      .catch(err => { if (!cancelled) setFetchError(caregiverErrorMessage(err, 'Could not load full details. Showing available data.')); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [dependent.patientSystemId]);

  // Edits always start from the saved values, never from defaults.
  function startEditingPersonal() {
    if (!patient) return;
    setSaveError(''); setFieldErrors({});
    setPFirst(patient.firstName ?? '');
    setPLast(patient.lastName ?? '');
    setPPhone(patient.phone ?? '');
    setEditingPersonal(true);
  }
  function startEditingHealth() {
    if (!patient) return;
    setSaveError(''); setFieldErrors({});
    setHDob(dateOnlyParts(patient.dateOfBirth) ? (patient.dateOfBirth ?? '').slice(0, 10) : '');
    setHGender(oneOf(patient.gender, GENDERS));
    setHBlood(oneOf(patient.bloodType, BLOOD_TYPES));
    setHStatus(oneOf(patient.healthStatus, STATUSES));
    setHAllergies(parseList(patient.allergies));
    setHConditions(parseList(patient.chronicConditions));
    setEditingHealth(true);
  }
  function startEditingAccess() {
    setSaveError(''); setFieldErrors({});
    setALevel(dependent.accessLevel);
    // Pre-fill the date only: the stored value is a date-time ("until the end of that day")
    setAExpires(dateOnlyParts(dependent.expiresAt) ? (dependent.expiresAt ?? '').slice(0, 10) : '');
    setAPerms(permsFrom(dependent));
    setEditingAccess(true);
  }

  function changeAccessLevel(level: AccessLevel) {
    setALevel(level);
    if (level !== 'CUSTOM') setAPerms(prev => ({ ...prev, ...permissionsForLevel(level) }));
  }

  async function savePersonal() {
    if (!patient) return;
    const e: Record<string, string> = {};
    if (!pFirst.trim()) e.first = 'Required';
    if (!pLast.trim())  e.last  = 'Required';
    setFieldErrors(e);
    if (Object.keys(e).length) return;
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
    const e: Record<string, string> = {};
    const dob = hDob.trim();
    if (!dob) e.dob = 'Required';
    else if (!isValidDateOnly(dob)) e.dob = 'Use a real date as YYYY-MM-DD';
    else if ((daysUntilDateOnly(dob) ?? 0) > 0) e.dob = 'Date of birth cannot be in the future';
    if (!hGender) e.gender = 'Select a gender';
    if (!hBlood)  e.blood  = 'Select a blood type';
    setFieldErrors(e);
    if (Object.keys(e).length) return;
    setSaving(true); setSaveError('');
    try {
      // Only values the caregiver actually set are sent (no made-up defaults).
      const { data } = await api.put<PatientDetail>(`/api/profile/user/${dependent.patientSystemId}`, {
        dateOfBirth: dob, gender: hGender, bloodType: hBlood,
        ...(hStatus ? { healthStatus: hStatus } : {}),
        allergies: JSON.stringify(hAllergies), chronicConditions: JSON.stringify(hConditions),
      });
      setPatient(data);
      setEditingHealth(false);
    } catch (err) { setSaveError(caregiverSaveErrorMessage(err, 'Failed to save health profile.')); }
    finally { setSaving(false); }
  }

  const currentExpiry = dateOnlyParts(dependent.expiresAt) ? (dependent.expiresAt ?? '').slice(0, 10) : null;

  async function saveAccess() {
    const e: Record<string, string> = {};
    const expiry = aExpires.trim();
    if (expiry) {
      if (!isValidDateOnly(expiry)) e.expires = 'Use a real date as YYYY-MM-DD';
      else if ((daysUntilDateOnly(expiry) ?? 0) < 0) e.expires = 'The end date cannot be in the past';
      // Valid YYYY-MM-DD strings compare correctly as text
      else if (currentExpiry && expiry > currentExpiry) e.expires = `You cannot extend your access past ${formatDateOnly(currentExpiry)}`;
    }
    setFieldErrors(e);
    if (Object.keys(e).length) return;
    setSaving(true); setSaveError('');
    try {
      // The backend only lets a caregiver reduce their own access. Messaging and video calls are left out
      // (= unchanged): only the patient can turn them on.
      const { data } = await api.patch<CaregiverDependent>(`/api/caregiver/dependents/${dependent.accessId}`, {
        accessLevel:              aLevel,
        expiresAt:                expiry || null, // null keeps the current expiry
        canViewRecords:           aPerms.canViewRecords,
        canViewAppointments:      aPerms.canViewAppointments,
        canViewMedications:       aPerms.canViewMedications,
        canViewLabResults:        aPerms.canViewLabResults,
        canViewImaging:           aPerms.canViewImaging,
        canViewConsultations:     aPerms.canViewConsultations,
        canScheduleAppointments:  aPerms.canScheduleAppointments,
        canManageCarePlan:        aPerms.canManageCarePlan,
      });
      if (data) setDependent(prev => ({ ...prev, ...data }));
      setEditingAccess(false);
    } catch (err) { setSaveError(caregiverSaveErrorMessage(err, 'Failed to save access settings.')); }
    finally { setSaving(false); }
  }

  const name       = patient ? `${patient.firstName} ${patient.lastName}`.trim() : dependent.patientName ?? 'Unknown Patient';
  const firstName  = (patient?.firstName ?? dependent.patientName?.split(' ')[0] ?? '').trim() || 'the patient';
  const dob        = patient ? patient.dateOfBirth : dependent.patientDateOfBirth;
  const gender     = patient ? patient.gender : dependent.patientGender;
  const email      = patient?.email ?? dependent.patientEmail;
  const phone      = patient?.phone;
  const createdAt  = patient?.createdAt;
  const memberSince = dateOnlyParts(createdAt) ? String(dateOnlyParts(createdAt)!.y) : '—';

  // The backend redacts blood type, allergies, conditions and status (sends null) when records are not shared.
  const recordsShared = patient
    ? patient.healthStatus != null || patient.bloodType != null || patient.allergies != null || patient.chronicConditions != null
    : dependent.patientHealthStatus != null || dependent.patientBloodType != null;
  const status     = patient ? patient.healthStatus : dependent.patientHealthStatus ?? null;
  const bloodType  = patient ? patient.bloodType : dependent.patientBloodType;
  const allergies  = parseList(patient?.allergies);
  const conditions = parseList(patient?.chronicConditions);

  // Same rule as the backend (PatientPermission.MANAGE_PROFILE): FULL_ACCESS, or can_manage_care_plan and not VIEW_ONLY.
  const accessActive   = dependent.accessStatus !== 'EXPIRED' && dependent.accessStatus !== 'REVOKED';
  const canEditProfile = accessActive && (dependent.accessLevel === 'FULL_ACCESS' || (!!dependent.canManageCarePlan && dependent.accessLevel !== 'VIEW_ONLY'));
  const canEditPersonal = canEditProfile && !!patient;
  const canEditHealth   = canEditProfile && !!patient && recordsShared;

  const authorizedBy = !dependent.authorizedBySystemId ? '—'
    : user && dependent.authorizedBySystemId === user.system_id ? 'You'
    : dependent.authorizedBySystemId === dependent.patientSystemId ? name
    : 'Another account';

  const sc = recordsShared ? healthColors(status) : healthColors(null);
  const dc = dotColor(recordsShared ? status : null);
  const lc = accessColors(dependent.accessLevel);
  const statusLabel = recordsShared ? capitalize(status) : 'Not shared';

  return (
    <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
    <ScrollView
      style={styles.scroll}
      contentContainerStyle={{ padding: s(14), paddingBottom: s(24) }}
      showsVerticalScrollIndicator={false}
      keyboardShouldPersistTaps="handled"
      keyboardDismissMode={Platform.OS === 'ios' ? 'interactive' : 'on-drag'}
    >
      {/* Back header */}
      <View style={[styles.backHeader, { marginBottom: s(14), gap: s(8) }]}>
        <TouchableOpacity style={[styles.backBtn, { gap: s(5), width: s(64) }]} onPress={onBack} activeOpacity={0.7} accessibilityRole="button" accessibilityLabel="Back to dashboard">
          <Ionicons name="arrow-back-outline" size={s(18)} color={PURPLE} importantForAccessibility="no" />
          <Text style={[styles.backText, { fontSize: s(14) }]}>Back</Text>
        </TouchableOpacity>
        <Text style={[styles.headerTitle, { fontSize: s(16), flexShrink: 1 }]} accessibilityRole="header" numberOfLines={1}>Patient Profile</Text>
        <View style={{ width: s(64) }} />
      </View>

      {fetchError ? <ErrorBanner message={fetchError} /> : null}
      {saveError ? <ErrorBanner message={saveError} /> : null}

      {/* Message the care team (or why not yet) */}
      <View style={{ marginBottom: s(12) }}>
        <MobileMessageCareTeamButton dependent={dependent} />
      </View>

      {/* Hero */}
      <View style={[styles.card, { borderRadius: s(14), padding: s(14), marginBottom: s(12) }]}>
        <View style={[styles.heroRow, { gap: s(12) }]}>
          <View importantForAccessibility="no-hide-descendants" accessibilityElementsHidden>
            <View style={{ width: s(68), height: s(68), borderRadius: s(34), backgroundColor: avatarColor(name), alignItems: 'center', justifyContent: 'center' }}>
              <Text style={{ color: WHITE, fontSize: s(20), fontWeight: '700' }}>{initials(name)}</Text>
            </View>
            <View style={{ width: s(11), height: s(11), borderRadius: s(6), backgroundColor: dc, borderWidth: 2, borderColor: WHITE, position: 'absolute', bottom: s(2), right: s(2) }} />
          </View>
          <View style={{ flex: 1, minWidth: 0, gap: s(3) }}>
            <View style={[styles.heroTopRow, { gap: s(6) }]}>
              <Text style={[styles.heroName, { fontSize: s(17) }]} numberOfLines={1} accessibilityRole="header">{name}</Text>
              <View
                style={[styles.healthPill, { backgroundColor: sc.bg, borderRadius: s(12), paddingHorizontal: s(8), paddingVertical: s(3) }]}
                accessible
                accessibilityLabel={`Health status: ${statusLabel}`}
              >
                <Text style={{ fontSize: s(11), fontWeight: '600', color: sc.text }}>{statusLabel}</Text>
              </View>
            </View>
            <Text style={[styles.heroRel, { fontSize: s(12) }]} numberOfLines={1}>{capitalize(dependent.relationship)}</Text>
            {loading
              ? <ActivityIndicator size="small" color={PURPLE} style={{ marginTop: s(4) }} accessibilityLabel="Loading details" />
              : <View style={{ gap: s(2), marginTop: s(2) }}>
                  {email ? <View style={[styles.metaRow, { gap: s(4) }]}><Ionicons name="mail-outline" size={s(11)} color={GRAY} importantForAccessibility="no" /><Text style={[styles.metaText, { fontSize: s(11) }]} numberOfLines={1}>{email}</Text></View> : null}
                  {phone ? <View style={[styles.metaRow, { gap: s(4) }]}><Ionicons name="call-outline" size={s(11)} color={GRAY} importantForAccessibility="no" /><Text style={[styles.metaText, { fontSize: s(11) }]} numberOfLines={1}>{phone}</Text></View> : null}
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
            { label: 'Blood Type',   value: recordsShared ? bloodType ?? '—' : 'Not shared' },
            { label: 'Member Since', value: memberSince },
          ].map(t => (
            <View key={t.label} style={[styles.overviewTile, { flex: 1, minWidth: '45%', borderRadius: s(10), padding: s(10) }]} accessible accessibilityLabel={`${t.label}: ${t.value}`}>
              <Text style={[styles.overviewLabel, { fontSize: s(10) }]} numberOfLines={1}>{t.label}</Text>
              <Text style={[styles.overviewValue, { fontSize: s(15) }]} numberOfLines={1}>{t.value}</Text>
            </View>
          ))}
        </View>
      </View>

      {/* ── Personal Details ── */}
      <Card title="Personal Details" onEdit={canEditPersonal && !editingPersonal ? startEditingPersonal : undefined}>
        {editingPersonal ? (
          <View style={{ gap: s(10) }}>
            <View style={{ flexDirection: 'row', gap: s(10) }}>
              <View style={{ flex: 1 }}>
                <MLabel text="First Name" required />
                <MInput value={pFirst} onChange={setPFirst} placeholder="First name" label="First name" error={fieldErrors.first} autoCapitalize="words" />
              </View>
              <View style={{ flex: 1 }}>
                <MLabel text="Last Name" required />
                <MInput value={pLast} onChange={setPLast} placeholder="Last name" label="Last name" error={fieldErrors.last} autoCapitalize="words" />
              </View>
            </View>
            <MLabel text="Phone" />
            <MInput value={pPhone} onChange={setPPhone} placeholder="+263…" keyboardType="phone-pad" label="Phone" />
            <SaveRow onSave={savePersonal} onCancel={() => { setFieldErrors({}); setEditingPersonal(false); }} saving={saving} />
          </View>
        ) : (
          <View style={{ gap: s(8) }}>
            {[
              { label: 'Full Name',    value: name        },
              { label: 'Email',        value: email ?? '—' },
              { label: 'Phone',        value: phone ?? '—' },
              { label: 'Member Since', value: memberSince },
            ].map((row, i, arr) => (
              <View key={row.label}>
                <InfoRow label={row.label} value={row.value} />
                {i < arr.length - 1 && <View style={[styles.sep, { marginTop: s(8) }]} />}
              </View>
            ))}
          </View>
        )}
      </Card>

      {/* ── Health Profile ── */}
      <Card title="Health Profile" onEdit={canEditHealth && !editingHealth ? startEditingHealth : undefined}>
        {editingHealth ? (
          <View style={{ gap: s(12) }}>
            <View>
              <MLabel text="Date of Birth" required />
              <MInput value={hDob} onChange={setHDob} placeholder="YYYY-MM-DD" label="Date of birth, YYYY-MM-DD" error={fieldErrors.dob} />
            </View>
            <View>
              <MLabel text="Gender" required />
              <MChipRow<Gender> label="Gender" options={[{value:'MALE',label:'Male'},{value:'FEMALE',label:'Female'},{value:'OTHER',label:'Other'}]} value={hGender} onChange={setHGender} />
              {fieldErrors.gender ? <Text style={[styles.errorText, { fontSize: s(11), marginTop: s(3) }]}>{fieldErrors.gender}</Text> : null}
            </View>
            <View>
              <MLabel text="Blood Type" required />
              <MChipRow<BloodType> label="Blood type" options={BLOOD_TYPES.map(v=>({value:v,label:v}))} value={hBlood} onChange={setHBlood} />
              {fieldErrors.blood ? <Text style={[styles.errorText, { fontSize: s(11), marginTop: s(3) }]}>{fieldErrors.blood}</Text> : null}
            </View>
            <View>
              <MLabel text="Health Status" />
              <MChipRow<HealthStat> label="Health status" options={[{value:'STABLE',label:'Stable'},{value:'MONITOR',label:'Monitor'},{value:'CRITICAL',label:'Critical'}]} value={hStatus} onChange={setHStatus} />
            </View>
            <View>
              <MLabel text="Known Allergies" />
              <MTagInput label="Known allergies" tags={hAllergies} onAdd={t=>setHAllergies(a=>[...a,t])} onRemove={i=>setHAllergies(a=>a.filter((_,idx)=>idx!==i))} placeholder="e.g. Penicillin…" />
            </View>
            <View>
              <MLabel text="Chronic Conditions" />
              <MTagInput label="Chronic conditions" tags={hConditions} onAdd={t=>setHConditions(c=>[...c,t])} onRemove={i=>setHConditions(c=>c.filter((_,idx)=>idx!==i))} placeholder="e.g. Hypertension…" />
            </View>
            <SaveRow onSave={saveHealth} onCancel={() => { setFieldErrors({}); setEditingHealth(false); }} saving={saving} />
          </View>
        ) : !recordsShared ? (
          <Text style={[styles.noneText, { fontSize: s(12) }]}>
            {loading ? 'Loading…' : `${firstName}'s health details are not shared with you.`}
          </Text>
        ) : (
          <View style={{ gap: s(10) }}>
            <View style={[styles.metaRow, { gap: s(6) }]}>
              <Ionicons name="heart-outline" size={s(15)} color={dc} importantForAccessibility="no" />
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
      <Card title="Your Access" onEdit={accessActive && !editingAccess ? startEditingAccess : undefined}>
        {editingAccess ? (
          <View style={{ gap: s(12) }}>
            <Text style={[styles.hint, { fontSize: s(11) }]}>
              You can reduce your own access here. Only {firstName} can give you more.
            </Text>
            <View>
              <MLabel text="Access Level" required />
              <View style={{ gap: s(8) }} accessibilityRole="radiogroup" accessibilityLabel="Access level">
                {LEVEL_OPTIONS.map(opt => (
                  <TouchableOpacity
                    key={opt.value}
                    style={[styles.accessCard, { borderRadius:s(12), padding:s(12), gap:s(10) }, aLevel===opt.value && { borderColor:opt.color, borderWidth:2 }]}
                    onPress={() => changeAccessLevel(opt.value)} activeOpacity={0.7}
                    accessibilityRole="radio"
                    accessibilityLabel={`${opt.label}: ${opt.sub}`}
                    accessibilityState={{ selected: aLevel === opt.value, checked: aLevel === opt.value }}
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
                <Text style={[styles.permBoxTitle, { fontSize: s(13), marginBottom: s(8) }]} accessibilityRole="header">Individual Permissions</Text>
                {PERMS.map(p => (
                  <MPermSwitch key={p.key} label={p.label} value={aPerms[p.key]} onChange={v => setAPerms(prev => ({ ...prev, [p.key]: v }))} />
                ))}
              </View>
            )}

            <View>
              <MLabel text="Access End Date (optional)" />
              <MInput
                value={aExpires}
                onChange={setAExpires}
                placeholder="YYYY-MM-DD"
                label="Access end date, YYYY-MM-DD, optional"
                error={fieldErrors.expires}
               
              />
              <Text style={[styles.hint, { fontSize: s(11), marginTop: s(3) }]}>
                {currentExpiry
                  ? `Currently ends ${formatDateOnly(currentExpiry)}. You can choose an earlier date, not a later one. Leave blank to keep it.`
                  : 'Your access has no end date. Enter a date to end it then, or leave blank to keep it open.'}
              </Text>
            </View>

            <SaveRow onSave={saveAccess} onCancel={() => { setFieldErrors({}); setEditingAccess(false); }} saving={saving} />
          </View>
        ) : (
          <View style={{ gap: s(8) }}>
            {[
              { label: 'Relationship',   value: capitalize(dependent.relationship) },
              { label: 'Authorized On',  value: formatDateOnly(dependent.authorizedDate) },
              { label: 'Expires',        value: dependent.expiresAt ? formatDateOnly(dependent.expiresAt) : 'No expiry' },
              { label: 'Authorized By',  value: authorizedBy },
            ].map((row, i, arr) => (
              <View key={row.label}>
                <InfoRow label={row.label} value={row.value} />
                {i < arr.length - 1 && <View style={[styles.sep, { marginTop: s(8) }]} />}
              </View>
            ))}
            <View style={styles.sep} />
            <View style={[styles.infoRow, { gap: s(8) }]}>
              <Text style={[styles.infoLabel, { fontSize: s(12) }]}>Access Level</Text>
              <View style={[styles.badge, { backgroundColor: lc.bg, borderRadius: s(12), paddingHorizontal: s(8), paddingVertical: s(4) }]}>
                <Text style={{ fontSize: s(12), fontWeight: '600', color: lc.text }}>{accessLabel(dependent.accessLevel)}</Text>
              </View>
            </View>
            <View style={[styles.sep, { marginVertical: s(2) }]} />
            <Text style={[styles.subLabel, { fontSize: s(11) }]}>Permissions</Text>
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: s(6) }}>
              {PERMS.map(p => {
                const granted = !!dependent[p.key];
                return (
                  <View
                    key={p.key}
                    style={{ width: '30%', alignItems: 'center', gap: s(4), padding: s(7), backgroundColor: BG, borderRadius: s(10), borderWidth: 1, borderColor: BORDER }}
                    accessible
                    accessibilityLabel={`${p.label}: ${granted ? 'allowed' : 'not allowed'}`}
                  >
                    <View style={{ width: s(26), height: s(26), borderRadius: s(13), backgroundColor: granted ? '#D1FAE5' : '#F3F4F6', alignItems: 'center', justifyContent: 'center' }}>
                      <Ionicons name={granted ? 'checkmark-circle' : 'close-circle'} size={s(13)} color={granted ? GREEN : GRAY2} />
                    </View>
                    <Text style={{ fontSize: s(9), color: TEXT, fontWeight: '500', textAlign: 'center' }} numberOfLines={2}>{p.label}</Text>
                  </View>
                );
              })}
            </View>
          </View>
        )}
      </Card>

      {/* ── Quick Actions: only the records this loved one shares ── */}
      {onOpenRecord && (
        <Card title="Quick Actions">
          <View style={{ gap: s(8) }}>
            {QUICK_ACTIONS.filter(a => !a.flag || dependent.accessLevel === 'FULL_ACCESS' || !!dependent[a.flag]).map(a => (
              <TouchableOpacity
                key={a.key}
                style={[styles.quickBtn, { borderRadius: s(10), paddingHorizontal: s(12), paddingVertical: s(10), gap: s(10) }]}
                onPress={() => onOpenRecord(a.key)}
                activeOpacity={0.7}
                accessibilityRole="button"
                accessibilityLabel={`${a.label} for ${name}`}
              >
                <Ionicons name={a.icon} size={s(16)} color={PURPLE} importantForAccessibility="no" />
                <Text style={[styles.quickBtnText, { fontSize: s(13) }]} numberOfLines={1}>{a.label}</Text>
                <Ionicons name="chevron-forward" size={s(14)} color={GRAY2} importantForAccessibility="no" />
              </TouchableOpacity>
            ))}
          </View>
        </Card>
      )}

    </ScrollView>
    </KeyboardAvoidingView>
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
  infoVal:   { color: TEXT, fontWeight: '600', flexShrink: 1 },
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
  errorText:     { color: RED },

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

  quickBtn:     { flexDirection: 'row', alignItems: 'center', borderWidth: 1, borderColor: BORDER, backgroundColor: BG },
  quickBtnText: { flex: 1, color: TEXT, fontWeight: '600' },

});
