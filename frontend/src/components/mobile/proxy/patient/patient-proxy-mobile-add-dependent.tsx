import { Ionicons } from '@expo/vector-icons';
import { useState } from 'react';
import {
  ActivityIndicator, ScrollView, StyleSheet, Text,
  TextInput, TouchableOpacity, useWindowDimensions, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from '@/hooks/use-current-user';

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
  can_view_imaging: boolean; can_message_care_team: boolean;
  can_view_consultations: boolean; can_schedule_appointments: boolean;
  can_manage_care_plan: boolean;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function generatePassword(firstName: string, lastName: string) {
  const rand = Math.random().toString(36).slice(2, 7);
  return `${firstName.charAt(0).toUpperCase()}${lastName.toLowerCase()}@${rand}`;
}

function permissionsForLevel(level: AccessLevel): Partial<AccessForm> {
  if (level === 'FULL_ACCESS') return {
    can_view_records: true, can_view_appointments: true, can_view_medications: true,
    can_view_lab_results: true, can_view_imaging: true, can_message_care_team: true,
    can_view_consultations: true, can_schedule_appointments: true, can_manage_care_plan: true,
  };
  if (level === 'VIEW_ONLY') return {
    can_view_records: true, can_view_appointments: true, can_view_medications: true,
    can_view_lab_results: true, can_view_imaging: false, can_message_care_team: false,
    can_view_consultations: true, can_schedule_appointments: false, can_manage_care_plan: false,
  };
  if (level === 'EDIT_ONLY') return {
    can_view_records: true, can_view_appointments: true, can_view_medications: true,
    can_view_lab_results: true, can_view_imaging: true, can_message_care_team: true,
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

function MInput({ value, onChange, placeholder, error, secureTextEntry, keyboardType, s }: {
  value: string; onChange: (v: string) => void; placeholder?: string;
  error?: string; secureTextEntry?: boolean; keyboardType?: any;
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
      />
      {error ? <Text style={[styles.errorText, { fontSize: s(11), marginTop: s(3) }]}>{error}</Text> : null}
    </View>
  );
}

function MSelectRow<T extends string>({ options, value, onChange, s }: {
  options: { value: T; label: string }[]; value: T | ''; onChange: (v: T) => void;
  s: (n: number) => number;
}) {
  return (
    <View style={[styles.selectRow, { gap: s(8), marginBottom: s(4) }]}>
      {options.map(o => (
        <TouchableOpacity
          key={o.value}
          style={[styles.selectChip, { borderRadius: s(8), paddingHorizontal: s(12), paddingVertical: s(8) }, value === o.value && styles.selectChipActive]}
          activeOpacity={0.7}
          onPress={() => onChange(o.value)}
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
            <Text style={[styles.tagChipText, { fontSize: s(12) }]}>{t}</Text>
            <TouchableOpacity onPress={() => onRemove(i)} hitSlop={{ top: 6, right: 6, bottom: 6, left: 6 }}>
              <Ionicons name="close" size={s(12)} color={PURPLE} />
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
        />
        <TouchableOpacity
          style={[styles.tagAddBtn, { borderRadius: s(10), paddingHorizontal: s(14), paddingVertical: s(9) }]}
          onPress={add}
          activeOpacity={0.7}
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
    <View style={[styles.stepper, { borderRadius: s(14), padding: s(14), marginBottom: s(16) }]}>
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
            <Text style={[styles.stepLabel, { fontSize: s(10) }, active && styles.stepLabelActive]}>{label}</Text>
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
      <Text style={[styles.cardTitle, { fontSize: s(16) }]}>{title}</Text>
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
  const { s, sh } = useScale();
  const insets    = useSafeAreaInsets();
  const { user }  = useCurrentUser();
  const [step, setStep] = useState(0);

  const [personal, setPersonal] = useState<PersonalForm>({
    first_name: '', last_name: '', email: '', phone: '',
    password: Math.random().toString(36).slice(2, 10) + 'A1!',
  });
  const [health, setHealth] = useState<HealthForm>({
    date_of_birth: '', gender: '', blood_type: '',
    health_status: 'STABLE', allergies: [], chronic_conditions: [],
  });
  const [access, setAccess] = useState<AccessForm>({
    relationship: '', access_level: 'FULL_ACCESS', expires_at: '',
    can_view_records: true, can_view_appointments: true, can_view_medications: true,
    can_view_lab_results: true, can_view_imaging: true, can_message_care_team: true,
    can_view_consultations: true, can_schedule_appointments: true, can_manage_care_plan: true,
  });

  const [errors,     setErrors]     = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);
  const [submitErr,  setSubmitErr]  = useState('');
  const [success,    setSuccess]    = useState(false);
  const [pwVisible,  setPwVisible]  = useState(false);

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
    setP('password', generatePassword(personal.first_name || 'user', personal.last_name || 'care'));
  }

  function validateStep1() {
    const e: Record<string, string> = {};
    if (!personal.first_name.trim()) e.first_name = 'Required';
    if (!personal.last_name.trim())  e.last_name  = 'Required';
    if (!personal.email.trim())      e.email      = 'Required';
    else if (!/\S+@\S+\.\S+/.test(personal.email)) e.email = 'Invalid email';
    if (!personal.phone.trim())      e.phone      = 'Required';
    setErrors(e);
    return Object.keys(e).length === 0;
  }

  function validateStep2() {
    const e: Record<string, string> = {};
    if (!health.date_of_birth) e.date_of_birth = 'Required';
    if (!health.gender)        e.gender        = 'Select a gender';
    if (!health.blood_type)    e.blood_type    = 'Select blood type';
    setErrors(e);
    return Object.keys(e).length === 0;
  }

  function validateStep3() {
    const e: Record<string, string> = {};
    if (!access.relationship.trim()) e.relationship = 'Required';
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
    if (!user) return;
    setSubmitting(true);
    setSubmitErr('');

    try {
      const regRes    = await api.post('/api/auth/register', {
        firstName: personal.first_name.trim(),
        lastName:  personal.last_name.trim(),
        email:     personal.email.trim().toLowerCase(),
        phone:     personal.phone.trim(),
        password:  personal.password,
        role:      'patient',
      });
      const newUserId = regRes.data.user.user_id;

      const patRes    = await api.post('/api/patients', {
        user_id:            newUserId,
        date_of_birth:      health.date_of_birth,
        gender:             health.gender,
        blood_type:         health.blood_type,
        health_status:      health.health_status,
        allergies:          health.allergies,
        chronic_conditions: health.chronic_conditions,
      });
      const newPatientId = patRes.data.patient_id;

      const cgRes       = await api.get(`/api/caregivers/user/${user.user_id}`);
      const caregiverId = cgRes.data.caregiver_id;

      await api.post('/api/dependent-access', {
        caregiver_id:              caregiverId,
        patient_id:                newPatientId,
        relationship:              access.relationship.trim().toLowerCase(),
        access_level:              access.access_level,
        can_view_records:          access.can_view_records          ? 1 : 0,
        can_view_appointments:     access.can_view_appointments     ? 1 : 0,
        can_view_medications:      access.can_view_medications      ? 1 : 0,
        can_view_lab_results:      access.can_view_lab_results      ? 1 : 0,
        can_view_imaging:          access.can_view_imaging          ? 1 : 0,
        can_message_care_team:     access.can_message_care_team     ? 1 : 0,
        can_view_consultations:    access.can_view_consultations    ? 1 : 0,
        can_schedule_appointments: access.can_schedule_appointments ? 1 : 0,
        can_manage_care_plan:      access.can_manage_care_plan      ? 1 : 0,
        authorized_by:             user.user_id,
        expires_at:                access.expires_at.trim() || null,
      });

      setSuccess(true);
    } catch (err: any) {
      setSubmitErr(err?.response?.data?.message ?? 'Something went wrong. Please try again.');
    } finally {
      setSubmitting(false);
    }
  }

  // ── Success ──────────────────────────────────────────────────────────────────
  if (success) {
    return (
      <ScrollView
        style={{ flex: 1, backgroundColor: BG }}
        contentContainerStyle={{ flexGrow: 1, alignItems: 'center', justifyContent: 'center', padding: s(24), paddingBottom: insets.bottom + s(24) }}
        showsVerticalScrollIndicator={false}
      >
        <View style={[styles.successCard, { borderRadius: s(20), padding: s(28), gap: s(14), width: '100%' }]}>
          <View style={{ alignItems: 'center' }}>
            <Ionicons name="checkmark-circle" size={s(64)} color={GREEN} />
          </View>
          <Text style={[styles.successTitle, { fontSize: s(22), textAlign: 'center' }]}>Loved One Added!</Text>
          <Text style={[styles.cardSub, { fontSize: s(13), textAlign: 'center', lineHeight: s(20) }]}>
            {personal.first_name} {personal.last_name} has been added as a linked patient under your care.
          </Text>

          <View style={[styles.credentialsBox, { borderRadius: s(12), padding: s(16), gap: s(10) }]}>
            <Text style={[styles.cardTitle, { fontSize: s(13), marginBottom: s(4) }]}>
              Login Credentials for {personal.first_name}
            </Text>
            <View style={[styles.credRow, { gap: s(8) }]}>
              <Text style={[styles.cardSub, { fontSize: s(12) }]}>Email</Text>
              <Text style={[styles.credValue, { fontSize: s(13) }]} numberOfLines={1}>{personal.email}</Text>
            </View>
            <View style={[styles.credRow, { gap: s(8) }]}>
              <Text style={[styles.cardSub, { fontSize: s(12) }]}>Password</Text>
              <Text style={[styles.credValue, { fontSize: s(13) }]}>{personal.password}</Text>
            </View>
            <Text style={[styles.cardSub, { fontSize: s(11), fontStyle: 'italic', marginTop: s(2) }]}>
              Share these credentials securely. {personal.first_name} can change the password after first login.
            </Text>
          </View>

          <TouchableOpacity
            style={[styles.primaryBtn, { borderRadius: s(12), paddingVertical: s(14), marginTop: s(4) }]}
            onPress={onDone}
            activeOpacity={0.8}
          >
            <Text style={[styles.primaryBtnText, { fontSize: s(15) }]}>Back to Dashboard</Text>
          </TouchableOpacity>
        </View>
      </ScrollView>
    );
  }

  // ── Form ─────────────────────────────────────────────────────────────────────
  return (
    <ScrollView
      style={{ flex: 1, backgroundColor: BG }}
      contentContainerStyle={{ padding: s(16), paddingTop: insets.top > 0 ? insets.top : s(16), paddingBottom: insets.bottom + s(24) }}
      showsVerticalScrollIndicator={false}
      keyboardShouldPersistTaps="handled"
    >
      {/* Header */}
      <View style={[styles.pageHeader, { gap: s(4), marginBottom: s(16) }]}>
        <TouchableOpacity
          style={[styles.backBtn, { gap: s(6), marginBottom: s(6) }]}
          onPress={onCancel}
          activeOpacity={0.7}
        >
          <Ionicons name="arrow-back-outline" size={s(18)} color={GRAY} />
          <Text style={[styles.backBtnText, { fontSize: s(13) }]}>Back</Text>
        </TouchableOpacity>
        <Text style={[styles.successTitle, { fontSize: s(20) }]}>Add Loved One</Text>
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
                <MInput value={personal.first_name} onChange={v => setP('first_name', v)} placeholder="Thomas" error={errors.first_name} s={s} />
              </View>
              <View style={{ flex: 1 }}>
                <MLabel text="Last Name" required s={s} />
                <MInput value={personal.last_name} onChange={v => setP('last_name', v)} placeholder="Dube" error={errors.last_name} s={s} />
              </View>
            </View>

            <MLabel text="Email Address" required s={s} />
            <MInput value={personal.email} onChange={v => setP('email', v)} placeholder="thomas.dube@email.com" keyboardType="email-address" error={errors.email} s={s} />

            <MLabel text="Phone Number" required s={s} />
            <MInput value={personal.phone} onChange={v => setP('phone', v)} placeholder="+263771234567" keyboardType="phone-pad" error={errors.phone} s={s} />

            <MLabel text="Temporary Password" s={s} />
            <View style={{ flexDirection: 'row', gap: s(8), alignItems: 'center' }}>
              <View style={{ flex: 1 }}>
                <MInput value={personal.password} onChange={v => setP('password', v)} secureTextEntry={!pwVisible} s={s} />
              </View>
              <TouchableOpacity
                style={[styles.iconBtn, { width: s(42), height: s(42), borderRadius: s(10) }]}
                onPress={() => setPwVisible(v => !v)}
              >
                <Ionicons name={pwVisible ? 'eye-off-outline' : 'eye-outline'} size={s(18)} color={GRAY} />
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.iconBtn, { width: s(42), height: s(42), borderRadius: s(10) }]}
                onPress={regeneratePassword}
              >
                <Ionicons name="refresh-outline" size={s(18)} color={PURPLE} />
              </TouchableOpacity>
            </View>
            <Text style={[styles.hint, { fontSize: s(11) }]}>
              Share this temporary password with the patient. They can change it after first login.
            </Text>
          </View>
        </MCard>
      )}

      {/* ── Step 2: Health Profile ── */}
      {step === 1 && (
        <MCard title="Health Profile" sub="Medical and demographic information" s={s}>
          <View style={{ gap: s(6) }}>
            <MLabel text="Date of Birth" required s={s} />
            <MInput value={health.date_of_birth} onChange={v => setH('date_of_birth', v)} placeholder="YYYY-MM-DD" error={errors.date_of_birth} s={s} />

            <MLabel text="Gender" required s={s} />
            <MSelectRow<Gender>
              options={[{ value: 'MALE', label: 'Male' }, { value: 'FEMALE', label: 'Female' }, { value: 'OTHER', label: 'Other' }]}
              value={health.gender}
              onChange={v => setH('gender', v)}
              s={s}
            />
            {errors.gender ? <Text style={[styles.errorText, { fontSize: s(11) }]}>{errors.gender}</Text> : null}

            <MLabel text="Blood Type" required s={s} />
            <MSelectRow<BloodType>
              options={(['A+','A-','B+','B-','O+','O-','AB+','AB-'] as BloodType[]).map(v => ({ value: v, label: v }))}
              value={health.blood_type}
              onChange={v => setH('blood_type', v)}
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
            <MInput value={access.relationship} onChange={v => setA('relationship', v)} placeholder="e.g. Father, Mother, Guardian…" error={errors.relationship} s={s} />

            <MLabel text="Access Level" required s={s} />
            <View style={{ gap: s(8), marginBottom: s(4) }}>
              {([
                { value: 'FULL_ACCESS', label: 'Full Access',  sub: 'All permissions enabled',           color: GREEN  },
                { value: 'VIEW_ONLY',   label: 'View Only',    sub: 'Read records, no actions',          color: PURPLE },
                { value: 'EDIT_ONLY',   label: 'Edit Only',    sub: 'View + message + schedule',         color: ORANGE },
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
                <Text style={[styles.cardTitle, { fontSize: s(13), marginBottom: s(10) }]}>Individual Permissions</Text>
                {([
                  { key: 'can_view_records',          label: 'View Medical Records'     },
                  { key: 'can_view_appointments',     label: 'View Appointments'        },
                  { key: 'can_view_medications',      label: 'View Medications'         },
                  { key: 'can_view_lab_results',      label: 'View Lab Results'         },
                  { key: 'can_view_imaging',          label: 'View Imaging Results'     },
                  { key: 'can_message_care_team',     label: 'Message Care Team'        },
                  { key: 'can_view_consultations',    label: 'View Consultations'       },
                  { key: 'can_schedule_appointments', label: 'Schedule Appointments'    },
                  { key: 'can_manage_care_plan',      label: 'Manage Care Plan'         },
                ] as { key: keyof AccessForm; label: string }[]).map(p => (
                  <MPermSwitch key={p.key} label={p.label} value={access[p.key] as boolean} onChange={v => setA(p.key, v)} s={s} />
                ))}
              </View>
            )}

            <MLabel text="Access Expiry Date (optional)" s={s} />
            <MInput value={access.expires_at} onChange={v => setA('expires_at', v)} placeholder="YYYY-MM-DD (leave blank for no expiry)" s={s} />
            <Text style={[styles.hint, { fontSize: s(11) }]}>Leave blank if this proxy access should not expire.</Text>
          </View>
        </MCard>
      )}

      {/* Error banner */}
      {submitErr ? (
        <View style={[styles.errorBanner, { borderRadius: s(10), padding: s(14), gap: s(8), marginBottom: s(12) }]}>
          <Ionicons name="alert-circle-outline" size={s(16)} color={RED} />
          <Text style={[styles.errorBannerText, { fontSize: s(13) }]}>{submitErr}</Text>
        </View>
      ) : null}

      {/* Navigation */}
      <View style={[styles.navRow, { gap: s(12) }]}>
        <TouchableOpacity
          style={[styles.outlineBtn, { flex: 1, borderRadius: s(10), paddingVertical: s(13), gap: s(6) }]}
          onPress={step > 0 ? () => setStep(st => st - 1) : onCancel}
          activeOpacity={0.7}
        >
          {step > 0 && <Ionicons name="arrow-back-outline" size={s(16)} color={GRAY} />}
          <Text style={[styles.outlineBtnText, { fontSize: s(14) }]}>{step > 0 ? 'Back' : 'Cancel'}</Text>
        </TouchableOpacity>

        {step < 2 ? (
          <TouchableOpacity
            style={[styles.primaryBtn, { flex: 1, borderRadius: s(10), paddingVertical: s(13), gap: s(6) }]}
            onPress={nextStep}
            activeOpacity={0.8}
          >
            <Text style={[styles.primaryBtnText, { fontSize: s(14) }]}>Continue</Text>
            <Ionicons name="arrow-forward-outline" size={s(16)} color={WHITE} />
          </TouchableOpacity>
        ) : (
          <TouchableOpacity
            style={[styles.primaryBtn, { flex: 1, borderRadius: s(10), paddingVertical: s(13), gap: s(6) }, submitting && { opacity: 0.5 }]}
            onPress={handleSubmit}
            activeOpacity={0.8}
            disabled={submitting}
          >
            {submitting
              ? <ActivityIndicator size="small" color={WHITE} />
              : <>
                  <Ionicons name="person-add-outline" size={s(16)} color={WHITE} />
                  <Text style={[styles.primaryBtnText, { fontSize: s(14) }]}>Add Loved One</Text>
                </>
            }
          </TouchableOpacity>
        )}
      </View>
    </ScrollView>
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
