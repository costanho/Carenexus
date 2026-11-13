import { zodResolver } from '@hookform/resolvers/zod';
import { type ReactNode, useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import {
  EditableSection, EmailRow, initialsOf, NamePhoneFields, ProfileForm, ProfileGrid, ProfileIdentity,
  ProfileTabsBody, profileCell, useProfileSave, useProfileTab,
} from '@/components/web/profile/profile-shared';
import {
  Banner, Button, Card, colors, decorative, EmptyView, ErrorView, fontSize, FormActions, FormError, Icon,
  InfoRow, type IoniconsName, LoadingView, radius, Screen, SectionCard, space, StatusBadge, statusLabel,
  statusTone, useUiTheme,
} from '@/components/web/ui';
import { usePendingAccessRequestCount } from '@/hooks/use-caregiver-access-requests';
import { type CaregiverDependent, useCaregiverDependents } from '@/hooks/use-caregiver-dependents';
import { type UpdateUserProfile, useMyProfile, type UserProfile } from '@/hooks/use-my-profile';
import { isGrantActive } from '@/lib/caregiver/permissions';
import type { SectionProps } from '@/lib/nav/sections';
import { formatClinicDate } from '@/lib/time/clinic';
import {
  type AccountDetailsForm, accountDetailsChanges, accountDetailsDefaults, accountDetailsSchema,
} from '@/lib/validation/profile';

// The caregiver's own account: name and phone (editable), sign-in email, how they described themselves at
// registration, the patients they are linked to and the access requests they sent. Everything is read from
// GET /api/profile, so the phone and the account status are the stored ones. The Security tab is shown when
// the dashboard passes a `securityPanel`. A `patient` URL param (the link of a "your access changed"
// notification) marks that loved one in the list.

export interface PatientProxyProfileSettingsProps extends Partial<SectionProps> {
  /** Opens another section of the caregiver dashboard; without it `navigate` is used. */
  onNavigate?:      (key: string) => void;
  onSelectPatient?: (dep: CaregiverDependent) => void;
  /** Content of the Security tab (`?tab=security`); without it the screen has no tabs. */
  securityPanel?:   ReactNode;
}

const AVATAR_COLORS = ['#4F46E5', '#0F766E', '#B45309', '#B91C1C', '#6D28D9', '#047857', '#0369A1'];
function avatarColor(name: string) {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) & 0xffff;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}

/**
 * Health status of a linked patient: a dot in the status palette on the avatar, plus an icon and the status
 * word in a text shade that reads on a light surface, so colour is never the only cue.
 */
interface HealthLook { label: string; dot: string; text: string; icon: IoniconsName }
const HEALTH: Record<string, HealthLook> = {
  STABLE:   { label: 'Stable',   dot: '#16A34A', text: '#166534', icon: 'checkmark-circle' },
  MONITOR:  { label: 'Monitor',  dot: '#F59E0B', text: '#92400E', icon: 'alert-circle' },
  CRITICAL: { label: 'Critical', dot: '#DC2626', text: '#991B1B', icon: 'warning' },
};

function accessLabel(level: string) {
  if (level === 'FULL_ACCESS') return 'Full Access';
  if (level === 'VIEW_ONLY')   return 'View Only';
  if (level === 'EDIT_ONLY')   return 'Edit Only';
  return 'Custom';
}

function capitalise(text: string | null | undefined): string {
  const t = (text ?? '').trim();
  return t ? t.charAt(0).toUpperCase() + t.slice(1) : '';
}

// ─── Personal information ─────────────────────────────────────────────────────

const FIELDS = ['firstName', 'lastName', 'phone'] as const;

function AccountDetailsEdit({ profile, save, onDone, onCancel }: {
  profile:  UserProfile;
  save:     (changes: UpdateUserProfile) => Promise<unknown>;
  onDone:   (saved: boolean) => void;
  onCancel: () => void;
}) {
  const schema = useMemo(() => accountDetailsSchema({ hasPhone: !!profile.phone }), [profile.phone]);
  const { control, handleSubmit, setError, setFocus } = useForm<AccountDetailsForm>({
    resolver: zodResolver(schema),
    defaultValues: accountDetailsDefaults(profile),
  });
  const { submit, pending, formError } = useProfileSave({
    handleSubmit, setError, fields: FIELDS,
    changesOf: (values: AccountDetailsForm) => accountDetailsChanges(profile, values),
    save, onDone,
  });
  useEffect(() => { setFocus('firstName'); }, [setFocus]);

  return (
    <ProfileForm>
      <FormError message={formError} />
      <NamePhoneFields control={control} onSubmit={submit} phoneRequired={!!profile.phone} />
      <FormActions submitLabel="Save changes" onSubmit={submit} onCancel={onCancel} submitting={pending} />
    </ProfileForm>
  );
}

function AccountDetailsView({ profile, onOpenSecurity }: { profile: UserProfile; onOpenSecurity?: () => void }) {
  return (
    <View>
      <InfoRow label="First name" value={profile.firstName} />
      <InfoRow label="Last name" value={profile.lastName} />
      <InfoRow label="Phone number" value={profile.phone} />
      <EmailRow email={profile.email} onOpenSecurity={onOpenSecurity} />
      {/* Recorded at registration only; the row is left out when nothing was recorded. */}
      {profile.relationship ? <InfoRow label="Relationship" value={capitalise(profile.relationship)} /> : null}
      <InfoRow label="Role" value="Caregiver" />
      <InfoRow label="Account status">
        <StatusBadge
          label={profile.isActive ? 'Active' : 'Inactive'}
          tone={statusTone('account', profile.isActive ? 'ACTIVE' : 'INACTIVE')}
        />
      </InfoRow>
    </View>
  );
}

// ─── Linked patients ──────────────────────────────────────────────────────────

function LinkedPatientRow({ dep, first, highlighted = false, onManage }: {
  dep:          CaregiverDependent;
  first:        boolean;
  /** The loved one a notification link points at. */
  highlighted?: boolean;
  onManage?:    () => void;
}) {
  const { accent, accentBg } = useUiTheme();
  const name = dep.patientName ?? 'Unknown';
  const health = dep.patientHealthStatus ? HEALTH[dep.patientHealthStatus] ?? null : null;
  const relationship = capitalise(dep.relationship);
  // A grant that is no longer in force is labelled with why: revoked, or past its end date.
  const ended = isGrantActive(dep) ? null : dep.accessStatus && dep.accessStatus !== 'ACTIVE' ? dep.accessStatus : 'EXPIRED';
  const expiry = ended ? null : dep.expiresAt ? `Expires ${formatClinicDate(dep.expiresAt, 'on an unknown date')}` : 'No expiry';

  return (
    <View
      style={[styles.depRow, !first && styles.depDivider, highlighted && [styles.depHighlight, { backgroundColor: accentBg, borderColor: accent }]]}
      role="listitem"
    >
      <View style={styles.depAvatarWrap} {...decorative}>
        <View style={[styles.depAvatar, { backgroundColor: avatarColor(name) }]}>
          <Text style={styles.depInitials}>{initialsOf(name) || '?'}</Text>
        </View>
        {health ? <View style={[styles.depDot, { backgroundColor: health.dot }]} /> : null}
      </View>
      <View style={styles.depMain}>
        <Text style={styles.depName}>{name}</Text>
        {highlighted ? <Text style={[styles.depLinked, { color: accent }]}>From your notification</Text> : null}
        {relationship ? <Text style={styles.depRel}>{relationship}</Text> : null}
        <View style={styles.depMeta}>
          <StatusBadge label={accessLabel(dep.accessLevel)} tone="neutral" icon={null} />
          {ended ? <StatusBadge label={statusLabel(ended)} tone={statusTone('access', ended)} /> : null}
          {health ? (
            <View style={styles.healthTag}>
              <Icon name={health.icon} size={13} color={health.text} />
              <Text style={[styles.healthText, { color: health.text }]}>{health.label}</Text>
            </View>
          ) : null}
          {expiry ? <Text style={styles.depExpiry}>{expiry}</Text> : null}
        </View>
      </View>
      {onManage ? (
        <Button
          label="Manage" accessibilityLabel={`Manage access for ${name}`}
          variant="secondary" size="sm" onPress={onManage}
        />
      ) : null}
    </View>
  );
}

// ─── Access requests ──────────────────────────────────────────────────────────

/** The requests for access the caregiver sent to existing patients: how many wait for an answer, and a link. */
function AccessRequestsCard({ onOpen }: { onOpen: () => void }) {
  const pending = usePendingAccessRequestCount();
  const count = pending.data;
  let summary;
  if (pending.loading && count === null) {
    summary = <LoadingView compact label="Loading your access requests" />;
  } else if (pending.error && count === null) {
    summary = <ErrorView compact message={pending.error} onRetry={pending.refetch} />;
  } else if (count && count > 0) {
    summary = (
      <View style={styles.requestsRow}>
        <StatusBadge label={`${count} pending`} tone="warning" icon="time-outline" />
        <Text style={styles.requestsText}>
          {count === 1 ? 'One request is waiting for the patient to answer.' : `${count} requests are waiting for the patients to answer.`}
        </Text>
      </View>
    );
  } else {
    summary = <Text style={styles.requestsText}>No requests are waiting for an answer.</Text>;
  }
  return (
    <SectionCard
      title="Access Requests"
      subtitle="Requests you sent to patients who already use CareNexus"
      style={profileCell}
      action={(
        <Button
          label="View access requests"
          icon="mail-open-outline"
          variant="secondary"
          size="sm"
          accessibilityLabel={count && count > 0 ? `View access requests, ${count} pending` : 'View access requests'}
          onPress={onOpen}
        />
      )}
    >
      {summary}
    </SectionCard>
  );
}

// ─── Screen ───────────────────────────────────────────────────────────────────

export default function PatientProxyProfileSettings({
  onNavigate, onSelectPatient, params, navigate, theme, securityPanel,
}: PatientProxyProfileSettingsProps) {
  const { profile, loading, refreshing, error, refetch, updateProfile } = useMyProfile();
  const dependents = useCaregiverDependents();
  const { tab, setTab } = useProfileTab(params, !!securityPanel);
  const [editing, setEditing] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  // The registry passes the two callbacks; a plain `SectionProps` mount gets the same behaviour from `navigate`.
  const openSection = onNavigate ?? (navigate ? (key: string) => navigate(key) : undefined);
  const selectPatient = onSelectPatient
    ?? (navigate ? (dep: CaregiverDependent) => navigate('patient-profile', { patient: dep.patientSystemId }) : undefined);
  const openSecurity = securityPanel ? () => setTab('security') : undefined;
  const linkedPatient = params?.patient?.trim() || null;

  const fullName = profile ? `${profile.firstName} ${profile.lastName}`.trim() : '';

  return (
    <Screen
      title="Profile & Settings"
      subtitle="Your account details and the people you care for"
      theme={theme}
      role="proxy"
      actions={profile ? (
        <Button
          label="Refresh" icon="refresh-outline" variant="secondary" size="sm"
          onPress={() => { refetch(); dependents.refetch(); }} loading={refreshing}
        />
      ) : undefined}
    >
      <ProfileTabsBody tab={tab} onTabChange={setTab} securityPanel={securityPanel}>
        {profile && error ? <Banner tone="danger" text={error} action={{ label: 'Retry', onPress: refetch }} /> : null}
        {notice ? <Banner tone="success" text={notice} onDismiss={() => setNotice(null)} /> : null}

        {loading ? (
          <Card><LoadingView compact label="Loading your profile" /></Card>
        ) : !profile ? (
          <Card><ErrorView compact message={error ?? 'Profile not found.'} onRetry={refetch} /></Card>
        ) : (
          <ProfileIdentity
            name={fullName}
            initials={initialsOf(profile.firstName, profile.lastName)}
            tagline="Caregiver"
            facts={profile.systemId ? [{ label: 'Caregiver ID', value: profile.systemId }] : []}
          />
        )}

        <ProfileGrid>
          {profile ? (
            <EditableSection
              title="Personal Information"
              editing={editing}
              onEdit={() => { setNotice(null); setEditing(true); }}
              editLabel="Edit personal information"
            >
              {editing ? (
                <AccountDetailsEdit
                  profile={profile}
                  save={updateProfile}
                  onCancel={() => setEditing(false)}
                  onDone={saved => { setEditing(false); if (saved) setNotice('Your personal information was saved.'); }}
                />
              ) : (
                <AccountDetailsView profile={profile} onOpenSecurity={openSecurity} />
              )}
            </EditableSection>
          ) : null}

          <SectionCard
            title="Linked Patients"
            subtitle="Patients you have been authorised to manage as proxy"
            style={profileCell}
            action={openSection ? (
              <Button
                label="Add Linked Patient" icon="add" variant="secondary" size="sm"
                onPress={() => openSection('add-dependent')}
              />
            ) : undefined}
          >
            {dependents.loading ? (
              <LoadingView compact label="Loading linked patients" />
            ) : dependents.error ? (
              <ErrorView compact message={dependents.error} onRetry={dependents.refetch} />
            ) : dependents.dependents.length === 0 ? (
              <EmptyView
                compact
                icon="people-outline"
                title="No linked patients yet"
                sub="Patients appear here once you add them or they give you access."
              />
            ) : (
              <View role="list" aria-label="Linked patients">
                {dependents.dependents.map((dep, index) => (
                  <LinkedPatientRow
                    key={dep.accessId}
                    dep={dep}
                    first={index === 0}
                    highlighted={dep.patientSystemId === linkedPatient}
                    onManage={selectPatient ? () => selectPatient(dep) : undefined}
                  />
                ))}
              </View>
            )}
          </SectionCard>

          {openSection ? <AccessRequestsCard onOpen={() => openSection('access-requests')} /> : null}
        </ProfileGrid>
      </ProfileTabsBody>
    </Screen>
  );
}

const styles = StyleSheet.create({
  depRow:        { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.md, paddingVertical: space.md },
  depDivider:    { borderTopWidth: 1, borderTopColor: colors.border },
  depAvatarWrap: { position: 'relative', flexShrink: 0 },
  depAvatar:     { width: 44, height: 44, borderRadius: radius.pill, alignItems: 'center', justifyContent: 'center' },
  depInitials:   { color: '#FFFFFF', fontSize: fontSize.md, fontWeight: '700' },
  depDot: {
    position: 'absolute', bottom: 0, right: 0, width: 12, height: 12, borderRadius: radius.pill,
    borderWidth: 2, borderColor: colors.surface,
  },
  depMain:    { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0, gap: 2 },
  depHighlight: { borderWidth: 1, borderRadius: radius.md, paddingHorizontal: space.md },
  depName:    { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  depLinked:  { fontSize: fontSize.xs, fontWeight: '700' },
  requestsRow:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
  requestsText: { flexShrink: 1, fontSize: fontSize.sm, color: colors.text, lineHeight: 19 },
  depRel:     { fontSize: fontSize.sm, color: colors.textMuted },
  depMeta:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', columnGap: space.sm, rowGap: space.xs, marginTop: 2 },
  healthTag:  { flexDirection: 'row', alignItems: 'center', gap: 3 },
  healthText: { fontSize: fontSize.xs, fontWeight: '600' },
  depExpiry:  { fontSize: fontSize.xs, color: colors.textMuted },
});
