import { zodResolver } from '@hookform/resolvers/zod';
import { type ReactNode, useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import {
  accessibleName, ariaLevel, Banner, Button, Card, colors, DateField, fontSize, FormActions, FormError, Icon, InfoRow,
  type IoniconsName, LoadingView, Modal, NoAccessView, radius, ROLE_THEME, type RoleTheme, Screen, SectionCard,
  SelectField, space, STATUS_TONES, StatusBadge, statusLabel, statusTone, TagListField, TextField, useUiTheme,
} from '@/components/web/ui';
import { markDependentsStale, refreshDependents } from '@/components/web/sections/proxy-dependents';
import type { CaregiverDependent } from '@/hooks/use-caregiver-dependents';
import { caregiverCallsStatus, caregiverMessagingState, useCareTeamConversation } from '@/hooks/use-caregiver-messaging';
import { useAction } from '@/hooks/use-action';
import { useResource } from '@/hooks/use-resource';
import { applyFieldErrors } from '@/lib/api/form-errors';
import {
  accessLevelLabel, COMM_FLAGS, COMM_LABELS, keepableDataFlags, narrowingDraftOf, narrowingExpiryError,
  narrowingLevels, type NarrowingDraft, PERMISSION_OPTIONS, planNarrowing, toAccessLevel,
} from '@/lib/caregiver/access';
import {
  fetchDependentProfile, inviteDependent, narrowDependentAccess, updateDependentProfile,
} from '@/lib/caregiver/api';
import { CommunicationFields, PermissionFields } from '@/lib/caregiver/permission-fields';
import { effectivePermission, effectivePermissions, hasCaregiverPermission, isGrantActive } from '@/lib/caregiver/permissions';
import {
  HEALTH_FIELDS, healthChanges, healthDefaults, healthProfileSchema, type HealthProfileValues,
  PERSONAL_FIELDS, personalChanges, personalDefaults, personalDetailsSchema, type PersonalDetailsValues,
} from '@/lib/caregiver/profile';
import { EMAIL_MAX, inviteSchema, type InviteFormValues, LIST_ITEM_MAX, LIST_MAX_ITEMS, NAME_MAX, PHONE_MAX } from '@/lib/caregiver/schemas';
import type { BloodType, DependentProfile, Gender, NarrowAccessBody } from '@/lib/caregiver/types';
import type { SectionParamsInput } from '@/lib/nav/sections';
import { clinicToday, formatClinicDate } from '@/lib/time/clinic';
import { ageFromDateOnly, formatDateOnly } from '@/lib/ui/date-only';
import { MessageCareTeamButton } from './message-care-team-button';

// A loved one's profile as their caregiver sees it: who they are, their health profile, the caregiver's own
// access and, for an account the caregiver manages, the invitation to sign in.
//
// What the caregiver may do is decided from the grant with the shared permission rule (FULL_ACCESS grants every
// data permission, VIEW_ONLY blocks the "do" permissions, an ended grant gives nothing), never from raw flags:
//   - editing names, phone, date of birth and gender needs "manage profile and care plan";
//   - the health profile is shown and edited only with "view medical records";
//   - the caregiver can only reduce their own access, after confirming what they give up.

const GENDER_OPTIONS: { value: Gender; label: string }[] = [
  { value: 'MALE', label: 'Male' }, { value: 'FEMALE', label: 'Female' }, { value: 'OTHER', label: 'Other' },
];
const BLOOD_TYPE_OPTIONS: { value: BloodType; label: string }[] =
  (['A+', 'A-', 'B+', 'B-', 'O+', 'O-', 'AB+', 'AB-'] as BloodType[]).map(v => ({ value: v, label: v }));

type Editor = 'personal' | 'health' | 'access';

function initials(name: string): string {
  return name.split(/\s+/).filter(Boolean).map(w => w[0]).join('').slice(0, 2).toUpperCase() || '?';
}

function capitalize(value: string | null | undefined): string {
  const v = (value ?? '').trim();
  return v ? v.charAt(0).toUpperCase() + v.slice(1).toLowerCase() : '';
}

function genderLabel(value: string | null | undefined): string {
  return GENDER_OPTIONS.find(o => o.value === (value ?? '').toUpperCase())?.label ?? '';
}

// ─── Small display pieces ─────────────────────────────────────────────────────

function TagList({ items, empty }: { items: string[]; empty: string }) {
  if (items.length === 0) return <Text style={styles.none}>{empty}</Text>;
  return (
    <View style={styles.tags} role="list">
      {items.map(item => (
        <View key={item} style={styles.tag} role="listitem">
          <Text style={styles.tagText}>{item}</Text>
        </View>
      ))}
    </View>
  );
}

function PermissionTile({ icon, label, granted }: { icon: IoniconsName; label: string; granted: boolean }) {
  const tone = granted ? STATUS_TONES.success : STATUS_TONES.neutral;
  return (
    <View style={styles.tile} role="listitem" accessibilityLabel={`${label}: ${granted ? 'allowed' : 'not allowed'}`}>
      <Icon name={icon} size={18} color={colors.textMuted} />
      <Text style={styles.tileLabel}>{label}</Text>
      <View style={[styles.tileState, { backgroundColor: tone.bg, borderColor: tone.border }]}>
        <Icon name={granted ? 'checkmark-circle' : 'close-circle-outline'} size={14} color={tone.fg} />
        <Text style={[styles.tileStateText, { color: tone.fg }]}>{granted ? 'Allowed' : 'Not allowed'}</Text>
      </View>
    </View>
  );
}

type CommStatus = 'ALLOWED' | 'WAITING' | 'NOT_ALLOWED';

function CommRow({ label, status, firstName }: { label: string; status: CommStatus; firstName: string }) {
  const text = status === 'ALLOWED' ? 'Allowed' : status === 'WAITING' ? `Waiting for ${firstName} to approve` : 'Not allowed';
  const tone = status === 'ALLOWED' ? 'success' : status === 'WAITING' ? 'warning' : 'neutral';
  return (
    <InfoRow label={label}>
      <StatusBadge label={text} tone={tone} />
    </InfoRow>
  );
}

// ─── Personal details editor ──────────────────────────────────────────────────

function PersonalEditor({ patientSystemId, profile, contactPhone, onSaved, onCancel }: {
  patientSystemId: string;
  profile:      DependentProfile;
  /** A contact number kept for a managed dependent whose number is shared with another account. */
  contactPhone: string | null;
  onSaved:      (profile: DependentProfile | null) => void;
  onCancel:     () => void;
}) {
  const loaded = useMemo(() => personalDefaults(profile), [profile]);
  const schema = useMemo(() => personalDetailsSchema(loaded), [loaded]);
  const { control, handleSubmit, setError } = useForm<PersonalDetailsValues>({
    resolver: zodResolver(schema),
    defaultValues: loaded,
  });
  const save = useAction(
    (body: ReturnType<typeof personalChanges>) => updateDependentProfile(patientSystemId, body),
    { errorMessage: 'Could not save the personal details. Please try again.' },
  );
  const [formError, setFormError] = useState<string | null>(null);
  const today = clinicToday();

  const submit = handleSubmit(async values => {
    setFormError(null);
    const body = personalChanges(values, profile);
    if (Object.keys(body).length === 0) { onSaved(null); return; }
    const result = await save.run(body);
    if (result.ok) onSaved(result.data);
    else if (result.error) setFormError(applyFieldErrors(result.error, setError, PERSONAL_FIELDS));
  });

  const phoneHint = loaded.phone
    ? 'A phone number cannot be removed, only changed.'
    : contactPhone
      ? `The contact number ${contactPhone} stays on file. Enter a number only if they have one of their own.`
      : 'Optional.';

  return (
    <View style={styles.editor}>
      <View style={styles.row}>
        <View style={styles.col}>
          <TextField label="First name" control={control} name="firstName" maxLength={NAME_MAX} autoComplete="off" required />
        </View>
        <View style={styles.col}>
          <TextField label="Last name" control={control} name="lastName" maxLength={NAME_MAX} autoComplete="off" required />
        </View>
      </View>
      <TextField
        label="Phone number"
        control={control}
        name="phone"
        placeholder="+263 77 123 4567"
        inputMode="tel"
        autoComplete="off"
        maxLength={PHONE_MAX}
        hint={phoneHint}
      />
      <View style={styles.row}>
        <View style={styles.col}>
          <DateField
            label="Date of birth"
            control={control}
            name="dateOfBirth"
            max={today}
            hint={loaded.dateOfBirth ? undefined : 'Leave empty if you do not know it.'}
          />
        </View>
        <View style={styles.col}>
          <SelectField<Gender, PersonalDetailsValues>
            label="Gender" control={control} name="gender" options={GENDER_OPTIONS} placeholder="Not known" allowEmpty
          />
        </View>
      </View>
      <Text style={styles.note}>
        The sign-in email cannot be changed here. The account holder changes it from their own security settings.
      </Text>
      <FormError message={formError} />
      <FormActions
        submitLabel="Save changes"
        onSubmit={submit}
        onCancel={onCancel}
        submitting={save.pending}
      />
    </View>
  );
}

// ─── Health profile editor ────────────────────────────────────────────────────

function HealthEditor({ patientSystemId, profile, onSaved, onCancel }: {
  patientSystemId: string;
  profile:  DependentProfile;
  onSaved:  (profile: DependentProfile | null) => void;
  onCancel: () => void;
}) {
  const loaded = useMemo(() => healthDefaults(profile), [profile]);
  const { control, handleSubmit, setError } = useForm<HealthProfileValues>({
    resolver: zodResolver(healthProfileSchema),
    defaultValues: loaded,
  });
  const save = useAction(
    (body: ReturnType<typeof healthChanges>) => updateDependentProfile(patientSystemId, body),
    { errorMessage: 'Could not save the health profile. Please try again.' },
  );
  const [formError, setFormError] = useState<string | null>(null);

  const submit = handleSubmit(async values => {
    setFormError(null);
    const body = healthChanges(values, profile);
    if (Object.keys(body).length === 0) { onSaved(null); return; }
    const result = await save.run(body);
    if (result.ok) onSaved(result.data);
    else if (result.error) setFormError(applyFieldErrors(result.error, setError, HEALTH_FIELDS));
  });

  return (
    <View style={styles.editor}>
      <SelectField<BloodType, HealthProfileValues>
        label="Blood type"
        control={control}
        name="bloodType"
        options={BLOOD_TYPE_OPTIONS}
        placeholder="Not known"
        allowEmpty
        hint="Choose Not known rather than guessing: doctors rely on this value."
      />
      <TagListField
        label="Known allergies"
        control={control}
        name="allergies"
        placeholder="For example penicillin"
        maxItems={LIST_MAX_ITEMS}
        maxLength={LIST_ITEM_MAX}
        hint="Add one at a time."
      />
      <TagListField
        label="Chronic conditions"
        control={control}
        name="chronicConditions"
        placeholder="For example hypertension"
        maxItems={LIST_MAX_ITEMS}
        maxLength={LIST_ITEM_MAX}
        hint="Add one at a time."
      />
      <Text style={styles.note}>Health status is set by their doctor and cannot be changed here.</Text>
      <FormError message={formError} />
      <FormActions submitLabel="Save changes" onSubmit={submit} onCancel={onCancel} submitting={save.pending} />
    </View>
  );
}

// ─── Reducing the caregiver's own access ──────────────────────────────────────

function AccessEditor({ dependent, firstName, onSaved, onCancel }: {
  dependent: CaregiverDependent;
  firstName: string;
  onSaved:   (dependent: CaregiverDependent | null) => void;
  onCancel:  () => void;
}) {
  const [draft, setDraft] = useState<NarrowingDraft>(() => narrowingDraftOf(dependent));
  const [confirming, setConfirming] = useState(false);
  const [showErrors, setShowErrors] = useState(false);
  const save = useAction(
    (body: NarrowAccessBody) => narrowDependentAccess(dependent.accessId, body),
    { errorMessage: 'Could not change your access. Please try again.' },
  );

  const currentLevel = toAccessLevel(dependent.accessLevel);
  const today = clinicToday();
  const currentDay = (dependent.expiresAt ?? '').slice(0, 10);
  const expiryError = narrowingExpiryError(dependent.expiresAt, draft.expiresOn, today, firstName);
  const plan = planNarrowing(dependent, draft);
  const held = keepableDataFlags(dependent);

  const review = () => {
    setShowErrors(true);
    if (expiryError) return;
    if (!plan.changed) { onSaved(null); return; }
    save.reset();
    setConfirming(true);
  };

  const confirm = async () => {
    const result = await save.run(plan.body);
    if (result.ok) { setConfirming(false); onSaved(result.data); }
  };

  return (
    <View style={styles.editor}>
      <Banner
        tone="warning"
        text={`You can only reduce your own access here. Only ${firstName} can give back anything you remove.`}
      />
      <PermissionFields
        level={draft.level}
        flags={draft.flags}
        levels={narrowingLevels(currentLevel)}
        held={held}
        grantorName={firstName}
        onLevelChange={level => setDraft(d => ({ ...d, level }))}
        onFlagChange={(flag, value) => setDraft(d => ({ ...d, flags: { ...d.flags, [flag]: value } }))}
        disabled={save.pending}
      />
      <CommunicationFields
        value={draft.comm}
        held={{ canMessageCareTeam: dependent.canMessageCareTeam, canJoinCalls: dependent.canJoinCalls }}
        onChange={(flag, value) => setDraft(d => ({ ...d, comm: { ...d.comm, [flag]: value } }))}
        note={`You can switch these off. Only ${firstName} can allow messaging or video calls.`}
        disabled={save.pending}
      />
      <DateField
        label="Access end date"
        value={draft.expiresOn}
        onChange={expiresOn => setDraft(d => ({ ...d, expiresOn }))}
        min={today}
        max={currentDay || undefined}
        error={showErrors ? expiryError : null}
        hint={currentDay
          ? `Your access ends at the end of ${formatDateOnly(currentDay)}. You can choose an earlier day.`
          : 'Your access has no end date. Choose a day to end it then.'}
      />
      <FormActions
        submitLabel="Review changes"
        onSubmit={review}
        onCancel={onCancel}
        submitting={save.pending}
      />

      <Modal
        open={confirming}
        title="Reduce your access?"
        onClose={() => setConfirming(false)}
        size="sm"
        busy={save.pending}
        dismissOnBackdrop={false}
        footer={(
          <FormActions
            submitLabel="Reduce my access"
            destructive
            onSubmit={() => { void confirm(); }}
            onCancel={() => setConfirming(false)}
            cancelLabel="Go back"
            submitting={save.pending}
          />
        )}
      >
        <Text style={styles.body}>{`After saving, for ${firstName}:`}</Text>
        <View role="list" style={styles.lostList}>
          {plan.removed.map(line => (
            <View key={line} role="listitem" style={styles.lostItem}>
              <Icon name="remove-circle-outline" size={16} color={STATUS_TONES.warning.fg} />
              <Text style={styles.lostText}>{line}</Text>
            </View>
          ))}
        </View>
        <Text style={styles.body}>
          {`You cannot undo this yourself. Only ${firstName} can give back what you remove.`}
        </Text>
        <FormError message={save.error} />
      </Modal>
    </View>
  );
}

// ─── Invitation to sign in ────────────────────────────────────────────────────

function InviteDialog({ dependent, firstName, open, onClose, onInvited }: {
  dependent: CaregiverDependent;
  firstName: string;
  open:      boolean;
  onClose:   () => void;
  onInvited: (dependent: CaregiverDependent, email: string) => void;
}) {
  const { control, handleSubmit, setError, reset } = useForm<InviteFormValues>({
    resolver: zodResolver(inviteSchema),
    defaultValues: { email: dependent.patientEmail ?? '' },
  });
  const invite = useAction(
    (email: string) => inviteDependent(dependent.accessId, email),
    { errorMessage: 'Could not send the invitation. Please try again.' },
  );
  const [formError, setFormError] = useState<string | null>(null);

  useEffect(() => {
    if (open) { reset({ email: dependent.patientEmail ?? '' }); setFormError(null); invite.reset(); }
    // Reset only when the dialog opens.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const submit = handleSubmit(async values => {
    setFormError(null);
    const email = values.email.trim().toLowerCase();
    const result = await invite.run(email);
    if (result.ok) { onInvited(result.data, email); return; }
    const error = result.error;
    if (!error) return;
    if (error.status === 409 && /email/i.test(error.message)) {
      setError('email', { type: 'server', message: error.message }, { shouldFocus: true });
    } else {
      setFormError(applyFieldErrors(error, setError, ['email']));
    }
  });

  const again = !!dependent.patientEmail;
  return (
    <Modal
      open={open}
      title={again ? 'Send the invitation again' : 'Invite to sign in'}
      onClose={onClose}
      size="sm"
      busy={invite.pending}
      dismissOnBackdrop={false}
      footer={(
        <FormActions
          submitLabel="Send invitation"
          submitIcon="mail-outline"
          onSubmit={submit}
          onCancel={onClose}
          submitting={invite.pending}
        />
      )}
    >
      <View style={styles.editor}>
        <Text style={styles.body}>
          {`We email ${firstName} a link to choose a password. Until they do, you keep managing the account. The link works for a limited time: if it expires, send the invitation again.`}
        </Text>
        <TextField
          label={`${firstName}'s email address`}
          control={control}
          name="email"
          placeholder="name@example.com"
          inputMode="email"
          autoComplete="off"
          autoCapitalize="none"
          maxLength={EMAIL_MAX}
          hint="An address only they use. It becomes their sign-in email."
          required
          onSubmit={submit}
        />
        <FormError message={formError} />
      </View>
    </Modal>
  );
}

// ─── Screen ───────────────────────────────────────────────────────────────────

/** A shortcut to a dashboard section opened on this loved one. */
interface QuickLink {
  /** Unique within the list (several shortcuts open Documents & Results on different tabs). */
  id:      string;
  /** The section key. */
  key:     string;
  label:   string;
  icon:    IoniconsName;
  allowed: boolean;
  params:  SectionParamsInput;
}

export interface PatientProxyWebProfileProps {
  dependent:     CaregiverDependent;
  onBack:        () => void;
  /**
   * Opens another section of the caregiver dashboard. The shortcuts pass the loved one in the `patient` param
   * (and a `tab` where one applies), so the section opens on this person only.
   */
  onNavigate?:   (key: string, params?: SectionParamsInput) => void;
  /** Switches the dashboard to Messages on this thread (used by "Message Care Team"). */
  onOpenThread?: (threadId: number) => void;
  /** The loved one's care team panel (read-only for a caregiver), shown after the access card. */
  careTeamSlot?: ReactNode;
  theme?:        RoleTheme;
}

export default function PatientProxyWebProfile({
  dependent: initialDependent, onBack, onNavigate, onOpenThread, careTeamSlot, theme,
}: PatientProxyWebProfileProps) {
  // Local copy so a change saved here (access reduced, invitation sent) shows at once.
  const [dependent, setDependent] = useState<CaregiverDependent>(initialDependent);
  useEffect(() => { setDependent(initialDependent); }, [initialDependent]);

  const patientId = dependent.patientSystemId;
  const profile = useResource<DependentProfile>(
    () => fetchDependentProfile(patientId),
    [patientId],
    { errorMessage: 'Could not load the full profile.' },
  );
  const careTeam = useCareTeamConversation(onOpenThread);

  const [editing, setEditing] = useState<Editor | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [inviting, setInviting] = useState(false);

  const data = profile.data;
  const canEditProfile = hasCaregiverPermission(dependent, 'MANAGE_PROFILE');
  const canSeeRecords = effectivePermission(dependent, 'canViewRecords');
  const effective = effectivePermissions(dependent);

  const name = (data ? `${data.firstName ?? ''} ${data.lastName ?? ''}` : dependent.patientName ?? '').trim() || 'Your loved one';
  const firstName = (data?.firstName ?? dependent.patientName ?? '').trim().split(/\s+/)[0] || 'the patient';
  const status = canSeeRecords ? (data?.healthStatus ?? dependent.patientHealthStatus ?? null) : null;
  const dob = data?.dateOfBirth ?? dependent.patientDateOfBirth;
  const gender = data?.gender ?? dependent.patientGender;
  const bloodType = canSeeRecords ? (data?.bloodType ?? dependent.patientBloodType) : null;
  const email = data?.email ?? dependent.patientEmail;
  const phone = data?.phone ?? dependent.patientPhone;
  const age = ageFromDateOnly(dob);
  const memberSince = data?.createdAt ? data.createdAt.slice(0, 4) : null;

  const openEditor = (editor: Editor) => { setNotice(null); setEditing(editor); };

  const profileSaved = (updated: DependentProfile | null, what: string) => {
    setEditing(null);
    if (!updated) { setNotice('Nothing was changed.'); return; }
    profile.setData(updated);
    setNotice(`${what} saved.`);
    const newName = `${updated.firstName ?? ''} ${updated.lastName ?? ''}`.trim();
    if (newName && newName !== (dependent.patientName ?? '').trim()) {
      setDependent(d => ({ ...d, patientName: newName }));
      // The sidebar lists loved ones by name.
      refreshDependents();
    }
  };

  const accessSaved = (updated: CaregiverDependent | null) => {
    setEditing(null);
    if (!updated) { setNotice('Nothing was changed.'); return; }
    setDependent(d => ({ ...d, ...updated }));
    setNotice('Your access was reduced.');
    refreshDependents();
  };

  const invited = (updated: CaregiverDependent, address: string) => {
    setInviting(false);
    setDependent(d => ({ ...d, ...updated }));
    setNotice(`Invitation sent to ${address}. ${firstName} can sign in after choosing a password from the link.`);
    markDependentsStale();
  };

  const messaging = caregiverMessagingState(dependent).status;
  const calls = caregiverCallsStatus(dependent);
  const authorizedBy = dependent.authorizedBySystemId === dependent.caregiverSystemId
    ? 'You'
    : dependent.authorizedBySystemId === dependent.patientSystemId
      ? `${firstName} (the patient)`
      : dependent.authorizedBySystemId;

  // Shortcuts to the dashboard sections, each opened on this loved one only. A shortcut is offered only when the
  // grant covers what the section shows (shared permission rule); the server checks every request again.
  const patient = dependent.patientSystemId;
  const grantActive = isGrantActive(dependent);
  const canBook = effectivePermission(dependent, 'canScheduleAppointments');
  const quickLinks: QuickLink[] = [
    { id: 'appointments',  key: 'appointments',      label: 'View appointments', icon: 'calendar-outline',         allowed: effective.canViewAppointments, params: { patient } },
    { id: 'medications',   key: 'medications',       label: 'View medications',  icon: 'medkit-outline',           allowed: effective.canViewMedications,  params: { patient } },
    { id: 'reminders',     key: 'medications',       label: 'Reminders',         icon: 'alarm-outline',            allowed: grantActive,                   params: { patient, tab: 'reminders' } },
    { id: 'labs',          key: 'documents-results', label: 'Lab results',       icon: 'flask-outline',            allowed: effective.canViewLabResults,   params: { patient, tab: 'labs' } },
    { id: 'imaging',       key: 'documents-results', label: 'Imaging',           icon: 'scan-outline',             allowed: effective.canViewImaging,      params: { patient, tab: 'imaging' } },
    { id: 'records',       key: 'documents-results', label: 'Medical records',   icon: 'document-text-outline',    allowed: effective.canViewRecords,      params: { patient, tab: 'records' } },
    { id: 'consultations', key: 'documents-results', label: 'Consultations',     icon: 'chatbubbles-outline',      allowed: effective.canViewConsultations, params: { patient, tab: 'consultations' } },
    { id: 'documents',     key: 'documents-results', label: 'Documents',         icon: 'folder-open-outline',      allowed: effective.canViewRecords || effective.canManageCarePlan, params: { patient, tab: 'documents' } },
    { id: 'referrals',     key: 'documents-results', label: 'Referrals',         icon: 'git-branch-outline',       allowed: effective.canViewRecords,      params: { patient, tab: 'referrals' } },
    { id: 'health',        key: 'health-summary',    label: 'Health summary',    icon: 'heart-outline',            allowed: grantActive,                   params: { patient } },
    { id: 'care-plans',    key: 'care-plans',        label: 'Care plans',        icon: 'clipboard-outline',        allowed: hasCaregiverPermission(dependent, 'CARE_PLANS'), params: { patient } },
    { id: 'alerts',        key: 'health-alerts',     label: 'Health alerts',     icon: 'shield-checkmark-outline', allowed: effective.canViewRecords,      params: { patient } },
    { id: 'billing',       key: 'billing',           label: 'Billing',           icon: 'card-outline',             allowed: effective.canViewBilling,      params: { patient } },
  ];
  const links = onNavigate ? quickLinks.filter(l => l.allowed) : [];
  const bookAppointment = onNavigate && canBook
    ? () => onNavigate('appointments', { patient, mode: 'book' })
    : undefined;

  const editButton = (editor: Editor, label: string, accessibilityLabel: string) => (
    editing === null && data
      ? <Button label={label} icon="create-outline" variant="secondary" size="sm" accessibilityLabel={accessibilityLabel} onPress={() => openEditor(editor)} />
      : null
  );

  return (
    <Screen
      title="Patient Profile"
      subtitle={`What you can see and do for ${name}`}
      theme={theme ?? ROLE_THEME.proxy}
      actions={<Button label="Back to Dashboard" icon="arrow-back-outline" variant="secondary" onPress={onBack} />}
    >
      <View style={styles.page}>
        {notice ? <Banner tone="success" text={notice} onDismiss={() => setNotice(null)} /> : null}
        {profile.error ? (
          <Banner
            tone="danger"
            text={profile.errorStatus === 403
              ? 'Your access does not allow reading this profile any more. Return to the dashboard to see your loved ones.'
              : `${profile.error} Only the summary from your access is shown, and editing is unavailable.`}
            action={profile.errorStatus === 403 ? undefined : { label: 'Retry', onPress: profile.refetch }}
          />
        ) : null}

        {/* ── Summary ── */}
        <HeroCard
          name={name}
          relationship={dependent.relationship}
          email={email}
          phone={phone}
          status={status}
          canSeeRecords={canSeeRecords}
          managed={dependent.managed}
          loading={profile.loading}
        />

        <Card style={styles.overview}>
          {[
            { label: 'Age',          value: age !== null ? `${age} ${age === 1 ? 'year' : 'years'}` : 'Not known' },
            { label: 'Gender',       value: genderLabel(gender) || 'Not known' },
            { label: 'Blood type',   value: canSeeRecords ? (bloodType || 'Not known') : 'Not shared' },
            { label: 'Member since', value: memberSince ?? 'Not known' },
          ].map(tile => (
            <View key={tile.label} style={styles.overviewTile}>
              <Text style={styles.overviewLabel}>{tile.label}</Text>
              <Text style={styles.overviewValue}>{tile.value}</Text>
            </View>
          ))}
        </Card>

        {/* ── Signing in (managed dependents) ── */}
        {dependent.managed ? (
          <SectionCard
            title="Signing in"
            subtitle={`${firstName} cannot sign in yet`}
            action={dependent.canInvite ? (
              <Button
                label={dependent.patientEmail ? 'Send invitation again' : 'Invite to sign in'}
                icon="mail-outline"
                size="sm"
                accessibilityLabel={`${dependent.patientEmail ? 'Send invitation again' : 'Invite to sign in'} for ${name}`}
                onPress={() => { setNotice(null); setInviting(true); }}
              />
            ) : null}
          >
            <Text style={styles.body}>
              {dependent.patientEmail
                ? `An invitation was sent to ${dependent.patientEmail}. ${firstName} can sign in once they choose a password from the link. Until then you manage the account.`
                : `This account has no sign-in details. You manage it for ${firstName}. When they are ready to manage their own care, invite them to sign in with their own email address.`}
            </Text>
            {!dependent.canInvite ? (
              <Text style={styles.note}>Only the caregiver who created this account can invite them to sign in.</Text>
            ) : null}
          </SectionCard>
        ) : null}

        {/* ── Personal details ── */}
        <SectionCard
          title="Personal Details"
          action={canEditProfile ? editButton('personal', 'Edit', 'Edit personal details') : null}
        >
          {editing === 'personal' && data ? (
            <PersonalEditor
              patientSystemId={patientId}
              profile={data}
              contactPhone={data.phone ? null : dependent.patientPhone}
              onSaved={updated => profileSaved(updated, 'Personal details')}
              onCancel={() => setEditing(null)}
            />
          ) : (
            <View>
              <InfoRow label="Full name" value={name} />
              <InfoRow label="Date of birth" value={dob ? formatDateOnly(dob, undefined, '') : null} emptyText="Not known" />
              <InfoRow label="Gender" value={genderLabel(gender)} emptyText="Not known" />
              <InfoRow label="Email" value={email} emptyText={dependent.managed ? 'No sign-in email yet' : 'Not provided'} />
              <InfoRow label="Phone" value={phone} />
              {!canEditProfile ? (
                <Text style={styles.note}>
                  {`Your access does not include managing ${firstName}'s profile, so these details are read-only.`}
                </Text>
              ) : null}
            </View>
          )}
        </SectionCard>

        {/* ── Health profile ── */}
        <SectionCard
          title="Health Profile"
          action={canSeeRecords && canEditProfile ? editButton('health', 'Edit', 'Edit health profile') : null}
        >
          {!canSeeRecords ? (
            <NoAccessView
              compact
              message="Health details are not shared with you"
              sub={`Blood type, allergies, conditions and health status need "View medical records". Only ${firstName} can add it to your access.`}
            />
          ) : editing === 'health' && data ? (
            <HealthEditor
              patientSystemId={patientId}
              profile={data}
              onSaved={updated => profileSaved(updated, 'Health profile')}
              onCancel={() => setEditing(null)}
            />
          ) : profile.loading ? (
            <LoadingView compact label="Loading health profile" />
          ) : (
            <View style={styles.health}>
              <InfoRow label="Health status">
                {status
                  ? <StatusBadge label={statusLabel(status)} tone={statusTone('health', status)} />
                  : <Text style={styles.none}>Not known</Text>}
              </InfoRow>
              <Text style={styles.note}>Health status is set by their doctor.</Text>
              <InfoRow label="Blood type" value={bloodType} emptyText="Not known" />
              <Text style={styles.subLabel} accessibilityRole="header" {...ariaLevel(3)}>Known allergies</Text>
              <TagList items={data ? healthDefaults(data).allergies : []} empty={data ? 'None recorded' : 'Not loaded'} />
              <Text style={styles.subLabel} accessibilityRole="header" {...ariaLevel(3)}>Chronic conditions</Text>
              <TagList items={data ? healthDefaults(data).chronicConditions : []} empty={data ? 'None recorded' : 'Not loaded'} />
            </View>
          )}
        </SectionCard>

        {/* ── The caregiver's own access ── */}
        <SectionCard
          title="Your Access"
          action={editing === null
            ? <Button label="Reduce access" icon="remove-circle-outline" variant="secondary" size="sm" accessibilityLabel={accessibleName('Reduce access', `Reduce access to ${name}`)} onPress={() => openEditor('access')} />
            : null}
        >
          {editing === 'access' ? (
            <AccessEditor
              dependent={dependent}
              firstName={firstName}
              onSaved={accessSaved}
              onCancel={() => setEditing(null)}
            />
          ) : (
            <View style={styles.access}>
              <View>
                <InfoRow label="Relationship" value={capitalize(dependent.relationship)} />
                <InfoRow label="Access level" value={accessLevelLabel(dependent.accessLevel)} />
                <InfoRow label="Given on" value={formatClinicDate(dependent.authorizedDate)} />
                <InfoRow label="Given by" value={authorizedBy} />
                <InfoRow label="Ends" value={dependent.expiresAt ? formatClinicDate(dependent.expiresAt) : 'No end date'} />
                {dependent.guardianAttestedAt ? (
                  <InfoRow
                    label="Guardian confirmation"
                    value={`You confirmed you are ${firstName}'s parent or legal guardian on ${formatClinicDate(dependent.guardianAttestedAt)}`}
                  />
                ) : null}
              </View>

              <Text style={styles.subLabel} accessibilityRole="header" {...ariaLevel(3)}>What you can see and do</Text>
              <View style={styles.tiles} role="list">
                {PERMISSION_OPTIONS.map(option => (
                  <PermissionTile key={option.flag} icon={option.icon} label={option.short} granted={effective[option.flag]} />
                ))}
              </View>

              <Text style={styles.subLabel} accessibilityRole="header" {...ariaLevel(3)}>Messaging and video calls</Text>
              <View>
                <CommRow label={COMM_LABELS.canMessageCareTeam} status={messaging} firstName={firstName} />
                <CommRow label={COMM_LABELS.canJoinCalls} status={calls} firstName={firstName} />
              </View>
              {COMM_FLAGS.some(flag => !dependent[flag]) ? (
                <Text style={styles.note}>{`Only ${firstName} can allow messaging or video calls.`}</Text>
              ) : null}
            </View>
          )}
        </SectionCard>

        {careTeamSlot ?? null}

        {/* ── Shortcuts ── */}
        {onOpenThread || links.length > 0 || bookAppointment ? (
          <SectionCard
            title="Quick Actions"
            action={bookAppointment ? (
              <Button
                label="Book appointment"
                icon="calendar-number-outline"
                size="sm"
                accessibilityLabel={`Book appointment for ${name}`}
                onPress={bookAppointment}
              />
            ) : undefined}
          >
            {onOpenThread ? (
              <MessageCareTeamButton
                dependent={dependent}
                onPress={careTeam.open}
                busy={careTeam.openingFor === dependent.accessId}
                error={careTeam.error}
              />
            ) : null}
            {links.length > 0 ? (
              <View style={styles.links} role="list" aria-label={`Shortcuts for ${name}`}>
                {links.map(link => (
                  <View key={link.id} role="listitem">
                    <Button
                      label={link.label}
                      icon={link.icon}
                      variant="secondary"
                      size="sm"
                      accessibilityLabel={`${link.label}, ${name}`}
                      onPress={() => onNavigate?.(link.key, link.params)}
                    />
                  </View>
                ))}
              </View>
            ) : null}
          </SectionCard>
        ) : null}
      </View>

      {dependent.canInvite ? (
        <InviteDialog
          dependent={dependent}
          firstName={firstName}
          open={inviting}
          onClose={() => setInviting(false)}
          onInvited={invited}
        />
      ) : null}
    </Screen>
  );
}

function HeroCard({ name, relationship, email, phone, status, canSeeRecords, managed, loading }: {
  name:          string;
  relationship:  string;
  email:         string | null | undefined;
  phone:         string | null | undefined;
  status:        string | null;
  canSeeRecords: boolean;
  managed:       boolean;
  loading:       boolean;
}) {
  const { accent, accentBg } = useUiTheme();
  return (
    <Card style={styles.hero}>
      <View style={[styles.avatar, { backgroundColor: accentBg }]} aria-hidden importantForAccessibility="no-hide-descendants">
        <Text style={[styles.avatarText, { color: accent }]}>{initials(name)}</Text>
      </View>
      <View style={styles.heroInfo}>
        <Text style={styles.heroName} accessibilityRole="header" {...ariaLevel(2)}>{name}</Text>
        {relationship ? <Text style={[styles.heroRel, { color: accent }]}>{capitalize(relationship)}</Text> : null}
        <View style={styles.heroBadges}>
          {canSeeRecords
            ? status
              ? <StatusBadge label={`Health: ${statusLabel(status)}`} tone={statusTone('health', status)} />
              : null
            : <StatusBadge label="Health status not shared" tone="neutral" icon="lock-closed-outline" />}
          {managed ? <StatusBadge label="Managed by you" tone="info" icon="person-circle-outline" /> : null}
        </View>
        {loading ? <LoadingView compact label="Loading profile" /> : (
          <View style={styles.heroMeta}>
            {email ? (
              <View style={styles.metaRow}>
                <Icon name="mail-outline" size={14} color={colors.textMuted} />
                <Text style={styles.metaText}>{email}</Text>
              </View>
            ) : null}
            {phone ? (
              <View style={styles.metaRow}>
                <Icon name="call-outline" size={14} color={colors.textMuted} />
                <Text style={styles.metaText}>{phone}</Text>
              </View>
            ) : null}
          </View>
        )}
      </View>
    </Card>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  page:   { width: '100%', maxWidth: 860, gap: space.lg, minWidth: 0 },
  editor: { gap: space.md, minWidth: 0 },
  row:    { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  col:    { flexGrow: 1, flexShrink: 1, flexBasis: 220, minWidth: 0 },

  body:     { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  note:     { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  none:     { fontSize: fontSize.md, color: colors.textSubtle },
  subLabel: { fontSize: fontSize.sm, fontWeight: '700', color: colors.text, marginTop: space.sm },

  hero:       { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: space.lg },
  avatar:     { width: 72, height: 72, borderRadius: 36, alignItems: 'center', justifyContent: 'center', flexShrink: 0 },
  avatarText: { fontSize: 24, fontWeight: '700' },
  heroInfo:   { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0, gap: space.xs },
  heroName:   { fontSize: fontSize.xl, fontWeight: '700', color: colors.text },
  heroRel:    { fontSize: fontSize.md, fontWeight: '600' },
  heroBadges: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, marginTop: space.xs },
  heroMeta:   { gap: space.xs, marginTop: space.xs },
  metaRow:    { flexDirection: 'row', alignItems: 'center', gap: space.xs, minWidth: 0 },
  metaText:   { flexShrink: 1, fontSize: fontSize.sm, color: colors.textMuted },

  overview:      { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  overviewTile:  { flexGrow: 1, flexBasis: 120, minWidth: 0, gap: 2, alignItems: 'center' },
  overviewLabel: { fontSize: fontSize.xs, fontWeight: '600', color: colors.textMuted },
  overviewValue: { fontSize: fontSize.lg, fontWeight: '700', color: colors.text, textAlign: 'center' },

  health: { gap: space.xs },
  tags:    { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  tag:     { paddingHorizontal: space.md, paddingVertical: space.xs, borderRadius: radius.pill, backgroundColor: colors.bgSubtle, borderWidth: 1, borderColor: colors.border },
  tagText: { fontSize: fontSize.sm, fontWeight: '600', color: colors.text },

  access: { gap: space.sm },
  tiles:  { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  tile:   {
    flexGrow: 1, flexBasis: 150, minWidth: 0, gap: space.xs, padding: space.md,
    borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, backgroundColor: colors.bg,
  },
  tileLabel:     { fontSize: fontSize.sm, fontWeight: '600', color: colors.text },
  tileState:     { flexDirection: 'row', alignItems: 'center', alignSelf: 'flex-start', gap: 4, paddingHorizontal: space.sm, paddingVertical: 2, borderRadius: radius.pill, borderWidth: 1 },
  tileStateText: { fontSize: fontSize.xs, fontWeight: '700' },

  lostList: { gap: space.xs, marginVertical: space.sm },
  lostItem: { flexDirection: 'row', alignItems: 'flex-start', gap: space.sm },
  lostText: { flex: 1, minWidth: 0, fontSize: fontSize.md, color: colors.text, lineHeight: 20 },

  links: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
});
