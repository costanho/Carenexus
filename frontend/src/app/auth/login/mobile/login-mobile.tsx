import { Ionicons } from '@expo/vector-icons';
import { zodResolver } from '@hookform/resolvers/zod';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import {
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
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { loginSchema, type LoginFormData } from '@/lib/validation/auth-schemas';

const TEAL   = '#0D9488';
const PURPLE = '#7C3AED';
const INDIGO = '#4F46E5';
const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const RED    = '#EF4444';

type Role = 'patient' | 'doctor' | 'proxy';
type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

const roles: { key: Role; label: string; icon: IoniconsName; color: string; bg: string }[] = [
  { key: 'patient', label: 'Patient',       icon: 'person-outline',  color: TEAL,   bg: '#E6F4F1' },
  { key: 'doctor',  label: 'Doctor',        icon: 'medical-outline', color: INDIGO, bg: '#EEF2FF' },
  { key: 'proxy',   label: 'Proxy / Carer', icon: 'people-outline',  color: PURPLE, bg: '#F5F3FF' },
];

export default function LoginMobile() {
  const router = useRouter();
  const { width, height } = useWindowDimensions();
  const insets = useSafeAreaInsets();

  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));

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
    <KeyboardAvoidingView
      style={styles.screen}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      {/* Branded header strip */}
      <View style={[styles.header, {
        backgroundColor: active.color,
        paddingTop: insets.top + sh(20),
        paddingBottom: sh(28),
        paddingHorizontal: s(24),
        gap: s(8),
      }]}>
        <View style={[styles.headerLogoRow, { gap: s(10) }]}>
          <View style={[styles.headerIconBg, { width: s(40), height: s(40), borderRadius: s(20) }]}>
            <Ionicons name="heart" size={s(20)} color={active.color} />
          </View>
          <Text style={[styles.headerLogoText, { fontSize: s(22) }]}>
            <Text style={{ color: WHITE }}>Care</Text>
            <Text style={{ color: 'rgba(255,255,255,0.75)' }}>Nexus</Text>
          </Text>
        </View>
        <Text style={[styles.headerTitle, { fontSize: s(26) }]}>Welcome back</Text>
        <Text style={[styles.headerSub, { fontSize: s(13) }]}>Sign in to continue to your dashboard</Text>
      </View>

      <ScrollView
        style={styles.scroll}
        contentContainerStyle={[styles.scrollContent, {
          padding: s(20),
          paddingBottom: insets.bottom + sh(32),
          gap: s(18),
        }]}
        showsVerticalScrollIndicator={false}
        keyboardShouldPersistTaps="handled"
      >
        {/* Role selector */}
        <View style={{ gap: s(8) }}>
          <Text style={[styles.label, { fontSize: s(13) }]}>I am a</Text>
          <View style={[styles.roleRow, { gap: s(10) }]}>
            {roles.map(r => {
              const on = role === r.key;
              return (
                <TouchableOpacity
                  key={r.key}
                  style={[
                    styles.roleBtn,
                    { gap: s(6), borderRadius: s(12), paddingVertical: s(12) },
                    on && { borderColor: r.color, backgroundColor: r.bg },
                  ]}
                  onPress={() => setRole(r.key)}
                  activeOpacity={0.8}
                >
                  <Ionicons name={r.icon} size={s(22)} color={on ? r.color : GRAY} />
                  <Text style={[styles.roleLabel, { fontSize: s(11) }, on && { color: r.color, fontWeight: '700' }]}>
                    {r.label}
                  </Text>
                  {on && (
                    <View style={[styles.roleCheck, {
                      backgroundColor: r.color,
                      width: s(16), height: s(16), borderRadius: s(8),
                    }]}>
                      <Ionicons name="checkmark" size={s(9)} color={WHITE} />
                    </View>
                  )}
                </TouchableOpacity>
              );
            })}
          </View>
        </View>

        {/* Email */}
        <View style={{ gap: s(6) }}>
          <Text style={[styles.label, { fontSize: s(13) }]}>Email address</Text>
          <Controller
            control={control}
            name="email"
            render={({ field: { onChange, value } }) => (
              <View style={[
                styles.inputRow,
                { borderRadius: s(12), paddingHorizontal: s(14), paddingVertical: s(14) },
                !!errors.email && styles.inputRowError,
              ]}>
                <Ionicons name="mail-outline" size={s(18)} color={GRAY} style={{ marginRight: s(10) }} />
                <TextInput
                  style={[styles.input, { fontSize: s(14) }]}
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
          {!!errors.email && (
            <View style={[styles.fieldError, { gap: s(5) }]}>
              <Ionicons name="alert-circle-outline" size={s(13)} color={RED} />
              <Text style={[styles.fieldErrorText, { fontSize: s(11) }]}>{errors.email.message}</Text>
            </View>
          )}
        </View>

        {/* Password */}
        <View style={{ gap: s(6) }}>
          <View style={styles.fieldHeaderRow}>
            <Text style={[styles.label, { fontSize: s(13) }]}>Password</Text>
            <TouchableOpacity activeOpacity={0.7}>
              <Text style={[styles.forgotText, { fontSize: s(12), color: active.color }]}>Forgot password?</Text>
            </TouchableOpacity>
          </View>
          <Controller
            control={control}
            name="password"
            render={({ field: { onChange, value } }) => (
              <View style={[
                styles.inputRow,
                { borderRadius: s(12), paddingHorizontal: s(14), paddingVertical: s(14) },
                !!errors.password && styles.inputRowError,
              ]}>
                <Ionicons name="lock-closed-outline" size={s(18)} color={GRAY} style={{ marginRight: s(10) }} />
                <TextInput
                  style={[styles.input, { fontSize: s(14) }]}
                  placeholder="Enter your password"
                  placeholderTextColor={GRAY}
                  value={value}
                  onChangeText={onChange}
                  secureTextEntry={!showPass}
                  autoCapitalize="none"
                />
                <TouchableOpacity onPress={() => setShowPass(v => !v)} style={{ padding: s(4) }} activeOpacity={0.7}>
                  <Ionicons name={showPass ? 'eye-off-outline' : 'eye-outline'} size={s(18)} color={GRAY} />
                </TouchableOpacity>
              </View>
            )}
          />
          {!!errors.password && (
            <View style={[styles.fieldError, { gap: s(5) }]}>
              <Ionicons name="alert-circle-outline" size={s(13)} color={RED} />
              <Text style={[styles.fieldErrorText, { fontSize: s(11) }]}>{errors.password.message}</Text>
            </View>
          )}
        </View>

        {/* Sign In */}
        <TouchableOpacity
          style={[styles.signInBtn, {
            backgroundColor: active.color,
            borderRadius: s(12),
            paddingVertical: s(16),
            gap: s(8),
          }]}
          onPress={handleSubmit(onSubmit)}
          activeOpacity={0.85}
        >
          <Text style={[styles.signInText, { fontSize: s(15) }]}>Sign In</Text>
          <Ionicons name="arrow-forward-outline" size={s(18)} color={WHITE} />
        </TouchableOpacity>

        {/* Divider */}
        <View style={[styles.dividerRow, { gap: s(12) }]}>
          <View style={styles.dividerLine} />
          <Text style={[styles.dividerText, { fontSize: s(12) }]}>or</Text>
          <View style={styles.dividerLine} />
        </View>

        {/* Create account */}
        <TouchableOpacity
          style={[styles.createBtn, { borderRadius: s(12), paddingVertical: s(14), gap: s(8) }]}
          activeOpacity={0.8}
          onPress={() => router.push('/auth/register/mobile/register-mobile')}
        >
          <Ionicons name="person-add-outline" size={s(16)} color={active.color} />
          <Text style={[styles.createText, { fontSize: s(13), color: active.color }]}>Create a new account</Text>
        </TouchableOpacity>

        {/* Footer */}
        <Text style={[styles.footerText, { fontSize: s(11) }]}>
          By signing in you agree to our{' '}
          <Text style={[styles.footerLink, { color: active.color }]}>Terms of Service</Text>
          {' '}and{' '}
          <Text style={[styles.footerLink, { color: active.color }]}>Privacy Policy</Text>
        </Text>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: WHITE },

  header:        { overflow: 'hidden' },
  headerLogoRow: { flexDirection: 'row', alignItems: 'center' },
  headerIconBg:  { backgroundColor: WHITE, alignItems: 'center', justifyContent: 'center' },
  headerLogoText:{ fontWeight: '800' },
  headerTitle:   { fontWeight: '800', color: WHITE },
  headerSub:     { color: 'rgba(255,255,255,0.8)' },

  scroll:        { flex: 1, backgroundColor: WHITE },
  scrollContent: { flexGrow: 1 },

  label:          { fontWeight: '600', color: TEXT },
  fieldHeaderRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  forgotText:     { fontWeight: '500' },

  roleRow: { flexDirection: 'row' },
  roleBtn: {
    flex: 1, alignItems: 'center',
    borderWidth: 1.5, borderColor: BORDER,
    position: 'relative',
  },
  roleLabel: { color: GRAY, textAlign: 'center' },
  roleCheck: { position: 'absolute', top: -6, right: -6, alignItems: 'center', justifyContent: 'center' },

  inputRow:      { flexDirection: 'row', alignItems: 'center', borderWidth: 1.5, borderColor: BORDER, backgroundColor: BG },
  inputRowError: { borderColor: RED },
  input:         { flex: 1, color: TEXT },

  fieldError:     { flexDirection: 'row', alignItems: 'center' },
  fieldErrorText: { color: RED },

  signInBtn:  { flexDirection: 'row', alignItems: 'center', justifyContent: 'center' },
  signInText: { fontWeight: '700', color: WHITE },

  dividerRow:  { flexDirection: 'row', alignItems: 'center' },
  dividerLine: { flex: 1, height: StyleSheet.hairlineWidth, backgroundColor: BORDER },
  dividerText: { color: GRAY },

  createBtn:  { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', borderWidth: 1.5, borderColor: BORDER },
  createText: { fontWeight: '600' },

  footerText: { color: GRAY, textAlign: 'center', lineHeight: 18 },
  footerLink: { fontWeight: '600' },
});
