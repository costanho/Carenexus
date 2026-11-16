import { zodResolver } from '@hookform/resolvers/zod';
import { useCallback, useEffect, useRef, useState } from 'react';
import { type Control, Controller, type FieldErrors, useForm } from 'react-hook-form';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Banner, Button, Card, colors, DateField, fontSize, FormError, Icon, IconButton, type IoniconsName,
  radius, ROLE_THEME, type RoleTheme, Screen, SectionCard, SelectField, space, STATUS_TONES,
  TagListField, TextField, useUiTheme, webOnly, type WebKeyEvent,
} from '@/components/web/ui';
import { refreshDependents } from '@/components/web/sections/proxy-dependents';
import { useRequestAccess } from '@/hooks/use-caregiver-access-requests';
import { useCaregiverDependents } from '@/hooks/use-caregiver-dependents';
import { useSectionNav } from '@/hooks/use-section-nav';
import { apiError, isNetworkError } from '@/lib/api/errors';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { useIdempotencyKey } from '@/lib/api/idempotency';
import { allDataFlags, dataFlagsOf, presetDataFlags } from '@/lib/caregiver/access';
import { createDependent, fetchDependents } from '@/lib/caregiver/api';
import { clearClipboard, copyToClipboard, generateTemporaryPassword } from '@/lib/caregiver/credentials';
import { CommunicationFields, PermissionFields } from '@/lib/caregiver/permission-fields';
import {
  accessRequestSchema, type AccessRequestFormValues,
  CREATE_DEPENDENT_STEPS, createDependentSchema, type CreateDependentFormValues,
  EMAIL_MAX, LIST_ITEM_MAX, LIST_MAX_ITEMS, MESSAGE_MAX, NAME_MAX, PASSWORD_MAX, PHONE_MAX, RELATIONSHIP_MAX,
  toAccessRequestBody, toCreateDependentBody,
} from '@/lib/caregiver/schemas';
import type {
  AccessLevel, BloodType, CaregiverDependent, CommFlag, CreateDependentBody, DataFlag, Gender,
} from '@/lib/caregiver/types';
import { clinicToday } from '@/lib/time/clinic';
import { AccessRequestsPanel, type AccessRequestTab } from './patient-proxy-access-requests';

// Add Loved One. Two ways to link a patient to the signed-in caregiver:
//
//   Link an existing patient   the person already has an account: a request is sent and the patient decides
//                              what to share. The page shows the same confirmation whatever the email, because
//                              the server never says whether an email belongs to a patient.
//   Create a new account       for someone the caregiver is the parent or legal guardian of. Email and password
//                              are optional: without them the caregiver manages the account until the person is
//                              invited to sign in from their profile.
//
// The chosen way lives in the URL (`mode=link|create|requests`), so Back and a refresh keep it.

type Mode = 'link' | 'create' | 'requests';

function toMode(value: string | undefined): Mode | null {
  return value === 'link' || value === 'create' || value === 'requests' ? value : null;
}

const GENDER_OPTIONS: { value: Gender; label: string }[] = [
  { value: 'MALE', label: 'Male' }, { value: 'FEMALE', label: 'Female' }, { value: 'OTHER', label: 'Other' },
];
const BLOOD_TYPE_OPTIONS: { value: BloodType; label: string }[] =
  (['A+', 'A-', 'B+', 'B-', 'O+', 'O-', 'AB+', 'AB-'] as BloodType[]).map(v => ({ value: v, label: v }));

const DATA_FLAG_NAMES: readonly DataFlag[] = [
  'canViewRecords', 'canViewAppointments', 'canViewMedications', 'canViewLabResults', 'canViewImaging',
  'canViewConsultations', 'canViewBilling', 'canScheduleAppointments', 'canManageCarePlan', 'canManageBilling',
];

// ─── Choice ───────────────────────────────────────────────────────────────────

function ChoiceCard({ icon, title, description, onPress }: {
  icon: IoniconsName; title: string; description: string; onPress: () => void;
}) {
  const { accent, accentBg } = useUiTheme();
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={`${title}. ${description}`}
      style={({ pressed }) => [styles.choice, pressed && { borderColor: accent, backgroundColor: accentBg }]}
    >
      <View style={[styles.choiceIcon, { backgroundColor: accentBg }]}>
        <Icon name={icon} size={22} color={accent} />
      </View>
      <View style={styles.choiceText}>
        <Text style={styles.choiceTitle}>{title}</Text>
        <Text style={styles.choiceDescription}>{description}</Text>
      </View>
      <Icon name="chevron-forward" size={18} color={colors.textMuted} />
    </Pressable>
  );
}

function ChoiceStep({ onChoose, onViewRequests }: { onChoose: (mode: Mode) => void; onViewRequests: () => void }) {
  return (
    <SectionCard title="Who are you adding?" subtitle="Choose the option that fits this person.">
      <ChoiceCard
        icon="link-outline"
        title="Link an existing patient"
        description="They already have a CareNexus account. They get your request and decide what to share with you."
        onPress={() => onChoose('link')}
      />
      <ChoiceCard
        icon="person-add-outline"
        title="Create a new account"
        description="For a child or someone you are the legal guardian of who has no account yet. You can manage it for them."
        onPress={() => onChoose('create')}
      />
      <Button
        label="View requests you have sent"
        icon="mail-open-outline"
        variant="ghost"
        onPress={onViewRequests}
      />
    </SectionCard>
  );
}

// ─── Link an existing patient ─────────────────────────────────────────────────

const LINK_FIELDS = ['patientEmail', 'relationship', 'accessLevel', 'message'] as const;

function LinkExistingFlow({ onDone, onViewRequests }: { onDone: () => void; onViewRequests: () => void }) {
  const { control, handleSubmit, watch, setValue, getValues, setError, reset } = useForm<AccessRequestFormValues>({
    resolver: zodResolver(accessRequestSchema),
    defaultValues: {
      patientEmail: '', relationship: '', accessLevel: 'VIEW_ONLY', message: '',
      ...presetDataFlags('VIEW_ONLY', allDataFlags(false)),
      canMessageCareTeam: false, canJoinCalls: false,
    },
  });
  const send = useRequestAccess();
  const [formError, setFormError] = useState<string | null>(null);
  const [sent, setSent] = useState<string | null>(null);
  const values = watch();

  const changeLevel = (level: AccessLevel) => {
    setValue('accessLevel', level, { shouldDirty: true });
    const next = presetDataFlags(level, dataFlagsOf(getValues()));
    for (const flag of DATA_FLAG_NAMES) setValue(flag, next[flag]);
  };

  const submit = handleSubmit(async form => {
    setFormError(null);
    const result = await send.run(toAccessRequestBody(form));
    if (result.ok) setSent(result.data);
    // A 409 here means the caregiver is already linked to this patient: its sentence is shown, not the confirmation.
    else if (result.error) setFormError(applyFieldErrors(result.error, setError, LINK_FIELDS));
  });

  if (sent) {
    return (
      <Card style={styles.result}>
        <Icon name="paper-plane-outline" size={40} color={STATUS_TONES.success.fg} />
        <Text style={styles.resultTitle} accessibilityRole="header" {...ariaLevel(2)}>Request sent</Text>
        <Text style={styles.resultText} role="status">{sent}</Text>
        <Text style={styles.resultNote}>
          You will get a notification when your request is answered. Until then you cannot see any of their
          information. A request that is not answered expires after 30 days.
        </Text>
        <View style={styles.resultActions}>
          <Button label="View my requests" icon="mail-open-outline" variant="secondary" onPress={onViewRequests} />
          <Button
            label="Send another request"
            icon="add-outline"
            variant="secondary"
            onPress={() => { reset(); setSent(null); }}
          />
          <Button label="Back to Dashboard" onPress={onDone} />
        </View>
      </Card>
    );
  }

  return (
    <SectionCard
      title="Link an existing patient"
      subtitle="Send a request to someone who already has their own account. They choose what you can see and do."
    >
      <TextField
        label="Patient's email address"
        control={control}
        name="patientEmail"
        placeholder="name@example.com"
        inputMode="email"
        autoComplete="off"
        autoCapitalize="none"
        maxLength={EMAIL_MAX}
        hint="The email they use to sign in to CareNexus."
        required
      />
      <TextField
        label="Your relationship to them"
        control={control}
        name="relationship"
        placeholder="For example daughter, husband, friend"
        maxLength={RELATIONSHIP_MAX}
        required
      />
      <Banner
        tone="info"
        text="This is what you are asking for. The patient can change it when they approve, and can change or end your access at any time."
      />
      <PermissionFields
        level={values.accessLevel}
        flags={dataFlagsOf(values)}
        onLevelChange={changeLevel}
        onFlagChange={(flag, value) => setValue(flag, value, { shouldDirty: true })}
        disabled={send.pending}
      />
      <CommunicationFields
        value={{ canMessageCareTeam: values.canMessageCareTeam, canJoinCalls: values.canJoinCalls }}
        onChange={(flag: CommFlag, value) => setValue(flag, value, { shouldDirty: true })}
        note="Messaging and video calls take effect only if the patient switches them on when they approve."
        disabled={send.pending}
      />
      <TextField
        label="Message to the patient (optional)"
        control={control}
        name="message"
        placeholder="Tell them why you are asking"
        multiline
        rows={3}
        maxLength={MESSAGE_MAX}
        showCount
      />
      <FormError message={formError} />
      <View style={styles.formActions}>
        <Button label="Send request" icon="paper-plane-outline" onPress={submit} loading={send.pending} />
      </View>
    </SectionCard>
  );
}

// ─── Create a new account ─────────────────────────────────────────────────────

const STEP_TITLES = ['Personal Details', 'Health Profile', 'Access Settings'] as const;

const CREATE_FIELDS = [
  'firstName', 'lastName', 'email', 'password', 'phone', 'dateOfBirth', 'gender', 'bloodType', 'allergies',
  'chronicConditions', 'relationship', 'accessLevel', 'guardianAttested',
] as const;

/** The first step that holds one of these fields, or -1. */
function stepOf(fields: readonly string[]): number {
  return CREATE_DEPENDENT_STEPS.findIndex(step => step.some(name => fields.includes(name)));
}

function StepHeader({ current }: { current: number }) {
  const { accent } = useUiTheme();
  return (
    <View style={styles.steps}>
      <Text style={styles.stepStatus} role="status" aria-live="polite">
        {`Step ${current + 1} of ${STEP_TITLES.length}: ${STEP_TITLES[current]}`}
      </Text>
      <View style={styles.stepBars} aria-hidden importantForAccessibility="no-hide-descendants">
        {STEP_TITLES.map((title, i) => (
          <View key={title} style={[styles.stepBar, i <= current && { backgroundColor: accent }]} />
        ))}
      </View>
    </View>
  );
}

type CopyState = 'idle' | 'copied' | 'failed';

/** Copy feedback that says so when the clipboard refused, and remembers that a password was put there. */
function usePasswordCopy() {
  const [state, setState] = useState<CopyState>('idle');
  const copied = useRef(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const copy = useCallback(async (password: string) => {
    const ok = await copyToClipboard(password);
    if (ok) copied.current = true;
    setState(ok ? 'copied' : 'failed');
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => setState('idle'), ok ? 2500 : 6000);
  }, []);

  /** Empties the clipboard if this form put a password there. */
  const clear = useCallback(() => {
    if (!copied.current) return;
    copied.current = false;
    void clearClipboard();
  }, []);

  // Leaving the form by any route (sidebar, browser Back) also clears it.
  useEffect(() => () => {
    if (timer.current) clearTimeout(timer.current);
    if (copied.current) void clearClipboard();
  }, []);

  return { state, copy, clear };
}

function CopyStatus({ state }: { state: CopyState }) {
  return (
    <Text
      style={[styles.copyStatus, state === 'failed' && { color: colors.danger }]}
      role={state === 'failed' ? 'alert' : 'status'}
      aria-live={state === 'failed' ? 'assertive' : 'polite'}
    >
      {state === 'copied' ? 'Copied' : state === 'failed' ? 'Could not copy. Show the password and copy it by hand.' : ''}
    </Text>
  );
}

/**
 * The guardian attestation: a required checkbox (role "checkbox", Space toggles it on the web) with its
 * explanation and the validation message tied to it.
 */
function GuardianCheckbox({ control }: { control: Control<CreateDependentFormValues> }) {
  const { accent } = useUiTheme();
  const label = "I am this person's parent or legal guardian";
  const note = 'You confirm that you may create and manage this account for them. If this person manages their own '
    + 'care, ask them to create their own account and use Link an existing patient instead.';
  return (
    <Controller
      control={control}
      name="guardianAttested"
      render={({ field, fieldState }) => {
        const checked = field.value === true;
        const toggle = () => field.onChange(!checked);
        const error = fieldState.error?.message;
        return (
          <View style={styles.attest}>
            <Pressable
              ref={field.ref}
              role="checkbox"
              aria-checked={checked}
              aria-required
              aria-invalid={error ? true : undefined}
              accessibilityState={{ checked }}
              accessibilityLabel={`${label}. ${note}${error ? ` ${error}` : ''}`}
              onPress={toggle}
              onBlur={field.onBlur}
              style={styles.attestRow}
              {...webOnly({
                onKeyDown: (event: WebKeyEvent) => {
                  if (event.key === ' ' || event.key === 'Spacebar') { event.preventDefault(); toggle(); }
                },
              })}
            >
              <View style={[styles.checkbox, checked && { backgroundColor: accent, borderColor: accent }, !!error && !checked && { borderColor: colors.danger }]}>
                {checked ? <Icon name="checkmark" size={16} color="#FFFFFF" /> : null}
              </View>
              <View style={styles.attestText}>
                <Text style={styles.attestLabel}>
                  {label}
                  <Text style={styles.required}> *</Text>
                </Text>
                <Text style={styles.attestNote}>{note}</Text>
              </View>
            </Pressable>
            {error ? (
              <View style={styles.attestError} accessibilityRole="alert">
                <Icon name="alert-circle-outline" size={14} color={colors.danger} />
                <Text style={styles.attestErrorText}>{error}</Text>
              </View>
            ) : null}
          </View>
        );
      }}
    />
  );
}

interface CreatedState {
  dependent: CaregiverDependent;
  /** The temporary password, only when it is certain that this is the one stored on the account. */
  password:  string | null;
  /** The account was created by an earlier attempt whose answer never arrived. */
  recovered: boolean;
}

function CreatedView({ created, copy, onDone }: {
  created: CreatedState;
  copy:    ReturnType<typeof usePasswordCopy>;
  onDone:  () => void;
}) {
  const [visible, setVisible] = useState(false);
  const { dependent, password, recovered } = created;
  const name = dependent.patientName?.trim() || 'Your loved one';
  const firstName = name.split(/\s+/)[0];
  const canSignIn = !dependent.managed && !!dependent.patientEmail;

  return (
    <Card style={styles.result}>
      <Icon name="checkmark-circle" size={48} color={STATUS_TONES.success.fg} />
      <Text style={styles.resultTitle} accessibilityRole="header" {...ariaLevel(2)}>Loved One Added!</Text>
      <Text style={styles.resultText} role="status">
        {recovered
          ? `${name} was already added by your earlier attempt and is now linked under your care. No second account was created.`
          : `${name} has been added as a linked patient under your care.`}
      </Text>

      {canSignIn ? (
        <View style={styles.credentials}>
          <Text style={styles.credentialsTitle} accessibilityRole="header" {...ariaLevel(3)}>
            {`Sign-in details for ${firstName}`}
          </Text>
          <View style={styles.credentialRow}>
            <Text style={styles.credentialLabel}>Email</Text>
            <Text style={styles.credentialValue}>{dependent.patientEmail}</Text>
          </View>
          {password ? (
            <>
              <View style={styles.credentialRow}>
                <Text style={styles.credentialLabel}>Temporary password</Text>
                <Text style={styles.credentialValue}>{visible ? password : '••••••••••••'}</Text>
                <IconButton
                  label={visible ? 'Hide password' : 'Show password'}
                  icon={visible ? 'eye-off-outline' : 'eye-outline'}
                  size="sm"
                  onPress={() => setVisible(v => !v)}
                />
                <IconButton label="Copy password" icon="copy-outline" size="sm" onPress={() => { void copy.copy(password); }} />
              </View>
              <CopyStatus state={copy.state} />
              <Text style={styles.resultNote}>
                {`Give these to ${firstName} in person or by phone. Do not send the password by email or SMS. It is not shown again after you leave this page.`}
              </Text>
            </>
          ) : (
            <Text style={styles.resultNote}>
              {`The temporary password from your earlier attempt cannot be shown again. ${firstName} can choose a new one with "Forgot password" on the sign-in page.`}
            </Text>
          )}
        </View>
      ) : (
        <Banner
          tone="info"
          text={`You manage this account. ${firstName} cannot sign in yet. When they are ready, open their profile and choose Invite to sign in.`}
        />
      )}

      <Button label="Back to Dashboard" onPress={() => { copy.clear(); onDone(); }} />
    </Card>
  );
}

function CreateAccountFlow({ onDone }: { onDone: () => void }) {
  const idempotencyKey = useIdempotencyKey();
  const dependentsState = useCaregiverDependents();
  const copy = usePasswordCopy();
  const form = useForm<CreateDependentFormValues>({
    resolver: zodResolver(createDependentSchema),
    defaultValues: {
      firstName: '', lastName: '', email: '', password: '', phone: '',
      dateOfBirth: '', gender: '', bloodType: '', allergies: [], chronicConditions: [],
      relationship: '', accessLevel: 'FULL_ACCESS', expiresOn: '', guardianAttested: false,
      ...allDataFlags(true),
      canMessageCareTeam: false, canJoinCalls: false,
    },
  });
  const { control, handleSubmit, trigger, watch, setValue, getValues, setError } = form;

  const [step, setStep] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [created, setCreated] = useState<CreatedState | null>(null);
  const [passwordVisible, setPasswordVisible] = useState(false);

  // The body of the first attempt made with the current idempotency key. A repeat with the same key is answered
  // with the first attempt's result, so only when the body is unchanged is the typed password the stored one.
  const firstBody = useRef<string | null>(null);
  // The caregiver's grants before the first attempt: a grant that is not among them was created by this form.
  const baseline = useRef<Set<number> | null>(null);
  const busy = useRef(false);

  const values = watch();
  const hasEmail = values.email.trim() !== '';
  const today = clinicToday();

  // An account with an email needs a password: one is generated as soon as an email is typed.
  useEffect(() => {
    if (hasEmail && !getValues('password')) setValue('password', generateTemporaryPassword());
  }, [hasEmail, getValues, setValue]);

  const changeLevel = (level: AccessLevel) => {
    setValue('accessLevel', level, { shouldDirty: true });
    const next = presetDataFlags(level, dataFlagsOf(getValues()));
    for (const flag of DATA_FLAG_NAMES) setValue(flag, next[flag]);
  };

  const next = async () => {
    setFormError(null);
    if (await trigger([...CREATE_DEPENDENT_STEPS[step]], { shouldFocus: true })) setStep(s => s + 1);
  };

  /** A grant created by an earlier attempt of this form whose answer never arrived, if there is one. */
  const findCreated = async (body: CreateDependentBody): Promise<CaregiverDependent | 'already-linked' | null> => {
    const known = baseline.current;
    if (!known) return null;
    let rows: CaregiverDependent[];
    try {
      rows = await fetchDependents();
    } catch {
      return null;
    }
    if (body.email) {
      const match = rows.find(d => (d.patientEmail ?? '').toLowerCase() === body.email);
      if (!match) return null;
      return known.has(match.accessId) ? 'already-linked' : match;
    }
    const name = `${body.firstName} ${body.lastName}`.toLowerCase();
    return rows.find(d => !known.has(d.accessId) && d.managed && (d.patientName ?? '').trim().toLowerCase() === name) ?? null;
  };

  const finish = (dependent: CaregiverDependent, body: CreateDependentBody, bodyKey: string, recovered: boolean) => {
    const certain = firstBody.current === bodyKey;
    idempotencyKey.renew();
    firstBody.current = null;
    setCreated({ dependent, password: certain && body.password ? body.password : null, recovered });
    // The new loved one belongs in the sidebar at once, whichever way the caregiver leaves this page.
    refreshDependents();
  };

  const submit = handleSubmit(
    async formValues => {
      if (busy.current) return;
      busy.current = true;
      setSubmitting(true);
      setFormError(null);

      const body = toCreateDependentBody(formValues);
      const bodyKey = JSON.stringify(body);
      try {
        if (!baseline.current) {
          // The list the page already loaded, or a fresh one when it has not arrived yet.
          const rows = !dependentsState.loading && !dependentsState.error
            ? dependentsState.dependents
            : await fetchDependents().catch(() => null);
          if (rows) baseline.current = new Set(rows.map(d => d.accessId));
        }
        if (firstBody.current === null) firstBody.current = bodyKey;

        // One atomic call: the server creates the patient account, the health profile and this caregiver's
        // grant in a single transaction. The caregiver is taken from the signed-in session, never from the body.
        const dependent = await createDependent(body, idempotencyKey.current());
        finish(dependent, body, bodyKey, false);
      } catch (e) {
        const failure = apiError(e, 'Could not add your loved one. Please try again.');

        if (failure.status === 409) {
          // Either an earlier attempt is still running or already created the account (its answer was lost),
          // or the email belongs to someone else.
          const found = await findCreated(body);
          if (found && found !== 'already-linked') {
            finish(found, body, bodyKey, true);
          } else if (found === 'already-linked') {
            setError('email', { type: 'server', message: 'This person is already one of your loved ones.' }, { shouldFocus: true });
            setStep(0);
          } else if (failure.code === 'IDEMPOTENCY_IN_PROGRESS') {
            setFormError(failure.message);
          } else {
            // Nothing was created: the corrected form is a new request.
            idempotencyKey.renew();
            firstBody.current = null;
            setError('email', { type: 'server', message: failure.message }, { shouldFocus: true });
            setStep(0);
          }
        } else if (failure.status === 0 && isNetworkError(e)) {
          setFormError(
            'We could not confirm that the account was created. Check your connection, then press Add Loved One '
            + 'again without changing the form: the same request is sent, so no second account is created.',
          );
        } else if (failure.status >= 400 && failure.status < 500) {
          // Refused before anything was created: the corrected form is a new request.
          idempotencyKey.renew();
          firstBody.current = null;
          setFormError(applyFieldErrors(failure, setError, CREATE_FIELDS));
          const at = stepOf(Object.keys(failure.fieldErrors ?? {}));
          if (at >= 0) setStep(at);
        } else {
          setFormError(failure.message);
        }
      } finally {
        busy.current = false;
        setSubmitting(false);
      }
    },
    (invalid: FieldErrors<CreateDependentFormValues>) => {
      // A field on an earlier step is invalid: go back to it.
      const at = stepOf(Object.keys(invalid));
      if (at >= 0 && at !== step) setStep(at);
    },
  );

  if (created) return <CreatedView created={created} copy={copy} onDone={onDone} />;

  return (
    <View style={styles.flow}>
      <StepHeader current={step} />

      {step === 0 && (
        <SectionCard title="Personal Details" subtitle="Who this account is for">
          <View style={styles.row}>
            <View style={styles.col}>
              <TextField label="First name" control={control} name="firstName" maxLength={NAME_MAX} autoComplete="off" required />
            </View>
            <View style={styles.col}>
              <TextField label="Last name" control={control} name="lastName" maxLength={NAME_MAX} autoComplete="off" required />
            </View>
          </View>
          <TextField
            label="Phone number (optional)"
            control={control}
            name="phone"
            placeholder="+263 77 123 4567"
            inputMode="tel"
            autoComplete="off"
            maxLength={PHONE_MAX}
            hint="A number you share with this person is fine."
          />

          <Banner
            tone="info"
            text="Sign-in details are optional. Leave the email empty if this person will not sign in themselves, for example a child: you manage the account, and you can invite them to sign in later from their profile."
          />
          <TextField
            label="Email address (optional)"
            control={control}
            name="email"
            placeholder="name@example.com"
            inputMode="email"
            autoComplete="off"
            autoCapitalize="none"
            maxLength={EMAIL_MAX}
            hint="Only needed if they will sign in themselves."
          />
          {hasEmail && (
            <>
              <View style={styles.passwordRow}>
                <View style={styles.col}>
                  <TextField
                    label="Temporary password"
                    control={control}
                    name="password"
                    secure={!passwordVisible}
                    autoComplete="new-password"
                    autoCapitalize="none"
                    maxLength={PASSWORD_MAX}
                    hint="At least 8 characters with a letter and a number. Give it to them in person or by phone."
                    required
                  />
                </View>
                <View style={styles.passwordButtons}>
                  <IconButton
                    label={passwordVisible ? 'Hide password' : 'Show password'}
                    icon={passwordVisible ? 'eye-off-outline' : 'eye-outline'}
                    variant="secondary"
                    onPress={() => setPasswordVisible(v => !v)}
                  />
                  <IconButton
                    label="Copy password"
                    icon="copy-outline"
                    variant="secondary"
                    onPress={() => { void copy.copy(getValues('password')); }}
                  />
                  <Button
                    label="Regenerate"
                    icon="refresh-outline"
                    variant="secondary"
                    accessibilityLabel="Regenerate password"
                    onPress={() => setValue('password', generateTemporaryPassword(), { shouldDirty: true, shouldValidate: true })}
                  />
                </View>
              </View>
              <CopyStatus state={copy.state} />
            </>
          )}
        </SectionCard>
      )}

      {step === 1 && (
        <SectionCard
          title="Health Profile"
          subtitle="Everything here is optional. Leave out what you do not know: a doctor can add it later."
        >
          <DateField label="Date of birth" control={control} name="dateOfBirth" max={today} />
          <View style={styles.row}>
            <View style={styles.col}>
              <SelectField<Gender, CreateDependentFormValues>
                label="Gender" control={control} name="gender" options={GENDER_OPTIONS} placeholder="Not known" allowEmpty
              />
            </View>
            <View style={styles.col}>
              <SelectField<BloodType, CreateDependentFormValues>
                label="Blood type" control={control} name="bloodType" options={BLOOD_TYPE_OPTIONS} placeholder="Not known" allowEmpty
              />
            </View>
          </View>
          <TagListField
            label="Known allergies"
            control={control}
            name="allergies"
            placeholder="For example penicillin"
            maxItems={LIST_MAX_ITEMS}
            maxLength={LIST_ITEM_MAX}
            hint="Add one at a time. Leave empty if none are known."
          />
          <TagListField
            label="Chronic conditions"
            control={control}
            name="chronicConditions"
            placeholder="For example asthma"
            maxItems={LIST_MAX_ITEMS}
            maxLength={LIST_ITEM_MAX}
            hint="Add one at a time. Leave empty if none are known."
          />
        </SectionCard>
      )}

      {step === 2 && (
        <SectionCard title="Access Settings" subtitle="Define your proxy relationship and what you are permitted to access">
          <TextField
            label="Your relationship to them"
            control={control}
            name="relationship"
            placeholder="For example mother, father, guardian"
            maxLength={RELATIONSHIP_MAX}
            required
          />
          <PermissionFields
            level={values.accessLevel}
            flags={dataFlagsOf(values)}
            onLevelChange={changeLevel}
            onFlagChange={(flag, value) => setValue(flag, value, { shouldDirty: true })}
            disabled={submitting}
          />
          <CommunicationFields
            value={{ canMessageCareTeam: values.canMessageCareTeam, canJoinCalls: values.canJoinCalls }}
            onChange={(flag: CommFlag, value) => setValue(flag, value, { shouldDirty: true })}
            note={hasEmail
              ? 'Messaging and video calls need their approval: they can approve after they sign in.'
              : 'Messaging and video calls need their approval, so these stay waiting until you invite them to sign in and they approve.'}
            disabled={submitting}
          />
          <DateField
            label="Access end date (optional)"
            control={control}
            name="expiresOn"
            min={today}
            hint="Your access ends at the end of this day. Leave empty for no end date."
          />
          <GuardianCheckbox control={control} />
        </SectionCard>
      )}

      <FormError message={formError} />

      <View style={styles.nav}>
        {step > 0
          ? <Button label="Back" icon="arrow-back-outline" variant="secondary" onPress={() => { setFormError(null); setStep(s => s - 1); }} disabled={submitting} />
          : <View />}
        {step < STEP_TITLES.length - 1
          ? <Button label="Continue" onPress={() => { void next(); }} />
          : <Button label="Add Loved One" icon="person-add-outline" onPress={submit} loading={submitting} />}
      </View>
    </View>
  );
}

// ─── Screen ───────────────────────────────────────────────────────────────────

export interface PatientProxyWebAddDependentProps {
  /** Leaves the screen after a loved one was added or a request was sent (the caller reloads the list). */
  onDone:   () => void;
  /** Leaves the screen without adding anyone. */
  onCancel: () => void;
  /**
   * Opens the caregiver's access requests (the hidden `access-requests` section). Without it the requests are
   * listed on this screen instead.
   */
  onViewRequests?: () => void;
  theme?: RoleTheme;
}

export default function PatientProxyWebAddDependent({
  onDone, onCancel, onViewRequests, theme,
}: PatientProxyWebAddDependentProps) {
  const nav = useSectionNav();
  const mode = toMode(nav.params.mode);
  const [requestsTab, setRequestsTab] = useState<AccessRequestTab>('pending');

  const choose = (next: Mode | null) => nav.navigate(nav.section, { mode: next ?? undefined });
  const viewRequests = onViewRequests ?? (() => choose('requests'));

  const subtitle = mode === 'link'
    ? 'Ask a patient who already has an account to share their care with you'
    : mode === 'create'
      ? 'Create an account for someone in your care'
      : mode === 'requests'
        ? 'Requests you sent to patients who already have their own account'
        : 'Link someone to your caregiver account';

  return (
    <Screen
      title="Add Loved One"
      subtitle={subtitle}
      theme={theme ?? ROLE_THEME.proxy}
      actions={mode
        ? <Button label="Change option" icon="arrow-back-outline" variant="secondary" onPress={() => choose(null)} />
        : <Button label="Cancel" variant="secondary" onPress={onCancel} />}
    >
      <View style={styles.page}>
        {mode === null && <ChoiceStep onChoose={choose} onViewRequests={viewRequests} />}
        {mode === 'link' && <LinkExistingFlow onDone={onDone} onViewRequests={viewRequests} />}
        {mode === 'create' && <CreateAccountFlow onDone={onDone} />}
        {mode === 'requests' && (
          <AccessRequestsPanel tab={requestsTab} onTabChange={setRequestsTab} />
        )}
      </View>
    </Screen>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  page: { width: '100%', maxWidth: 820, gap: space.lg, minWidth: 0 },
  flow: { gap: space.lg, minWidth: 0 },

  choice: {
    flexDirection: 'row', alignItems: 'center', gap: space.md, padding: space.lg, minWidth: 0,
    borderWidth: 1, borderColor: colors.borderInput, borderRadius: radius.lg, backgroundColor: colors.surface,
  },
  choiceIcon:        { width: 44, height: 44, borderRadius: 22, alignItems: 'center', justifyContent: 'center', flexShrink: 0 },
  choiceText:        { flex: 1, minWidth: 0, gap: 2 },
  choiceTitle:       { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  choiceDescription: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },

  steps:      { gap: space.sm },
  stepStatus: { fontSize: fontSize.sm, fontWeight: '600', color: colors.textMuted },
  stepBars:   { flexDirection: 'row', gap: space.xs },
  stepBar:    { flex: 1, height: 4, borderRadius: 2, backgroundColor: colors.border },

  // Two fields side by side that stack on a phone.
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  col: { flexGrow: 1, flexShrink: 1, flexBasis: 220, minWidth: 0 },

  passwordRow:     { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: space.sm },
  // Lines the buttons up with the input, below the field's label.
  passwordButtons: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, paddingTop: 21 },
  copyStatus:      { minHeight: 17, fontSize: fontSize.xs, fontWeight: '500', color: colors.textMuted },

  formActions: { flexDirection: 'row', justifyContent: 'flex-end' },
  nav:         { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'center', gap: space.sm },

  result:        { alignItems: 'center', gap: space.md, paddingVertical: space.xl },
  resultTitle:   { fontSize: fontSize.title, fontWeight: '700', color: colors.text, textAlign: 'center' },
  resultText:    { fontSize: fontSize.md, color: colors.text, textAlign: 'center', lineHeight: 20, maxWidth: 520 },
  resultNote:    { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19, maxWidth: 520 },
  resultActions: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'center', gap: space.sm },

  attest:          { gap: space.xs, minWidth: 0 },
  attestRow:       { flexDirection: 'row', alignItems: 'flex-start', gap: space.md, paddingVertical: space.xs, minWidth: 0 },
  checkbox:        {
    width: 22, height: 22, marginTop: 1, borderRadius: radius.sm, borderWidth: 2, borderColor: colors.borderInput,
    alignItems: 'center', justifyContent: 'center', backgroundColor: colors.surface, flexShrink: 0,
  },
  attestText:      { flex: 1, minWidth: 0, gap: 2 },
  attestLabel:     { fontSize: fontSize.md, fontWeight: '600', color: colors.text },
  required:        { color: colors.danger, fontWeight: '700' },
  attestNote:      { fontSize: fontSize.xs, color: colors.textMuted, lineHeight: 17 },
  attestError:     { flexDirection: 'row', alignItems: 'flex-start', gap: space.xs },
  attestErrorText: { flex: 1, minWidth: 0, fontSize: fontSize.xs, fontWeight: '500', color: colors.danger, lineHeight: 17 },

  credentials: {
    alignSelf: 'stretch', gap: space.sm, padding: space.md, minWidth: 0,
    borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, backgroundColor: colors.bg,
  },
  credentialsTitle: { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  credentialRow:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, minWidth: 0 },
  credentialLabel:  { width: 150, maxWidth: '100%', fontSize: fontSize.sm, color: colors.textMuted },
  credentialValue:  { flexShrink: 1, minWidth: 0, fontSize: fontSize.md, fontWeight: '600', color: colors.text },
});
