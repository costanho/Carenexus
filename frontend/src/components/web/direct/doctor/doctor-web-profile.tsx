import { zodResolver } from '@hookform/resolvers/zod';
import { type ReactNode, useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import {
  EditableSection, EmailRow, initialsOf, NamePhoneFields, ProfileForm, ProfileGrid, ProfileIdentity,
  ProfileTabsBody, profileCell, useProfileSave, useProfileTab,
} from '@/components/web/profile/profile-shared';
import {
  Banner, Button, Card, colors, ErrorView, fontSize, FormActions, FormError, Icon, InfoRow, type IoniconsName,
  LoadingView, Screen, SectionCard, space, StatusBadge, statusTone, TextField, useUiTheme,
} from '@/components/web/ui';
import {
  type DoctorProfileDto, type UpdateDoctorProfileBody, useDoctorProfile, type VerificationStatus,
} from '@/hooks/use-doctor-profile';
import { doctorDisplayName } from '@/hooks/use-doctors';
import type { SectionProps } from '@/lib/nav/sections';
import { formatClinicDate } from '@/lib/time/clinic';
import {
  type DoctorProfileForm, doctorProfileChanges, doctorProfileDefaults, doctorProfileSchema, PROFILE_LIMITS,
} from '@/lib/validation/profile';

// The doctor's own profile. Name, phone, specialization and bio are editable in place; the licence and the
// verification status are managed by an administrator and shown read-only. The sign-in email is changed from
// the Security tab, which is shown when the dashboard passes a `securityPanel`.

export interface DoctorWebProfileProps extends Partial<SectionProps> {
  /** Content of the Security tab (`?tab=security`); without it the screen has no tabs. */
  securityPanel?: ReactNode;
  /** The consultation fee editor, shown beside the professional details when the dashboard provides one. */
  feeSlot?: ReactNode;
}

const VERIFICATION_LABELS: Record<VerificationStatus, string> = {
  PENDING:   'Pending review',
  APPROVED:  'Verified',
  REJECTED:  'Not approved',
  SUSPENDED: 'Suspended',
};

/** "Zimbabwe" for "ZW"; the code itself when the browser cannot name the region. */
function countryName(code: string | null): string | null {
  if (!code) return null;
  try {
    return new Intl.DisplayNames(['en'], { type: 'region' }).of(code.toUpperCase()) ?? code;
  } catch {
    return code;
  }
}

// ─── Stats ────────────────────────────────────────────────────────────────────

function StatCard({ icon, label, value }: { icon: IoniconsName; label: string; value: number }) {
  const { accent, accentBg } = useUiTheme();
  return (
    <Card style={styles.stat}>
      <View style={[styles.statIcon, { backgroundColor: accentBg }]}>
        <Icon name={icon} size={18} color={accent} />
      </View>
      <View style={styles.statText}>
        <Text style={styles.statValue}>{value}</Text>
        <Text style={styles.statLabel}>{label}</Text>
      </View>
    </Card>
  );
}

// ─── Profile details ──────────────────────────────────────────────────────────

const FIELDS = ['firstName', 'lastName', 'phone', 'specialization', 'bio'] as const;

function ProfileDetailsEdit({ profile, focusField, save, onDone, onCancel }: {
  profile:    DoctorProfileDto;
  /** The field that takes focus when the form opens. */
  focusField: 'firstName' | 'bio';
  save:       (changes: UpdateDoctorProfileBody) => Promise<unknown>;
  onDone:     (saved: boolean) => void;
  onCancel:   () => void;
}) {
  const schema = useMemo(
    () => doctorProfileSchema({ hasPhone: !!profile.phone, hasSpecialization: !!profile.specialization }),
    [profile.phone, profile.specialization],
  );
  const { control, handleSubmit, setError, setFocus } = useForm<DoctorProfileForm>({
    resolver: zodResolver(schema),
    defaultValues: doctorProfileDefaults(profile),
  });
  const { submit, pending, formError } = useProfileSave({
    handleSubmit, setError, fields: FIELDS,
    changesOf: (values: DoctorProfileForm) => doctorProfileChanges(profile, values),
    save, onDone,
  });
  useEffect(() => { setFocus(focusField); }, [setFocus, focusField]);

  return (
    <ProfileForm>
      <FormError message={formError} />
      <NamePhoneFields control={control} onSubmit={submit} phoneRequired={!!profile.phone} />
      <TextField
        label="Specialization" control={control} name="specialization" required={!!profile.specialization}
        maxLength={PROFILE_LIMITS.specialization} autoComplete="off" onSubmit={submit}
      />
      <TextField
        label="Bio" control={control} name="bio" multiline rows={6}
        maxLength={PROFILE_LIMITS.bio} showCount
        hint="Patients see your bio when they choose a doctor. Leave it empty to remove it."
      />
      <FormActions submitLabel="Save changes" onSubmit={submit} onCancel={onCancel} submitting={pending} />
    </ProfileForm>
  );
}

function ProfileDetailsView({ profile, onOpenSecurity, onAddBio }: {
  profile:         DoctorProfileDto;
  onOpenSecurity?: () => void;
  onAddBio:        () => void;
}) {
  return (
    <View style={styles.details}>
      <View>
        <InfoRow label="First name" value={profile.firstName} />
        <InfoRow label="Last name" value={profile.lastName} />
        <InfoRow label="Phone number" value={profile.phone} />
        <EmailRow email={profile.email} onOpenSecurity={onOpenSecurity} />
        <InfoRow label="Specialization" value={profile.specialization} />
      </View>
      <View style={styles.bio}>
        <Text style={styles.bioLabel}>Bio</Text>
        {profile.bio ? (
          <Text style={styles.bioText}>{profile.bio}</Text>
        ) : (
          <View style={styles.bioEmpty}>
            <Text style={styles.note}>
              You have not added a bio yet. Patients see it when they choose a doctor.
            </Text>
            <Button label="Add a bio" icon="add" variant="secondary" size="sm" onPress={onAddBio} />
          </View>
        )}
      </View>
    </View>
  );
}

// ─── Screen ───────────────────────────────────────────────────────────────────

export default function DoctorWebProfile({ params, theme, securityPanel, feeSlot }: DoctorWebProfileProps) {
  const { raw: profile, stats, loading, refreshing, error, refetch, updateProfile } = useDoctorProfile();
  const { tab, setTab } = useProfileTab(params, !!securityPanel);
  const [editing, setEditing] = useState<'firstName' | 'bio' | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const frame = (children: ReactNode, actions?: ReactNode) => (
    <Screen
      title="Profile"
      subtitle="Your professional profile and contact details"
      theme={theme}
      role="doctor"
      actions={actions}
    >
      {children}
    </Screen>
  );

  if (loading) return frame(<LoadingView label="Loading profile" />);
  if (!profile) return frame(<ErrorView message={error ?? 'Profile not found.'} onRetry={refetch} />);

  const displayName = doctorDisplayName(profile.firstName, profile.lastName);
  const joined = profile.doctorCreatedAt ?? profile.userCreatedAt;
  const openSecurity = securityPanel ? () => setTab('security') : undefined;
  const startEdit = (field: 'firstName' | 'bio') => { setNotice(null); setEditing(field); };

  return frame(
    <ProfileTabsBody tab={tab} onTabChange={setTab} securityPanel={securityPanel}>
      {error ? <Banner tone="danger" text={error} action={{ label: 'Retry', onPress: refetch }} /> : null}
      {notice ? <Banner tone="success" text={notice} onDismiss={() => setNotice(null)} /> : null}

      <ProfileIdentity
        name={displayName}
        initials={initialsOf(profile.firstName, profile.lastName)}
        tagline={profile.specialization}
        badges={(
          <>
            {profile.verificationStatus ? (
              <StatusBadge
                label={VERIFICATION_LABELS[profile.verificationStatus]}
                tone={statusTone('verification', profile.verificationStatus)}
              />
            ) : null}
            <StatusBadge
              label={profile.isActive ? 'Active' : 'Inactive'}
              tone={statusTone('account', profile.isActive ? 'ACTIVE' : 'INACTIVE')}
            />
          </>
        )}
        facts={joined ? [{ label: 'Member since', value: formatClinicDate(joined, '—') }] : []}
      />

      <View style={styles.stats} role="group" aria-label="Your practice in numbers">
        <StatCard icon="calendar-number-outline" label="Appointments" value={stats.appointments} />
        <StatCard icon="people-outline" label="Patients" value={stats.patients} />
        <StatCard icon="videocam-outline" label="Consultations" value={stats.consultations} />
        <StatCard icon="medkit-outline" label="Prescriptions" value={stats.prescriptions} />
      </View>

      <ProfileGrid>
        <EditableSection
          title="Profile Details"
          subtitle="Your name, contact number, specialization and bio."
          editing={editing !== null}
          onEdit={() => startEdit('firstName')}
          editLabel="Edit profile details"
        >
          {editing !== null ? (
            <ProfileDetailsEdit
              profile={profile}
              focusField={editing}
              save={updateProfile}
              onCancel={() => setEditing(null)}
              onDone={saved => { setEditing(null); if (saved) setNotice('Your profile was saved.'); }}
            />
          ) : (
            <ProfileDetailsView profile={profile} onOpenSecurity={openSecurity} onAddBio={() => startEdit('bio')} />
          )}
        </EditableSection>

        <SectionCard title="Professional Details" style={profileCell}>
          <View>
            <InfoRow label="Licence number" value={profile.licenseNo} />
            <InfoRow label="Licensing body" value={profile.licensingBody} />
            <InfoRow label="Licence country" value={countryName(profile.licenseCountry)} />
            <InfoRow label="Verification" emptyText="Not available">
              {profile.verificationStatus ? (
                <StatusBadge
                  label={VERIFICATION_LABELS[profile.verificationStatus]}
                  tone={statusTone('verification', profile.verificationStatus)}
                />
              ) : undefined}
            </InfoRow>
          </View>
          <Text style={styles.note}>
            Your licence details and verification status are managed by an administrator. Contact an
            administrator if they need to be corrected.
          </Text>
        </SectionCard>
      </ProfileGrid>

      <ProfileGrid>
        {feeSlot ? <View style={profileCell}>{feeSlot}</View> : null}

        <SectionCard title="Account Details" style={profileCell}>
          <View>
            <InfoRow label="Doctor ID" value={profile.doctorSystemId} />
            <InfoRow label="Joined" value={formatClinicDate(joined, '')} />
            <InfoRow label="Last updated" value={formatClinicDate(profile.userUpdatedAt, '')} />
          </View>
        </SectionCard>
      </ProfileGrid>
    </ProfileTabsBody>,
    <Button
      label="Refresh" icon="refresh-outline" variant="secondary" size="sm"
      onPress={refetch} loading={refreshing}
    />,
  );
}

const styles = StyleSheet.create({
  note: { fontSize: fontSize.xs, color: colors.textMuted, lineHeight: 17 },

  stats:     { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  stat:      { flexGrow: 1, flexShrink: 1, flexBasis: 150, minWidth: 0, flexDirection: 'row', alignItems: 'center', gap: space.md },
  statIcon:  { width: 40, height: 40, borderRadius: 10, alignItems: 'center', justifyContent: 'center', flexShrink: 0 },
  statText:  { flexShrink: 1, minWidth: 0 },
  statValue: { fontSize: fontSize.xl, fontWeight: '700', color: colors.text },
  statLabel: { fontSize: fontSize.xs, color: colors.textMuted },

  details:  { gap: space.md },
  bio:      { gap: space.xs },
  bioLabel: { fontSize: fontSize.sm, color: colors.textMuted },
  bioText:  { fontSize: fontSize.md, color: colors.text, lineHeight: 21 },
  bioEmpty: { gap: space.sm, alignItems: 'flex-start' },
});
