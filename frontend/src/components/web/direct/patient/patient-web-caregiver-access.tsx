import { Ionicons } from '@expo/vector-icons';
import { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import type { AccessLevel } from '@/hooks/use-caregiver-dependents';
import {
  caregiverAccessErrorMessage,
  grantCaregiverAccess,
  revokeCaregiverAccess,
  updateCaregiverAccess,
  useCaregiverAccess,
  type CaregiverAccess,
  type UpdateCaregiverAccessBody,
} from '@/hooks/use-caregiver-access';
import { confirmAction } from '@/lib/ui/dialogs';

const TEAL    = '#0F766E';
const TEAL_L  = '#E6F4F1';
const GREEN   = '#047857';
const GREEN_L = '#ECFDF5';
const AMBER   = '#B45309';
const AMBER_L = '#FFFBEB';
const RED     = '#B91C1C';
const RED_L   = '#FEF2F2';
const GRAY    = '#5B6472';
const GRAY2   = '#9CA3AF';
const TEXT    = '#111827';
const BORDER  = '#E5E7EB';
const WHITE   = '#FFFFFF';
const BG      = '#F9FAFB';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

const INTRO =
  'Caregivers can message your care team or join your video calls only after you approve. ' +
  'Removing access also removes them from conversations and from any call in progress.';

type DataFlag =
  | 'canViewRecords' | 'canViewAppointments' | 'canViewMedications' | 'canViewLabResults'
  | 'canViewImaging' | 'canViewConsultations' | 'canScheduleAppointments' | 'canManageCarePlan';

const DATA_PERMS: { key: DataFlag; label: string }[] = [
  { key: 'canViewRecords',          label: 'View medical records' },
  { key: 'canViewAppointments',     label: 'View appointments'    },
  { key: 'canViewMedications',      label: 'View medications'     },
  { key: 'canViewLabResults',       label: 'View lab results'     },
  { key: 'canViewImaging',          label: 'View imaging results' },
  { key: 'canViewConsultations',    label: 'View consultations'   },
  { key: 'canScheduleAppointments', label: 'Book appointments'    },
  { key: 'canManageCarePlan',       label: 'Manage care plan'     },
];

const LEVELS: { value: AccessLevel; label: string; sub: string }[] = [
  { value: 'VIEW_ONLY',   label: 'View only',   sub: 'Can see what you choose, no actions'  },
  { value: 'FULL_ACCESS', label: 'Full access', sub: 'All records and booking'              },
  { value: 'EDIT_ONLY',   label: 'Edit',        sub: 'Can see and act on what you choose'   },
  { value: 'CUSTOM',      label: 'Custom',      sub: 'Choose each permission'               },
];

type DataPerms = Record<DataFlag, boolean>;

const EMPTY_PERMS: DataPerms = {
  canViewRecords: false, canViewAppointments: false, canViewMedications: false, canViewLabResults: false,
  canViewImaging: false, canViewConsultations: false, canScheduleAppointments: false, canManageCarePlan: false,
};

// ─── Helpers ──────────────────────────────────────────────────────────────────

function levelLabel(level: string) {
  return LEVELS.find(l => l.value === level)?.label ?? level;
}

function formatDate(value: string | null | undefined) {
  if (!value) return null;
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return null;
  return new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric' }).format(d);
}

function isValidDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const d = new Date(`${value}T00:00:00`);
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === value;
}

function isFutureDate(value: string) {
  return new Date(`${value}T23:59:59`).getTime() > Date.now();
}

function expiryError(value: string): string | null {
  const v = value.trim();
  if (!v) return null;
  if (!isValidDate(v)) return 'Use the format YYYY-MM-DD.';
  if (!isFutureDate(v)) return 'Access expiry must be in the future.';
  return null;
}

function dataPermsOf(grant: CaregiverAccess): DataPerms {
  const out = { ...EMPTY_PERMS };
  DATA_PERMS.forEach(p => { out[p.key] = !!grant[p.key]; });
  return out;
}

/** Offline state for the banner. Guarded for non-browser environments. */
function useOnline(): boolean {
  const [online, setOnline] = useState(() =>
    typeof navigator === 'undefined' || navigator.onLine !== false);
  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.addEventListener !== 'function') return;
    const on  = () => setOnline(true);
    const off = () => setOnline(false);
    window.addEventListener('online', on);
    window.addEventListener('offline', off);
    return () => {
      window.removeEventListener('online', on);
      window.removeEventListener('offline', off);
    };
  }, []);
  return online;
}

// ─── Small components ─────────────────────────────────────────────────────────

function Toggle({ label, sub, value, onChange, disabled }: {
  label: string; sub?: string; value: boolean; onChange: (v: boolean) => void; disabled?: boolean;
}) {
  return (
    <TouchableOpacity
      style={[styles.toggleRow, disabled && styles.disabled]}
      onPress={() => { if (!disabled) onChange(!value); }}
      disabled={disabled}
      activeOpacity={0.7}
      accessibilityRole="switch"
      accessibilityLabel={label}
      accessibilityState={{ checked: value, disabled: !!disabled }}
      aria-checked={value}
    >
      <View style={{ flex: 1, gap: 2 }}>
        <Text style={styles.toggleLabel}>{label}</Text>
        {sub ? <Text style={styles.toggleSub}>{sub}</Text> : null}
      </View>
      <View style={[styles.toggle, value && styles.toggleOn]}>
        <View style={[styles.toggleThumb, value && styles.toggleThumbOn]} />
      </View>
    </TouchableOpacity>
  );
}

function Badge({ text, color, bg, icon }: { text: string; color: string; bg: string; icon?: IoniconsName }) {
  return (
    <View style={[styles.badge, { backgroundColor: bg }]}>
      {icon ? <Ionicons name={icon} size={12} color={color} importantForAccessibility="no" /> : null}
      <Text style={[styles.badgeText, { color }]}>{text}</Text>
    </View>
  );
}

function Banner({ kind, text, actionLabel, onAction }: {
  kind: 'error' | 'info'; text: string; actionLabel?: string; onAction?: () => void;
}) {
  const error = kind === 'error';
  return (
    <View style={[styles.banner, error ? styles.bannerError : styles.bannerInfo]} accessibilityRole="alert">
      <Ionicons name={error ? 'alert-circle-outline' : 'cloud-offline-outline'} size={16} color={error ? RED : AMBER} />
      <Text style={[styles.bannerText, { color: error ? RED : AMBER }]}>{text}</Text>
      {actionLabel && onAction ? (
        <TouchableOpacity onPress={onAction} accessibilityRole="button" accessibilityLabel={actionLabel} style={styles.bannerBtn}>
          <Text style={[styles.bannerBtnText, { color: error ? RED : AMBER }]}>{actionLabel}</Text>
        </TouchableOpacity>
      ) : null}
    </View>
  );
}

function LevelPicker({ value, onChange }: { value: AccessLevel; onChange: (v: AccessLevel) => void }) {
  return (
    <View style={styles.levelGrid} accessibilityRole="radiogroup">
      {LEVELS.map(opt => {
        const selected = value === opt.value;
        return (
          <TouchableOpacity
            key={opt.value}
            style={[styles.levelCard, selected && styles.levelCardOn]}
            onPress={() => onChange(opt.value)}
            activeOpacity={0.7}
            accessibilityRole="radio"
            accessibilityLabel={opt.label}
            accessibilityState={{ selected }}
            aria-checked={selected}
          >
            <View style={[styles.radio, selected && { borderColor: TEAL }]}>
              {selected ? <View style={styles.radioDot} /> : null}
            </View>
            <View style={{ flex: 1 }}>
              <Text style={[styles.levelLabel, selected && { color: TEAL }]}>{opt.label}</Text>
              <Text style={styles.levelSub}>{opt.sub}</Text>
            </View>
          </TouchableOpacity>
        );
      })}
    </View>
  );
}

function DataPermsEditor({ level, perms, onChange }: {
  level: AccessLevel; perms: DataPerms; onChange: (p: DataPerms) => void;
}) {
  if (level === 'FULL_ACCESS') {
    return <Text style={styles.hint}>Full access includes every record and booking. It does not include messaging or video calls.</Text>;
  }
  return (
    <View style={styles.box}>
      <Text style={styles.boxTitle}>What they can see and do</Text>
      {DATA_PERMS.map(p => (
        <Toggle key={p.key} label={p.label} value={perms[p.key]} onChange={v => onChange({ ...perms, [p.key]: v })} />
      ))}
    </View>
  );
}

// ─── Grant form ───────────────────────────────────────────────────────────────

function GrantForm({ onCancel, onGranted, online }: {
  onCancel: () => void; onGranted: () => void; online: boolean;
}) {
  const [email,        setEmail]        = useState('');
  const [relationship, setRelationship] = useState('');
  const [level,        setLevel]        = useState<AccessLevel>('VIEW_ONLY');
  const [perms,        setPerms]        = useState<DataPerms>({ ...EMPTY_PERMS, canViewRecords: true, canViewAppointments: true });
  const [messaging,    setMessaging]    = useState(false);
  const [calls,        setCalls]        = useState(false);
  const [expiresAt,    setExpiresAt]    = useState('');
  const [errors,       setErrors]       = useState<Record<string, string>>({});
  const [submitting,   setSubmitting]   = useState(false);
  const [submitError,  setSubmitError]  = useState<string | null>(null);

  function validate() {
    const e: Record<string, string> = {};
    const em = email.trim();
    if (!em) e.email = 'Enter the email of their caregiver account.';
    else if (!/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(em)) e.email = 'Enter a valid email address.';
    const rel = relationship.trim();
    if (!rel) e.relationship = 'Enter how this person is related to you.';
    else if (rel.length > 80) e.relationship = 'Use at most 80 characters.';
    const ex = expiryError(expiresAt);
    if (ex) e.expiresAt = ex;
    setErrors(e);
    return Object.keys(e).length === 0;
  }

  async function submit() {
    if (!validate() || submitting) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      await grantCaregiverAccess({
        caregiverEmail: email.trim().toLowerCase(),
        relationship:   relationship.trim(),
        accessLevel:    level,
        expiresAt:      expiresAt.trim() || null,
        ...(level === 'FULL_ACCESS' ? {} : perms),
        canMessageCareTeam: messaging,
        canJoinCalls:       calls,
      });
      onGranted();
    } catch (err) {
      setSubmitError(caregiverAccessErrorMessage(err, 'Could not grant access. Please try again.'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <View style={styles.card}>
      <Text style={styles.cardTitle}>Grant access</Text>
      <Text style={styles.cardSub}>The person needs a CareNexus caregiver account. Enter the email they signed up with.</Text>

      <Text style={styles.label}>Caregiver email</Text>
      <TextInput
        style={[styles.input, !!errors.email && styles.inputError]}
        value={email}
        onChangeText={v => { setEmail(v); if (errors.email) setErrors(e => ({ ...e, email: '' })); }}
        placeholder="name@example.com"
        placeholderTextColor={GRAY2}
        autoCapitalize="none"
        keyboardType="email-address"
        accessibilityLabel="Caregiver email"
      />
      {errors.email ? <Text style={styles.fieldError}>{errors.email}</Text> : null}

      <Text style={styles.label}>Relationship</Text>
      <TextInput
        style={[styles.input, !!errors.relationship && styles.inputError]}
        value={relationship}
        onChangeText={v => { setRelationship(v); if (errors.relationship) setErrors(e => ({ ...e, relationship: '' })); }}
        placeholder="e.g. Daughter, Husband, Neighbour"
        placeholderTextColor={GRAY2}
        maxLength={80}
        accessibilityLabel="Relationship"
      />
      {errors.relationship ? <Text style={styles.fieldError}>{errors.relationship}</Text> : null}

      <Text style={styles.label}>Access level</Text>
      <LevelPicker value={level} onChange={setLevel} />
      <DataPermsEditor level={level} perms={perms} onChange={setPerms} />

      <View style={styles.box}>
        <Text style={styles.boxTitle}>Messaging and video calls</Text>
        <Toggle label="Message my care team" sub="Turning this on approves it." value={messaging} onChange={setMessaging} />
        <Toggle label="Join my video calls" sub="Turning this on approves it." value={calls} onChange={setCalls} />
      </View>

      <Text style={styles.label}>Access ends on (optional)</Text>
      <TextInput
        style={[styles.input, !!errors.expiresAt && styles.inputError]}
        value={expiresAt}
        onChangeText={v => { setExpiresAt(v); if (errors.expiresAt) setErrors(e => ({ ...e, expiresAt: '' })); }}
        placeholder="YYYY-MM-DD (leave blank for no end date)"
        placeholderTextColor={GRAY2}
        accessibilityLabel="Access end date"
      />
      {errors.expiresAt ? <Text style={styles.fieldError}>{errors.expiresAt}</Text> : null}

      {submitError ? <Banner kind="error" text={submitError} /> : null}

      <View style={styles.actionsRow}>
        <TouchableOpacity style={styles.secondaryBtn} onPress={onCancel} accessibilityRole="button" accessibilityLabel="Cancel">
          <Text style={styles.secondaryBtnText}>Cancel</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.primaryBtn, (submitting || !online) && styles.disabled]}
          onPress={submit}
          disabled={submitting || !online}
          accessibilityRole="button"
          accessibilityLabel="Grant access"
          accessibilityState={{ disabled: submitting || !online, busy: submitting }}
          aria-busy={submitting}
        >
          {submitting ? <ActivityIndicator size="small" color={WHITE} accessibilityLabel="Granting access" /> : <Text style={styles.primaryBtnText}>Grant access</Text>}
        </TouchableOpacity>
      </View>
    </View>
  );
}

// ─── One caregiver ────────────────────────────────────────────────────────────

function GrantCard({ grant, online, onChanged, onRemoved }: {
  grant: CaregiverAccess;
  online: boolean;
  onChanged: (g: CaregiverAccess) => void;
  onRemoved: (accessId: number) => void;
}) {
  const [busy,    setBusy]    = useState(false);
  const [error,   setError]   = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const [level,   setLevel]   = useState<AccessLevel>(grant.accessLevel);
  const [perms,   setPerms]   = useState<DataPerms>(dataPermsOf(grant));
  const [expires, setExpires] = useState(grant.expiresAt ? grant.expiresAt.slice(0, 10) : '');
  const [expErr,  setExpErr]  = useState<string | null>(null);

  const name     = grant.caregiverName?.trim() || grant.caregiverEmail || 'Caregiver';
  const expired  = grant.accessStatus === 'EXPIRED';
  const expiry   = formatDate(grant.expiresAt);
  const disabled = busy || !online;

  function startEdit() {
    setLevel(grant.accessLevel);
    setPerms(dataPermsOf(grant));
    setExpires(grant.expiresAt ? grant.expiresAt.slice(0, 10) : '');
    setExpErr(null);
    setError(null);
    setEditing(true);
  }

  async function patch(body: UpdateCaregiverAccessBody, fallback: string): Promise<boolean> {
    setBusy(true);
    setError(null);
    try {
      const updated = await updateCaregiverAccess(grant.accessId, body);
      onChanged(updated);
      return true;
    } catch (err) {
      setError(caregiverAccessErrorMessage(err, fallback));
      return false;
    } finally {
      setBusy(false);
    }
  }

  function approvePending() {
    const body: UpdateCaregiverAccessBody = {};
    if (grant.canMessageCareTeam && !grant.messagingAllowed) body.canMessageCareTeam = true;
    if (grant.canJoinCalls && !grant.joinCallsAllowed) body.canJoinCalls = true;
    patch(body, 'Could not approve. Please try again.');
  }

  async function saveEdit() {
    const ex = expiryError(expires);
    if (ex) { setExpErr(ex); return; }
    const currentDate = grant.expiresAt ? grant.expiresAt.slice(0, 10) : '';
    const body: UpdateCaregiverAccessBody = {
      accessLevel: level,
      ...(level === 'FULL_ACCESS' ? {} : perms),
    };
    if (expires.trim() !== currentDate) body.expiresAt = expires.trim(); // "" removes the end date
    if (await patch(body, 'Could not save changes. Please try again.')) setEditing(false);
  }

  async function revoke() {
    const ok = await confirmAction(
      `Remove ${name}'s access?`,
      `${name} will no longer see your information. They are also removed from conversations and from any call in progress.`,
      'Remove access',
      true,
    );
    if (!ok) return;
    setBusy(true);
    setError(null);
    try {
      await revokeCaregiverAccess(grant.accessId);
      onRemoved(grant.accessId);
    } catch (err) {
      setError(caregiverAccessErrorMessage(err, 'Could not remove access. Please try again.'));
      setBusy(false);
    }
  }

  return (
    <View style={styles.card}>
      <View style={styles.grantHeader}>
        <View style={styles.avatar}><Ionicons name="person-outline" size={18} color={TEAL} importantForAccessibility="no" /></View>
        <View style={{ flex: 1, minWidth: 160, gap: 2 }}>
          <Text style={styles.grantName}>{name}</Text>
          {grant.caregiverEmail ? <Text style={styles.grantMeta}>{grant.caregiverEmail}</Text> : null}
          <Text style={styles.grantMeta}>{grant.relationship}</Text>
        </View>
        <View style={styles.badgeRow}>
          <Badge text={levelLabel(grant.accessLevel)} color={TEAL} bg={TEAL_L} />
          {expired
            ? <Badge text="Expired" color={RED} bg={RED_L} icon="time-outline" />
            : <Badge text="Active" color={GREEN} bg={GREEN_L} />}
        </View>
      </View>

      <Text style={styles.grantMeta}>{expiry ? `${expired ? 'Ended' : 'Ends'} ${expiry}` : 'No end date'}</Text>

      {grant.pendingApproval ? (
        <View style={styles.pendingBox}>
          <Ionicons name="hand-left-outline" size={16} color={AMBER} importantForAccessibility="no" />
          <View style={{ flex: 1, minWidth: 160 }}>
            <Text style={styles.pendingTitle}>Needs your approval</Text>
            <Text style={styles.pendingText}>
              {name} asked to {[
                grant.canMessageCareTeam && !grant.messagingAllowed ? 'message your care team' : null,
                grant.canJoinCalls && !grant.joinCallsAllowed ? 'join your video calls' : null,
              ].filter(Boolean).join(' and ')}.
            </Text>
          </View>
          <TouchableOpacity
            style={[styles.approveBtn, disabled && styles.disabled]}
            onPress={approvePending}
            disabled={disabled}
            accessibilityRole="button"
            accessibilityLabel={`Approve requests from ${name}`}
          >
            <Text style={styles.approveBtnText}>Approve</Text>
          </TouchableOpacity>
        </View>
      ) : null}

      <View style={styles.box}>
        <Text style={styles.boxTitle}>Messaging and video calls</Text>
        <Toggle
          label="Message my care team"
          sub={grant.messagingAllowed ? 'Allowed' : grant.canMessageCareTeam ? 'Requested, not approved' : 'Not allowed'}
          value={!!grant.messagingAllowed}
          onChange={v => patch({ canMessageCareTeam: v }, 'Could not update messaging. Please try again.')}
          disabled={disabled || expired}
        />
        <Toggle
          label="Join my video calls"
          sub={grant.joinCallsAllowed ? 'Allowed' : grant.canJoinCalls ? 'Requested, not approved' : 'Not allowed'}
          value={!!grant.joinCallsAllowed}
          onChange={v => patch({ canJoinCalls: v }, 'Could not update video calls. Please try again.')}
          disabled={disabled || expired}
        />
        {expired ? <Text style={styles.hint}>This access has ended. Change the end date to use it again.</Text> : null}
      </View>

      {editing ? (
        <View style={{ gap: 10 }}>
          <Text style={styles.label}>Access level</Text>
          <LevelPicker value={level} onChange={setLevel} />
          <DataPermsEditor level={level} perms={perms} onChange={setPerms} />
          <Text style={styles.label}>Access ends on (optional)</Text>
          <TextInput
            style={[styles.input, !!expErr && styles.inputError]}
            value={expires}
            onChangeText={v => { setExpires(v); setExpErr(null); }}
            placeholder="YYYY-MM-DD (leave blank for no end date)"
            placeholderTextColor={GRAY2}
            accessibilityLabel="Access end date"
          />
          {expErr ? <Text style={styles.fieldError}>{expErr}</Text> : null}
        </View>
      ) : null}

      {error ? <Banner kind="error" text={error} /> : null}

      <View style={styles.actionsRow}>
        {editing ? (
          <>
            <TouchableOpacity style={styles.secondaryBtn} onPress={() => setEditing(false)} accessibilityRole="button" accessibilityLabel="Cancel changes">
              <Text style={styles.secondaryBtnText}>Cancel</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[styles.primaryBtn, disabled && styles.disabled]}
              onPress={saveEdit}
              disabled={disabled}
              accessibilityRole="button"
              accessibilityLabel={`Save changes for ${name}`}
            >
              {busy ? <ActivityIndicator size="small" color={WHITE} accessibilityLabel="Saving changes" /> : <Text style={styles.primaryBtnText}>Save changes</Text>}
            </TouchableOpacity>
          </>
        ) : (
          <>
            <TouchableOpacity
              style={[styles.dangerBtn, disabled && styles.disabled]}
              onPress={revoke}
              disabled={disabled}
              accessibilityRole="button"
              accessibilityLabel={`Remove access for ${name}`}
            >
              <Ionicons name="trash-outline" size={14} color={RED} importantForAccessibility="no" />
              <Text style={styles.dangerBtnText}>Remove access</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[styles.secondaryBtn, disabled && styles.disabled]}
              onPress={startEdit}
              disabled={disabled}
              accessibilityRole="button"
              accessibilityLabel={`Change permissions for ${name}`}
            >
              <Ionicons name="create-outline" size={14} color={TEAL} importantForAccessibility="no" />
              <Text style={[styles.secondaryBtnText, { color: TEAL }]}>Change permissions</Text>
            </TouchableOpacity>
            {busy ? <ActivityIndicator size="small" color={TEAL} accessibilityLabel="Updating access" /> : null}
          </>
        )}
      </View>
    </View>
  );
}

// ─── Screen ───────────────────────────────────────────────────────────────────

export default function PatientWebCaregiverAccess({ onBack }: { onBack?: () => void }) {
  const { grants, loading, error, refetch } = useCaregiverAccess();
  const online = useOnline();
  const [items,    setItems]    = useState<CaregiverAccess[]>([]);
  const [granting, setGranting] = useState(false);
  const [notice,   setNotice]   = useState<string | null>(null);

  useEffect(() => { setItems(grants); }, [grants]);

  function changed(updated: CaregiverAccess) {
    setItems(prev => prev.map(g => (g.accessId === updated.accessId ? { ...g, ...updated } : g)));
  }

  function removed(accessId: number) {
    setItems(prev => prev.filter(g => g.accessId !== accessId));
    setNotice('Access removed.');
  }

  function granted() {
    setGranting(false);
    setNotice('Access granted.');
    refetch();
  }

  const pendingCount = items.filter(g => g.pendingApproval).length;

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container}>
      <View style={styles.header}>
        {onBack ? (
          <TouchableOpacity style={styles.backBtn} onPress={onBack} accessibilityRole="button" accessibilityLabel="Back to dashboard">
            <Ionicons name="arrow-back-outline" size={18} color={TEAL} importantForAccessibility="no" />
            <Text style={styles.backText}>Back to dashboard</Text>
          </TouchableOpacity>
        ) : null}
        <View style={styles.titleRow}>
          <View style={{ flex: 1, minWidth: 200, gap: 4 }}>
            <Text style={styles.title} accessibilityRole="header">My caregivers</Text>
            <Text style={styles.intro}>{INTRO}</Text>
          </View>
          {!granting ? (
            <TouchableOpacity
              style={[styles.primaryBtn, !online && styles.disabled]}
              onPress={() => { setNotice(null); setGranting(true); }}
              disabled={!online}
              accessibilityRole="button"
              accessibilityLabel="Grant access to a caregiver"
            >
              <Ionicons name="person-add-outline" size={15} color={WHITE} importantForAccessibility="no" />
              <Text style={styles.primaryBtnText}>Grant access</Text>
            </TouchableOpacity>
          ) : null}
        </View>
      </View>

      {!online ? <Banner kind="info" text="You're offline. Changes can't be saved until you reconnect." /> : null}
      {notice ? (
        <View style={styles.notice} accessibilityRole="alert">
          <Ionicons name="checkmark-circle-outline" size={16} color={GREEN} />
          <Text style={styles.noticeText}>{notice}</Text>
        </View>
      ) : null}
      {pendingCount > 0 && !loading ? (
        <Banner kind="info" text={`${pendingCount} ${pendingCount === 1 ? 'caregiver needs' : 'caregivers need'} your approval.`} />
      ) : null}

      {granting ? <GrantForm online={online} onCancel={() => setGranting(false)} onGranted={granted} /> : null}

      {loading && items.length === 0 ? (
        <View style={styles.center}>
          <ActivityIndicator size="large" color={TEAL} accessibilityLabel="Loading your caregivers" />
          <Text style={styles.muted}>Loading your caregivers…</Text>
        </View>
      ) : error && items.length === 0 ? (
        <Banner kind="error" text={error} actionLabel="Retry" onAction={refetch} />
      ) : items.length === 0 ? (
        !granting ? (
          <View style={[styles.card, styles.center]}>
            <Ionicons name="people-outline" size={36} color={GRAY2} />
            <Text style={styles.emptyTitle}>No caregivers yet</Text>
            <Text style={styles.muted}>Grant access to someone who helps with your care, such as a family member.</Text>
          </View>
        ) : null
      ) : (
        <>
          {error ? <Banner kind="error" text={error} actionLabel="Retry" onAction={refetch} /> : null}
          {items.map(g => (
            <GrantCard key={g.accessId} grant={g} online={online} onChanged={changed} onRemoved={removed} />
          ))}
        </>
      )}
    </ScrollView>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  scroll:    { flex: 1, backgroundColor: BG },
  container: { alignSelf: 'center', width: '100%', maxWidth: 860, padding: 16, gap: 16, paddingBottom: 48 } as any,

  header:    { gap: 6 },
  backBtn:   { flexDirection: 'row', alignItems: 'center', gap: 6, alignSelf: 'flex-start', paddingVertical: 4 },
  backText:  { fontSize: 14, color: TEAL, fontWeight: '500' },
  titleRow:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: 12 },
  title:     { fontSize: 24, fontWeight: '700', color: TEXT },
  intro:     { fontSize: 13, color: GRAY, lineHeight: 19 },

  banner:        { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: 8, borderRadius: 10, padding: 12, borderWidth: 1 },
  bannerError:   { backgroundColor: RED_L, borderColor: '#FECACA' },
  bannerInfo:    { backgroundColor: AMBER_L, borderColor: '#FDE68A' },
  bannerText:    { fontSize: 13, flex: 1, minWidth: 160 },
  bannerBtn:     { paddingHorizontal: 10, paddingVertical: 4 },
  bannerBtnText: { fontSize: 13, fontWeight: '700' },

  notice:     { flexDirection: 'row', alignItems: 'center', gap: 8, backgroundColor: GREEN_L, borderRadius: 10, padding: 12 },
  noticeText: { fontSize: 13, color: '#047857', flex: 1 },

  center:     { alignItems: 'center', justifyContent: 'center', gap: 8, paddingVertical: 32 },
  muted:      { fontSize: 13, color: GRAY, textAlign: 'center' },
  emptyTitle: { fontSize: 16, fontWeight: '700', color: TEXT },

  card:      { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER, borderRadius: 14, padding: 16, gap: 12 },
  cardTitle: { fontSize: 17, fontWeight: '700', color: TEXT },
  cardSub:   { fontSize: 13, color: GRAY, marginTop: -6 },

  grantHeader: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: 12 },
  avatar:      { width: 40, height: 40, borderRadius: 20, backgroundColor: TEAL_L, alignItems: 'center', justifyContent: 'center' },
  grantName:   { fontSize: 15, fontWeight: '700', color: TEXT },
  grantMeta:   { fontSize: 12, color: GRAY },
  badgeRow:    { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  badge:       { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 10, paddingVertical: 4, borderRadius: 20 },
  badgeText:   { fontSize: 12, fontWeight: '600' },

  pendingBox:     { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 10, backgroundColor: AMBER_L, borderWidth: 1, borderColor: '#FDE68A', borderRadius: 12, padding: 12 },
  pendingTitle:   { fontSize: 13, fontWeight: '700', color: AMBER },
  pendingText:    { fontSize: 12, color: AMBER },
  approveBtn:     { backgroundColor: AMBER, borderRadius: 8, paddingHorizontal: 14, paddingVertical: 8 },
  approveBtnText: { fontSize: 13, fontWeight: '700', color: WHITE },

  box:      { backgroundColor: BG, borderRadius: 12, borderWidth: 1, borderColor: BORDER, paddingHorizontal: 14, paddingVertical: 8 },
  boxTitle: { fontSize: 13, fontWeight: '700', color: TEXT, paddingVertical: 6 },

  toggleRow:     { flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 10, borderTopWidth: 1, borderTopColor: BORDER },
  toggleLabel:   { fontSize: 13, color: TEXT, fontWeight: '500' },
  toggleSub:     { fontSize: 11, color: GRAY },
  toggle:        { width: 40, height: 22, borderRadius: 11, backgroundColor: BORDER, justifyContent: 'center', paddingHorizontal: 2 },
  toggleOn:      { backgroundColor: TEAL },
  toggleThumb:   { width: 18, height: 18, borderRadius: 9, backgroundColor: WHITE },
  toggleThumbOn: { alignSelf: 'flex-end' },

  levelGrid:   { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  levelCard:   { flexGrow: 1, flexBasis: 160, flexDirection: 'row', alignItems: 'flex-start', gap: 10, borderWidth: 1, borderColor: BORDER, borderRadius: 12, padding: 12, backgroundColor: WHITE },
  levelCardOn: { borderColor: TEAL, borderWidth: 2 },
  radio:       { width: 18, height: 18, borderRadius: 9, borderWidth: 2, borderColor: BORDER, alignItems: 'center', justifyContent: 'center', marginTop: 2 },
  radioDot:    { width: 8, height: 8, borderRadius: 4, backgroundColor: TEAL },
  levelLabel:  { fontSize: 13, fontWeight: '700', color: TEXT },
  levelSub:    { fontSize: 11, color: GRAY },

  label:      { fontSize: 13, fontWeight: '600', color: TEXT, marginBottom: -6 },
  input:      { borderWidth: 1, borderColor: BORDER, borderRadius: 10, paddingHorizontal: 12, paddingVertical: 10, fontSize: 14, color: TEXT, backgroundColor: WHITE } as any,
  inputError: { borderColor: RED },
  fieldError: { fontSize: 12, color: RED, marginTop: -6 },
  hint:       { fontSize: 12, color: GRAY, paddingVertical: 6 },

  actionsRow:       { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'flex-end', alignItems: 'center', gap: 10 },
  primaryBtn:       { flexDirection: 'row', alignItems: 'center', gap: 6, backgroundColor: TEAL, borderRadius: 10, paddingHorizontal: 16, paddingVertical: 10 },
  primaryBtnText:   { fontSize: 14, color: WHITE, fontWeight: '700' },
  secondaryBtn:     { flexDirection: 'row', alignItems: 'center', gap: 6, borderWidth: 1, borderColor: BORDER, borderRadius: 10, paddingHorizontal: 14, paddingVertical: 9, backgroundColor: WHITE },
  secondaryBtnText: { fontSize: 13, color: GRAY, fontWeight: '600' },
  dangerBtn:        { flexDirection: 'row', alignItems: 'center', gap: 6, borderWidth: 1, borderColor: '#FECACA', borderRadius: 10, paddingHorizontal: 14, paddingVertical: 9, backgroundColor: RED_L },
  dangerBtnText:    { fontSize: 13, color: RED, fontWeight: '600' },
  disabled:         { opacity: 0.5 },
});
