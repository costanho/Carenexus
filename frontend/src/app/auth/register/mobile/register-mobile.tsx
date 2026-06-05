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
import { PhoneInput } from '@/components/shared/phone-input';
import { registerSchema, type RegisterFormData } from '@/lib/validation/auth-schemas';

const TEAL   = '#0D9488';
const PURPLE = '#7C3AED';
const INDIGO = '#4F46E5';
const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const RED    = '#EF4444';
const GREEN  = '#10B981';

type Role = 'patient' | 'doctor' | 'proxy';
type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

const roles: { key: Role; label: string; icon: IoniconsName; color: string; bg: string }[] = [
  { key: 'patient', label: 'Patient',       icon: 'person-outline',  color: TEAL,   bg: '#E6F4F1' },
  { key: 'doctor',  label: 'Doctor',        icon: 'medical-outline', color: INDIGO, bg: '#EEF2FF' },
  { key: 'proxy',   label: 'Proxy / Carer', icon: 'people-outline',  color: PURPLE, bg: '#F5F3FF' },
];

export default function RegisterMobile() {
  const router = useRouter();
  const { width, height } = useWindowDimensions();
  const insets = useSafeAreaInsets();

  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));

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

  // Field defined inside to close over s()
  function Field({
    label, icon, placeholder, value, onChangeText,
    keyboardType = 'default', secureTextEntry = false,
    rightEl, error,
  }: {
    label: string; icon: IoniconsName; placeholder: string;
    value: string; onChangeText: (v: string) => void;
    keyboardType?: any; secureTextEntry?: boolean;
    rightEl?: React.ReactNode; error?: string;
  }) {
    return (
      <View style={{ marginBottom: s(12) }}>
        <Text style={[styles.label, { fontSize: s(12), marginBottom: s(6) }]}>{label}</Text>
        <View style={[styles.inputRow, {
          borderRadius: s(10), paddingHorizontal: s(12), paddingVertical: s(12),
          borderColor: error ? RED : BORDER,
        }]}>
          <Ionicons name={icon} size={s(16)} color={GRAY} style={{ marginRight: s(8) }} />
          <TextInput
            style={[styles.input, { fontSize: s(13) }]}
            placeholder={placeholder}
            placeholderTextColor={GRAY}
            value={value}
            onChangeText={onChangeText}
            keyboardType={keyboardType}
            secureTextEntry={secureTextEntry}
            autoCapitalize={secureTextEntry || keyboardType === 'email-address' ? 'none' : 'words'}
            autoCorrect={false}
          />
          {rightEl}
        </View>
        {!!error && (
          <View style={[styles.fieldError, { gap: s(4), marginTop: s(4) }]}>
            <Ionicons name="alert-circle-outline" size={s(12)} color={RED} />
            <Text style={[styles.fieldErrorText, { fontSize: s(11) }]}>{error}</Text>
          </View>
        )}
      </View>
    );
  }

  return (
    <KeyboardAvoidingView
      style={styles.screen}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      {/* Branded header */}
      <View style={[styles.header, {
        backgroundColor: active.color,
        paddingTop: insets.top + sh(16),
        paddingBottom: sh(22),
        paddingHorizontal: s(20),
        gap: s(6),
      }]}>
        <View style={[styles.headerLogoRow, { gap: s(8) }]}>
          <View style={[styles.headerIconBg, { width: s(36), height: s(36), borderRadius: s(18) }]}>
            <Ionicons name="heart" size={s(18)} color={active.color} />
          </View>
          <Text style={[styles.headerLogoText, { fontSize: s(20) }]}>
            <Text style={{ color: WHITE }}>Care</Text>
            <Text style={{ color: 'rgba(255,255,255,0.75)' }}>Nexus</Text>
          </Text>
        </View>
        <Text style={[styles.headerTitle, { fontSize: s(22) }]}>Create account</Text>
        <Text style={[styles.headerSub, { fontSize: s(12) }]}>Join CareNexus — it only takes a minute.</Text>
      </View>

      <ScrollView
        style={styles.scroll}
        contentContainerStyle={[styles.scrollContent, {
          padding: s(18),
          paddingBottom: insets.bottom + sh(32),
          gap: s(4),
        }]}
        showsVerticalScrollIndicator={false}
        keyboardShouldPersistTaps="handled"
      >
        {/* Role selector */}
        <Text style={[styles.label, { fontSize: s(12), marginBottom: s(8) }]}>I am a</Text>
        <View style={[styles.roleRow, { gap: s(8), marginBottom: s(16) }]}>
          {roles.map(r => {
            const on = role === r.key;
            return (
              <TouchableOpacity
                key={r.key}
                style={[
                  styles.roleBtn,
                  { gap: s(4), borderRadius: s(10), paddingVertical: s(10) },
                  on && { borderColor: r.color, backgroundColor: r.bg },
                ]}
                onPress={() => setRole(r.key)}
                activeOpacity={0.8}
              >
                <Ionicons name={r.icon} size={s(20)} color={on ? r.color : GRAY} />
                <Text style={[styles.roleLabel, { fontSize: s(10) }, on && { color: r.color, fontWeight: '700' }]}>
                  {r.label}
                </Text>
                {on && (
                  <View style={[styles.roleCheck, {
                    backgroundColor: r.color,
                    width: s(15), height: s(15), borderRadius: s(8),
                  }]}>
                    <Ionicons name="checkmark" size={s(9)} color={WHITE} />
                  </View>
                )}
              </TouchableOpacity>
            );
          })}
        </View>

        {/* Name row — side by side */}
        <View style={[styles.twoCol, { gap: s(10) }]}>
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

        <View style={{ marginBottom: s(12) }}>
          <Text style={[styles.label, { fontSize: s(12), marginBottom: s(6) }]}>Phone Number</Text>
          <Controller
            control={control}
            name="phone"
            render={({ field: { onChange, value } }) => (
              <PhoneInput
                value={value}
                onChange={onChange}
                error={errors.phone?.message}
                accentColor={active.color}
                scale={s}
              />
            )}
          />
        </View>

        <Controller
          control={control}
          name="password"
          render={({ field: { onChange, value } }) => (
            <Field
              label="Password" icon="lock-closed-outline" placeholder="Min. 8 characters"
              value={value} onChangeText={onChange} secureTextEntry={!showPass} error={errors.password?.message}
              rightEl={
                <TouchableOpacity onPress={() => setShowPass(v => !v)} style={{ padding: s(4) }} activeOpacity={0.7}>
                  <Ionicons name={showPass ? 'eye-off-outline' : 'eye-outline'} size={s(16)} color={GRAY} />
                </TouchableOpacity>
              }
            />
          )}
        />

        {/* Password strength */}
        {passwordValue?.length > 0 && (
          <View style={[styles.strengthRow, { gap: s(5), marginBottom: s(10), marginTop: -s(6) }]}>
            {[1, 2, 3].map(i => (
              <View key={i} style={[styles.strengthBar, { backgroundColor: i <= pwStrength ? strengthColor : BORDER }]} />
            ))}
            <Text style={[styles.strengthLabel, { fontSize: s(10), color: strengthColor }]}>{strengthLabel}</Text>
          </View>
        )}

        <Controller
          control={control}
          name="confirmPassword"
          render={({ field: { onChange, value } }) => (
            <Field
              label="Confirm Password" icon="lock-closed-outline" placeholder="Re-enter password"
              value={value} onChangeText={onChange} secureTextEntry={!showConf} error={errors.confirmPassword?.message}
              rightEl={
                <TouchableOpacity onPress={() => setShowConf(v => !v)} style={{ padding: s(4) }} activeOpacity={0.7}>
                  <Ionicons name={showConf ? 'eye-off-outline' : 'eye-outline'} size={s(16)} color={GRAY} />
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
              style={[styles.termsRow, { gap: s(10), marginBottom: s(6), marginTop: s(4) }]}
              onPress={() => onChange(agreedValue ? (undefined as any) : true)}
              activeOpacity={0.8}
            >
              <View style={[styles.checkbox, {
                width: s(20), height: s(20), borderRadius: s(5),
              }, agreedValue ? { backgroundColor: active.color, borderColor: active.color } : undefined]}>
                {agreedValue && <Ionicons name="checkmark" size={s(12)} color={WHITE} />}
              </View>
              <Text style={[styles.termsText, { fontSize: s(12) }]}>
                I agree to the{' '}
                <Text style={[styles.termsLink, { color: active.color }]}>Terms of Service</Text>
                {' '}and{' '}
                <Text style={[styles.termsLink, { color: active.color }]}>Privacy Policy</Text>
              </Text>
            </TouchableOpacity>
          )}
        />
        {!!errors.agreed && (
          <View style={[styles.fieldError, { gap: s(4), marginBottom: s(10) }]}>
            <Ionicons name="alert-circle-outline" size={s(12)} color={RED} />
            <Text style={[styles.fieldErrorText, { fontSize: s(11) }]}>{errors.agreed.message}</Text>
          </View>
        )}

        {/* Submit */}
        <TouchableOpacity
          style={[styles.submitBtn, {
            backgroundColor: active.color,
            borderRadius: s(12), paddingVertical: s(15),
            gap: s(8), marginTop: s(4),
          }]}
          onPress={handleSubmit(onSubmit)}
          activeOpacity={0.85}
        >
          <Ionicons name="person-add-outline" size={s(18)} color={WHITE} />
          <Text style={[styles.submitText, { fontSize: s(15) }]}>Create Account</Text>
        </TouchableOpacity>

        {/* Sign in */}
        <View style={[styles.signinRow, { marginTop: s(16) }]}>
          <Text style={[styles.signinText, { fontSize: s(12) }]}>Already have an account? </Text>
          <TouchableOpacity activeOpacity={0.7} onPress={() => router.push('/auth/login/mobile/login-mobile')}>
            <Text style={[styles.signinLink, { fontSize: s(12), color: active.color }]}>Sign in</Text>
          </TouchableOpacity>
        </View>
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

  label: { fontWeight: '600', color: TEXT },

  roleRow: { flexDirection: 'row' },
  roleBtn: {
    flex: 1, alignItems: 'center',
    borderWidth: 1.5, borderColor: BORDER, position: 'relative',
  },
  roleLabel: { color: GRAY, textAlign: 'center' },
  roleCheck: { position: 'absolute', top: -6, right: -6, alignItems: 'center', justifyContent: 'center' },

  twoCol: { flexDirection: 'row' },

  inputRow:  { flexDirection: 'row', alignItems: 'center', borderWidth: 1.5, backgroundColor: BG },
  input:     { flex: 1, color: TEXT },

  fieldError:     { flexDirection: 'row', alignItems: 'center' },
  fieldErrorText: { color: RED },

  strengthRow:   { flexDirection: 'row', alignItems: 'center' },
  strengthBar:   { flex: 1, height: 3, borderRadius: 2 },
  strengthLabel: { fontWeight: '600', width: 40 },

  termsRow: { flexDirection: 'row', alignItems: 'flex-start' },
  checkbox: { borderWidth: 1.5, borderColor: BORDER, alignItems: 'center', justifyContent: 'center', marginTop: 1 },
  termsText: { flex: 1, color: GRAY, lineHeight: 18 },
  termsLink: { fontWeight: '600' },

  submitBtn:  { flexDirection: 'row', alignItems: 'center', justifyContent: 'center' },
  submitText: { fontWeight: '700', color: WHITE },

  signinRow:  { flexDirection: 'row', justifyContent: 'center', alignItems: 'center' },
  signinText: { color: GRAY },
  signinLink: { fontWeight: '700' },
});
