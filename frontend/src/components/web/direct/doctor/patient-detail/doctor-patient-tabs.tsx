import { useMemo, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { PatientAlertsPanel } from '@/components/web/ai';
import { CarePlanList } from '@/components/web/careplans';
import { CareTeamPanel } from '@/components/web/careteam';
import { DocumentList, DocumentTypeFilter, UploadDocumentButton } from '@/components/web/documents';
import { HealthPanel } from '@/components/web/health';
import { ReferralBadges } from '@/components/web/referrals';
import { RemindersPanel } from '@/components/web/reminders';
import {
  ariaLevel, Banner, Button, Card, colors, fontSize, PagedList, ROLE_THEME, space,
} from '@/components/web/ui';
import { useCarePlanBoard, usePatientCarePlans } from '@/hooks/use-care-plans';
import { useCurrentUser } from '@/hooks/use-current-user';
import { useDocuments } from '@/hooks/use-documents';
import { type DoctorPatientDto, patientFullName } from '@/hooks/use-doctor-patients';
import { usePatientReferrals } from '@/hooks/use-referrals';
import { useSectionNav } from '@/hooks/use-section-nav';
import { canChangePlan, isPlanAuthor } from '@/lib/careplans/rules';
import type { DocumentDto, DocumentType } from '@/lib/documents/types';
import { referralDateLabel, specialistSummary } from '@/lib/referrals/format';
import type { Referral } from '@/lib/referrals/types';
import type { PatientDetailTab } from './patient-detail-panel';
import { PatientHistoryTab } from './patient-history-tab';

// The tabs the doctor dashboard adds to the patient detail panel after Overview. The same set is used on the
// Patients section and in the consultation editor, so a doctor sees the same record in both places.
//
// Tab keys are the values of the `tab` URL param on the Patients section. `vitals` is fixed by the backend's
// HEALTH_TRACKING notification link (/dashboard/doctor?section=patients&tab=vitals&patient=<PAT>&id=<symptomId>).

export const PATIENT_TAB_KEYS = {
  history:    'history',
  careTeam:   'care-team',
  vitals:     'vitals',
  alerts:     'alerts',
  carePlans:  'care-plans',
  documents:  'documents',
  referrals:  'referrals',
  reminders:  'reminders',
} as const;

const THEME = ROLE_THEME.doctor;

export interface DoctorPatientTabsOptions {
  /** The symptom a notification link points at (the `id` URL param on tab=vitals); highlighted in the timeline. */
  focusSymptomId?: number | null;
  /** The doctor left the patient's care team and lost access: the host closes the panel and refreshes its list. */
  onAccessLost?: () => void;
}

/** The extra tabs of the doctor's patient detail panel, in display order. */
export function useDoctorPatientTabs({ focusSymptomId = null, onAccessLost }: DoctorPatientTabsOptions = {}): PatientDetailTab[] {
  return useMemo<PatientDetailTab[]>(() => [
    {
      key: PATIENT_TAB_KEYS.history,
      label: 'History',
      render: (id, patient) => <PatientHistoryTab patientSystemId={id} patientName={patientFullName(patient)} />,
    },
    {
      key: PATIENT_TAB_KEYS.careTeam,
      label: 'Care team',
      render: (id, patient) => (
        <CareTeamPanel
          patientSystemId={id}
          patientName={patientFullName(patient)}
          canManage
          canSetPrimary
          framed={false}
          theme={THEME}
          onAccessLost={onAccessLost}
        />
      ),
    },
    {
      key: PATIENT_TAB_KEYS.vitals,
      label: 'Vitals',
      render: (id, patient) => (
        <HealthPanel
          patientSystemId={id}
          canRecord
          theme={THEME}
          patientName={patientFullName(patient)}
          focusSymptomId={focusSymptomId}
        />
      ),
    },
    {
      key: PATIENT_TAB_KEYS.alerts,
      label: 'Alerts',
      render: (id, patient) => <PatientAlertsPanel patientSystemId={id} patientName={patientFullName(patient)} />,
    },
    {
      key: PATIENT_TAB_KEYS.carePlans,
      label: 'Care plans',
      render: (id, patient) => <PatientCarePlansTab key={id} patient={patient} />,
    },
    {
      key: PATIENT_TAB_KEYS.documents,
      label: 'Documents',
      render: (id, patient) => <PatientDocumentsTab key={id} patient={patient} />,
    },
    {
      key: PATIENT_TAB_KEYS.referrals,
      label: 'Referrals',
      render: (id, patient) => <PatientReferralsTab key={id} patient={patient} />,
    },
    {
      key: PATIENT_TAB_KEYS.reminders,
      label: 'Reminders',
      render: id => <RemindersPanel patientSystemId={id} canManage theme={THEME} />,
    },
  ], [focusSymptomId, onAccessLost]);
}

/** The symptom id of a `tab=vitals&id=<symptomId>` link, or null. */
export function focusSymptomParam(tab: string | undefined, id: string | undefined): number | null {
  return tab === PATIENT_TAB_KEYS.vitals && id && /^\d{1,9}$/.test(id) ? Number(id) : null;
}

// ─── Care plans ───────────────────────────────────────────────────────────────

function PatientCarePlansTab({ patient }: { patient: DoctorPatientDto }) {
  const { navigate, hasSection } = useSectionNav();
  const { user } = useCurrentUser();
  const me = user?.system_id ?? null;
  const id = patient.patientSystemId;
  const name = patientFullName(patient);
  // The opened plan stays inside the panel: the `id` URL param of the Patients section belongs to the vitals link.
  const [openId, setOpenId] = useState<number | null>(null);
  const board = useCarePlanBoard(usePatientCarePlans(id), openId, { patientSystemId: id });
  const linked = hasSection('care-plans');

  return (
    <View style={styles.tab}>
      {linked ? (
        <View style={styles.toolbar}>
          <Button
            label="New care plan"
            icon="add"
            size="sm"
            onPress={() => navigate('care-plans', { patient: id, mode: 'new' })}
            accessibilityLabel={`New care plan for ${name}`}
          />
          <Button
            label="Open in Care Plans"
            icon="arrow-forward"
            variant="secondary"
            size="sm"
            onPress={() => navigate('care-plans', { patient: id, tab: 'all', id: openId ?? undefined })}
            accessibilityLabel={`Open in Care Plans, the care plans of ${name}`}
          />
        </View>
      ) : null}
      <CarePlanList
        board={board}
        onOpenChange={setOpenId}
        headingLevel={3}
        label={`Care plans for ${name}`}
        noAccessMessage="You are not part of this patient's care team, so their care plans are not available to you."
        empty={{
          icon: 'clipboard-outline',
          title: 'No care plans yet',
          sub: linked ? `Write a care plan to set goals and review dates for ${name}.` : undefined,
        }}
        authorLabel={plan => (isPlanAuthor(plan, me) ? 'you' : undefined)}
        goalActor={plan => (canChangePlan(plan, me) ? 'doctor' : null)}
        renderActions={linked ? plan => (
          canChangePlan(plan, me) ? (
            <Button
              label="Edit in Care Plans"
              icon="create-outline"
              variant="secondary"
              size="sm"
              onPress={() => navigate('care-plans', { patient: id, tab: 'all', id: plan.planId })}
              accessibilityLabel={`Edit in Care Plans, ${plan.title}`}
            />
          ) : null
        ) : undefined}
      />
    </View>
  );
}

// ─── Documents ────────────────────────────────────────────────────────────────

function PatientDocumentsTab({ patient }: { patient: DoctorPatientDto }) {
  const { navigate, hasSection } = useSectionNav();
  const id = patient.patientSystemId;
  const name = patientFullName(patient);
  const [type, setType] = useState<DocumentType | null>(null);
  const [uploaded, setUploaded] = useState<DocumentDto | null>(null);
  const documents = useDocuments(id, { type });
  const refused = documents.errorStatus === 403 && documents.items.length === 0;

  const onUploaded = (doc: DocumentDto) => {
    setUploaded(doc);
    if (type && type !== doc.documentType) setType(null);
    else documents.refetch();
  };

  return (
    <View style={styles.tab}>
      <View style={styles.toolbar}>
        <UploadDocumentButton
          patientSystemId={id}
          patientName={name}
          defaultType={type}
          onUploaded={onUploaded}
          size="sm"
          disabled={refused}
        />
        {hasSection('documents') ? (
          <Button
            label="View all"
            icon="arrow-forward"
            variant="secondary"
            size="sm"
            onPress={() => navigate('documents', { patient: id })}
            accessibilityLabel={`View all documents of ${name}`}
          />
        ) : null}
      </View>
      {uploaded ? (
        <Banner tone="success" text={`"${uploaded.title}" was added to ${name}'s documents.`} onDismiss={() => setUploaded(null)} />
      ) : null}
      <DocumentList
        state={documents}
        onDeleted={doc => { if (uploaded?.documentId === doc.documentId) setUploaded(null); }}
        header={<DocumentTypeFilter value={type} onChange={setType} />}
        label={`${name}'s documents`}
        empty={{
          title: type ? 'No documents of this type' : 'No documents yet',
          sub: type ? 'Choose another type or show all types.' : 'Files you or the patient add to this record will appear here.',
        }}
      />
    </View>
  );
}

// ─── Referrals ────────────────────────────────────────────────────────────────

function PatientReferralsTab({ patient }: { patient: DoctorPatientDto }) {
  const { navigate, hasSection } = useSectionNav();
  const id = patient.patientSystemId;
  const name = patientFullName(patient);
  const referrals = usePatientReferrals(id, { pageSize: 10 });
  const linked = hasSection('referrals');

  return (
    <View style={styles.tab}>
      {linked ? (
        <View style={styles.toolbar}>
          <Button
            label="New referral"
            icon="arrow-redo-outline"
            size="sm"
            onPress={() => navigate('referrals', { mode: 'new', patient: id })}
            accessibilityLabel={`New referral for ${name}`}
          />
          <Button
            label="View all"
            icon="arrow-forward"
            variant="secondary"
            size="sm"
            onPress={() => navigate('referrals', { patient: id })}
            accessibilityLabel={`View all referrals of ${name}`}
          />
        </View>
      ) : null}
      <PagedList<Referral>
        state={referrals}
        label={`Referrals of ${name}`}
        keyOf={r => r.referralId}
        noAccessMessage="This patient's referrals are not available to you."
        empty={{ icon: 'swap-horizontal-outline', title: 'No referrals yet', sub: `Referrals made for ${name} by any doctor appear here.` }}
        renderItem={r => (
          <Card style={styles.referral}>
            <View style={styles.referralHead}>
              <View style={styles.referralText}>
                <Text style={styles.referralTitle} accessibilityRole="header" {...ariaLevel(3)}>{r.reason}</Text>
                <Text style={styles.meta}>{`To: ${specialistSummary(r)}`}</Text>
                <Text style={styles.meta}>{`From: ${r.referringDoctorName} · Issued ${referralDateLabel(r)}`}</Text>
              </View>
              <ReferralBadges referral={r} />
            </View>
            {linked ? (
              <View style={styles.toolbar}>
                <Button
                  label="View details"
                  variant="secondary"
                  size="sm"
                  onPress={() => navigate('referrals', { patient: id, id: r.referralId })}
                  accessibilityLabel={`View details of the referral: ${r.reason}`}
                />
              </View>
            ) : null}
          </Card>
        )}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  tab:           { gap: space.md, minWidth: 0 },
  toolbar:       { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
  referral:      { gap: space.sm },
  referralHead:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', gap: space.sm },
  referralText:  { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0, gap: 2 },
  referralTitle: { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  meta:          { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
});
