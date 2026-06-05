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
import { loginSchema, type LoginFormData } from '@/lib/validation/auth-schemas';

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

type Role = 'patient' | 'doctor' | 'proxy';
type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

const roles: { key: Role; label: string; icon: IoniconsName; color: string; bg: string }[] = [
  { key: 'patient', label: 'Patient',       icon: 'person-outline',   color: TEAL,   bg: '#E6F4F1' },
  { key: 'doctor',  label: 'Doctor',        icon: 'medical-outline',  color: INDIGO, bg: '#EEF2FF' },
  { key: 'proxy',   label: 'Proxy / Carer', icon: 'people-outline',   color: PURPLE, bg: '#F5F3FF' },
];

const features: { icon: IoniconsName; text: string }[] = [
  { icon: 'lock-closed-outline',      text: 'End-to-end encrypted records'    },
  { icon: 'videocam-outline',         text: 'Secure video consultations'       },
  { icon: 'sync-outline',             text: 'Real-time care coordination'      },
  { icon: 'shield-checkmark-outline', text: 'HIPAA compliant infrastructure'   },
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

export default function LoginWeb() {
  const router = useRouter();
  const { width } = useWindowDimensions();
  const narrow = width < 768;

  const [role, setRole]         = useState<Role>('patient');
  const [showPass, setShowPass] = useState(false);

  const active = roles.find(r => r.key === role)!;

  const { control, handleSubmit, formState: { errors } } = useForm<LoginFormData>({
    resolver:      zodResolver(loginSchema),
    defaultValues: { email: '', password: '' },
  });

  function onSubmit(data: LoginFormData) {
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
            <Ionicons name="heart" size={22} color={active.color} />
          </View>
          <View>
            <Text style={styles.logoText}>
              <Text style={{ color: NAVY }}>Care</Text>
              <Text style={{ color: active.color }}>Nexus</Text>
            </Text>
            <Text style={[styles.logoSub, { color: active.color }]}>HEALTHCARE PLATFORM</Text>
          </View>
        </View>

        <Text style={styles.heading}>Welcome back</Text>
        <Text style={styles.subheading}>Sign in to continue to your dashboard</Text>

        {/* Role selector */}
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
                <Ionicons name={r.icon} size={22} color={on ? r.color : GRAY} />
                <Text style={[styles.roleLabel, on && { color: r.color, fontWeight: '700' }]}>{r.label}</Text>
                {on && (
                  <View style={[styles.roleCheck, { backgroundColor: r.color }]}>
                    <Ionicons name="checkmark" size={10} color={WHITE} />
                  </View>
                )}
              </TouchableOpacity>
            );
          })}
        </View>

        {/* Email */}
        <View style={styles.fieldGroup}>
          <Text style={styles.label}>Email address</Text>
          <Controller
            control={control}
            name="email"
            render={({ field: { onChange, value } }) => (
              <View style={[styles.inputRow, !!errors.email && styles.inputRowError]}>
                <Ionicons name="mail-outline" size={18} color={GRAY} style={styles.inputIcon} />
                <TextInput
                  style={styles.input}
                  placeholder="you@example.com"
                  placeholderTextColor={GRAY}
                  value={value}
                  onChangeText={onChange}
                  keyboardType="email-address"
                  autoCapitalize="none"
                  autoCorrect={false}
                />
              </View>
            )}
          />
          <FieldError message={errors.email?.message} />
        </View>

        {/* Password */}
        <View style={styles.fieldGroup}>
          <View style={styles.fieldHeaderRow}>
            <Text style={styles.label}>Password</Text>
            <TouchableOpacity activeOpacity={0.7}>
              <Text style={[styles.forgotText, { color: active.color }]}>Forgot password?</Text>
            </TouchableOpacity>
          </View>
          <Controller
            control={control}
            name="password"
            render={({ field: { onChange, value } }) => (
              <View style={[styles.inputRow, !!errors.password && styles.inputRowError]}>
                <Ionicons name="lock-closed-outline" size={18} color={GRAY} style={styles.inputIcon} />
                <TextInput
                  style={styles.input}
                  placeholder="Enter your password"
                  placeholderTextColor={GRAY}
                  value={value}
                  onChangeText={onChange}
                  secureTextEntry={!showPass}
                  autoCapitalize="none"
                />
                <TouchableOpacity onPress={() => setShowPass(v => !v)} style={{ padding: 4 }} activeOpacity={0.7}>
                  <Ionicons name={showPass ? 'eye-off-outline' : 'eye-outline'} size={18} color={GRAY} />
                </TouchableOpacity>
              </View>
            )}
          />
          <FieldError message={errors.password?.message} />
        </View>

        {/* Sign In */}
        <TouchableOpacity
          style={[styles.signInBtn, { backgroundColor: active.color }]}
          onPress={handleSubmit(onSubmit)}
          activeOpacity={0.85}
        >
          <Text style={styles.signInText}>Sign In</Text>
          <Ionicons name="arrow-forward-outline" size={18} color={WHITE} />
        </TouchableOpacity>

        {/* Divider */}
        <View style={styles.dividerRow}>
          <View style={styles.dividerLine} />
          <Text style={styles.dividerText}>or</Text>
          <View style={styles.dividerLine} />
        </View>

        {/* Create account */}
        <TouchableOpacity
          style={styles.createBtn}
          activeOpacity={0.8}
          onPress={() => router.push('/auth/register/web/register-web')}
        >
          <Ionicons name="person-add-outline" size={16} color={active.color} />
          <Text style={[styles.createText, { color: active.color }]}>Create a new account</Text>
        </TouchableOpacity>

        {/* Footer */}
        <Text style={styles.footerText}>
          By signing in you agree to our{' '}
          <Text style={[styles.footerLink, { color: active.color }]}>Terms of Service</Text>
          {' '}and{' '}
          <Text style={[styles.footerLink, { color: active.color }]}>Privacy Policy</Text>
        </Text>
      </ScrollView>

      {/* ── Right: branded panel (50%, hidden on narrow) ── */}
      {!narrow && (
        <View style={[styles.panel, { backgroundColor: active.color }]}>
          <View style={styles.panelInner}>
            <View style={styles.panelLogoRow}>
              <Ionicons name="heart" size={28} color={WHITE} />
              <Text style={styles.panelLogoText}>CareNexus</Text>
            </View>
            <Text style={styles.panelTitle}>Your health,{'\n'}securely connected.</Text>
            <Text style={styles.panelSub}>
              CareNexus brings together patients, doctors, and carers on one trusted platform — accessible from anywhere.
            </Text>
            <View style={styles.featureList}>
              {features.map(f => (
                <View key={f.text} style={styles.featureRow}>
                  <View style={styles.featureIconBg}>
                    <Ionicons name={f.icon} size={16} color={active.color} />
                  </View>
                  <Text style={styles.featureText}>{f.text}</Text>
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
  screen: {
    flex: 1,
    flexDirection: 'row',
    backgroundColor: BG,
  },

  formPane:     { flex: 1, backgroundColor: WHITE, borderRightWidth: 1, borderRightColor: BORDER },
  formPaneFull: { flex: 1, borderRightWidth: 0 },
  formScroll: {
    flexGrow: 1,
    justifyContent: 'center',
    padding: 48,
    maxWidth: 520,
    alignSelf: 'center',
    width: '100%',
  },

  logoRow:  { flexDirection: 'row', alignItems: 'center', gap: 12, marginBottom: 36 },
  logoIcon: { width: 46, height: 46, borderRadius: 23, borderWidth: 2, alignItems: 'center', justifyContent: 'center' },
  logoText: { fontSize: 22, fontWeight: '800', lineHeight: 26 },
  logoSub:  { fontSize: 9, fontWeight: '700', letterSpacing: 2.5, marginTop: 1 },

  heading:    { fontSize: 28, fontWeight: '800', color: TEXT, marginBottom: 6 },
  subheading: { fontSize: 14, color: GRAY, marginBottom: 28 },

  label:   { fontSize: 13, fontWeight: '600', color: TEXT, marginBottom: 10 },
  roleRow: { flexDirection: 'row', gap: 10, marginBottom: 24 },
  roleBtn: {
    flex: 1, alignItems: 'center', gap: 6,
    borderWidth: 1.5, borderColor: BORDER, borderRadius: 12,
    paddingVertical: 14, paddingHorizontal: 6, position: 'relative',
  },
  roleLabel: { fontSize: 12, color: GRAY, textAlign: 'center' },
  roleCheck: {
    position: 'absolute', top: -7, right: -7,
    width: 18, height: 18, borderRadius: 9,
    alignItems: 'center', justifyContent: 'center',
  },

  fieldGroup:     { marginBottom: 18 },
  fieldHeaderRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 },
  forgotText:     { fontSize: 13, fontWeight: '500' },
  inputRow: {
    flexDirection: 'row', alignItems: 'center',
    borderWidth: 1.5, borderColor: BORDER, borderRadius: 12,
    paddingHorizontal: 14, paddingVertical: 14,
    backgroundColor: BG,
  },
  inputRowError: { borderColor: RED },
  inputIcon:     { marginRight: 10 },
  input:         { flex: 1, fontSize: 15, color: TEXT, outlineStyle: 'none' } as any,

  fieldError:     { flexDirection: 'row', alignItems: 'center', gap: 5, marginTop: 5 },
  fieldErrorText: { fontSize: 12, color: RED },

  signInBtn:  { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8, borderRadius: 12, paddingVertical: 16, marginBottom: 20 },
  signInText: { fontSize: 16, fontWeight: '700', color: WHITE },

  dividerRow:  { flexDirection: 'row', alignItems: 'center', gap: 12, marginBottom: 20 },
  dividerLine: { flex: 1, height: 1, backgroundColor: BORDER },
  dividerText: { fontSize: 13, color: GRAY },

  createBtn:  { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8, borderWidth: 1.5, borderColor: BORDER, borderRadius: 12, paddingVertical: 14, marginBottom: 28 },
  createText: { fontSize: 14, fontWeight: '600' },

  footerText: { fontSize: 12, color: GRAY, textAlign: 'center', lineHeight: 18 },
  footerLink: { fontWeight: '600' },

  panel: { flex: 1, overflow: 'hidden' },
  panelInner: { flex: 1, justifyContent: 'center', padding: 56, gap: 20 },
  panelLogoRow:  { flexDirection: 'row', alignItems: 'center', gap: 10, marginBottom: 8 },
  panelLogoText: { fontSize: 24, fontWeight: '800', color: WHITE },
  panelTitle:    { fontSize: 34, fontWeight: '800', color: WHITE, lineHeight: 42 },
  panelSub:      { fontSize: 15, color: 'rgba(255,255,255,0.8)', lineHeight: 24 },

  featureList:   { gap: 14, marginTop: 8 },
  featureRow:    { flexDirection: 'row', alignItems: 'center', gap: 12 },
  featureIconBg: { width: 32, height: 32, borderRadius: 8, backgroundColor: 'rgba(255,255,255,0.2)', alignItems: 'center', justifyContent: 'center' },
  featureText:   { fontSize: 14, color: 'rgba(255,255,255,0.92)', fontWeight: '500' },

  decor:      { position: 'absolute', borderWidth: 1.5, borderColor: 'rgba(255,255,255,0.15)', borderRadius: 999 },
  decorBig:   { width: 320, height: 320, bottom: -100, right: -80 },
  decorSmall: { width: 160, height: 160, top: -40, right: 40 },
});
