import { Ionicons } from '@expo/vector-icons';
import { zodResolver } from '@hookform/resolvers/zod';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import {
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  useWindowDimensions,
  View,
} from 'react-native';
import { PhoneInput } from '@/components/shared/phone-input';
import { registerSchema, type RegisterFormData } from '@/lib/validation/auth-schemas';

const TEAL   = '#0D9488';
const PURPLE = '#7C3AED';
const INDIGO = '#4F46E5';
const NAVY   = '#0D1B2E';
const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const RED    = '#EF4444';
const GREEN  = '#10B981';

type Role = 'patient' | 'doctor' | 'proxy';
type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

const roles: { key: Role; label: string; icon: IoniconsName; color: string; bg: string; desc: string }[] = [
  { key: 'patient', label: 'Patient',       icon: 'person-outline',   color: TEAL,   bg: '#E6F4F1', desc: 'Manage your health records' },
  { key: 'doctor',  label: 'Doctor',        icon: 'medical-outline',  color: INDIGO, bg: '#EEF2FF', desc: 'Manage your practice'        },
  { key: 'proxy',   label: 'Proxy / Carer', icon: 'people-outline',   color: PURPLE, bg: '#F5F3FF', desc: 'Care for a loved one'         },
];

const panelPoints: { icon: IoniconsName; text: string }[] = [
  { icon: 'person-add-outline',       text: 'Create your secure health profile'   },
  { icon: 'shield-checkmark-outline', text: 'Your data is encrypted and private'  },
  { icon: 'videocam-outline',         text: 'Consult doctors from anywhere'        },
  { icon: 'notifications-outline',    text: 'Get real-time care updates'           },
];

function FieldError({ message }: { message?: string }) {
  if (!message) return null;
  return (
    <View style={styles.fieldError}>
      <Ionicons name="alert-circle-outline" size={13} color={RED} />
      <Text style={styles.fieldErrorText}>{message}</Text>
    </View>
  );
}

function Field({
  label, icon, placeholder, value, onChangeText,
  keyboardType = 'default', secureTextEntry = false,
  rightElement, error,
}: {
  label: string; icon: IoniconsName; placeholder: string;
  value: string; onChangeText: (v: string) => void;
  keyboardType?: any; secureTextEntry?: boolean;
  rightElement?: React.ReactNode; error?: string;
}) {
  return (
    <View style={styles.fieldGroup}>
      <Text style={styles.label}>{label}</Text>
      <View style={[styles.inputRow, !!error && styles.inputRowError]}>
        <Ionicons name={icon} size={17} color={GRAY} style={styles.inputIcon} />
        <TextInput
          style={styles.input}
          placeholder={placeholder}
          placeholderTextColor={GRAY}
          value={value}
          onChangeText={onChangeText}
          keyboardType={keyboardType}
          secureTextEntry={secureTextEntry}
          autoCapitalize={secureTextEntry || keyboardType === 'email-address' ? 'none' : 'words'}
          autoCorrect={false}
        />
        {rightElement}
      </View>
      <FieldError message={error} />
    </View>
  );
}

export default function RegisterWeb() {
  const router = useRouter();
  const { width } = useWindowDimensions();
  const narrow = width < 768;

  const [role, setRole]         = useState<Role>('patient');
  const [showPass, setShowPass] = useState(false);
  const [showConf, setShowConf] = useState(false);

  const active = roles.find(r => r.key === role)!;

  const {
    control,
    handleSubmit,
    watch,
    formState: { errors },
  } = useForm<RegisterFormData>({
    resolver:      zodResolver(registerSchema),
    defaultValues: {
      firstName: '', lastName: '', email: '',
      phone: '', password: '', confirmPassword: '',
      agreed: undefined as any,
    },
  });

  const passwordValue = watch('password');
  const agreedValue   = watch('agreed');

  const pwStrength = !passwordValue ? 0
    : passwordValue.length < 6  ? 1
    : passwordValue.length < 10 ? 2 : 3;
  const strengthColor = ['transparent', RED, INDIGO, GREEN][pwStrength];
  const strengthLabel = ['', 'Weak', 'Good', 'Strong'][pwStrength];

  function onSubmit(data: RegisterFormData) {
    // TODO: connect to auth service
    console.log({ ...data, role });
  }

  return (
    <View style={styles.screen}>
      {/* ── Left: form (50%) ── */}
      <ScrollView
        style={[styles.formPane, narrow && styles.formPaneFull]}
        contentContainerStyle={styles.formScroll}
        showsVerticalScrollIndicator={false}
        keyboardShouldPersistTaps="handled"
      >
        {/* Logo */}
        <View style={styles.logoRow}>
          <View style={[styles.logoIcon, { borderColor: active.color }]}>
            <Ionicons name="heart" size={20} color={active.color} />
          </View>
          <View>
            <Text style={styles.logoText}>
              <Text style={{ color: NAVY }}>Care</Text>
              <Text style={{ color: active.color }}>Nexus</Text>
            </Text>
            <Text style={[styles.logoSub, { color: active.color }]}>HEALTHCARE PLATFORM</Text>
          </View>
        </View>

        <Text style={styles.heading}>Create your account</Text>
        <Text style={styles.subheading}>Join CareNexus — it only takes a minute.</Text>

        {/* Role */}
        <Text style={styles.label}>I am a</Text>
        <View style={styles.roleRow}>
          {roles.map(r => {
            const on = role === r.key;
            return (
              <TouchableOpacity
                key={r.key}
                style={[styles.roleBtn, on && { borderColor: r.color, backgroundColor: r.bg }]}
                onPress={() => setRole(r.key)}
                activeOpacity={0.8}
              >
                <Ionicons name={r.icon} size={20} color={on ? r.color : GRAY} />
                <Text style={[styles.roleLabel, on && { color: r.color, fontWeight: '700' }]}>{r.label}</Text>
                <Text style={[styles.roleDesc, on && { color: r.color }]}>{r.desc}</Text>
                {on && (
                  <View style={[styles.roleCheck, { backgroundColor: r.color }]}>
                    <Ionicons name="checkmark" size={10} color={WHITE} />
                  </View>
                )}
              </TouchableOpacity>
            );
          })}
        </View>

        {/* Name row */}
        <View style={styles.twoCol}>
          <View style={{ flex: 1 }}>
            <Controller
              control={control}
              name="firstName"
              render={({ field: { onChange, value } }) => (
                <Field label="First Name" icon="person-outline" placeholder="John"
                  value={value} onChangeText={onChange} error={errors.firstName?.message} />
              )}
            />
          </View>
          <View style={{ flex: 1 }}>
            <Controller
              control={control}
              name="lastName"
              render={({ field: { onChange, value } }) => (
                <Field label="Last Name" icon="person-outline" placeholder="Smith"
                  value={value} onChangeText={onChange} error={errors.lastName?.message} />
              )}
            />
          </View>
        </View>

        <Controller
          control={control}
          name="email"
          render={({ field: { onChange, value } }) => (
            <Field label="Email Address" icon="mail-outline" placeholder="you@example.com"
              value={value} onChangeText={onChange} keyboardType="email-address" error={errors.email?.message} />
          )}
        />

        <View style={styles.fieldGroup}>
          <Text style={styles.label}>Phone Number</Text>
          <Controller
            control={control}
            name="phone"
            render={({ field: { onChange, value } }) => (
              <PhoneInput
                value={value}
                onChange={onChange}
                error={errors.phone?.message}
                accentColor={active.color}
              />
            )}
          />
        </View>

        {/* Password */}
        <Controller
          control={control}
          name="password"
          render={({ field: { onChange, value } }) => (
            <Field
              label="Password" icon="lock-closed-outline" placeholder="Min. 8 characters"
              value={value} onChangeText={onChange} secureTextEntry={!showPass} error={errors.password?.message}
              rightElement={
                <TouchableOpacity onPress={() => setShowPass(v => !v)} style={{ padding: 4 }} activeOpacity={0.7}>
                  <Ionicons name={showPass ? 'eye-off-outline' : 'eye-outline'} size={17} color={GRAY} />
                </TouchableOpacity>
              }
            />
          )}
        />
        {passwordValue.length > 0 && (
          <View style={styles.strengthRow}>
            {[1, 2, 3].map(i => (
              <View key={i} style={[styles.strengthBar, { backgroundColor: i <= pwStrength ? strengthColor : BORDER }]} />
            ))}
            <Text style={[styles.strengthLabel, { color: strengthColor }]}>{strengthLabel}</Text>
          </View>
        )}

        {/* Confirm password */}
        <Controller
          control={control}
          name="confirmPassword"
          render={({ field: { onChange, value } }) => (
            <Field
              label="Confirm Password" icon="lock-closed-outline" placeholder="Re-enter your password"
              value={value} onChangeText={onChange} secureTextEntry={!showConf} error={errors.confirmPassword?.message}
              rightElement={
                <TouchableOpacity onPress={() => setShowConf(v => !v)} style={{ padding: 4 }} activeOpacity={0.7}>
                  <Ionicons name={showConf ? 'eye-off-outline' : 'eye-outline'} size={17} color={GRAY} />
                </TouchableOpacity>
              }
            />
          )}
        />

        {/* Terms */}
        <Controller
          control={control}
          name="agreed"
          render={({ field: { onChange } }) => (
            <TouchableOpacity
              style={styles.termsRow}
              onPress={() => onChange(agreedValue ? (undefined as any) : true)}
              activeOpacity={0.8}
            >
              <View style={[styles.checkbox, agreedValue ? { backgroundColor: active.color, borderColor: active.color } : undefined]}>
                {agreedValue && <Ionicons name="checkmark" size={12} color={WHITE} />}
              </View>
              <Text style={styles.termsText}>
                I agree to the{' '}
                <Text style={[styles.termsLink, { color: active.color }]}>Terms of Service</Text>
                {' '}and{' '}
                <Text style={[styles.termsLink, { color: active.color }]}>Privacy Policy</Text>
              </Text>
            </TouchableOpacity>
          )}
        />
        {!!errors.agreed && (
          <View style={[styles.fieldError, { marginTop: -8, marginBottom: 8 }]}>
            <Ionicons name="alert-circle-outline" size={13} color={RED} />
            <Text style={styles.fieldErrorText}>{errors.agreed.message}</Text>
          </View>
        )}

        {/* Submit */}
        <TouchableOpacity
          style={[styles.submitBtn, { backgroundColor: active.color }]}
          onPress={handleSubmit(onSubmit)}
          activeOpacity={0.85}
        >
          <Ionicons name="person-add-outline" size={18} color={WHITE} />
          <Text style={styles.submitText}>Create Account</Text>
        </TouchableOpacity>

        {/* Sign in link */}
        <View style={styles.signinRow}>
          <Text style={styles.signinText}>Already have an account? </Text>
          <TouchableOpacity activeOpacity={0.7} onPress={() => router.push('/auth/login/web/login-web')}>
            <Text style={[styles.signinLink, { color: active.color }]}>Sign in</Text>
          </TouchableOpacity>
        </View>
      </ScrollView>

      {/* ── Right: branded panel (50%) ── */}
      {!narrow && (
        <View style={[styles.panel, { backgroundColor: active.color }]}>
          <View style={styles.panelInner}>
            <View style={styles.panelLogoRow}>
              <Ionicons name="heart" size={28} color={WHITE} />
              <Text style={styles.panelLogoText}>CareNexus</Text>
            </View>
            <Text style={styles.panelTitle}>Start your health journey today.</Text>
            <Text style={styles.panelSub}>
              Create a free account and get access to consultations, health records, and your care team — all in one place.
            </Text>
            <View style={styles.featureList}>
              {panelPoints.map(p => (
                <View key={p.text} style={styles.featureRow}>
                  <View style={styles.featureIconBg}>
                    <Ionicons name={p.icon} size={16} color={active.color} />
                  </View>
                  <Text style={styles.featureText}>{p.text}</Text>
                </View>
              ))}
            </View>
            <View style={[styles.decor, styles.decorBig]} />
            <View style={[styles.decor, styles.decorSmall]} />
          </View>
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, flexDirection: 'row', backgroundColor: BG },

  formPane:     { flex: 1, backgroundColor: WHITE, borderRightWidth: 1, borderRightColor: BORDER },
  formPaneFull: { flex: 1, borderRightWidth: 0 },
  formScroll:   { flexGrow: 1, justifyContent: 'center', padding: 48, maxWidth: 560, alignSelf: 'center', width: '100%' },

  logoRow:  { flexDirection: 'row', alignItems: 'center', gap: 12, marginBottom: 28 },
  logoIcon: { width: 44, height: 44, borderRadius: 22, borderWidth: 2, alignItems: 'center', justifyContent: 'center' },
  logoText: { fontSize: 20, fontWeight: '800', lineHeight: 24 },
  logoSub:  { fontSize: 9, fontWeight: '700', letterSpacing: 2.5, marginTop: 1 },

  heading:    { fontSize: 26, fontWeight: '800', color: TEXT, marginBottom: 4 },
  subheading: { fontSize: 14, color: GRAY, marginBottom: 24 },

  label: { fontSize: 13, fontWeight: '600', color: TEXT, marginBottom: 8 },

  roleRow: { flexDirection: 'row', gap: 10, marginBottom: 20 },
  roleBtn: {
    flex: 1, alignItems: 'center', gap: 4,
    borderWidth: 1.5, borderColor: BORDER, borderRadius: 12,
    paddingVertical: 14, paddingHorizontal: 8, position: 'relative',
  },
  roleLabel: { fontSize: 12, color: GRAY, fontWeight: '600', textAlign: 'center' },
  roleDesc:  { fontSize: 10, color: GRAY, textAlign: 'center', lineHeight: 14 },
  roleCheck: {
    position: 'absolute', top: -7, right: -7,
    width: 18, height: 18, borderRadius: 9,
    alignItems: 'center', justifyContent: 'center',
  },

  twoCol: { flexDirection: 'row', gap: 12 },

  fieldGroup: { marginBottom: 14 },
  inputRow: {
    flexDirection: 'row', alignItems: 'center',
    borderWidth: 1.5, borderColor: BORDER, borderRadius: 12,
    paddingHorizontal: 14, paddingVertical: 13,
    backgroundColor: BG,
  },
  inputRowError: { borderColor: RED },
  inputIcon:     { marginRight: 10 },
  input:         { flex: 1, fontSize: 14, color: TEXT, outlineStyle: 'none' } as any,

  fieldError:     { flexDirection: 'row', alignItems: 'center', gap: 4, marginTop: 5 },
  fieldErrorText: { fontSize: 12, color: RED },

  strengthRow:   { flexDirection: 'row', alignItems: 'center', gap: 6, marginTop: -8, marginBottom: 10 },
  strengthBar:   { flex: 1, height: 3, borderRadius: 2 },
  strengthLabel: { fontSize: 11, fontWeight: '600', width: 44 },

  termsRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 10, marginBottom: 16 },
  checkbox: {
    width: 20, height: 20, borderRadius: 5, borderWidth: 1.5,
    borderColor: BORDER, alignItems: 'center', justifyContent: 'center', marginTop: 1,
  },
  termsText: { flex: 1, fontSize: 13, color: GRAY, lineHeight: 20 },
  termsLink: { fontWeight: '600' },

  submitBtn:  { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8, borderRadius: 12, paddingVertical: 16, marginBottom: 20 },
  submitText: { fontSize: 16, fontWeight: '700', color: WHITE },

  signinRow:  { flexDirection: 'row', justifyContent: 'center', alignItems: 'center' },
  signinText: { fontSize: 13, color: GRAY },
  signinLink: { fontSize: 13, fontWeight: '700' },

  panel:      { flex: 1, overflow: 'hidden' },
  panelInner: { flex: 1, justifyContent: 'center', padding: 56, gap: 20 },
  panelLogoRow:  { flexDirection: 'row', alignItems: 'center', gap: 10, marginBottom: 8 },
  panelLogoText: { fontSize: 24, fontWeight: '800', color: WHITE },
  panelTitle:    { fontSize: 32, fontWeight: '800', color: WHITE, lineHeight: 40 },
  panelSub:      { fontSize: 15, color: 'rgba(255,255,255,0.8)', lineHeight: 24 },
  featureList:   { gap: 14, marginTop: 8 },
  featureRow:    { flexDirection: 'row', alignItems: 'center', gap: 12 },
  featureIconBg: { width: 32, height: 32, borderRadius: 8, backgroundColor: 'rgba(255,255,255,0.2)', alignItems: 'center', justifyContent: 'center' },
  featureText:   { fontSize: 14, color: 'rgba(255,255,255,0.92)', fontWeight: '500' },

  decor:      { position: 'absolute', borderWidth: 1.5, borderColor: 'rgba(255,255,255,0.15)', borderRadius: 999 },
  decorBig:   { width: 320, height: 320, bottom: -100, right: -80 },
  decorSmall: { width: 160, height: 160, top: -40, right: 40 },
});
