import { zodResolver } from '@hookform/resolvers/zod';
import { type ReactNode, useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import { EmergencyContactsCard } from '@/components/web/profile/emergency-contacts-card';
import {
  EditableSection, EmailRow, initialsOf, NamePhoneFields, ProfileForm, ProfileGrid, ProfileIdentity,
  ProfileTabsBody, profileCell, useProfileSave, useProfileTab,
} from '@/components/web/profile/profile-shared';
import {
  Banner, Button, colors, DateField, ErrorView, fontSize, FormActions, FormError, InfoRow, LoadingView, radius,
  Screen, SectionCard, SelectField, type SelectOption, space, StatusBadge, statusLabel, statusTone, TagListField,
  useUiTheme,
} from '@/components/web/ui';
import {
  BLOOD_TYPES, type BloodType, type Gender, parseProfileList, type UpdateUserProfile, type UserProfile,
} from '@/hooks/use-my-profile';
import { useEmergencyContacts } from '@/hooks/use-emergency-contacts';
import { usePatientProfile } from '@/hooks/use-patient-profile';
import type { SectionProps } from '@/lib/nav/sections';
import { clinicToday, formatClinicDate } from '@/lib/time/clinic';
import { ageFromDateOnly, dateOnlyParts, formatDateOnly } from '@/lib/ui/date-only';
import {
  type MedicalProfileForm, medicalProfileChanges, medicalProfileDefaults, medicalProfileSchema,
  type PersonalDetailsForm, personalDetailsChanges, personalDetailsDefaults, personalDetailsSchema, PROFILE_LIMITS,
} from '@/lib/validation/profile';

// The patient's own profile: personal details, medical profile and emergency contacts, each editable in
// place. Health status is set by the care team and shown read-only; the sign-in email is changed from the
// Security tab, which is shown when the dashboard passes a `securityPanel`.

export interface PatientWebProfileProps extends Partial<SectionProps> {
  /** Content of the Security tab (`?tab=security`); without it the screen has no tabs. */
  securityPanel?: ReactNode;
}

const GENDER_LABELS: Record<Gender, string> = { MALE: 'Male', FEMALE: 'Female', OTHER: 'Other' };

const GENDER_OPTIONS: readonly SelectOption<Gender>[] = [
  { value: 'MALE',   label: 'Male' },
  { value: 'FEMALE', label: 'Female' },
  { value: 'OTHER',  label: 'Other' },
];

const BLOOD_TYPE_OPTIONS: readonly SelectOption<BloodType>[] = BLOOD_TYPES.map(value => ({ value, label: value }));

const LONG_DATE: Intl.DateTimeFormatOptions = { day: 'numeric', month: 'long', year: 'numeric' };

/** The calendar day before a "YYYY-MM-DD" date. */
function dayBefore(date: string): string {
  const p = dateOnlyParts(date);
  return p ? new Date(Date.UTC(p.y, p.m - 1, p.d - 1)).toISOString().slice(0, 10) : date;
}

function ageText(dateOfBirth: string | null): string | null {
  const age = ageFromDateOnly(dateOfBirth);
  return age === null ? null : `${age} ${age === 1 ? 'year' : 'years'}`;
}

type Save = (changes: UpdateUserProfile) => Promise<unknown>;

// ─── Personal information ─────────────────────────────────────────────────────

const PERSONAL_FIELDS = ['firstName', 'lastName', 'phone', 'dateOfBirth', 'gender'] as const;

function PersonalDetailsEdit({ profile, save, onDone, onCancel }: {
  profile:  UserProfile;
  save:     Save;
  onDone:   (saved: boolean) => void;
  onCancel: () => void;
}) {
  const schema = useMemo(
    () => personalDetailsSchema({ hasPhone: !!profile.phone, hasDateOfBirth: !!profile.dateOfBirth }),
    [profile.phone, profile.dateOfBirth],
  );
  const { control, handleSubmit, setError, setFocus } = useForm<PersonalDetailsForm>({
    resolver: zodResolver(schema),
    defaultValues: personalDetailsDefaults(profile),
  });
  const { submit, pending, formError } = useProfileSave({
    handleSubmit, setError, fields: PERSONAL_FIELDS,
    changesOf: (values: PersonalDetailsForm) => personalDetailsChanges(profile, values),
    save, onDone,
  });
  useEffect(() => { setFocus('firstName'); }, [setFocus]);

  return (
    <ProfileForm>
      <FormError message={formError} />
      <NamePhoneFields control={control} onSubmit={submit} phoneRequired={!!profile.phone} />
      <DateField
        label="Date of birth" control={control} name="dateOfBirth"
        // A date of birth is before today's date at the clinic.
        max={dayBefore(clinicToday())}
      />
      <SelectField<Gender, PersonalDetailsForm>
        label="Gender" control={control} name="gender"
        options={GENDER_OPTIONS} allowEmpty placeholder="Not specified"
      />
      <FormActions submitLabel="Save changes" onSubmit={submit} onCancel={onCancel} submitting={pending} />
    </ProfileForm>
  );
}

function PersonalDetailsView({ profile, onOpenSecurity }: { profile: UserProfile; onOpenSecurity?: () => void }) {
  return (
    <View>
      <InfoRow label="First name" value={profile.firstName} />
      <InfoRow label="Last name" value={profile.lastName} />
      <InfoRow label="Date of birth" value={formatDateOnly(profile.dateOfBirth, LONG_DATE, '')} />
      <InfoRow label="Age" value={ageText(profile.dateOfBirth)} />
      <InfoRow label="Gender" value={profile.gender ? GENDER_LABELS[profile.gender] : null} emptyText="Not specified" />
      <InfoRow label="Phone number" value={profile.phone} />
      <EmailRow email={profile.email} onOpenSecurity={onOpenSecurity} />
    </View>
  );
}

// ─── Medical profile ──────────────────────────────────────────────────────────

const MEDICAL_FIELDS = ['bloodType', 'allergies', 'chronicConditions'] as const;

function MedicalProfileEdit({ profile, save, onDone, onCancel }: {
  profile:  UserProfile;
  save:     Save;
  onDone:   (saved: boolean) => void;
  onCancel: () => void;
}) {
  const { control, handleSubmit, setError, setFocus } = useForm<MedicalProfileForm>({
    resolver: zodResolver(medicalProfileSchema),
    defaultValues: medicalProfileDefaults(profile),
  });
  const { submit, pending, formError } = useProfileSave({
    handleSubmit, setError, fields: MEDICAL_FIELDS,
    changesOf: (values: MedicalProfileForm) => medicalProfileChanges(profile, values),
    save, onDone,
  });
  useEffect(() => { setFocus('bloodType'); }, [setFocus]);

  return (
    <ProfileForm>
      <FormError message={formError} />
      <SelectField<BloodType, MedicalProfileForm>
        label="Blood type" control={control} name="bloodType"
        options={BLOOD_TYPE_OPTIONS} allowEmpty placeholder="Not known"
      />
      <TagListField
        label="Allergies" control={control} name="allergies"
        hint="Type an allergy and press Enter. Remove all entries if you have none."
        placeholder="For example: penicillin"
        maxItems={PROFILE_LIMITS.listEntries} maxLength={PROFILE_LIMITS.listEntry}
      />
      <TagListField
        label="Chronic conditions" control={control} name="chronicConditions"
        hint="Type a condition and press Enter. Remove all entries if you have none."
        placeholder="For example: asthma"
        maxItems={PROFILE_LIMITS.listEntries} maxLength={PROFILE_LIMITS.listEntry}
      />
      <FormActions submitLabel="Save changes" onSubmit={submit} onCancel={onCancel} submitting={pending} />
    </ProfileForm>
  );
}

function TagList({ label, items }: { label: string; items: string[] }) {
  const { accentBg } = useUiTheme();
  return (
    <View style={styles.tagBlock}>
      <Text style={styles.tagLabel}>{label}</Text>
      {items.length === 0 ? (
        <Text style={styles.none}>None recorded</Text>
      ) : (
        <View style={styles.tags} role="list" aria-label={label}>
          {items.map((item, index) => (
            <View key={`${index}-${item}`} role="listitem" style={[styles.tag, { backgroundColor: accentBg }]}>
              <Text style={styles.tagText}>{item}</Text>
            </View>
          ))}
        </View>
      )}
    </View>
  );
}

function MedicalProfileView({ profile }: { profile: UserProfile }) {
  return (
    <View style={styles.medicalView}>
      <InfoRow label="Blood type" value={profile.bloodType} emptyText="Not known" />
      <TagList label="Allergies" items={parseProfileList(profile.allergies)} />
      <TagList label="Chronic conditions" items={parseProfileList(profile.chronicConditions)} />
    </View>
  );
}

function HealthStatusRow({ profile }: { profile: UserProfile }) {
  return (
    <View>
      <InfoRow label="Health status" emptyText="Not set">
        {profile.healthStatus ? (
          <StatusBadge label={statusLabel(profile.healthStatus)} tone={statusTone('health', profile.healthStatus)} />
        ) : undefined}
      </InfoRow>
      <Text style={styles.note}>Your health status is set by your care team and cannot be changed here.</Text>
    </View>
  );
}

// ─── Screen ───────────────────────────────────────────────────────────────────

export function PatientWebProfile({ params, theme, securityPanel }: PatientWebProfileProps) {
  const { raw: profile, loading, refreshing, error, refetch, updateProfile } = usePatientProfile();
  // Only a PATIENT account has emergency contacts; the list loads once the profile says who is signed in.
  const emergencyContacts = useEmergencyContacts({ enabled: profile?.role === 'PATIENT' });
  const { tab, setTab } = useProfileTab(params, !!securityPanel);
  const [editingPersonal, setEditingPersonal] = useState(false);
  const [editingMedical, setEditingMedical] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const frame = (children: ReactNode, actions?: ReactNode) => (
    <Screen
      title="Profile & Settings"
      subtitle="Your personal and health information"
      theme={theme}
      role="patient"
      actions={actions}
    >
      {children}
    </Screen>
  );

  if (loading) return frame(<LoadingView label="Loading profile" />);
  if (!profile) return frame(<ErrorView message={error ?? 'Profile not found.'} onRetry={refetch} />);

  const fullName = `${profile.firstName} ${profile.lastName}`.trim();
  const patientId = profile.patientSystemId ?? profile.systemId;
  const isPatient = profile.role === 'PATIENT';
  const openSecurity = securityPanel ? () => setTab('security') : undefined;

  return frame(
    <ProfileTabsBody tab={tab} onTabChange={setTab} securityPanel={securityPanel}>
      {error ? <Banner tone="danger" text={error} action={{ label: 'Retry', onPress: refetch }} /> : null}
      {notice ? <Banner tone="success" text={notice} onDismiss={() => setNotice(null)} /> : null}

      <ProfileIdentity
        name={fullName}
        initials={initialsOf(profile.firstName, profile.lastName)}
        badges={(
          <>
            <StatusBadge label={statusLabel(profile.role)} tone="neutral" icon="person-outline" />
            {profile.healthStatus ? (
              <StatusBadge label={statusLabel(profile.healthStatus)} tone={statusTone('health', profile.healthStatus)} />
            ) : null}
          </>
        )}
        facts={[
          ...(patientId ? [{ label: 'Patient ID', value: patientId }] : []),
          ...(profile.createdAt ? [{ label: 'Member since', value: formatClinicDate(profile.createdAt, '—') }] : []),
        ]}
      />

      <ProfileGrid>
        <EditableSection
          title="Personal Information"
          editing={editingPersonal}
          onEdit={() => { setNotice(null); setEditingPersonal(true); }}
          editLabel="Edit personal information"
        >
          {editingPersonal ? (
            <PersonalDetailsEdit
              profile={profile}
              save={updateProfile}
              onCancel={() => setEditingPersonal(false)}
              onDone={saved => { setEditingPersonal(false); if (saved) setNotice('Your personal information was saved.'); }}
            />
          ) : (
            <PersonalDetailsView profile={profile} onOpenSecurity={openSecurity} />
          )}
        </EditableSection>

        <EditableSection
          title="Medical Profile"
          editing={editingMedical}
          onEdit={() => { setNotice(null); setEditingMedical(true); }}
          editLabel="Edit medical profile"
        >
          <HealthStatusRow profile={profile} />
          {editingMedical ? (
            <MedicalProfileEdit
              profile={profile}
              save={updateProfile}
              onCancel={() => setEditingMedical(false)}
              onDone={saved => { setEditingMedical(false); if (saved) setNotice('Your medical profile was saved.'); }}
            />
          ) : (
            <MedicalProfileView profile={profile} />
          )}
        </EditableSection>
      </ProfileGrid>

      <ProfileGrid>
        {isPatient ? <EmergencyContactsCard state={emergencyContacts} /> : null}

        <SectionCard title="Account Details" style={profileCell}>
          <View>
            <InfoRow label="Patient ID" value={patientId} />
            <InfoRow label="Role" value={statusLabel(profile.role)} />
            <InfoRow label="Account status">
              <StatusBadge
                label={profile.isActive ? 'Active' : 'Inactive'}
                tone={statusTone('account', profile.isActive ? 'ACTIVE' : 'INACTIVE')}
              />
            </InfoRow>
            <InfoRow label="Registered" value={formatClinicDate(profile.createdAt, '')} />
            <InfoRow label="Last updated" value={formatClinicDate(profile.updatedAt, '')} />
          </View>
        </SectionCard>
      </ProfileGrid>
    </ProfileTabsBody>,
    <Button
      label="Refresh" icon="refresh-outline" variant="secondary" size="sm"
      onPress={() => { refetch(); if (isPatient) emergencyContacts.refetch(); }}
      loading={refreshing || emergencyContacts.refreshing}
    />,
  );
}

export default PatientWebProfile;

const styles = StyleSheet.create({
  note: { fontSize: fontSize.xs, color: colors.textMuted, lineHeight: 17 },

  medicalView: { gap: space.md },
  tagBlock:    { gap: space.xs },
  tagLabel:    { fontSize: fontSize.sm, color: colors.textMuted },
  none:        { fontSize: fontSize.md, color: colors.textSubtle },
  tags:        { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs },
  tag:         { maxWidth: '100%', paddingHorizontal: space.md, paddingVertical: space.xs, borderRadius: radius.pill },
  tagText:     { fontSize: fontSize.sm, color: colors.text },
});
