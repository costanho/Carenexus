import { Ionicons } from '@expo/vector-icons';
import { zodResolver } from '@hookform/resolvers/zod';
import * as Clipboard from 'expo-clipboard';
import { type Href, Link, useRouter } from 'expo-router';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import {
  ActivityIndicator,
  Image,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  useWindowDimensions,
  View,
} from 'react-native';
import {
  Banner, Button, FormError, ROLE_THEME, TextField, UiThemeProvider, ariaLevel, webOnly, type BannerTone,
} from '@/components/web/ui';
import { apiError } from '@/lib/api/errors';
import {
  completeMfaLogin, completeMfaSetup, describeWebDevice, login, logout, resendVerificationEmail, signInFailure,
  type SignInFailureKind,
} from '@/lib/auth/auth.service';
import {
  FORGOT_PASSWORD_ROUTE, LEGAL_PRIVACY_ROUTE, LEGAL_TERMS_ROUTE, ROLE_HOME, isAppRole, requiredAccountStep,
  type MfaChallengeType, type Role, type SignedIn,
} from '@/lib/auth/auth.types';
import {
  challengeSecondsLeft, formatCountdown, formatMfaSecret, qrImage, startMfaSetup, type MfaSetup, type QrImage,
} from '@/lib/auth/mfa-api';
import { consumeReturnTo } from '@/lib/nav/return-to';
import {
  mfaCodeSchema, parseCode, recoveryCodeSchema, webLoginSchema,
  type WebLoginFormData, type WebLoginFormInput,
} from '@/lib/validation/auth-schemas';

const TEAL   = '#0F766E'; // 4.5:1 or better on white and on the patient tint (WCAG AA)
const PURPLE = '#7C3AED';
const INDIGO = '#4F46E5';
const NAVY   = '#0D1B2E';
const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const MUTED  = '#4B5563'; // body copy on white (7.5:1)
const BORDER = '#E5E7EB';
/** Outline of the text fields: 4.83:1 against the field and the page (WCAG 1.4.11 asks 3:1). */
const INPUT_BORDER = '#6B7280';
const BG     = '#F9FAFB';
const RED    = '#B91C1C';

const REGISTER_ROUTE = '/auth/register/web/register-web';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

const roles: { key: Role; label: string; icon: IoniconsName; color: string; bg: string }[] = [
  { key: 'patient', label: 'Patient',       icon: 'person-outline',   color: TEAL,   bg: '#E6F4F1' },
  { key: 'doctor',  label: 'Doctor',        icon: 'medical-outline',  color: INDIGO, bg: '#EEF2FF' },
  { key: 'proxy',   label: 'Proxy / Carer', icon: 'people-outline',   color: PURPLE, bg: '#F5F3FF' },
];

const ROLE_LABELS: Record<Role, string> = { patient: 'Patient', doctor: 'Doctor', proxy: 'Proxy / Carer' };

const features: { icon: IoniconsName; text: string }[] = [
  { icon: 'calendar-outline',         text: 'Book and track appointments'          },
  { icon: 'document-text-outline',    text: 'View prescriptions and lab results'   },
  { icon: 'people-outline',           text: 'Caregiver access for loved ones'      },
  { icon: 'person-circle-outline',    text: 'Separate access for patients, doctors and carers' },
];

// ── Sign-in steps ─────────────────────────────────────────────────────────────

/** Where the sign-in is: email and password, a code of an enrolled authenticator, a forced enrolment, or its codes. */
type Step = 'credentials' | 'mfa' | 'mfa-setup' | 'recovery-codes';

/** The second step the server asked for. The token lives in this component's memory only. */
interface ActiveChallenge {
  type:      MfaChallengeType;
  token:     string;
  /** Epoch milliseconds after which the server no longer accepts the token. */
  expiresAt: number;
}

/** What the email and password step shows above the Sign in button. */
interface Notice {
  kind:    SignInFailureKind | 'wrong-role';
  message: string;
}

const CHALLENGE_EXPIRED: Notice = {
  kind:    'challenge-invalid',
  message: 'This sign-in step has expired. Please sign in again.',
};

const DEFAULT_CHALLENGE_SECONDS = 300;

/** Failures of a second-step request after which the person has to start again with email and password. */
const ENDS_CHALLENGE: ReadonlySet<SignInFailureKind> = new Set<SignInFailureKind>([
  'challenge-invalid', 'invalid-credentials', 'email-not-verified', 'pending-verification', 'rejected', 'closed', 'inactive',
]);

function noticeTone(kind: Notice['kind']): BannerTone {
  if (kind === 'email-not-verified' || kind === 'pending-verification' || kind === 'rate-limited') return 'warning';
  return 'danger';
}

function noticeIcon(kind: Notice['kind']): IoniconsName | undefined {
  if (kind === 'email-not-verified') return 'mail-unread-outline';
  if (kind === 'pending-verification' || kind === 'rate-limited') return 'time-outline';
  return undefined;
}

/** Moves keyboard focus to an element that was just shown (an input, or a heading with tabIndex -1). */
function focusElement(target: unknown): void {
  const el = target as { focus?: () => void } | null;
  if (el && typeof el.focus === 'function') el.focus();
}

/**
 * Seconds left of a challenge, counted down once a second. `onExpired` is called once when the time is up; a
 * request that is still in flight then is answered by the server with "expired" as well.
 */
function useChallengeCountdown(expiresAt: number, onExpired: () => void): number {
  const [left, setLeft] = useState(() => challengeSecondsLeft(expiresAt));
  const expired = useRef(onExpired);
  expired.current = onExpired;

  useEffect(() => {
    let fired = false;
    const tick = () => {
      const seconds = challengeSecondsLeft(expiresAt);
      setLeft(seconds);
      if (seconds <= 0 && !fired) {
        fired = true;
        clearInterval(timer);
        expired.current();
      }
    };
    const timer = setInterval(tick, 1000);
    tick();
    return () => clearInterval(timer);
  }, [expiresAt]);

  return left;
}

/** Saves text as a file from the browser. False when the browser cannot do it. */
function downloadTextFile(filename: string, text: string): boolean {
  if (typeof document === 'undefined' || typeof URL === 'undefined' || typeof URL.createObjectURL !== 'function') return false;
  try {
    const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }));
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    anchor.style.display = 'none';
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    return true;
  } catch {
    return false;
  }
}

async function copyText(text: string): Promise<boolean> {
  try {
    return await Clipboard.setStringAsync(text);
  } catch {
    return false;
  }
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

/** Heading of a step. The steps after the first move focus here or to their first input when they appear. */
function StepHeading({ title, sub, headingRef }: { title: string; sub: string; headingRef?: React.Ref<Text> }) {
  return (
    <>
      <Text
        ref={headingRef}
        style={styles.heading}
        accessibilityRole="header"
        {...ariaLevel(1)}
        {...webOnly({ tabIndex: -1 })}
      >
        {title}
      </Text>
      <Text style={styles.subheading}>{sub}</Text>
    </>
  );
}

type ResendState =
  | { status: 'idle' | 'sending' }
  | { status: 'sent' | 'failed'; message: string };

/**
 * "Resend verification email" for an address that still has to be confirmed. The server answers with the same
 * sentence whether or not the address belongs to an account, so that sentence is shown as it is.
 * Also used by the registration screen after an account was created.
 */
export function ResendVerification({ email }: { email: string }) {
  const [state, setState] = useState<ResendState>({ status: 'idle' });
  const busy = useRef(false);

  async function resend() {
    if (busy.current) return;
    busy.current = true;
    setState({ status: 'sending' });
    try {
      setState({ status: 'sent', message: await resendVerificationEmail(email) });
    } catch (err) {
      setState({ status: 'failed', message: apiError(err, 'We could not send the email. Please try again.').message });
    } finally {
      busy.current = false;
    }
  }

  return (
    <View style={styles.resend}>
      <Button
        label="Resend verification email"
        variant="secondary"
        icon="mail-outline"
        onPress={resend}
        loading={state.status === 'sending'}
      />
      {state.status === 'sent' && <Banner tone="success" text={state.message} />}
      {state.status === 'failed' && <Banner tone="danger" text={state.message} />}
    </View>
  );
}

// ── Step: code of an enrolled authenticator ───────────────────────────────────

function MfaStep({ challenge, onSignedIn, onAbort }: {
  challenge:  ActiveChallenge;
  /** The session is stored; resolves once the screen has moved on (or returned to email and password). */
  onSignedIn: (session: SignedIn) => Promise<void>;
  /** Back to email and password; with a notice when the step ended on its own (expired, locked, refused). */
  onAbort:    (notice: Notice | null) => void;
}) {
  const [mode, setMode]             = useState<'code' | 'recovery'>('code');
  const [value, setValue]           = useState('');
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [formError, setFormError]   = useState<string | null>(null);
  const [pending, setPending]       = useState(false);
  const busy     = useRef(false);
  const inputRef = useRef<TextInput>(null);

  const secondsLeft = useChallengeCountdown(challenge.expiresAt, () => onAbort(CHALLENGE_EXPIRED));

  // The code box takes focus when the step appears and again after switching between the two kinds of code.
  useEffect(() => { focusElement(inputRef.current); }, [mode]);

  function switchMode() {
    setMode(current => (current === 'code' ? 'recovery' : 'code'));
    setValue('');
    setFieldError(null);
    setFormError(null);
  }

  async function submit() {
    if (busy.current) return;
    const parsed = parseCode(mode === 'code' ? mfaCodeSchema : recoveryCodeSchema, value);
    if (!parsed.ok) {
      setFieldError(parsed.message);
      focusElement(inputRef.current);
      return;
    }
    busy.current = true;
    setPending(true);
    setFieldError(null);
    setFormError(null);
    try {
      const session = await completeMfaLogin(
        challenge.token,
        mode === 'code' ? { code: parsed.value } : { recoveryCode: parsed.value },
      );
      await onSignedIn(session);
    } catch (err) {
      const failure = signInFailure(err);
      if (ENDS_CHALLENGE.has(failure.kind)) {
        onAbort(failure);
      } else if (failure.kind === 'code-invalid') {
        setValue('');
        setFieldError(failure.message);
        focusElement(inputRef.current);
      } else if (failure.kind === 'validation') {
        setFieldError(failure.fieldErrors?.code ?? failure.fieldErrors?.recoveryCode ?? failure.message);
        focusElement(inputRef.current);
      } else {
        setFormError(failure.message);
      }
    } finally {
      busy.current = false;
      setPending(false);
    }
  }

  return (
    <View style={styles.step}>
      <StepHeading
        title="Two-factor authentication"
        sub={mode === 'code'
          ? 'Enter the 6-digit code from your authenticator app to finish signing in.'
          : 'Enter one of the recovery codes you saved when you turned on two-factor authentication. Each code works once.'}
      />

      {mode === 'code' ? (
        <TextField
          key="code"
          label="Authentication code"
          value={value}
          onChange={text => { setValue(text); setFieldError(null); }}
          error={fieldError}
          hint="6 digits. The code changes every 30 seconds."
          required
          inputMode="numeric"
          autoComplete="one-time-code"
          autoCapitalize="none"
          maxLength={12}
          onSubmit={submit}
          inputRef={inputRef}
        />
      ) : (
        <TextField
          key="recovery"
          label="Recovery code"
          value={value}
          onChange={text => { setValue(text); setFieldError(null); }}
          error={fieldError}
          hint="For example ABCDE-FGHJK."
          required
          autoComplete="off"
          autoCapitalize="characters"
          maxLength={24}
          onSubmit={submit}
          inputRef={inputRef}
        />
      )}

      <FormError message={formError} />

      <Button label="Verify and sign in" icon="shield-checkmark-outline" onPress={submit} loading={pending} fullWidth />

      <View style={styles.stepLinks}>
        <Button
          label={mode === 'code' ? 'Use a recovery code instead' : 'Use an authenticator code instead'}
          variant="ghost"
          onPress={switchMode}
          disabled={pending}
        />
        <Button label="Back to sign in" variant="ghost" icon="arrow-back-outline" onPress={() => onAbort(null)} disabled={pending} />
      </View>

      <Text style={styles.countdown}>Time left to finish this step: {formatCountdown(secondsLeft)}</Text>
    </View>
  );
}

// ── Step: forced enrolment ────────────────────────────────────────────────────

type SetupState =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ready'; setup: MfaSetup; qr: QrImage | null };

function MfaSetupStep({ challenge, onEnrolled, onAbort }: {
  challenge:  ActiveChallenge;
  onEnrolled: (result: { recoveryCodes: string[]; session: SignedIn }) => void;
  onAbort:    (notice: Notice | null) => void;
}) {
  const [state, setState]           = useState<SetupState>({ status: 'loading' });
  const [attempt, setAttempt]       = useState(0);
  const [code, setCode]             = useState('');
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [formError, setFormError]   = useState<string | null>(null);
  const [pending, setPending]       = useState(false);
  const [copied, setCopied]         = useState<'yes' | 'failed' | null>(null);
  const busy       = useRef(false);
  const inputRef   = useRef<TextInput>(null);
  const headingRef = useRef<Text>(null);
  const abort      = useRef(onAbort);
  abort.current    = onAbort;

  // The Sign in button that had focus is gone: start reading at the top of this step.
  useEffect(() => { focusElement(headingRef.current); }, []);

  const secondsLeft = useChallengeCountdown(challenge.expiresAt, () => onAbort(CHALLENGE_EXPIRED));

  // The secret belongs to this challenge: asking again (a retry, or the effect running twice) returns the same one.
  useEffect(() => {
    let cancelled = false;
    setState({ status: 'loading' });
    startMfaSetup(challenge.token)
      .then(async setup => {
        if (!setup?.secret) throw new Error('The setup response carried no secret');
        // Without a QR code (null) the step still works: the key below can be typed into the app.
        const qr = await qrImage(setup.otpauthUri);
        if (!cancelled) setState({ status: 'ready', setup, qr });
      })
      .catch(err => {
        if (cancelled) return;
        const failure = signInFailure(err);
        if (ENDS_CHALLENGE.has(failure.kind)) abort.current(failure);
        else setState({ status: 'error', message: failure.message });
      });
    return () => { cancelled = true; };
  }, [challenge.token, attempt]);

  async function copyKey(secret: string) {
    setCopied((await copyText(secret)) ? 'yes' : 'failed');
  }

  async function submit() {
    if (busy.current || state.status !== 'ready') return;
    const parsed = parseCode(mfaCodeSchema, code);
    if (!parsed.ok) {
      setFieldError(parsed.message);
      focusElement(inputRef.current);
      return;
    }
    busy.current = true;
    setPending(true);
    setFieldError(null);
    setFormError(null);
    try {
      onEnrolled(await completeMfaSetup(challenge.token, parsed.value));
    } catch (err) {
      const failure = signInFailure(err);
      if (ENDS_CHALLENGE.has(failure.kind)) {
        onAbort(failure);
      } else if (failure.kind === 'code-invalid') {
        setCode('');
        setFieldError(failure.message);
        focusElement(inputRef.current);
      } else if (failure.kind === 'validation') {
        setFieldError(failure.fieldErrors?.code ?? failure.message);
        focusElement(inputRef.current);
      } else {
        setFormError(failure.message);
        // 409: the server has no pending secret for this challenge. Ask for it again so the key on screen is valid.
        if (failure.status === 409) setAttempt(n => n + 1);
      }
    } finally {
      busy.current = false;
      setPending(false);
    }
  }

  return (
    <View style={styles.step}>
      <StepHeading
        headingRef={headingRef}
        title="Set up two-factor authentication"
        sub="Your account must be protected with two-factor authentication before you can sign in. You need an authenticator app on your phone."
      />

      {state.status === 'loading' && (
        <View style={styles.stepLoading} role="status" aria-live="polite">
          <ActivityIndicator size="small" color={MUTED} aria-hidden />
          <Text style={styles.bodyText}>Preparing two-factor setup…</Text>
        </View>
      )}

      {state.status === 'error' && (
        <Banner tone="danger" text={state.message} action={{ label: 'Retry', onPress: () => setAttempt(n => n + 1) }} />
      )}

      {state.status === 'ready' && (
        <>
          <View style={styles.setupBlock}>
            <Text style={styles.setupStepTitle} accessibilityRole="header" {...ariaLevel(2)}>
              1. Add CareNexus to your authenticator app
            </Text>
            {state.qr ? (
              <>
                <Text style={styles.bodyText}>Scan this QR code with the app, or type the key below into it.</Text>
                <View style={styles.qrFrame}>
                  <Image
                    source={{ uri: state.qr.uri }}
                    style={[{ width: state.qr.size, height: state.qr.size }, styles.qrImage]}
                    // The role makes the label valid: without it the name would sit on a plain container.
                    accessibilityRole="image"
                    accessibilityLabel="QR code that adds CareNexus to an authenticator app"
                  />
                </View>
              </>
            ) : (
              <Text style={styles.bodyText}>Type this key into the app (choose a time-based key).</Text>
            )}
            <View style={styles.keyGroup} role="group" aria-labelledby="mfa-setup-key-label">
              <Text style={styles.keyLabel} nativeID="mfa-setup-key-label">Setup key</Text>
              <Text style={styles.keyValue} selectable>{formatMfaSecret(state.setup.secret)}</Text>
            </View>
            <View style={styles.inlineActions}>
              <Button label="Copy key" variant="secondary" size="sm" icon="copy-outline" onPress={() => copyKey(state.setup.secret)} />
              <Text style={styles.statusText} role="status" aria-live="polite">
                {copied === 'yes' ? 'Key copied.' : copied === 'failed' ? 'Could not copy. Select the key and copy it.' : ''}
              </Text>
            </View>
          </View>

          <View style={styles.setupBlock}>
            <Text style={styles.setupStepTitle} accessibilityRole="header" {...ariaLevel(2)}>
              2. Enter the code the app shows
            </Text>
            <TextField
              label="Authentication code"
              value={code}
              onChange={text => { setCode(text); setFieldError(null); }}
              error={fieldError}
              hint="6 digits. The code changes every 30 seconds."
              required
              inputMode="numeric"
              autoComplete="one-time-code"
              autoCapitalize="none"
              maxLength={12}
              onSubmit={submit}
              inputRef={inputRef}
            />
            <FormError message={formError} />
            <Button label="Turn on and sign in" icon="shield-checkmark-outline" onPress={submit} loading={pending} fullWidth />
          </View>
        </>
      )}

      <View style={styles.stepLinks}>
        <Button label="Back to sign in" variant="ghost" icon="arrow-back-outline" onPress={() => onAbort(null)} disabled={pending} />
      </View>

      <Text style={styles.countdown}>Time left to finish this step: {formatCountdown(secondsLeft)}</Text>
    </View>
  );
}

// ── Step: recovery codes after a forced enrolment ─────────────────────────────

function RecoveryCodesStep({ codes, email, continuing, onContinue }: {
  codes:      string[];
  email:      string;
  continuing: boolean;
  onContinue: () => void;
}) {
  const [status, setStatus] = useState('');
  const headingRef = useRef<Text>(null);

  useEffect(() => { focusElement(headingRef.current); }, []);

  const fileText = useMemo(() => [
    'CareNexus recovery codes',
    `Account: ${email}`,
    `Created: ${new Date().toISOString().slice(0, 10)}`,
    '',
    ...codes,
    '',
    'Each code can be used once to sign in when you cannot use your authenticator app.',
    'Keep them somewhere safe and do not share them.',
    '',
  ].join('\n'), [codes, email]);

  async function copyCodes() {
    setStatus((await copyText(codes.join('\n'))) ? 'Recovery codes copied.' : 'Could not copy. Select the codes and copy them.');
  }

  function downloadCodes() {
    setStatus(downloadTextFile('carenexus-recovery-codes.txt', fileText)
      ? 'Recovery codes downloaded.'
      : 'Could not download. Copy the codes instead.');
  }

  return (
    <View style={styles.step}>
      <StepHeading
        headingRef={headingRef}
        title="Save your recovery codes"
        sub="Two-factor authentication is on and you are signed in. If you lose your authenticator app, one of these codes lets you sign in. Each code works once."
      />

      <Banner tone="warning" text="These codes are shown only now. Copy or download them before you continue." />

      {codes.length > 0 ? (
        <View style={styles.codeGrid} role="list" aria-label="Recovery codes">
          {codes.map(code => (
            <Text key={code} style={styles.codeItem} selectable role="listitem">{code}</Text>
          ))}
        </View>
      ) : (
        <Banner
          tone="danger"
          text="The recovery codes could not be shown. Create new ones from the two-factor authentication settings of your account."
        />
      )}

      {codes.length > 0 && (
        <View style={styles.inlineActions}>
          <Button label="Copy codes" variant="secondary" icon="copy-outline" onPress={copyCodes} />
          <Button label="Download codes" variant="secondary" icon="download-outline" onPress={downloadCodes} />
        </View>
      )}
      <Text style={styles.statusText} role="status" aria-live="polite">{status}</Text>

      <Button
        label={codes.length > 0 ? 'I have saved these codes' : 'Continue'}
        icon="arrow-forward-outline"
        onPress={onContinue}
        loading={continuing}
        fullWidth
      />
    </View>
  );
}

// ── Screen ────────────────────────────────────────────────────────────────────

export default function LoginWeb() {
  const router = useRouter();
  const { width } = useWindowDimensions();
  const narrow  = width < 768;
  const compact = width < 480;

  const [role, setRole]           = useState<Role>('patient');
  const [showPass, setShowPass]   = useState(false);
  const [notice, setNotice]       = useState<Notice | null>(null);
  const [loading, setLoading]     = useState(false);
  const [step, setStep]           = useState<Step>('credentials');
  const [challenge, setChallenge] = useState<ActiveChallenge | null>(null);
  const [enrolled, setEnrolled]   = useState<{ recoveryCodes: string[]; session: SignedIn } | null>(null);
  /** The address of the last sign-in attempt: what "Resend verification email" is sent for. */
  const [attemptEmail, setAttemptEmail] = useState('');
  const busy = useRef(false);

  const active = roles.find(r => r.key === role)!;

  const { control, handleSubmit, resetField, setError, setFocus, formState: { errors } } =
    useForm<WebLoginFormInput, unknown, WebLoginFormData>({
      resolver:      zodResolver(webLoginSchema),
      defaultValues: { email: '', password: '' },
    });

  // Coming back from a second step, the buttons that had focus are gone: continue in the email box.
  const previousStep = useRef<Step>(step);
  useEffect(() => {
    if (step === 'credentials' && previousStep.current !== 'credentials') setFocus('email');
    previousStep.current = step;
  }, [step, setFocus]);

  /** Drops the second step (its token is forgotten) and shows email and password again. */
  const backToCredentials = useCallback((next: Notice | null) => {
    setChallenge(null);
    setEnrolled(null);
    setStep('credentials');
    setNotice(next);
  }, []);

  /** A session is stored: check it matches the selected role, then open what the account needs first. */
  const finishSignIn = useCallback(async (session: SignedIn) => {
    const signedInRole = session.user.role;
    if (!isAppRole(signedInRole)) {
      await logout();
      backToCredentials({ kind: 'wrong-role', message: 'This account cannot be used in CareNexus on the web. Please contact support.' });
      return;
    }
    // Administrators have no tab here: whichever one is selected, they go to the admin area.
    if (signedInRole !== 'admin' && signedInRole !== role) {
      await logout();
      backToCredentials({
        kind:    'wrong-role',
        message: `This account is registered as a ${ROLE_LABELS[signedInRole]}. Please select the correct role.`,
      });
      return;
    }
    // A password that must be changed or terms that must be accepted come before anything else (the dashboard
    // guard enforces the same for a typed URL). Otherwise a dashboard link that was opened while signed out
    // (for example from an email) is opened now, and without one the role home.
    const next = requiredAccountStep(session.flags) ?? consumeReturnTo(signedInRole) ?? ROLE_HOME[signedInRole];
    router.replace(next as Href);
  }, [role, router, backToCredentials]);

  async function onSubmit(data: WebLoginFormData) {
    if (busy.current) return; // Enter in the password box and the button share this handler
    busy.current = true;
    setNotice(null);
    setLoading(true);
    setAttemptEmail(data.email);
    try {
      const response = await login({ email: data.email, password: data.password, role, ...describeWebDevice() });
      const pending = response.challenge;
      if (pending) {
        if ((pending.type !== 'MFA' && pending.type !== 'MFA_SETUP') || !pending.token) {
          setNotice({ kind: 'other', message: 'This account needs a sign-in step that this version of CareNexus does not support.' });
          return;
        }
        const lifetime = Number.isFinite(pending.expiresInSeconds) && pending.expiresInSeconds > 0
          ? pending.expiresInSeconds
          : DEFAULT_CHALLENGE_SECONDS;
        // The password has done its part; it is not kept in the form while the second step is open.
        resetField('password');
        setChallenge({ type: pending.type, token: pending.token, expiresAt: Date.now() + lifetime * 1000 });
        setStep(pending.type === 'MFA_SETUP' ? 'mfa-setup' : 'mfa');
        return;
      }
      if (!response.user) throw new Error('The sign-in response carried no user');
      await finishSignIn({ user: response.user, flags: response.flags ?? {} });
    } catch (err) {
      const failure = signInFailure(err);
      const fields = failure.kind === 'validation' ? failure.fieldErrors : undefined;
      if (fields?.email) setError('email', { type: 'server', message: fields.email });
      if (fields?.password) setError('password', { type: 'server', message: fields.password });
      if (!fields?.email && !fields?.password) setNotice({ kind: failure.kind, message: failure.message });
    } finally {
      busy.current = false;
      setLoading(false);
    }
  }

  const [continuing, setContinuing] = useState(false);
  async function continueAfterEnrolment() {
    if (!enrolled || continuing) return;
    setContinuing(true);
    try {
      await finishSignIn(enrolled.session);
    } finally {
      setContinuing(false);
    }
  }

  const submit = handleSubmit(onSubmit);

  return (
    <View style={styles.screen}>
      {/* ── Left: form (50%) ── */}
      <ScrollView
        style={[styles.formPane, narrow && styles.formPaneFull]}
        contentContainerStyle={[styles.formScroll, compact && styles.formScrollCompact]}
        showsVerticalScrollIndicator={false}
        keyboardShouldPersistTaps="handled"
      >
        {/* Logo */}
        <View style={styles.logoRow}>
          <View style={[styles.logoIcon, { borderColor: active.color }]}>
            <Ionicons name="heart" size={22} color={active.color} aria-hidden />
          </View>
          <View>
            <Text style={styles.logoText}>
              <Text style={{ color: NAVY }}>Care</Text>
              <Text style={{ color: active.color }}>Nexus</Text>
            </Text>
            <Text style={[styles.logoSub, { color: active.color }]}>HEALTHCARE PLATFORM</Text>
          </View>
        </View>

        {/* The shared buttons, fields and banners of the later steps follow the colour of the selected role. */}
        <UiThemeProvider theme={ROLE_THEME[role]}>
          {step === 'credentials' && (
            <>
              <Text style={styles.heading} accessibilityRole="header" {...ariaLevel(1)}>Welcome back</Text>
              <Text style={styles.subheading}>Sign in to continue to your dashboard</Text>

              {/* Role selector */}
              <Text style={styles.label} nativeID="login-role-label">I am a</Text>
              <View style={styles.roleRow} accessibilityRole="radiogroup" aria-labelledby="login-role-label">
                {roles.map(r => {
                  const on = role === r.key;
                  return (
                    <TouchableOpacity
                      key={r.key}
                      style={[styles.roleBtn, on && { borderColor: r.color, backgroundColor: r.bg }]}
                      onPress={() => setRole(r.key)}
                      activeOpacity={0.8}
                      accessibilityRole="radio"
                      accessibilityLabel={r.label}
                      accessibilityState={{ checked: on }}
                      // react-native-web ignores accessibilityState; without aria-checked a screen reader
                      // cannot tell which role is selected.
                      aria-checked={on}
                    >
                      <Ionicons name={r.icon} size={22} color={on ? r.color : GRAY} aria-hidden />
                      <Text style={[styles.roleLabel, on && { color: r.color, fontWeight: '700' }]}>{r.label}</Text>
                      {on && (
                        <View style={[styles.roleCheck, { backgroundColor: r.color }]}>
                          <Ionicons name="checkmark" size={10} color={WHITE} aria-hidden />
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
                  render={({ field: { onChange, onBlur, value, ref } }) => (
                    <View style={[styles.inputRow, !!errors.email && styles.inputRowError]}>
                      <Ionicons name="mail-outline" size={18} color={GRAY} style={styles.inputIcon} aria-hidden />
                      <TextInput
                        ref={ref}
                        style={styles.input}
                        accessibilityLabel="Email address"
                        aria-invalid={!!errors.email}
                        placeholder="you@example.com"
                        placeholderTextColor={GRAY}
                        value={value}
                        onChangeText={onChange}
                        onBlur={onBlur}
                        keyboardType="email-address"
                        autoComplete="email"
                        autoCapitalize="none"
                        autoCorrect={false}
                        onSubmitEditing={submit}
                      />
                    </View>
                  )}
                />
                <FieldError message={errors.email?.message} />
              </View>

              {/* Password */}
              <View style={styles.fieldGroup}>
                <View style={styles.fieldHeaderRow}>
                  <Text style={[styles.label, styles.labelInRow]}>Password</Text>
                  <Link href={FORGOT_PASSWORD_ROUTE as Href} style={[styles.forgotText, { color: active.color }]}>
                    Forgot password?
                  </Link>
                </View>
                <Controller
                  control={control}
                  name="password"
                  render={({ field: { onChange, onBlur, value, ref } }) => (
                    <View style={[styles.inputRow, !!errors.password && styles.inputRowError]}>
                      <Ionicons name="lock-closed-outline" size={18} color={GRAY} style={styles.inputIcon} aria-hidden />
                      <TextInput
                        ref={ref}
                        style={styles.input}
                        accessibilityLabel="Password"
                        aria-invalid={!!errors.password}
                        placeholder="Enter your password"
                        placeholderTextColor={GRAY}
                        value={value}
                        onChangeText={onChange}
                        onBlur={onBlur}
                        secureTextEntry={!showPass}
                        autoComplete="current-password"
                        autoCapitalize="none"
                        onSubmitEditing={submit}
                      />
                      <TouchableOpacity
                        onPress={() => setShowPass(v => !v)}
                        style={{ padding: 4 }}
                        activeOpacity={0.7}
                        accessibilityRole="button"
                        accessibilityLabel={showPass ? 'Hide password' : 'Show password'}
                      >
                        <Ionicons name={showPass ? 'eye-off-outline' : 'eye-outline'} size={18} color={GRAY} aria-hidden />
                      </TouchableOpacity>
                    </View>
                  )}
                />
                <FieldError message={errors.password?.message} />
              </View>

              {/* Why the last attempt did not sign in */}
              {notice && (
                <View style={styles.noticeBox}>
                  <Banner tone={noticeTone(notice.kind)} icon={noticeIcon(notice.kind)} text={notice.message} />
                  {notice.kind === 'email-not-verified' && !!attemptEmail && (
                    // Keyed by the address so an earlier "sent" message never stands under another address.
                    <ResendVerification key={attemptEmail} email={attemptEmail} />
                  )}
                </View>
              )}

              {/* Sign In */}
              <TouchableOpacity
                style={[styles.signInBtn, { backgroundColor: active.color, opacity: loading ? 0.7 : 1 }]}
                onPress={submit}
                activeOpacity={0.85}
                disabled={loading}
                accessibilityRole="button"
                accessibilityLabel="Sign in"
                accessibilityState={{ disabled: loading, busy: loading }}
                aria-busy={loading}
              >
                {loading
                  ? <Text style={styles.signInText}>Signing in…</Text>
                  : <>
                      <Text style={styles.signInText}>Sign In</Text>
                      <Ionicons name="arrow-forward-outline" size={18} color={WHITE} aria-hidden />
                    </>
                }
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
                onPress={() => router.push(REGISTER_ROUTE as Href)}
                accessibilityRole="link"
              >
                <Ionicons name="person-add-outline" size={16} color={active.color} aria-hidden />
                <Text style={[styles.createText, { color: active.color }]}>Create a new account</Text>
              </TouchableOpacity>

              {/* Footer */}
              <Text style={styles.footerText}>
                By signing in you agree to our{' '}
                <Link href={LEGAL_TERMS_ROUTE as Href} style={[styles.footerLink, { color: active.color }]}>Terms of Service</Link>
                {' '}and{' '}
                <Link href={LEGAL_PRIVACY_ROUTE as Href} style={[styles.footerLink, { color: active.color }]}>Privacy Policy</Link>
              </Text>
            </>
          )}

          {step === 'mfa' && challenge && (
            <MfaStep key={challenge.token} challenge={challenge} onSignedIn={finishSignIn} onAbort={backToCredentials} />
          )}

          {step === 'mfa-setup' && challenge && (
            <MfaSetupStep
              key={challenge.token}
              challenge={challenge}
              onAbort={backToCredentials}
              onEnrolled={result => {
                // The session is stored. The codes are shown before anything else: the server cannot show them again.
                setChallenge(null);
                setEnrolled(result);
                setStep('recovery-codes');
              }}
            />
          )}

          {step === 'recovery-codes' && enrolled && (
            <RecoveryCodesStep
              codes={enrolled.recoveryCodes}
              email={enrolled.session.user.email}
              continuing={continuing}
              onContinue={continueAfterEnrolment}
            />
          )}
        </UiThemeProvider>
      </ScrollView>

      {/* ── Right: branded panel (50%, hidden on narrow) ── */}
      {!narrow && (
        <View style={[styles.panel, { backgroundColor: active.color }]}>
          <View style={styles.panelInner}>
            <View style={styles.panelLogoRow}>
              <Ionicons name="heart" size={28} color={WHITE} aria-hidden />
              <Text style={styles.panelLogoText}>CareNexus</Text>
            </View>
            <Text style={styles.panelTitle}>Your care,{'\n'}in one place.</Text>
            <Text style={styles.panelSub}>
              CareNexus brings together patients, doctors, and carers on one platform.
            </Text>
            <View style={styles.featureList}>
              {features.map(f => (
                <View key={f.text} style={styles.featureRow}>
                  <View style={styles.featureIconBg}>
                    <Ionicons name={f.icon} size={16} color={active.color} aria-hidden />
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
  // Phone width: the padding gives way so inputs and the QR code keep a usable width.
  formScrollCompact: { paddingHorizontal: 20, paddingVertical: 32 },

  logoRow:  { flexDirection: 'row', alignItems: 'center', gap: 12, marginBottom: 36 },
  logoIcon: { width: 46, height: 46, borderRadius: 23, borderWidth: 2, alignItems: 'center', justifyContent: 'center' },
  logoText: { fontSize: 22, fontWeight: '800', lineHeight: 26 },
  logoSub:  { fontSize: 9, fontWeight: '700', letterSpacing: 2.5, marginTop: 1 },

  heading:    { fontSize: 28, fontWeight: '800', color: TEXT, marginBottom: 6, outlineStyle: 'none' } as any,
  subheading: { fontSize: 14, color: MUTED, marginBottom: 28, lineHeight: 21 },

  label:      { fontSize: 13, fontWeight: '600', color: TEXT, marginBottom: 10 },
  labelInRow: { marginBottom: 0 },
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
    borderWidth: 1.5, borderColor: INPUT_BORDER, borderRadius: 12,
    paddingHorizontal: 14, paddingVertical: 14,
    backgroundColor: BG,
  },
  inputRowError: { borderColor: RED },
  inputIcon:     { marginRight: 10 },
  input:         { flex: 1, minWidth: 0, fontSize: 15, color: TEXT, outlineStyle: 'none' } as any,

  fieldError:     { flexDirection: 'row', alignItems: 'center', gap: 5, marginTop: 5 },
  fieldErrorText: { fontSize: 12, color: RED, flex: 1 },

  noticeBox: { gap: 10, marginBottom: 14 },
  resend:    { gap: 10, alignItems: 'flex-start' },

  signInBtn:  { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8, borderRadius: 12, paddingVertical: 16, marginBottom: 20 },
  signInText: { fontSize: 16, fontWeight: '700', color: WHITE },

  dividerRow:  { flexDirection: 'row', alignItems: 'center', gap: 12, marginBottom: 20 },
  dividerLine: { flex: 1, height: 1, backgroundColor: BORDER },
  dividerText: { fontSize: 13, color: GRAY },

  createBtn:  { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8, borderWidth: 1.5, borderColor: BORDER, borderRadius: 12, paddingVertical: 14, marginBottom: 28 },
  createText: { fontSize: 14, fontWeight: '600' },

  footerText: { fontSize: 12, color: GRAY, textAlign: 'center', lineHeight: 18 },
  footerLink: { fontWeight: '600', textDecorationLine: 'underline' },

  // Second-step screens
  step:           { gap: 16 },
  stepLinks:      { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'space-between', gap: 8 },
  stepLoading:    { flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 12 },
  bodyText:       { fontSize: 14, color: MUTED, lineHeight: 21 },
  countdown:      { fontSize: 13, color: MUTED },
  statusText:     { fontSize: 13, color: MUTED, flexShrink: 1, minHeight: 18 },
  inlineActions:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 10 },

  setupBlock:     { gap: 10, borderWidth: 1, borderColor: BORDER, borderRadius: 12, padding: 16 },
  setupStepTitle: { fontSize: 15, fontWeight: '700', color: TEXT },
  qrFrame:        { alignSelf: 'flex-start', maxWidth: '100%', backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER, borderRadius: 8, overflow: 'hidden' },
  qrImage:        { maxWidth: '100%', imageRendering: 'pixelated' } as any,
  keyGroup:       { gap: 6 },
  keyLabel:       { fontSize: 13, fontWeight: '600', color: TEXT },
  keyValue: {
    fontFamily: 'monospace', fontSize: 15, letterSpacing: 1, color: TEXT, lineHeight: 24,
    backgroundColor: BG, borderWidth: 1, borderColor: BORDER, borderRadius: 8, paddingHorizontal: 12, paddingVertical: 10,
  },

  codeGrid: {
    flexDirection: 'row', flexWrap: 'wrap', columnGap: 24, rowGap: 8,
    backgroundColor: BG, borderWidth: 1, borderColor: BORDER, borderRadius: 12, padding: 16,
  },
  codeItem: { width: 140, fontFamily: 'monospace', fontSize: 15, letterSpacing: 1, color: TEXT, lineHeight: 24 },

  panel: { flex: 1, overflow: 'hidden' },
  panelInner: { flex: 1, justifyContent: 'center', padding: 56, gap: 20 },
  panelLogoRow:  { flexDirection: 'row', alignItems: 'center', gap: 10, marginBottom: 8 },
  panelLogoText: { fontSize: 24, fontWeight: '800', color: WHITE },
  panelTitle:    { fontSize: 34, fontWeight: '800', color: WHITE, lineHeight: 42 },
  panelSub:      { fontSize: 15, color: 'rgba(255,255,255,0.92)', lineHeight: 24 },

  featureList:   { gap: 14, marginTop: 8 },
  featureRow:    { flexDirection: 'row', alignItems: 'center', gap: 12 },
  featureIconBg: { width: 32, height: 32, borderRadius: 8, backgroundColor: 'rgba(255,255,255,0.2)', alignItems: 'center', justifyContent: 'center' },
  featureText:   { fontSize: 14, color: 'rgba(255,255,255,0.92)', fontWeight: '500' },

  decor:      { position: 'absolute', borderWidth: 1.5, borderColor: 'rgba(255,255,255,0.15)', borderRadius: 999 },
  decorBig:   { width: 320, height: 320, bottom: -100, right: -80 },
  decorSmall: { width: 160, height: 160, top: -40, right: 40 },
});
