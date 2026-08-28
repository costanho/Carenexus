import { Ionicons } from '@expo/vector-icons';
import { useState } from 'react';
import {
  ActivityIndicator, ScrollView, StyleSheet, Text,
  TextInput, TouchableOpacity, View,
} from 'react-native';
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
const GREEN      = '#10B981';
const ORANGE     = '#F59E0B';
const RED        = '#EF4444';

// ─── Types ────────────────────────────────────────────────────────────────────

type AccessLevel = 'FULL_ACCESS' | 'VIEW_ONLY' | 'EDIT_ONLY' | 'CUSTOM';
type Gender      = 'MALE' | 'FEMALE' | 'OTHER';
type HealthStat  = 'STABLE' | 'MONITOR' | 'CRITICAL';
type BloodType   = 'A+' | 'A-' | 'B+' | 'B-' | 'O+' | 'O-' | 'AB+' | 'AB-';

interface PersonalForm {
  first_name: string;
  last_name:  string;
  email:      string;
  phone:      string;
  password:   string;
}
interface HealthForm {
  date_of_birth:      string;
  gender:             Gender | '';
  blood_type:         BloodType | '';
  health_status:      HealthStat;
  allergies:          string[];
  chronic_conditions: string[];
}
interface AccessForm {
  relationship:           string;
  access_level:           AccessLevel;
  expires_at:             string;
  can_view_records:       boolean;
  can_view_appointments:  boolean;
  can_view_medications:   boolean;
  can_view_lab_results:   boolean;
  can_view_imaging:       boolean;
  can_message_care_team:  boolean;
  can_view_consultations: boolean;
  can_schedule_appointments: boolean;
  can_manage_care_plan:   boolean;
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

// ─── Small components ─────────────────────────────────────────────────────────

function Label({ text, required }: { text: string; required?: boolean }) {
  return (
    <Text style={styles.label}>
      {text}{required && <Text style={{ color: RED }}> *</Text>}
    </Text>
  );
}

function Input({ value, onChange, placeholder, error, secureTextEntry, keyboardType, editable = true }: {
  value: string; onChange: (v: string) => void; placeholder?: string;
  error?: string; secureTextEntry?: boolean; keyboardType?: any; editable?: boolean;
}) {
  return (
    <View style={styles.inputWrap}>
      <TextInput
        style={[styles.input, !!error && styles.inputError, !editable && styles.inputDisabled]}
        value={value}
        onChangeText={onChange}
        placeholder={placeholder}
        placeholderTextColor={GRAY2}
        secureTextEntry={secureTextEntry}
        keyboardType={keyboardType}
        editable={editable}
        autoCapitalize="none"
      />
      {error ? <Text style={styles.errorText}>{error}</Text> : null}
    </View>
  );
}

function SelectRow<T extends string>({ options, value, onChange }: {
  options: { value: T; label: string }[];
  value: T | '';
  onChange: (v: T) => void;
}) {
  return (
    <View style={styles.selectRow}>
      {options.map(o => (
        <TouchableOpacity
          key={o.value}
          style={[styles.selectChip, value === o.value && styles.selectChipActive]}
          activeOpacity={0.7}
          onPress={() => onChange(o.value)}
        >
          <Text style={[styles.selectChipText, value === o.value && styles.selectChipTextActive]}>
            {o.label}
          </Text>
        </TouchableOpacity>
      ))}
    </View>
  );
}

function TagInput({ tags, onAdd, onRemove, placeholder }: {
  tags: string[]; onAdd: (t: string) => void; onRemove: (i: number) => void; placeholder?: string;
}) {
  const [text, setText] = useState('');
  function add() {
    const t = text.trim();
    if (t && !tags.includes(t)) { onAdd(t); setText(''); }
  }
  return (
    <View style={styles.tagContainer}>
      <View style={styles.tagChips}>
        {tags.map((t, i) => (
          <View key={i} style={styles.tagChip}>
            <Text style={styles.tagChipText}>{t}</Text>
            <TouchableOpacity onPress={() => onRemove(i)} hitSlop={{ top: 6, right: 6, bottom: 6, left: 6 }}>
              <Ionicons name="close" size={12} color={PURPLE} />
            </TouchableOpacity>
          </View>
        ))}
      </View>
      <View style={styles.tagInputRow}>
        <TextInput
          style={styles.tagInput}
          value={text}
          onChangeText={setText}
          placeholder={placeholder ?? 'Type and press Add…'}
          placeholderTextColor={GRAY2}
          onSubmitEditing={add}
          returnKeyType="done"
        />
        <TouchableOpacity style={styles.tagAddBtn} onPress={add} activeOpacity={0.7}>
          <Text style={styles.tagAddBtnText}>Add</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

function PermSwitch({ label, value, onChange }: { label: string; value: boolean; onChange: (v: boolean) => void }) {
  return (
    <TouchableOpacity style={styles.permRow} activeOpacity={0.7} onPress={() => onChange(!value)}>
      <Text style={styles.permLabel}>{label}</Text>
      <View style={[styles.toggle, value && styles.toggleOn]}>
        <View style={[styles.toggleThumb, value && styles.toggleThumbOn]} />
      </View>
    </TouchableOpacity>
  );
}

// ─── Step header ──────────────────────────────────────────────────────────────

const STEPS = ['Personal Details', 'Health Profile', 'Access Settings'];

function Stepper({ current }: { current: number }) {
  return (
    <View style={styles.stepper}>
      {STEPS.map((label, i) => {
        const done   = i < current;
        const active = i === current;
        return (
          <View key={i} style={styles.stepItem}>
            <View style={[styles.stepCircle, active && styles.stepCircleActive, done && styles.stepCircleDone]}>
              {done
                ? <Ionicons name="checkmark" size={14} color={WHITE} />
                : <Text style={[styles.stepNum, (active || done) && { color: WHITE }]}>{i + 1}</Text>}
            </View>
            <Text style={[styles.stepLabel, active && styles.stepLabelActive]}>{label}</Text>
            {i < STEPS.length - 1 && (
              <View style={[styles.stepLine, done && styles.stepLineDone]} />
            )}
          </View>
        );
      })}
    </View>
  );
}

// ─── Main Component ───────────────────────────────────────────────────────────

export default function PatientProxyWebAddDependent({
  onDone,
  onCancel,
}: {
  onDone:   () => void;
  onCancel: () => void;
}) {
  const { user } = useCurrentUser();
  const [step, setStep] = useState(0);

  const [personal, setPersonal] = useState<PersonalForm>({
    first_name: '', last_name: '', email: '', phone: '',
    password: Math.random().toString(36).slice(2, 10) + 'A1!',
  });
  const [health, setHealth] = useState<HealthForm>({
    date_of_birth: '', gender: '', blood_type: '', health_status: 'STABLE',
    allergies: [], chronic_conditions: [],
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
  const [pwCopied,   setPwCopied]   = useState(false);

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
    const perms = permissionsForLevel(level);
    setAccess(a => ({ ...a, access_level: level, ...perms }));
  }

  function regeneratePassword() {
    if (!personal.first_name && !personal.last_name) {
      setP('password', Math.random().toString(36).slice(2, 10) + 'A1!');
    } else {
      setP('password', generatePassword(personal.first_name || 'user', personal.last_name || 'care'));
    }
  }

  function copyPassword() {
    if (typeof navigator !== 'undefined' && navigator.clipboard) {
      navigator.clipboard.writeText(personal.password);
    }
    setPwCopied(true);
    setTimeout(() => setPwCopied(false), 2000);
  }

  function validateStep1() {
    const e: Record<string, string> = {};
    if (!personal.first_name.trim()) e.first_name = 'First name is required';
    if (!personal.last_name.trim())  e.last_name  = 'Last name is required';
    if (!personal.email.trim())      e.email      = 'Email is required';
    else if (!/\S+@\S+\.\S+/.test(personal.email)) e.email = 'Enter a valid email';
    if (!personal.phone.trim())      e.phone      = 'Phone number is required';
    setErrors(e);
    return Object.keys(e).length === 0;
  }

  function validateStep2() {
    const e: Record<string, string> = {};
    if (!health.date_of_birth) e.date_of_birth = 'Date of birth is required';
    if (!health.gender)        e.gender        = 'Gender is required';
    if (!health.blood_type)    e.blood_type    = 'Blood type is required';
    setErrors(e);
    return Object.keys(e).length === 0;
  }

  function validateStep3() {
    const e: Record<string, string> = {};
    if (!access.relationship.trim()) e.relationship = 'Relationship is required';
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
      // 1. Register user as PATIENT
      const regRes = await api.post('/api/auth/register', {
        firstName: personal.first_name.trim(),
        lastName:  personal.last_name.trim(),
        email:     personal.email.trim().toLowerCase(),
        phone:     personal.phone.trim(),
        password:  personal.password,
        role:      'patient',
      });
      const newUserId = regRes.data.user.user_id;

      // 2. Create patient health profile
      const patRes = await api.post('/api/patients', {
        user_id:            newUserId,
        date_of_birth:      health.date_of_birth,
        gender:             health.gender,
        blood_type:         health.blood_type,
        health_status:      health.health_status,
        allergies:          health.allergies,
        chronic_conditions: health.chronic_conditions,
      });
      const newPatientId = patRes.data.patient_id;

      // 3. Resolve caregiver_id for logged-in user
      const cgRes    = await api.get(`/api/caregivers/user/${user.user_id}`);
      const caregiverId = cgRes.data.caregiver_id;

      // 4. Create dependent access record
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
      const msg = err?.response?.data?.message ?? 'Something went wrong. Please try again.';
      setSubmitErr(msg);
    } finally {
      setSubmitting(false);
    }
  }

  // ── Success Screen ───────────────────────────────────────────────────────────
  if (success) {
    return (
      <View style={styles.successWrap}>
        <View style={styles.successCard}>
          <View style={styles.successIcon}>
            <Ionicons name="checkmark-circle" size={56} color={GREEN} />
          </View>
          <Text style={styles.successTitle}>Loved One Added!</Text>
          <Text style={styles.successSub}>
            {personal.first_name} {personal.last_name} has been added as a linked patient under your care.
          </Text>

          <View style={styles.credentialsBox}>
            <Text style={styles.credTitle}>Login Credentials for {personal.first_name}</Text>
            <View style={styles.credRow}>
              <Text style={styles.credLabel}>Email</Text>
              <Text style={styles.credValue}>{personal.email}</Text>
            </View>
            <View style={styles.credRow}>
              <Text style={styles.credLabel}>Password</Text>
              <Text style={styles.credValue}>{personal.password}</Text>
            </View>
            <Text style={styles.credNote}>
              Please share these credentials securely with {personal.first_name}. They can change their password on first login.
            </Text>
          </View>

          <TouchableOpacity style={styles.doneBtn} onPress={onDone} activeOpacity={0.8}>
            <Text style={styles.doneBtnText}>Back to Dashboard</Text>
          </TouchableOpacity>
        </View>
      </View>
    );
  }

  // ── Form ────────────────────────────────────────────────────────────────────
  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>
      {/* Page header */}
      <View style={styles.pageHeader}>
        <TouchableOpacity style={styles.backBtn} onPress={onCancel} activeOpacity={0.7}>
          <Ionicons name="arrow-back-outline" size={18} color={GRAY} />
          <Text style={styles.backBtnText}>Back</Text>
        </TouchableOpacity>
        <View>
          <Text style={styles.pageTitle}>Add Loved One</Text>
          <Text style={styles.pageSub}>Register a new patient under your proxy care</Text>
        </View>
      </View>

      {/* Stepper */}
      <Stepper current={step} />

      {/* ── Step 1: Personal Details ── */}
      {step === 0 && (
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Personal Details</Text>
          <Text style={styles.cardSub}>Basic identity information for the new patient account</Text>

          <View style={styles.row2}>
            <View style={{ flex: 1 }}>
              <Label text="First Name" required />
              <Input value={personal.first_name} onChange={v => setP('first_name', v)} placeholder="e.g. Thomas" error={errors.first_name} />
            </View>
            <View style={{ flex: 1 }}>
              <Label text="Last Name" required />
              <Input value={personal.last_name} onChange={v => setP('last_name', v)} placeholder="e.g. Dube" error={errors.last_name} />
            </View>
          </View>

          <Label text="Email Address" required />
          <Input value={personal.email} onChange={v => setP('email', v)} placeholder="e.g. thomas.dube@email.com" keyboardType="email-address" error={errors.email} />

          <Label text="Phone Number" required />
          <Input value={personal.phone} onChange={v => setP('phone', v)} placeholder="e.g. +263771234567" keyboardType="phone-pad" error={errors.phone} />

          <Label text="Temporary Password" />
          <View style={styles.pwRow}>
            <View style={{ flex: 1 }}>
              <Input
                value={personal.password}
                onChange={v => setP('password', v)}
                secureTextEntry={!pwVisible}
                editable
              />
            </View>
            <TouchableOpacity style={styles.pwIconBtn} onPress={() => setPwVisible(v => !v)}>
              <Ionicons name={pwVisible ? 'eye-off-outline' : 'eye-outline'} size={18} color={GRAY} />
            </TouchableOpacity>
            <TouchableOpacity style={styles.pwIconBtn} onPress={copyPassword}>
              <Ionicons name={pwCopied ? 'checkmark-outline' : 'copy-outline'} size={18} color={pwCopied ? GREEN : GRAY} />
            </TouchableOpacity>
            <TouchableOpacity style={styles.regenBtn} onPress={regeneratePassword} activeOpacity={0.7}>
              <Ionicons name="refresh-outline" size={14} color={PURPLE} />
              <Text style={styles.regenText}>Regenerate</Text>
            </TouchableOpacity>
          </View>
          <Text style={styles.hint}>This temporary password will be shared with the patient. They can change it after first login.</Text>
        </View>
      )}

      {/* ── Step 2: Health Profile ── */}
      {step === 1 && (
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Health Profile</Text>
          <Text style={styles.cardSub}>Medical and demographic information for the patient record</Text>

          <Label text="Date of Birth" required />
          <Input
            value={health.date_of_birth}
            onChange={v => setH('date_of_birth', v)}
            placeholder="YYYY-MM-DD"
            error={errors.date_of_birth}
          />

          <Label text="Gender" required />
          <SelectRow<Gender>
            options={[{ value: 'MALE', label: 'Male' }, { value: 'FEMALE', label: 'Female' }, { value: 'OTHER', label: 'Other' }]}
            value={health.gender}
            onChange={v => setH('gender', v)}
          />
          {errors.gender ? <Text style={styles.errorText}>{errors.gender}</Text> : null}

          <Label text="Blood Type" required />
          <SelectRow<BloodType>
            options={(['A+','A-','B+','B-','O+','O-','AB+','AB-'] as BloodType[]).map(v => ({ value: v, label: v }))}
            value={health.blood_type}
            onChange={v => setH('blood_type', v)}
          />
          {errors.blood_type ? <Text style={styles.errorText}>{errors.blood_type}</Text> : null}

          <Label text="Health Status" required />
          <SelectRow<HealthStat>
            options={[
              { value: 'STABLE',   label: 'Stable'   },
              { value: 'MONITOR',  label: 'Monitor'  },
              { value: 'CRITICAL', label: 'Critical' },
            ]}
            value={health.health_status}
            onChange={v => setH('health_status', v)}
          />

          <Label text="Known Allergies" />
          <TagInput
            tags={health.allergies}
            onAdd={t => setH('allergies', [...health.allergies, t])}
            onRemove={i => setH('allergies', health.allergies.filter((_, idx) => idx !== i))}
            placeholder="e.g. Penicillin, Latex…"
          />

          <Label text="Chronic Conditions" />
          <TagInput
            tags={health.chronic_conditions}
            onAdd={t => setH('chronic_conditions', [...health.chronic_conditions, t])}
            onRemove={i => setH('chronic_conditions', health.chronic_conditions.filter((_, idx) => idx !== i))}
            placeholder="e.g. Hypertension, Type 2 Diabetes…"
          />
        </View>
      )}

      {/* ── Step 3: Access Settings ── */}
      {step === 2 && (
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Access Settings</Text>
          <Text style={styles.cardSub}>Define your proxy relationship and what you are permitted to access</Text>

          <Label text="Relationship to Patient" required />
          <Input
            value={access.relationship}
            onChange={v => setA('relationship', v)}
            placeholder="e.g. Father, Mother, Sibling, Guardian…"
            error={errors.relationship}
          />

          <Label text="Access Level" required />
          <View style={styles.accessGrid}>
            {([
              { value: 'FULL_ACCESS', label: 'Full Access',  sub: 'All permissions enabled',             color: GREEN  },
              { value: 'VIEW_ONLY',   label: 'View Only',    sub: 'Read records, no actions',            color: PURPLE },
              { value: 'EDIT_ONLY',   label: 'Edit Only',    sub: 'View + message + schedule',           color: ORANGE },
              { value: 'CUSTOM',      label: 'Custom',        sub: 'Set individual permissions below',   color: GRAY   },
            ] as { value: AccessLevel; label: string; sub: string; color: string }[]).map(opt => (
              <TouchableOpacity
                key={opt.value}
                style={[styles.accessCard, access.access_level === opt.value && { borderColor: opt.color, borderWidth: 2 }]}
                activeOpacity={0.7}
                onPress={() => changeAccessLevel(opt.value)}
              >
                <View style={[styles.accessRadio, access.access_level === opt.value && { borderColor: opt.color }]}>
                  {access.access_level === opt.value && <View style={[styles.accessRadioDot, { backgroundColor: opt.color }]} />}
                </View>
                <View style={{ flex: 1 }}>
                  <Text style={[styles.accessCardLabel, access.access_level === opt.value && { color: opt.color }]}>{opt.label}</Text>
                  <Text style={styles.accessCardSub}>{opt.sub}</Text>
                </View>
              </TouchableOpacity>
            ))}
          </View>

          {/* Custom permissions */}
          {access.access_level === 'CUSTOM' && (
            <View style={styles.permBox}>
              <Text style={styles.permBoxTitle}>Individual Permissions</Text>
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
                <PermSwitch
                  key={p.key}
                  label={p.label}
                  value={access[p.key] as boolean}
                  onChange={v => setA(p.key, v)}
                />
              ))}
            </View>
          )}

          <Label text="Access Expiry Date (optional)" />
          <Input
            value={access.expires_at}
            onChange={v => setA('expires_at', v)}
            placeholder="YYYY-MM-DD (leave blank for no expiry)"
          />
          <Text style={styles.hint}>Leave blank if this proxy access should not expire.</Text>
        </View>
      )}

      {/* Error banner */}
      {submitErr ? (
        <View style={styles.errorBanner}>
          <Ionicons name="alert-circle-outline" size={16} color={RED} />
          <Text style={styles.errorBannerText}>{submitErr}</Text>
        </View>
      ) : null}

      {/* Navigation buttons */}
      <View style={styles.navRow}>
        {step > 0 ? (
          <TouchableOpacity style={styles.backNavBtn} onPress={() => setStep(s => s - 1)} activeOpacity={0.7}>
            <Ionicons name="arrow-back-outline" size={16} color={GRAY} />
            <Text style={styles.backNavText}>Back</Text>
          </TouchableOpacity>
        ) : (
          <TouchableOpacity style={styles.backNavBtn} onPress={onCancel} activeOpacity={0.7}>
            <Text style={styles.backNavText}>Cancel</Text>
          </TouchableOpacity>
        )}

        {step < 2 ? (
          <TouchableOpacity style={styles.nextBtn} onPress={nextStep} activeOpacity={0.8}>
            <Text style={styles.nextBtnText}>Continue</Text>
            <Ionicons name="arrow-forward-outline" size={16} color={WHITE} />
          </TouchableOpacity>
        ) : (
          <TouchableOpacity
            style={[styles.nextBtn, submitting && styles.nextBtnDisabled]}
            onPress={handleSubmit}
            activeOpacity={0.8}
            disabled={submitting}
          >
            {submitting
              ? <ActivityIndicator size="small" color={WHITE} />
              : <>
                  <Ionicons name="person-add-outline" size={16} color={WHITE} />
                  <Text style={styles.nextBtnText}>Add Loved One</Text>
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
  scroll:     { flex: 1, backgroundColor: BG },
  container:  { padding: 28, paddingBottom: 60, gap: 20, maxWidth: 820, alignSelf: 'center', width: '100%' } as any,

  pageHeader: { gap: 6 },
  backBtn:    { flexDirection: 'row', alignItems: 'center', gap: 6, marginBottom: 8 },
  backBtnText:{ fontSize: 13, color: GRAY },
  pageTitle:  { fontSize: 24, fontWeight: '700', color: TEXT },
  pageSub:    { fontSize: 13, color: GRAY, marginTop: 2 },

  stepper:      { flexDirection: 'row', alignItems: 'center', backgroundColor: WHITE, borderRadius: 14, padding: 20, borderWidth: 1, borderColor: BORDER },
  stepItem:     { flexDirection: 'row', alignItems: 'center', flex: 1 },
  stepCircle:   { width: 30, height: 30, borderRadius: 15, borderWidth: 2, borderColor: BORDER, alignItems: 'center', justifyContent: 'center', backgroundColor: WHITE },
  stepCircleActive:{ borderColor: PURPLE, backgroundColor: PURPLE },
  stepCircleDone:  { borderColor: GREEN,  backgroundColor: GREEN  },
  stepNum:      { fontSize: 13, fontWeight: '700', color: GRAY },
  stepLabel:    { fontSize: 12, color: GRAY, marginLeft: 8, fontWeight: '500' },
  stepLabelActive: { color: PURPLE, fontWeight: '700' },
  stepLine:     { flex: 1, height: 2, backgroundColor: BORDER, marginHorizontal: 8 },
  stepLineDone: { backgroundColor: GREEN },

  card:      { backgroundColor: WHITE, borderRadius: 16, borderWidth: 1, borderColor: BORDER, padding: 24, gap: 14 },
  cardTitle: { fontSize: 18, fontWeight: '700', color: TEXT },
  cardSub:   { fontSize: 13, color: GRAY, marginTop: -6 },

  row2: { flexDirection: 'row', gap: 14 },

  label:     { fontSize: 13, fontWeight: '600', color: TEXT, marginBottom: 4 },
  inputWrap: { marginBottom: 4 },
  input:     { borderWidth: 1, borderColor: BORDER, borderRadius: 10, paddingHorizontal: 14, paddingVertical: 11, fontSize: 14, color: TEXT, backgroundColor: WHITE } as any,
  inputError:   { borderColor: RED },
  inputDisabled:{ backgroundColor: BG },
  errorText: { fontSize: 11, color: RED, marginTop: 4 },
  hint:      { fontSize: 11, color: GRAY2, marginTop: -8 },

  pwRow:     { flexDirection: 'row', alignItems: 'center', gap: 8 },
  pwIconBtn: { width: 38, height: 42, alignItems: 'center', justifyContent: 'center', borderWidth: 1, borderColor: BORDER, borderRadius: 10 },
  regenBtn:  { flexDirection: 'row', alignItems: 'center', gap: 4, borderWidth: 1, borderColor: BORDER, borderRadius: 10, paddingHorizontal: 10, paddingVertical: 9 },
  regenText: { fontSize: 12, color: PURPLE, fontWeight: '500' },

  selectRow:          { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 4 },
  selectChip:         { borderWidth: 1, borderColor: BORDER, borderRadius: 8, paddingHorizontal: 14, paddingVertical: 8 },
  selectChipActive:   { borderColor: PURPLE, backgroundColor: PURPLE_BG },
  selectChipText:     { fontSize: 13, color: GRAY, fontWeight: '500' },
  selectChipTextActive:{ color: PURPLE, fontWeight: '700' },

  tagContainer: { gap: 8, marginBottom: 4 },
  tagChips:     { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  tagChip:      { flexDirection: 'row', alignItems: 'center', gap: 5, backgroundColor: PURPLE_BG, borderRadius: 20, paddingHorizontal: 10, paddingVertical: 4 },
  tagChipText:  { fontSize: 12, color: PURPLE, fontWeight: '500' },
  tagInputRow:  { flexDirection: 'row', gap: 8 },
  tagInput:     { flex: 1, borderWidth: 1, borderColor: BORDER, borderRadius: 10, paddingHorizontal: 12, paddingVertical: 9, fontSize: 13, color: TEXT } as any,
  tagAddBtn:    { borderWidth: 1, borderColor: PURPLE, borderRadius: 10, paddingHorizontal: 14, paddingVertical: 9, backgroundColor: PURPLE_BG },
  tagAddBtnText:{ fontSize: 13, color: PURPLE, fontWeight: '600' },

  accessGrid:        { flexDirection: 'row', flexWrap: 'wrap', gap: 10, marginBottom: 4 },
  accessCard:        { flex: 1, minWidth: 160, flexDirection: 'row', alignItems: 'flex-start', gap: 10, borderWidth: 1, borderColor: BORDER, borderRadius: 12, padding: 14 },
  accessRadio:       { width: 18, height: 18, borderRadius: 9, borderWidth: 2, borderColor: BORDER, alignItems: 'center', justifyContent: 'center', marginTop: 2 },
  accessRadioDot:    { width: 8, height: 8, borderRadius: 4 },
  accessCardLabel:   { fontSize: 13, fontWeight: '700', color: TEXT, marginBottom: 2 },
  accessCardSub:     { fontSize: 11, color: GRAY },

  permBox:      { backgroundColor: BG, borderRadius: 12, borderWidth: 1, borderColor: BORDER, padding: 16, gap: 0 },
  permBoxTitle: { fontSize: 13, fontWeight: '700', color: TEXT, marginBottom: 12 },
  permRow:      { flexDirection: 'row', alignItems: 'center', paddingVertical: 10, borderBottomWidth: 1, borderBottomColor: BORDER },
  permLabel:    { flex: 1, fontSize: 13, color: TEXT },
  toggle:       { width: 40, height: 22, borderRadius: 11, backgroundColor: BORDER, justifyContent: 'center', paddingHorizontal: 2 },
  toggleOn:     { backgroundColor: PURPLE },
  toggleThumb:  { width: 18, height: 18, borderRadius: 9, backgroundColor: WHITE, shadowColor: '#000', shadowOpacity: 0.15, shadowRadius: 2 } as any,
  toggleThumbOn:{ alignSelf: 'flex-end' },

  errorBanner:     { flexDirection: 'row', alignItems: 'center', gap: 8, backgroundColor: '#FEE2E2', borderRadius: 10, padding: 14 },
  errorBannerText: { fontSize: 13, color: RED, flex: 1 },

  navRow:        { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  backNavBtn:    { flexDirection: 'row', alignItems: 'center', gap: 6, borderWidth: 1, borderColor: BORDER, borderRadius: 10, paddingHorizontal: 18, paddingVertical: 11 },
  backNavText:   { fontSize: 14, color: GRAY, fontWeight: '500' },
  nextBtn:       { flexDirection: 'row', alignItems: 'center', gap: 8, backgroundColor: PURPLE, borderRadius: 10, paddingHorizontal: 24, paddingVertical: 12 },
  nextBtnDisabled:{ opacity: 0.5 },
  nextBtnText:   { fontSize: 14, color: WHITE, fontWeight: '700' },

  successWrap: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: BG, padding: 24 },
  successCard: { backgroundColor: WHITE, borderRadius: 20, borderWidth: 1, borderColor: BORDER, padding: 32, maxWidth: 500, width: '100%' as any, alignItems: 'center', gap: 14 },
  successIcon: { marginBottom: 4 },
  successTitle:{ fontSize: 24, fontWeight: '700', color: TEXT, textAlign: 'center' },
  successSub:  { fontSize: 14, color: GRAY, textAlign: 'center', lineHeight: 22 },
  credentialsBox:{ backgroundColor: BG, borderRadius: 12, borderWidth: 1, borderColor: BORDER, padding: 16, width: '100%' as any, gap: 8 },
  credTitle:   { fontSize: 13, fontWeight: '700', color: TEXT, marginBottom: 4 },
  credRow:     { flexDirection: 'row', justifyContent: 'space-between' },
  credLabel:   { fontSize: 12, color: GRAY },
  credValue:   { fontSize: 13, fontWeight: '600', color: TEXT },
  credNote:    { fontSize: 11, color: GRAY, fontStyle: 'italic', marginTop: 4 },
  doneBtn:     { backgroundColor: PURPLE, borderRadius: 12, paddingHorizontal: 32, paddingVertical: 14, marginTop: 8 },
  doneBtnText: { fontSize: 15, color: WHITE, fontWeight: '700' },
});
