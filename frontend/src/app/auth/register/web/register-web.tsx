import { Ionicons } from '@expo/vector-icons';
import { zodResolver } from '@hookform/resolvers/zod';
import { type Href, Link, useRouter } from 'expo-router';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Controller, type Resolver, useForm } from 'react-hook-form';
import {
  ActivityIndicator,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  type TextInputProps,
  TouchableOpacity,
  useWindowDimensions,
  View,
} from 'react-native';
import { ResendVerification } from '@/app/auth/login/web/login-web';
import { PhoneInput } from '@/components/shared/phone-input';
import { Banner, Button, ROLE_THEME, SelectField, UiThemeProvider, ariaLevel, webOnly } from '@/components/web/ui';
import { useResource } from '@/hooks/use-resource';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { getCurrentLegal, register, registerFailure } from '@/lib/auth/auth.service';
import {
  LEGAL_PRIVACY_ROUTE, LEGAL_TERMS_ROUTE, ROLE_HOME, isAuthSession, requiredAccountStep,
} from '@/lib/auth/auth.types';
import { countries } from '@/lib/phone/countries';
import { REGISTER_LIMITS, webRegisterSchemaFor } from '@/lib/validation/register/register.schema';
import {
  COLORS, REGISTER_ROLES,
  type IoniconsName, type Role, type WebRegisterFormData, type WebRegisterFormInput,
} from '@/lib/validation/register/register.types';
import { getPasswordStrength } from '@/lib/validation/register/register.validators';

const { NAVY, WHITE, TEXT, GRAY, BORDER, BG, RED } = COLORS;
const MUTED = '#4B5563'; // body copy on white (7.5:1)
/** Outline of the text fields: 4.83:1 against the field and the page (WCAG 1.4.11 asks 3:1). */
const INPUT_BORDER = '#6B7280';

const LOGIN_ROUTE = '/auth/login/web/login-web';

const panelPoints: { icon: IoniconsName; text: string }[] = [
  { icon: 'person-add-outline',       text: 'Create your health profile'          },
  { icon: 'calendar-outline',         text: 'Book and track appointments'         },
  { icon: 'document-text-outline',    text: 'View prescriptions and lab results'  },
  { icon: 'people-outline',           text: 'Give a caregiver access when needed' },
];

/** Form fields the API can report an error for (the names are the same on both sides). */
const SERVER_FIELDS = [
  'firstName', 'lastName', 'email', 'phone', 'password',
  'licenseNo', 'specialization', 'licensingBody', 'licenseCountry',
] as const;

const CLINICIAN_FIELDS = ['licenseNo', 'specialization', 'licensingBody', 'licenseCountry'] as const;

/** Countries for the optional "country of licence" choice, by name (the value is the ISO 3166-1 alpha-2 code). */
const COUNTRY_OPTIONS = [...countries]
  .sort((a, b) => a.name.localeCompare(b.name))
  .map(c => ({ value: c.code, label: c.name }));

const LEGAL_LOAD_ERROR = 'We could not load the current Terms of Service and Privacy Policy.';

/** Moves keyboard focus to an element that was just shown (a heading with tabIndex -1). */
function focusElement(target: unknown): void {
  const el = target as { focus?: () => void } | null;
  if (el && typeof el.focus === 'function') el.focus();
}

function FieldError({ message }: { message?: string }) {
  if (!message) return null;
  return (
    <View style={styles.fieldError} accessibilityRole="alert">
      <Ionicons name="alert-circle-outline" size={13} color={RED} aria-hidden />
      <Text style={styles.fieldErrorText}>{message}</Text>
    </View>
  );
}

function Field({
  label, icon, placeholder, value, onChangeText,
  keyboardType = 'default', secureTextEntry = false,
  rightElement, error, onSubmitEditing, onBlur, inputRef,
  maxLength, autoComplete, autoCapitalize, required = false,
}: {
  label: string; icon: IoniconsName; placeholder: string;
  value: string; onChangeText: (v: string) => void;
  keyboardType?: TextInputProps['keyboardType']; secureTextEntry?: boolean;
  rightElement?: React.ReactNode; error?: string;
  /** Enter in the field submits the form. */
  onSubmitEditing?: () => void;
  onBlur?: () => void;
  /** Lets the form move focus here when the server refuses this field. */
  inputRef?: React.Ref<TextInput>;
  /** Longest value the API accepts. */
  maxLength?: number;
  autoComplete?: TextInputProps['autoComplete'];
  autoCapitalize?: TextInputProps['autoCapitalize'];
  required?: boolean;
}) {
  return (
    <View style={styles.fieldGroup}>
      <Text style={styles.label}>{label}</Text>
      <View style={[styles.inputRow, !!error && styles.inputRowError]}>
        <Ionicons name={icon} size={17} color={GRAY} style={styles.inputIcon} aria-hidden />
        <TextInput
          ref={inputRef}
          style={styles.input}
          // The visible label is a sibling Text; this gives the input itself an accessible name.
          accessibilityLabel={label}
          aria-invalid={!!error}
          {...webOnly({ 'aria-required': required || undefined })}
          placeholder={placeholder}
          placeholderTextColor={GRAY}
          value={value}
          onChangeText={onChangeText}
          onBlur={onBlur}
          keyboardType={keyboardType}
          secureTextEntry={secureTextEntry}
          maxLength={maxLength}
          autoComplete={autoComplete}
          autoCapitalize={autoCapitalize ?? (secureTextEntry || keyboardType === 'email-address' ? 'none' : 'words')}
          autoCorrect={false}
          onSubmitEditing={onSubmitEditing}
        />
        {rightElement}
      </View>
      <FieldError message={error} />
    </View>
  );
}

/** Shown in place of the form once the account exists: what happens next, and a way to get the email again. */
function AccountCreated({ email, doctor, onSignIn }: { email: string; doctor: boolean; onSignIn: () => void }) {
  const headingRef = useRef<Text>(null);
  useEffect(() => { focusElement(headingRef.current); }, []);

  return (
    <View style={styles.created}>
      <View style={styles.createdIcon} aria-hidden>
        <Ionicons aria-hidden name="mail-unread-outline" size={28} color={TEXT} />
      </View>
      <Text
        ref={headingRef}
        style={styles.heading}
        accessibilityRole="header"
        {...ariaLevel(1)}
        {...webOnly({ tabIndex: -1 })}
      >
        Check your inbox
      </Text>
      <Text style={styles.createdText}>
        Your account has been created. We sent a verification link to{' '}
        <Text style={styles.createdEmail}>{email}</Text>. Open the link to confirm your email address
        {doctor ? '.' : ', then sign in.'}
      </Text>

      {doctor && (
        <Banner
          tone="warning"
          icon="time-outline"
          text="Your clinician account is pending verification. An administrator checks your licence details, and you can sign in once the account is approved."
        />
      )}

      <Text style={styles.createdText}>
        The email can take a few minutes to arrive. If it is not in your inbox, check the spam folder or send it again.
      </Text>
      <ResendVerification email={email} />

      <Button label="Go to sign in" icon="arrow-forward-outline" onPress={onSignIn} fullWidth />
    </View>
  );
}

export default function RegisterWeb() {
  const router = useRouter();
  const { width } = useWindowDimensions();
  const narrow  = width < 768;
  const compact = width < 480;

  const [role, setRole]           = useState<Role>('patient');
  const [showPass, setShowPass]   = useState(false);
  const [showConf, setShowConf]   = useState(false);
  const [formError, setFormError] = useState('');
  const [loading, setLoading]     = useState(false);
  const [created, setCreated]     = useState<{ email: string; doctor: boolean } | null>(null);
  /** Create Account was pressed before the versions of the terms were on hand. */
  const [awaitingLegal, setAwaitingLegal] = useState(false);
  const busy = useRef(false);
  const scrollRef  = useRef<ScrollView>(null);
  const phoneTop   = useRef(0);

  const active   = REGISTER_ROLES.find(r => r.key === role)!;
  const isDoctor = role === 'doctor';

  // The versions of the terms and the privacy policy that ticking the consent box accepts.
  const legal = useResource(getCurrentLegal, [], { errorMessage: LEGAL_LOAD_ERROR });

  // Licence number and specialization are required for a doctor only, so the rules follow the selected role.
  const resolver = useMemo(
    () => zodResolver(webRegisterSchemaFor(role)) as Resolver<WebRegisterFormInput, unknown, WebRegisterFormData>,
    [role],
  );

  const { control, handleSubmit, watch, setError, setValue, clearErrors, reset, formState: { errors } } =
    useForm<WebRegisterFormInput, unknown, WebRegisterFormData>({
      resolver,
      defaultValues: {
        firstName: '', lastName: '', email: '',
        phone: '', password: '', confirmPassword: '',
        licenseNo: '', specialization: '', licensingBody: '', licenseCountry: '',
        agreed: undefined as any,
      },
    });

  const passwordValue = watch('password');
  const agreedValue   = watch('agreed');
  const strength      = getPasswordStrength(passwordValue);
  const submit        = handleSubmit(onSubmit);

  function chooseRole(next: Role) {
    setRole(next);
    // Errors of the clinician fields make no sense once those fields are gone.
    if (next !== 'doctor') clearErrors([...CLINICIAN_FIELDS]);
  }

  async function onSubmit(data: WebRegisterFormData) {
    if (busy.current) return; // Enter in a field and the button share this handler
    if (!legal.data) {
      // Consent is recorded against a version; without the versions there is nothing to accept yet.
      setFormError('');
      setAwaitingLegal(true);
      if (!legal.loading) legal.refetch();
      return;
    }
    busy.current = true;
    setAwaitingLegal(false);
    setFormError('');
    setLoading(true);
    try {
      const res = await register({
        firstName: data.firstName,
        lastName:  data.lastName,
        email:     data.email,
        phone:     data.phone,
        password:  data.password,
        role,
        licenseNo:      data.licenseNo,
        specialization: data.specialization,
        licensingBody:  data.licensingBody,
        licenseCountry: data.licenseCountry,
        acceptedTermsVersion:   legal.data.terms.version,
        acceptedPrivacyVersion: legal.data.privacy.version,
      });
      if (isAuthSession(res) && ROLE_HOME[res.user.role]) {
        // A server that signs the new account in straight away: go where the session has to go first.
        router.replace((requiredAccountStep(res.flags) ?? ROLE_HOME[res.user.role]) as Href);
        return;
      }
      // No session is issued: the address has to be verified first (and a clinician approved).
      setCreated({ email: res.user?.email ?? data.email, doctor: role === 'doctor' });
      reset();
    } catch (err) {
      const failure = registerFailure(err);
      if (failure.legalChanged) {
        // A new version was published while the form was open: load it and ask for consent again.
        setValue('agreed', undefined as any);
        legal.setData(null); // the versions on hand are stale: nothing can be accepted until the new ones arrive
        legal.refetch();
        setFormError('The Terms of Service or Privacy Policy changed. Review the current version and accept it to continue.');
      } else if (failure.duplicateField) {
        setError(failure.duplicateField, { type: 'server', message: failure.message }, { shouldFocus: true });
        // The phone input cannot be given focus from here, so its message is scrolled into view instead.
        if (failure.duplicateField === 'phone') scrollRef.current?.scrollTo({ y: Math.max(0, phoneTop.current - 24), animated: true });
      } else {
        setFormError(applyFieldErrors(failure, setError, SERVER_FIELDS) ?? '');
      }
    } finally {
      busy.current = false;
      setLoading(false);
    }
  }

  return (
    <View style={styles.screen}>
      {/* ── Left: form (50%) ── */}
      <ScrollView
        ref={scrollRef}
        style={[styles.formPane, narrow && styles.formPaneFull]}
        contentContainerStyle={[styles.formScroll, compact && styles.formScrollCompact]}
        showsVerticalScrollIndicator={false}
        keyboardShouldPersistTaps="handled"
      >
        {/* Logo */}
        <View style={styles.logoRow}>
          <View style={[styles.logoIcon, { borderColor: active.color }]}>
            <Ionicons name="heart" size={20} color={active.color} aria-hidden />
          </View>
          <View>
            <Text style={styles.logoText}>
              <Text style={{ color: NAVY }}>Care</Text>
              <Text style={{ color: active.color }}>Nexus</Text>
            </Text>
            <Text style={[styles.logoSub, { color: active.color }]}>HEALTHCARE PLATFORM</Text>
          </View>
        </View>

        {/* The shared buttons, banners and the select follow the colour of the selected role. */}
        <UiThemeProvider theme={ROLE_THEME[role]}>
          {created ? (
            <AccountCreated email={created.email} doctor={created.doctor} onSignIn={() => router.replace(LOGIN_ROUTE as Href)} />
          ) : (
            <>
              <Text style={styles.heading} accessibilityRole="header" {...ariaLevel(1)}>Create your account</Text>
              <Text style={styles.subheading}>Join CareNexus — it only takes a minute.</Text>

              {/* Role selector */}
              <Text style={styles.label} nativeID="register-role-label">I am a</Text>
              <View style={styles.roleRow} accessibilityRole="radiogroup" aria-labelledby="register-role-label">
                {REGISTER_ROLES.map(r => {
                  const on = role === r.key;
                  return (
                    <TouchableOpacity
                      key={r.key}
                      style={[styles.roleBtn, on && { borderColor: r.color, backgroundColor: r.bg }]}
                      onPress={() => chooseRole(r.key)}
                      activeOpacity={0.8}
                      accessibilityRole="radio"
                      accessibilityLabel={r.desc ? `${r.label}, ${r.desc}` : r.label}
                      accessibilityState={{ checked: on }}
                      // react-native-web ignores accessibilityState; aria-checked tells screen readers which is selected.
                      aria-checked={on}
                    >
                      <Ionicons name={r.icon} size={20} color={on ? r.color : GRAY} aria-hidden />
                      <Text style={[styles.roleLabel, on && { color: r.color, fontWeight: '700' }]}>{r.label}</Text>
                      <Text style={[styles.roleDesc, on && { color: r.color }]}>{r.desc}</Text>
                      {on && (
                        <View style={[styles.roleCheck, { backgroundColor: r.color }]}>
                          <Ionicons name="checkmark" size={10} color={WHITE} aria-hidden />
                        </View>
                      )}
                    </TouchableOpacity>
                  );
                })}
              </View>

              {/* Name row (stacked on a phone) */}
              <View style={compact ? undefined : styles.twoCol}>
                <View style={compact ? undefined : styles.col}>
                  <Controller control={control} name="firstName"
                    render={({ field: { onChange, onBlur, value, ref } }) => (
                      <Field label="First Name" icon="person-outline" placeholder="John" required
                        value={value} onChangeText={onChange} onBlur={onBlur} inputRef={ref}
                        maxLength={REGISTER_LIMITS.firstName} autoComplete="given-name"
                        onSubmitEditing={submit} error={errors.firstName?.message} />
                    )}
                  />
                </View>
                <View style={compact ? undefined : styles.col}>
                  <Controller control={control} name="lastName"
                    render={({ field: { onChange, onBlur, value, ref } }) => (
                      <Field label="Last Name" icon="person-outline" placeholder="Smith" required
                        value={value} onChangeText={onChange} onBlur={onBlur} inputRef={ref}
                        maxLength={REGISTER_LIMITS.lastName} autoComplete="family-name"
                        onSubmitEditing={submit} error={errors.lastName?.message} />
                    )}
                  />
                </View>
              </View>

              <Controller control={control} name="email"
                render={({ field: { onChange, onBlur, value, ref } }) => (
                  <Field label="Email Address" icon="mail-outline" placeholder="you@example.com" required
                    value={value} onChangeText={onChange} onBlur={onBlur} inputRef={ref}
                    keyboardType="email-address" maxLength={REGISTER_LIMITS.email} autoComplete="email"
                    onSubmitEditing={submit} error={errors.email?.message} />
                )}
              />

              <View style={styles.fieldGroup} onLayout={e => { phoneTop.current = e.nativeEvent.layout.y; }}>
                <Text style={styles.label}>Phone Number</Text>
                <Controller control={control} name="phone"
                  render={({ field: { onChange, value } }) => (
                    <PhoneInput value={value} onChange={onChange}
                      error={errors.phone?.message} accentColor={active.color} />
                  )}
                />
              </View>

              {/* Clinician details: asked only when registering as a doctor */}
              {isDoctor && (
                <View style={styles.clinician} role="group" aria-labelledby="register-clinician-title">
                  <Text style={styles.sectionTitle} nativeID="register-clinician-title" accessibilityRole="header" {...ariaLevel(2)}>
                    Clinician details
                  </Text>
                  <View style={styles.clinicianNote}>
                    <Banner
                      tone="info"
                      text="An administrator checks these details before a clinician account can sign in."
                    />
                  </View>

                  <Controller control={control} name="licenseNo"
                    render={({ field: { onChange, onBlur, value, ref } }) => (
                      <Field label="Licence number" icon="card-outline" placeholder="As shown on your practising licence" required
                        value={value} onChangeText={onChange} onBlur={onBlur} inputRef={ref}
                        maxLength={REGISTER_LIMITS.licenseNo} autoCapitalize="characters" autoComplete="off"
                        onSubmitEditing={submit} error={errors.licenseNo?.message} />
                    )}
                  />
                  <Controller control={control} name="specialization"
                    render={({ field: { onChange, onBlur, value, ref } }) => (
                      <Field label="Specialization" icon="medkit-outline" placeholder="For example General practice" required
                        value={value} onChangeText={onChange} onBlur={onBlur} inputRef={ref}
                        maxLength={REGISTER_LIMITS.specialization} autoCapitalize="sentences" autoComplete="off"
                        onSubmitEditing={submit} error={errors.specialization?.message} />
                    )}
                  />
                  <Controller control={control} name="licensingBody"
                    render={({ field: { onChange, onBlur, value, ref } }) => (
                      <Field label="Licensing body (optional)" icon="business-outline" placeholder="The council or board that issued the licence"
                        value={value} onChangeText={onChange} onBlur={onBlur} inputRef={ref}
                        maxLength={REGISTER_LIMITS.licensingBody} autoComplete="off"
                        onSubmitEditing={submit} error={errors.licensingBody?.message} />
                    )}
                  />
                  <View style={styles.fieldGroup}>
                    <SelectField
                      label="Country of licence (optional)"
                      control={control}
                      name="licenseCountry"
                      options={COUNTRY_OPTIONS}
                      placeholder="Not specified"
                      allowEmpty
                    />
                  </View>
                </View>
              )}

              {/* Password */}
              <Controller control={control} name="password"
                render={({ field: { onChange, onBlur, value, ref } }) => (
                  <Field label="Password" icon="lock-closed-outline" placeholder="Min. 8 characters" required
                    value={value} onChangeText={onChange} onBlur={onBlur} inputRef={ref}
                    secureTextEntry={!showPass} autoComplete="new-password"
                    onSubmitEditing={submit} error={errors.password?.message}
                    rightElement={
                      <TouchableOpacity accessibilityRole="button" accessibilityLabel={showPass ? 'Hide password' : 'Show password'} onPress={() => setShowPass(v => !v)} style={{ padding: 4 }} activeOpacity={0.7}>
                        <Ionicons name={showPass ? 'eye-off-outline' : 'eye-outline'} size={17} color={GRAY} aria-hidden />
                      </TouchableOpacity>
                    }
                  />
                )}
              />
              {passwordValue?.length > 0 && (
                <View style={styles.strengthRow}>
                  {[1, 2, 3].map(i => (
                    <View key={i} style={[styles.strengthBar, { backgroundColor: i <= strength.level ? strength.color : BORDER }]} />
                  ))}
                  <Text style={[styles.strengthLabel, { color: strength.color }]}>{strength.label}</Text>
                </View>
              )}

              <Controller control={control} name="confirmPassword"
                render={({ field: { onChange, onBlur, value, ref } }) => (
                  <Field label="Confirm Password" icon="lock-closed-outline" placeholder="Re-enter your password" required
                    value={value} onChangeText={onChange} onBlur={onBlur} inputRef={ref}
                    secureTextEntry={!showConf} autoComplete="new-password"
                    onSubmitEditing={submit} error={errors.confirmPassword?.message}
                    rightElement={
                      <TouchableOpacity accessibilityRole="button" accessibilityLabel={showConf ? 'Hide password confirmation' : 'Show password confirmation'} onPress={() => setShowConf(v => !v)} style={{ padding: 4 }} activeOpacity={0.7}>
                        <Ionicons name={showConf ? 'eye-off-outline' : 'eye-outline'} size={17} color={GRAY} aria-hidden />
                      </TouchableOpacity>
                    }
                  />
                )}
              />

              {/* Terms: the box is the checkbox; the words beside it toggle it too, except the two links */}
              <Controller control={control} name="agreed"
                render={({ field: { onChange } }) => {
                  const toggle = () => onChange(agreedValue ? (undefined as any) : true);
                  return (
                    <View style={styles.termsRow}>
                      <TouchableOpacity
                        style={styles.checkboxHit}
                        onPress={toggle}
                        activeOpacity={0.8}
                        accessibilityRole="checkbox"
                        accessibilityLabel="I agree to the Terms of Service and Privacy Policy"
                        accessibilityState={{ checked: !!agreedValue }}
                        aria-checked={!!agreedValue}
                        aria-invalid={!!errors.agreed}
                        {...webOnly({ 'aria-required': true })}
                      >
                        <View style={[styles.checkbox, agreedValue ? { backgroundColor: active.color, borderColor: active.color } : undefined]}>
                          {agreedValue && <Ionicons name="checkmark" size={12} color={WHITE} aria-hidden />}
                        </View>
                      </TouchableOpacity>
                      <Text style={styles.termsText}>
                        <Text onPress={toggle}>I agree to the{' '}</Text>
                        <Link href={LEGAL_TERMS_ROUTE as Href} style={[styles.termsLink, { color: active.color }]}>Terms of Service</Link>
                        <Text onPress={toggle}>{' '}and{' '}</Text>
                        <Link href={LEGAL_PRIVACY_ROUTE as Href} style={[styles.termsLink, { color: active.color }]}>Privacy Policy</Link>
                      </Text>
                    </View>
                  );
                }}
              />
              {!!errors.agreed && (
                <View style={[styles.fieldError, { marginTop: -8, marginBottom: 8 }]} accessibilityRole="alert">
                  <Ionicons name="alert-circle-outline" size={13} color={RED} aria-hidden />
                  <Text style={styles.fieldErrorText}>{errors.agreed.message}</Text>
                </View>
              )}
              {legal.loading && (
                <View style={styles.legalStatus} role="status" aria-live="polite">
                  <ActivityIndicator size="small" color={MUTED} aria-hidden />
                  <Text style={styles.legalStatusText}>Loading the current terms…</Text>
                </View>
              )}
              {!legal.loading && !legal.data && !!legal.error && (
                <View style={styles.legalError}>
                  <Banner tone="danger" text={legal.error} action={{ label: 'Retry', onPress: legal.refetch }} />
                </View>
              )}

              {/* Create Account was pressed while the versions to accept are missing; gone once they have loaded */}
              {awaitingLegal && !legal.data && (
                <View style={[styles.fieldError, styles.formError]} accessibilityRole="alert">
                  <Ionicons name="alert-circle-outline" size={15} color={RED} aria-hidden />
                  <Text style={[styles.fieldErrorText, { flex: 1, fontSize: 13 }]}>
                    Your account cannot be created until the current Terms of Service and Privacy Policy have loaded.
                  </Text>
                </View>
              )}

              {/* Server error that does not belong to one field */}
              {!!formError && (
                <View style={[styles.fieldError, styles.formError]} accessibilityRole="alert">
                  <Ionicons name="alert-circle-outline" size={15} color={RED} aria-hidden />
                  <Text style={[styles.fieldErrorText, { flex: 1, fontSize: 13 }]}>{formError}</Text>
                </View>
              )}

              {/* Submit */}
              <TouchableOpacity
                style={[styles.submitBtn, { backgroundColor: active.color, opacity: loading ? 0.7 : 1 }]}
                onPress={submit}
                activeOpacity={0.85}
                disabled={loading}
                accessibilityRole="button"
                accessibilityLabel="Create Account"
                accessibilityState={{ disabled: loading, busy: loading }}
                aria-busy={loading}
              >
                <Ionicons name="person-add-outline" size={18} color={WHITE} aria-hidden />
                <Text style={styles.submitText}>{loading ? 'Creating account…' : 'Create Account'}</Text>
              </TouchableOpacity>

              {/* Sign in */}
              <View style={styles.signinRow}>
                <Text style={styles.signinText}>Already have an account? </Text>
                <TouchableOpacity activeOpacity={0.7} onPress={() => router.push(LOGIN_ROUTE as Href)}
                  accessibilityRole="link" accessibilityLabel="Sign in">
                  <Text style={[styles.signinLink, { color: active.color }]}>Sign in</Text>
                </TouchableOpacity>
              </View>
            </>
          )}
        </UiThemeProvider>
      </ScrollView>

      {/* ── Right: branded panel (50%) ── */}
      {!narrow && (
        <View style={[styles.panel, { backgroundColor: active.color }]}>
          <View style={styles.panelInner}>
            <View style={styles.panelLogoRow}>
              <Ionicons name="heart" size={28} color={WHITE} aria-hidden />
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
                    <Ionicons name={p.icon} size={16} color={active.color} aria-hidden />
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
  // Phone width: the padding gives way so the inputs keep a usable width.
  formScrollCompact: { paddingHorizontal: 20, paddingVertical: 32 },

  logoRow:  { flexDirection: 'row', alignItems: 'center', gap: 12, marginBottom: 28 },
  logoIcon: { width: 44, height: 44, borderRadius: 22, borderWidth: 2, alignItems: 'center', justifyContent: 'center' },
  logoText: { fontSize: 20, fontWeight: '800', lineHeight: 24 },
  logoSub:  { fontSize: 9, fontWeight: '700', letterSpacing: 2.5, marginTop: 1 },

  heading:    { fontSize: 26, fontWeight: '800', color: TEXT, marginBottom: 4, outlineStyle: 'none' } as any,
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
  roleCheck: { position: 'absolute', top: -7, right: -7, width: 18, height: 18, borderRadius: 9, alignItems: 'center', justifyContent: 'center' },

  twoCol: { flexDirection: 'row', gap: 12 },
  col:    { flex: 1, minWidth: 0 },

  // Clinician details (doctor only)
  clinician:     { borderTopWidth: 1, borderTopColor: BORDER, paddingTop: 16, marginTop: 2, marginBottom: 2 },
  sectionTitle:  { fontSize: 15, fontWeight: '700', color: TEXT, marginBottom: 10 },
  clinicianNote: { marginBottom: 14 },

  fieldGroup: { marginBottom: 14 },
  inputRow: {
    flexDirection: 'row', alignItems: 'center',
    borderWidth: 1.5, borderColor: INPUT_BORDER, borderRadius: 12,
    paddingHorizontal: 14, paddingVertical: 13,
    backgroundColor: BG,
  },
  inputRowError: { borderColor: RED },
  inputIcon:     { marginRight: 10 },
  input:         { flex: 1, minWidth: 0, fontSize: 14, color: TEXT, outlineStyle: 'none' } as any,

  fieldError:     { flexDirection: 'row', alignItems: 'center', gap: 4, marginTop: 5 },
  fieldErrorText: { fontSize: 12, color: RED, flexShrink: 1 },
  formError:      { padding: 12, backgroundColor: '#FEF2F2', borderRadius: 10, marginBottom: 12 },

  strengthRow:   { flexDirection: 'row', alignItems: 'center', gap: 6, marginTop: -8, marginBottom: 10 },
  strengthBar:   { flex: 1, height: 3, borderRadius: 2 },
  strengthLabel: { fontSize: 11, fontWeight: '600', width: 44 },

  termsRow:    { flexDirection: 'row', alignItems: 'flex-start', gap: 6, marginBottom: 16 },
  // The pressable area is larger than the drawn box so it is easy to hit.
  checkboxHit: { padding: 4, margin: -4, marginRight: 0 },
  checkbox:    { width: 20, height: 20, borderRadius: 5, borderWidth: 1.5, borderColor: GRAY, alignItems: 'center', justifyContent: 'center', marginTop: 1 },
  termsText:   { flex: 1, fontSize: 13, color: MUTED, lineHeight: 20 },
  termsLink:   { fontWeight: '600', textDecorationLine: 'underline' },

  legalStatus:     { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: -6, marginBottom: 12 },
  legalStatusText: { fontSize: 12, color: MUTED },
  legalError:      { marginTop: -6, marginBottom: 12 },

  // "Check your inbox" after the account was created
  created:      { gap: 16 },
  createdIcon:  { width: 56, height: 56, borderRadius: 28, backgroundColor: BG, borderWidth: 1, borderColor: BORDER, alignItems: 'center', justifyContent: 'center' },
  createdText:  { fontSize: 14, color: MUTED, lineHeight: 22 },
  createdEmail: { fontWeight: '700', color: TEXT },

  submitBtn:  { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8, borderRadius: 12, paddingVertical: 16, marginBottom: 20 },
  submitText: { fontSize: 16, fontWeight: '700', color: WHITE },

  signinRow:  { flexDirection: 'row', justifyContent: 'center', alignItems: 'center' },
  signinText: { fontSize: 13, color: GRAY },
  signinLink: { fontSize: 13, fontWeight: '700' },

  panel:         { flex: 1, overflow: 'hidden' },
  panelInner:    { flex: 1, justifyContent: 'center', padding: 56, gap: 20 },
  panelLogoRow:  { flexDirection: 'row', alignItems: 'center', gap: 10, marginBottom: 8 },
  panelLogoText: { fontSize: 24, fontWeight: '800', color: WHITE },
  panelTitle:    { fontSize: 32, fontWeight: '800', color: WHITE, lineHeight: 40 },
  panelSub:      { fontSize: 15, color: 'rgba(255,255,255,0.92)', lineHeight: 24 },
  featureList:   { gap: 14, marginTop: 8 },
  featureRow:    { flexDirection: 'row', alignItems: 'center', gap: 12 },
  featureIconBg: { width: 32, height: 32, borderRadius: 8, backgroundColor: 'rgba(255,255,255,0.2)', alignItems: 'center', justifyContent: 'center' },
  featureText:   { fontSize: 14, color: 'rgba(255,255,255,0.92)', fontWeight: '500' },

  decor:      { position: 'absolute', borderWidth: 1.5, borderColor: 'rgba(255,255,255,0.15)', borderRadius: 999 },
  decorBig:   { width: 320, height: 320, bottom: -100, right: -80 },
  decorSmall: { width: 160, height: 160, top: -40, right: 40 },
});
