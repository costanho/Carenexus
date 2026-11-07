import { Ionicons } from '@expo/vector-icons';
import * as Clipboard from 'expo-clipboard';
import { useEffect, useState } from 'react';
import {
  ActivityIndicator, KeyboardAvoidingView, Platform, ScrollView, StyleSheet, Text,
  TextInput, TouchableOpacity, useWindowDimensions, View,
} from 'react-native';
import { api } from '@/lib/auth/auth.interceptor';
import { daysUntilDateOnly, isValidDateOnly } from '@/lib/ui/date-only';

const WHITE      = '#FFFFFF';
const TEXT       = '#111827';
const GRAY       = '#6B7280';
const GRAY2      = '#9CA3AF';
const BORDER     = '#E5E7EB';
const BG         = '#F9FAFB';
const PURPLE     = '#7C3AED';
const PURPLE_BG  = '#F5F3FF';
const PURPLE_LIGHT = '#EDE9FE';
const GREEN      = '#10B981';
const ORANGE     = '#F59E0B';
const RED        = '#EF4444';

// ─── Scaling ──────────────────────────────────────────────────────────────────

function useScale() {
  const { width, height } = useWindowDimensions();
  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));
  return { s, sh, width };
}

// ─── Types ────────────────────────────────────────────────────────────────────

type AccessLevel = 'FULL_ACCESS' | 'VIEW_ONLY' | 'EDIT_ONLY' | 'CUSTOM';
type Gender      = 'MALE' | 'FEMALE' | 'OTHER';
type HealthStat  = 'STABLE' | 'MONITOR' | 'CRITICAL';
type BloodType   = 'A+' | 'A-' | 'B+' | 'B-' | 'O+' | 'O-' | 'AB+' | 'AB-';

interface PersonalForm {
  first_name: string; last_name: string;
  email: string; phone: string; password: string;
}
interface HealthForm {
  date_of_birth: string; gender: Gender | '';
  blood_type: BloodType | ''; health_status: HealthStat;
  allergies: string[]; chronic_conditions: string[];
}
interface AccessForm {
  relationship: string; access_level: AccessLevel; expires_at: string;
  can_view_records: boolean; can_view_appointments: boolean;
  can_view_medications: boolean; can_view_lab_results: boolean;
  can_view_imaging: boolean;
  can_view_consultations: boolean; can_schedule_appointments: boolean;
  can_manage_care_plan: boolean;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

// Temporary password: 12 random characters with at least one letter and one digit (backend rule:
// at least 8 characters with a letter and a number).
function generatePassword() {
  const letters = 'abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ';
  const digits  = '23456789';
  const all     = letters + digits;
  const pick = (set: string) => {
    const buf = new Uint32Array(1);
    if (typeof globalThis.crypto?.getRandomValues === 'function') globalThis.crypto.getRandomValues(buf);
    else buf[0] = Math.floor(Math.random() * 0xffffffff);
    return set[buf[0] % set.length];
  };
  const chars = [pick(letters), pick(digits), ...Array.from({ length: 10 }, () => pick(all))];
  for (let i = chars.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [chars[i], chars[j]] = [chars[j], chars[i]];
  }
  return chars.join('');
}

// Dates are typed as YYYY-MM-DD and checked on their Y-M-D parts only (see lib/ui/date-only): parsing them
// through Date shifts the day in time zones ahead of UTC. An expiry date means "until the end of that day".

// Messaging is not part of an access level: it is a separate request the patient approves, so it stays off here.
function permissionsForLevel(level: AccessLevel): Partial<AccessForm> {
  if (level === 'FULL_ACCESS') return {
    can_view_records: true, can_view_appointments: true, can_view_medications: true,
    can_view_lab_results: true, can_view_imaging: true,
    can_view_consultations: true, can_schedule_appointments: true, can_manage_care_plan: true,
  };
  if (level === 'VIEW_ONLY') return {
    can_view_records: true, can_view_appointments: true, can_view_medications: true,
    can_view_lab_results: true, can_view_imaging: false,
    can_view_consultations: true, can_schedule_appointments: false, can_manage_care_plan: false,
  };
  if (level === 'EDIT_ONLY') return {
    can_view_records: true, can_view_appointments: true, can_view_medications: true,
    can_view_lab_results: true, can_view_imaging: true,
    can_view_consultations: true, can_schedule_appointments: true, can_manage_care_plan: false,
  };
  return {};
}

// ─── Sub-components ───────────────────────────────────────────────────────────

function MLabel({ text, required, s }: { text: string; required?: boolean; s: (n: number) => number }) {
  return (
    <Text style={[styles.label, { fontSize: s(13), marginBottom: s(4) }]}>
      {text}{required && <Text style={{ color: RED }}> *</Text>}
    </Text>
  );
}

function MInput({ value, onChange, placeholder, error, secureTextEntry, keyboardType, label, s }: {
  value: string; onChange: (v: string) => void; placeholder?: string;
  error?: string; secureTextEntry?: boolean; keyboardType?: any;
  /** Spoken name of the field (the visible label is a separate Text). */
  label?: string;
  s: (n: number) => number;
}) {
  return (
    <View style={{ marginBottom: s(4) }}>
      <TextInput
        style={[styles.input, { fontSize: s(14), borderRadius: s(10), paddingHorizontal: s(14), paddingVertical: s(11) }, !!error && styles.inputError]}
        value={value}
        onChangeText={onChange}
        placeholder={placeholder}
        placeholderTextColor={GRAY2}
        secureTextEntry={secureTextEntry}
        keyboardType={keyboardType}
        autoCapitalize="none"
        accessibilityLabel={label}
        accessibilityHint={error || undefined}
      />
      {error ? <Text style={[styles.errorText, { fontSize: s(11), marginTop: s(3) }]} accessibilityLiveRegion="polite">{error}</Text> : null}
    </View>
  );
}

function MSelectRow<T extends string>({ options, value, onChange, label, s }: {
  options: { value: T; label: string }[]; value: T | ''; onChange: (v: T) => void;
  /** Group name read before each option, e.g. "Gender". */
  label?: string;
  s: (n: number) => number;
}) {
  return (
    <View style={[styles.selectRow, { gap: s(8), marginBottom: s(4) }]} accessibilityRole="radiogroup" accessibilityLabel={label}>
      {options.map(o => (
        <TouchableOpacity
          key={o.value}
          style={[styles.selectChip, { borderRadius: s(8), paddingHorizontal: s(12), paddingVertical: s(8) }, value === o.value && styles.selectChipActive]}
          activeOpacity={0.7}
          onPress={() => onChange(o.value)}
          accessibilityRole="radio"
          accessibilityLabel={label ? `${label}: ${o.label}` : o.label}
          accessibilityState={{ selected: value === o.value, checked: value === o.value }}
        >
          <Text style={[styles.selectChipText, { fontSize: s(12) }, value === o.value && styles.selectChipTextActive]}>
            {o.label}
          </Text>
        </TouchableOpacity>
      ))}
    </View>
  );
}

function MTagInput({ tags, onAdd, onRemove, placeholder, s }: {
  tags: string[]; onAdd: (t: string) => void; onRemove: (i: number) => void;
  placeholder?: string; s: (n: number) => number;
}) {
  const [text, setText] = useState('');
  function add() {
    const t = text.trim();
    if (t && !tags.includes(t)) { onAdd(t); setText(''); }
  }
  return (
    <View style={{ gap: s(8), marginBottom: s(4) }}>
      <View style={[styles.tagChips, { gap: s(6) }]}>
        {tags.map((t, i) => (
          <View key={i} style={[styles.tagChip, { borderRadius: s(20), paddingHorizontal: s(10), paddingVertical: s(4), gap: s(5) }]}>
            <Text style={[styles.tagChipText, { fontSize: s(12), flexShrink: 1 }]}>{t}</Text>
            <TouchableOpacity
              onPress={() => onRemove(i)}
              hitSlop={{ top: 6, right: 6, bottom: 6, left: 6 }}
              accessibilityRole="button"
              accessibilityLabel={`Remove ${t}`}
            >
              <Ionicons name="close" size={s(12)} color={PURPLE} importantForAccessibility="no" />
            </TouchableOpacity>
          </View>
        ))}
      </View>
      <View style={[styles.tagInputRow, { gap: s(8) }]}>
        <TextInput
          style={[styles.tagInput, { flex: 1, fontSize: s(13), borderRadius: s(10), paddingHorizontal: s(12), paddingVertical: s(9) }]}
          value={text}
          onChangeText={setText}
          placeholder={placeholder ?? 'Type and press Add…'}
          placeholderTextColor={GRAY2}
          onSubmitEditing={add}
          returnKeyType="done"
          blurOnSubmit={false}
          accessibilityLabel={placeholder}
        />
        <TouchableOpacity
          style={[styles.tagAddBtn, { borderRadius: s(10), paddingHorizontal: s(14), paddingVertical: s(9) }]}
          onPress={add}
          activeOpacity={0.7}
          accessibilityRole="button"
          accessibilityLabel="Add"
        >
          <Text style={[styles.tagAddBtnText, { fontSize: s(13) }]}>Add</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

function MPermSwitch({ label, value, onChange, s }: {
  label: string; value: boolean; onChange: (v: boolean) => void; s: (n: number) => number;
}) {
  return (
    <TouchableOpacity
      style={[styles.permRow, { paddingVertical: s(11), borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: BORDER }]}
      activeOpacity={0.7}
      onPress={() => onChange(!value)}
      accessibilityRole="switch"
      accessibilityLabel={label}
      accessibilityState={{ checked: value }}
    >
      <Text style={[styles.permLabel, { fontSize: s(13) }]}>{label}</Text>
      <View style={[styles.toggle, { width: s(40), height: s(22), borderRadius: s(11) }, value && styles.toggleOn]}>
        <View style={[styles.toggleThumb, { width: s(18), height: s(18), borderRadius: s(9) }, value && styles.toggleThumbOn]} />
      </View>
    </TouchableOpacity>
  );
}

// ─── Stepper ──────────────────────────────────────────────────────────────────

const STEPS = ['Personal', 'Health', 'Access'];

function MStepper({ current, s }: { current: number; s: (n: number) => number }) {
  return (
    <View
      style={[styles.stepper, { borderRadius: s(14), padding: s(14), marginBottom: s(16) }]}
      accessible
      accessibilityLabel={`Step ${current + 1} of ${STEPS.length}: ${STEPS[current]}`}
    >
      {STEPS.map((label, i) => {
        const done   = i < current;
        const active = i === current;
        return (
          <View key={i} style={styles.stepItem}>
            <View style={[
              styles.stepCircle,
              { width: s(28), height: s(28), borderRadius: s(14) },
              active && styles.stepCircleActive,
              done   && styles.stepCircleDone,
            ]}>
              {done
                ? <Ionicons name="checkmark" size={s(13)} color={WHITE} />
                : <Text style={[styles.stepNum, { fontSize: s(12) }, (active || done) && { color: WHITE }]}>{i + 1}</Text>}
            </View>
            <Text style={[styles.stepLabel, { fontSize: s(10) }, active && styles.stepLabelActive]} numberOfLines={1}>{label}</Text>
            {i < STEPS.length - 1 && <View style={[styles.stepLine, done && styles.stepLineDone]} />}
          </View>
        );
      })}
    </View>
  );
}

// ─── Section Card ─────────────────────────────────────────────────────────────

function MCard({ title, sub, s, children }: { title: string; sub?: string; s: (n: number) => number; children: React.ReactNode }) {
  return (
    <View style={[styles.card, { borderRadius: s(14), padding: s(16), gap: s(12), marginBottom: s(16) }]}>
      <Text style={[styles.cardTitle, { fontSize: s(16) }]} accessibilityRole="header">{title}</Text>
      {sub && <Text style={[styles.cardSub, { fontSize: s(12), marginTop: -s(6) }]}>{sub}</Text>}
      {children}
    </View>
  );
}

// ─── Main Component ───────────────────────────────────────────────────────────

export default function PatientProxyMobileAddDependent({
  onDone,
  onCancel,
}: {
  onDone:   () => void;
  onCancel: () => void;
}) {
  const { s } = useScale();
  const [step, setStep] = useState(0);

  const [personal, setPersonal] = useState<PersonalForm>({
    first_name: '', last_name: '', email: '', phone: '',
    password: generatePassword(),
  });
  const [health, setHealth] = useState<HealthForm>({
    date_of_birth: '', gender: '', blood_type: '',
    health_status: 'STABLE', allergies: [], chronic_conditions: [],
  });
  const [access, setAccess] = useState<AccessForm>({
    relationship: '', access_level: 'FULL_ACCESS', expires_at: '',
    can_view_records: true, can_view_appointments: true, can_view_medications: true,
    can_view_lab_results: true, can_view_imaging: true,
    can_view_consultations: true, can_schedule_appointments: true, can_manage_care_plan: true,
  });

  const [errors,     setErrors]     = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);
  const [submitErr,  setSubmitErr]  = useState('');
  const [success,    setSuccess]    = useState(false);
  const [pwVisible,  setPwVisible]  = useState(false);
  const [pwCopied,   setPwCopied]   = useState(false);

  // "Copied" reverts after a moment so the button can be used again.
  useEffect(() => {
    if (!pwCopied) return;
    const t = setTimeout(() => setPwCopied(false), 2500);
    return () => clearTimeout(t);
  }, [pwCopied]);

  async function copyPassword() {
    try {
      await Clipboard.setStringAsync(personal.password);
      setPwCopied(true);
    } catch {
      // Clipboard unavailable: the password can still be revealed and long-pressed to copy.
      setPwCopied(false);
    }
  }

  function setP(k: keyof PersonalForm, v: string) {
    setPersonal(p => ({ ...p, [k]: v }));
    if (errors[k]) setErrors(e => ({ ...e, [k]: '' }));
  }
  function setH(k: keyof HealthForm, v: any) {
    setHealth(h => ({ ...h, [k]: v }));
    if (errors[k]) setErrors(e => ({ ...e, [k]: '' }));
  }
  function setA(k: keyof AccessForm, v: any) {
    setAccess(a => ({ ...a, [k]: v }));
    if (errors[k]) setErrors(e => ({ ...e, [k]: '' }));
  }

  function changeAccessLevel(level: AccessLevel) {
    setAccess(a => ({ ...a, access_level: level, ...permissionsForLevel(level) }));
  }

  function regeneratePassword() {
    setP('password', generatePassword());
  }

  function validateStep1() {
    const e: Record<string, string> = {};
    if (!personal.first_name.trim()) e.first_name = 'Required';
    if (!personal.last_name.trim())  e.last_name  = 'Required';
    if (!personal.email.trim())      e.email      = 'Required';
    else if (!/\S+@\S+\.\S+/.test(personal.email)) e.email = 'Invalid email';
    if (!personal.phone.trim())      e.phone      = 'Required';
    if (personal.password.length < 8 || !/[A-Za-z]/.test(personal.password) || !/\d/.test(personal.password))
      e.password = 'At least 8 characters with a letter and a number';
    setErrors(e);
    return Object.keys(e).length === 0;
  }

  function validateStep2() {
    const e: Record<string, string> = {};
    const dob = health.date_of_birth.trim();
    if (!dob) e.date_of_birth = 'Required';
    else if (!isValidDateOnly(dob)) e.date_of_birth = 'Use a real date as YYYY-MM-DD';
    else if ((daysUntilDateOnly(dob) ?? 0) > 0) e.date_of_birth = 'Date of birth cannot be in the future';
    if (!health.gender)        e.gender        = 'Select a gender';
    if (!health.blood_type)    e.blood_type    = 'Select blood type';
    setErrors(e);
    return Object.keys(e).length === 0;
  }

  function validateStep3() {
    const e: Record<string, string> = {};
    if (!access.relationship.trim()) e.relationship = 'Required';
    const expiry = access.expires_at.trim();
    if (expiry && !isValidDateOnly(expiry)) e.expires_at = 'Use a real date as YYYY-MM-DD';
    else if (expiry && (daysUntilDateOnly(expiry) ?? 0) < 0) e.expires_at = 'Expiry date cannot be in the past';
    setErrors(e);
    return Object.keys(e).length === 0;
  }

  function nextStep() {
    if (step === 0 && !validateStep1()) return;
    if (step === 1 && !validateStep2()) return;
    setStep(s => s + 1);
  }

  async function handleSubmit() {
    if (!validateStep3()) return;
    setSubmitting(true);
    setSubmitErr('');

    try {
      // One atomic call: the backend creates the patient account, the health profile and this caregiver's
      // access grant in a single transaction (nothing is created if any part fails). The caregiver is taken
      // from the signed-in session, never from this body.
      await api.post('/api/caregiver/dependents', {
        firstName:   personal.first_name.trim(),
        lastName:    personal.last_name.trim(),
        email:       personal.email.trim().toLowerCase(),
        phone:       personal.phone.trim(),
        password:    personal.password,

        dateOfBirth:       health.date_of_birth.trim(),
        gender:            health.gender || null,
        bloodType:         health.blood_type || null,
        healthStatus:      health.health_status,
        allergies:         health.allergies,
        chronicConditions: health.chronic_conditions,

        relationship:            access.relationship.trim().toLowerCase(),
        accessLevel:             access.access_level,
        expiresAt:               access.expires_at.trim() || null,
        canViewRecords:          access.can_view_records,
        canViewAppointments:     access.can_view_appointments,
        canViewMedications:      access.can_view_medications,
        canViewLabResults:       access.can_view_lab_results,
        canViewImaging:          access.can_view_imaging,
        // Never requested from here: messaging is a separate request the patient approves.
        canMessageCareTeam:      false,
        canViewConsultations:    access.can_view_consultations,
        canScheduleAppointments: access.can_schedule_appointments,
        canManageCarePlan:       access.can_manage_care_plan,
      });

      setSuccess(true);
    } catch (err: any) {
      const msg = err?.response?.data?.message
        ?? (err?.response ? 'Something went wrong. Please try again.' : 'Unable to reach the server. Please check your connection.');
      setSubmitErr(msg);
    } finally {
      setSubmitting(false);
    }
  }

  // ── Success ──────────────────────────────────────────────────────────────────
  if (success) {
    return (
      <ScrollView
        style={{ flex: 1, backgroundColor: BG }}
        contentContainerStyle={{ flexGrow: 1, alignItems: 'center', justifyContent: 'center', padding: s(24), paddingBottom: s(24) }}
        showsVerticalScrollIndicator={false}
      >
        <View style={[styles.successCard, { borderRadius: s(20), padding: s(28), gap: s(14), width: '100%' }]}>
          <View style={{ alignItems: 'center' }}>
            <Ionicons name="checkmark-circle" size={s(64)} color={GREEN} />
          </View>
          <Text style={[styles.successTitle, { fontSize: s(22), textAlign: 'center' }]} accessibilityRole="header">Loved One Added!</Text>
          <Text style={[styles.cardSub, { fontSize: s(13), textAlign: 'center', lineHeight: s(20) }]}>
            {personal.first_name} {personal.last_name} has been added as a linked patient under your care.
          </Text>

          <View style={[styles.credentialsBox, { borderRadius: s(12), padding: s(16), gap: s(10) }]}>
            <Text style={[styles.cardTitle, { fontSize: s(13), marginBottom: s(4) }]}>
              Login Credentials for {personal.first_name}
            </Text>
            <View style={[styles.credRow, { gap: s(8) }]}>
              <Text style={[styles.cardSub, { fontSize: s(12) }]}>Email</Text>
              <Text style={[styles.credValue, { fontSize: s(13) }]} numberOfLines={1} selectable>{personal.email}</Text>
            </View>
            <View style={[styles.credRow, { gap: s(8) }]}>
              <Text style={[styles.cardSub, { fontSize: s(12) }]}>Password</Text>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(6), flexShrink: 1 }}>
                <Text style={[styles.credValue, { fontSize: s(13) }]} selectable={pwVisible}>
                  {pwVisible ? personal.password : '••••••••••••'}
                </Text>
                <TouchableOpacity
                  onPress={() => setPwVisible(v => !v)}
                  hitSlop={{ top: 8, right: 8, bottom: 8, left: 8 }}
                  accessibilityRole="button"
                  accessibilityLabel={pwVisible ? 'Hide password' : 'Show password'}
                >
                  <Ionicons name={pwVisible ? 'eye-off-outline' : 'eye-outline'} size={s(16)} color={GRAY} importantForAccessibility="no" />
                </TouchableOpacity>
                <TouchableOpacity
                  onPress={copyPassword}
                  hitSlop={{ top: 8, right: 8, bottom: 8, left: 8 }}
                  accessibilityRole="button"
                  accessibilityLabel={pwCopied ? 'Password copied' : 'Copy password'}
                >
                  <Ionicons name={pwCopied ? 'checkmark-outline' : 'copy-outline'} size={s(16)} color={pwCopied ? '#10B981' : GRAY} importantForAccessibility="no" />
                </TouchableOpacity>
              </View>
            </View>
            <Text style={[styles.cardSub, { fontSize: s(11), fontStyle: 'italic', marginTop: s(2) }]}>
              Share these credentials with {personal.first_name} securely, in person or by phone. Do not send the password by email or SMS.
            </Text>
          </View>

          <TouchableOpacity
            style={[styles.primaryBtn, { borderRadius: s(12), paddingVertical: s(14), marginTop: s(4) }]}
            onPress={onDone}
            activeOpacity={0.8}
            accessibilityRole="button"
          >
            <Text style={[styles.primaryBtnText, { fontSize: s(15) }]}>Back to Dashboard</Text>
          </TouchableOpacity>
        </View>
      </ScrollView>
    );
  }

  // ── Form ─────────────────────────────────────────────────────────────────────
  // The top nav above already pads for the status bar, so no safe-area top inset here.
  return (
    <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
    <ScrollView
      style={{ flex: 1, backgroundColor: BG }}
      contentContainerStyle={{ padding: s(16), paddingBottom: s(24) }}
      showsVerticalScrollIndicator={false}
      keyboardShouldPersistTaps="handled"
      keyboardDismissMode={Platform.OS === 'ios' ? 'interactive' : 'on-drag'}
    >
      {/* Header */}
      <View style={[styles.pageHeader, { gap: s(4), marginBottom: s(16) }]}>
        <TouchableOpacity
          style={[styles.backBtn, { gap: s(6), marginBottom: s(6) }]}
          onPress={onCancel}
          activeOpacity={0.7}
          accessibilityRole="button"
          accessibilityLabel="Back to dashboard"
        >
          <Ionicons name="arrow-back-outline" size={s(18)} color={GRAY} importantForAccessibility="no" />
          <Text style={[styles.backBtnText, { fontSize: s(13) }]}>Back</Text>
        </TouchableOpacity>
        <Text style={[styles.successTitle, { fontSize: s(20) }]} accessibilityRole="header">Add Loved One</Text>
        <Text style={[styles.cardSub, { fontSize: s(12) }]}>Register a new patient under your proxy care</Text>
      </View>

      {/* Stepper */}
      <MStepper current={step} s={s} />

      {/* ── Step 1: Personal Details ── */}
      {step === 0 && (
        <MCard title="Personal Details" sub="Basic identity information for the new patient account" s={s}>
          <View style={{ gap: s(6) }}>
            <View style={{ flexDirection: 'row', gap: s(10) }}>
              <View style={{ flex: 1 }}>
                <MLabel text="First Name" required s={s} />
                <MInput value={personal.first_name} onChange={v => setP('first_name', v)} placeholder="Thomas" error={errors.first_name} label="First name" s={s} />
              </View>
              <View style={{ flex: 1 }}>
                <MLabel text="Last Name" required s={s} />
                <MInput value={personal.last_name} onChange={v => setP('last_name', v)} placeholder="Dube" error={errors.last_name} label="Last name" s={s} />
              </View>
            </View>

            <MLabel text="Email Address" required s={s} />
            <MInput value={personal.email} onChange={v => setP('email', v)} placeholder="thomas.dube@email.com" keyboardType="email-address" error={errors.email} label="Email address" s={s} />

            <MLabel text="Phone Number" required s={s} />
            <MInput value={personal.phone} onChange={v => setP('phone', v)} placeholder="+263771234567" keyboardType="phone-pad" error={errors.phone} label="Phone number" s={s} />

            <MLabel text="Temporary Password" s={s} />
            <View style={{ flexDirection: 'row', gap: s(8), alignItems: 'center' }}>
              <View style={{ flex: 1 }}>
                <MInput value={personal.password} onChange={v => setP('password', v)} secureTextEntry={!pwVisible} error={errors.password} label="Temporary password" s={s} />
              </View>
              <TouchableOpacity
                style={[styles.iconBtn, { width: s(42), height: s(42), borderRadius: s(10) }]}
                onPress={() => setPwVisible(v => !v)}
                accessibilityRole="button"
                accessibilityLabel={pwVisible ? 'Hide password' : 'Show password'}
              >
                <Ionicons name={pwVisible ? 'eye-off-outline' : 'eye-outline'} size={s(18)} color={GRAY} importantForAccessibility="no" />
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.iconBtn, { width: s(42), height: s(42), borderRadius: s(10) }]}
                onPress={regeneratePassword}
                accessibilityRole="button"
                accessibilityLabel="Generate a new password"
              >
                <Ionicons name="refresh-outline" size={s(18)} color={PURPLE} importantForAccessibility="no" />
              </TouchableOpacity>
            </View>
            <Text style={[styles.hint, { fontSize: s(11) }]}>
              Share this temporary password securely with the patient.
            </Text>
          </View>
        </MCard>
      )}

      {/* ── Step 2: Health Profile ── */}
      {step === 1 && (
        <MCard title="Health Profile" sub="Medical and demographic information" s={s}>
          <View style={{ gap: s(6) }}>
            <MLabel text="Date of Birth" required s={s} />
            <MInput value={health.date_of_birth} onChange={v => setH('date_of_birth', v)} placeholder="YYYY-MM-DD" error={errors.date_of_birth} label="Date of birth, YYYY-MM-DD" s={s} />

            <MLabel text="Gender" required s={s} />
            <MSelectRow<Gender>
              options={[{ value: 'MALE', label: 'Male' }, { value: 'FEMALE', label: 'Female' }, { value: 'OTHER', label: 'Other' }]}
              value={health.gender}
              onChange={v => setH('gender', v)}
              label="Gender"
              s={s}
            />
            {errors.gender ? <Text style={[styles.errorText, { fontSize: s(11) }]}>{errors.gender}</Text> : null}

            <MLabel text="Blood Type" required s={s} />
            <MSelectRow<BloodType>
              options={(['A+','A-','B+','B-','O+','O-','AB+','AB-'] as BloodType[]).map(v => ({ value: v, label: v }))}
              value={health.blood_type}
              onChange={v => setH('blood_type', v)}
              label="Blood type"
              s={s}
            />
            {errors.blood_type ? <Text style={[styles.errorText, { fontSize: s(11) }]}>{errors.blood_type}</Text> : null}

            <MLabel text="Health Status" required s={s} />
            <MSelectRow<HealthStat>
              options={[
                { value: 'STABLE',   label: 'Stable'   },
                { value: 'MONITOR',  label: 'Monitor'  },
                { value: 'CRITICAL', label: 'Critical' },
              ]}
              value={health.health_status}
              onChange={v => setH('health_status', v)}
              label="Health status"
              s={s}
            />

            <MLabel text="Known Allergies" s={s} />
            <MTagInput
              tags={health.allergies}
              onAdd={t => setH('allergies', [...health.allergies, t])}
              onRemove={i => setH('allergies', health.allergies.filter((_, idx) => idx !== i))}
              placeholder="e.g. Penicillin…"
              s={s}
            />

            <MLabel text="Chronic Conditions" s={s} />
            <MTagInput
              tags={health.chronic_conditions}
              onAdd={t => setH('chronic_conditions', [...health.chronic_conditions, t])}
              onRemove={i => setH('chronic_conditions', health.chronic_conditions.filter((_, idx) => idx !== i))}
              placeholder="e.g. Hypertension…"
              s={s}
            />
          </View>
        </MCard>
      )}

      {/* ── Step 3: Access Settings ── */}
      {step === 2 && (
        <MCard title="Access Settings" sub="Define your proxy relationship and permissions" s={s}>
          <View style={{ gap: s(6) }}>
            <MLabel text="Relationship to Patient" required s={s} />
            <MInput value={access.relationship} onChange={v => setA('relationship', v)} placeholder="e.g. Father, Mother, Guardian…" error={errors.relationship} label="Relationship to patient" s={s} />

            <MLabel text="Access Level" required s={s} />
            <View style={{ gap: s(8), marginBottom: s(4) }} accessibilityRole="radiogroup" accessibilityLabel="Access level">
              {([
                { value: 'FULL_ACCESS', label: 'Full Access',  sub: 'All permissions enabled',           color: GREEN  },
                { value: 'VIEW_ONLY',   label: 'View Only',    sub: 'Read records, no actions',          color: PURPLE },
                { value: 'EDIT_ONLY',   label: 'Edit Only',    sub: 'View + schedule',                   color: ORANGE },
                { value: 'CUSTOM',      label: 'Custom',        sub: 'Set individual permissions below', color: GRAY   },
              ] as { value: AccessLevel; label: string; sub: string; color: string }[]).map(opt => (
                <TouchableOpacity
                  key={opt.value}
                  style={[
                    styles.accessCard,
                    { borderRadius: s(12), padding: s(12), gap: s(10) },
                    access.access_level === opt.value && { borderColor: opt.color, borderWidth: 2 },
                  ]}
                  activeOpacity={0.7}
                  onPress={() => changeAccessLevel(opt.value)}
                  accessibilityRole="radio"
                  accessibilityLabel={`${opt.label}: ${opt.sub}`}
                  accessibilityState={{ selected: access.access_level === opt.value, checked: access.access_level === opt.value }}
                >
                  <View style={[styles.accessRadio, { width: s(18), height: s(18), borderRadius: s(9) }, access.access_level === opt.value && { borderColor: opt.color }]}>
                    {access.access_level === opt.value && (
                      <View style={[styles.accessRadioDot, { width: s(8), height: s(8), borderRadius: s(4), backgroundColor: opt.color }]} />
                    )}
                  </View>
                  <View style={{ flex: 1 }}>
                    <Text style={[styles.accessCardLabel, { fontSize: s(13) }, access.access_level === opt.value && { color: opt.color }]}>{opt.label}</Text>
                    <Text style={[styles.cardSub, { fontSize: s(11) }]}>{opt.sub}</Text>
                  </View>
                </TouchableOpacity>
              ))}
            </View>

            {access.access_level === 'CUSTOM' && (
              <View style={[styles.permBox, { borderRadius: s(12), padding: s(14) }]}>
                <Text style={[styles.cardTitle, { fontSize: s(13), marginBottom: s(10) }]} accessibilityRole="header">Individual Permissions</Text>
                {([
                  { key: 'can_view_records',          label: 'View Medical Records'     },
                  { key: 'can_view_appointments',     label: 'View Appointments'        },
                  { key: 'can_view_medications',      label: 'View Medications'         },
                  { key: 'can_view_lab_results',      label: 'View Lab Results'         },
                  { key: 'can_view_imaging',          label: 'View Imaging Results'     },
                  { key: 'can_view_consultations',    label: 'View Consultations'       },
                  { key: 'can_schedule_appointments', label: 'Schedule Appointments'    },
                  { key: 'can_manage_care_plan',      label: 'Manage Care Plan'         },
                ] as { key: keyof AccessForm; label: string }[]).map(p => (
                  <MPermSwitch key={p.key} label={p.label} value={access[p.key] as boolean} onChange={v => setA(p.key, v)} s={s} />
                ))}
              </View>
            )}

            <MLabel text="Access Expiry Date (optional)" s={s} />
            <MInput value={access.expires_at} onChange={v => setA('expires_at', v)} placeholder="YYYY-MM-DD" error={errors.expires_at} label="Access expiry date, YYYY-MM-DD, optional" s={s} />
            <Text style={[styles.hint, { fontSize: s(11) }]}>Leave blank if this proxy access should not expire.</Text>
          </View>
        </MCard>
      )}

      {/* Error banner */}
      {submitErr ? (
        <View style={[styles.errorBanner, { borderRadius: s(10), padding: s(14), gap: s(8), marginBottom: s(12) }]} accessibilityRole="alert" accessibilityLiveRegion="assertive">
          <Ionicons name="alert-circle-outline" size={s(16)} color={RED} importantForAccessibility="no" />
          <Text style={[styles.errorBannerText, { fontSize: s(13) }]}>{submitErr}</Text>
        </View>
      ) : null}

      {/* Navigation */}
      <View style={[styles.navRow, { gap: s(12) }]}>
        <TouchableOpacity
          style={[styles.outlineBtn, { flex: 1, borderRadius: s(10), paddingVertical: s(13), gap: s(6) }]}
          onPress={step > 0 ? () => setStep(st => st - 1) : onCancel}
          activeOpacity={0.7}
          accessibilityRole="button"
          accessibilityLabel={step > 0 ? 'Previous step' : 'Cancel'}
        >
          {step > 0 && <Ionicons name="arrow-back-outline" size={s(16)} color={GRAY} importantForAccessibility="no" />}
          <Text style={[styles.outlineBtnText, { fontSize: s(14) }]}>{step > 0 ? 'Back' : 'Cancel'}</Text>
        </TouchableOpacity>

        {step < 2 ? (
          <TouchableOpacity
            style={[styles.primaryBtn, { flex: 1, borderRadius: s(10), paddingVertical: s(13), gap: s(6) }]}
            onPress={nextStep}
            activeOpacity={0.8}
            accessibilityRole="button"
            accessibilityLabel="Continue"
          >
            <Text style={[styles.primaryBtnText, { fontSize: s(14) }]}>Continue</Text>
            <Ionicons name="arrow-forward-outline" size={s(16)} color={WHITE} importantForAccessibility="no" />
          </TouchableOpacity>
        ) : (
          <TouchableOpacity
            style={[styles.primaryBtn, { flex: 1, borderRadius: s(10), paddingVertical: s(13), gap: s(6) }, submitting && { opacity: 0.5 }]}
            onPress={handleSubmit}
            activeOpacity={0.8}
            disabled={submitting}
            accessibilityRole="button"
            accessibilityLabel={submitting ? 'Adding loved one' : 'Add loved one'}
            accessibilityState={{ disabled: submitting, busy: submitting }}
          >
            {submitting
              ? <ActivityIndicator size="small" color={WHITE} />
              : <>
                  <Ionicons name="person-add-outline" size={s(16)} color={WHITE} importantForAccessibility="no" />
                  <Text style={[styles.primaryBtnText, { fontSize: s(14) }]}>Add Loved One</Text>
                </>
            }
          </TouchableOpacity>
        )}
      </View>
    </ScrollView>
    </KeyboardAvoidingView>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  pageHeader: {},
  backBtn:    { flexDirection: 'row', alignItems: 'center' },
  backBtnText:{ color: GRAY },
  successTitle:{ fontWeight: '700', color: TEXT },

  stepper:      { flexDirection: 'row', alignItems: 'center', backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER },
  stepItem:     { flexDirection: 'row', alignItems: 'center', flex: 1 },
  stepCircle:   { borderWidth: 2, borderColor: BORDER, alignItems: 'center', justifyContent: 'center', backgroundColor: WHITE },
  stepCircleActive:{ borderColor: PURPLE, backgroundColor: PURPLE },
  stepCircleDone:  { borderColor: GREEN,  backgroundColor: GREEN  },
  stepNum:      { fontWeight: '700', color: GRAY },
  stepLabel:    { color: GRAY, marginLeft: 5, fontWeight: '500', flexShrink: 1 },
  stepLabelActive: { color: PURPLE, fontWeight: '700' },
  stepLine:     { flex: 1, height: 2, backgroundColor: BORDER, marginHorizontal: 5 },
  stepLineDone: { backgroundColor: GREEN },

  card:      { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER },
  cardTitle: { fontWeight: '700', color: TEXT },
  cardSub:   { color: GRAY },

  label:     { fontWeight: '600', color: TEXT },
  input:     { borderWidth: 1, borderColor: BORDER, color: TEXT, backgroundColor: WHITE } as any,
  inputError:{ borderColor: RED },
  errorText: { color: RED },
  hint:      { color: GRAY2 },

  iconBtn:   { borderWidth: 1, borderColor: BORDER, alignItems: 'center', justifyContent: 'center', backgroundColor: WHITE },

  selectRow:           { flexDirection: 'row', flexWrap: 'wrap' },
  selectChip:          { borderWidth: 1, borderColor: BORDER },
  selectChipActive:    { borderColor: PURPLE, backgroundColor: PURPLE_BG },
  selectChipText:      { color: GRAY, fontWeight: '500' },
  selectChipTextActive:{ color: PURPLE, fontWeight: '700' },

  tagChips:     { flexDirection: 'row', flexWrap: 'wrap' },
  tagChip:      { flexDirection: 'row', alignItems: 'center', backgroundColor: PURPLE_BG },
  tagChipText:  { color: PURPLE, fontWeight: '500' },
  tagInputRow:  { flexDirection: 'row' },
  tagInput:     { borderWidth: 1, borderColor: BORDER, color: TEXT } as any,
  tagAddBtn:    { borderWidth: 1, borderColor: PURPLE, backgroundColor: PURPLE_BG },
  tagAddBtnText:{ color: PURPLE, fontWeight: '600' },

  accessCard:      { flexDirection: 'row', alignItems: 'flex-start', borderWidth: 1, borderColor: BORDER, backgroundColor: WHITE },
  accessRadio:     { borderWidth: 2, borderColor: BORDER, alignItems: 'center', justifyContent: 'center', marginTop: 2 },
  accessRadioDot:  {},
  accessCardLabel: { fontWeight: '700', color: TEXT, marginBottom: 2 },

  permBox:   { backgroundColor: BG, borderWidth: 1, borderColor: BORDER },
  permRow:   { flexDirection: 'row', alignItems: 'center' },
  permLabel: { flex: 1, color: TEXT },
  toggle:    { backgroundColor: BORDER, justifyContent: 'center', paddingHorizontal: 2 },
  toggleOn:  { backgroundColor: PURPLE },
  toggleThumb:   { backgroundColor: WHITE, shadowColor: '#000', shadowOpacity: 0.15, shadowRadius: 2 } as any,
  toggleThumbOn: { alignSelf: 'flex-end' },

  errorBanner:     { flexDirection: 'row', alignItems: 'center', backgroundColor: '#FEE2E2' },
  errorBannerText: { color: RED, flex: 1 },

  navRow:          { flexDirection: 'row', alignItems: 'center' },
  outlineBtn:      { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', borderWidth: 1, borderColor: BORDER, backgroundColor: WHITE },
  outlineBtnText:  { color: GRAY, fontWeight: '500' },
  primaryBtn:      { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', backgroundColor: PURPLE },
  primaryBtnText:  { color: WHITE, fontWeight: '700' },

  successCard:     { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER, alignItems: 'stretch' },
  credentialsBox:  { backgroundColor: BG, borderWidth: 1, borderColor: BORDER },
  credRow:         { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  credValue:       { fontWeight: '600', color: TEXT, flexShrink: 1, textAlign: 'right' },
});
