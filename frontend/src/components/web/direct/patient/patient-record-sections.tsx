import { useCallback, useMemo } from 'react';
import { PatientCorrectionRequestsList, RequestCorrectionButton } from '@/components/web/corrections';
import { AttachmentButton } from '@/components/web/documents';
import { IMAGING_TYPE_LABELS, type RecordTab } from '@/components/web/records';
import { ErrorView, LoadingView } from '@/components/web/ui';
import { useCurrentUser } from '@/hooks/use-current-user';
import type { Consultation } from '@/hooks/use-consultations';
import type { ImagingResultRecord } from '@/hooks/use-imaging-results';
import type { LabResult } from '@/hooks/use-lab-results';
import type { MedicalRecord } from '@/hooks/use-medical-records';
import { useSectionNav } from '@/hooks/use-section-nav';
import type { SectionProps } from '@/lib/nav/sections';
import { PatientWebConsultationHistory } from './patient-web-consultation-history';
import { PatientWebImagingResults } from './patient-web-imaging-results';
import { PatientWebLabResults } from './patient-web-lab-results';
import { PatientWebMedicalRecords } from './patient-web-medical-records';

// The patient's four record sections as the dashboard registers them: each record screen with the controls the
// dashboard adds to its cards (attachments and "Request a correction"), and on Medical Records the Corrections
// tab (`?section=medical-records&tab=corrections[&id=<requestId>]`, the CORRECTIONS notification link).
//
// The cards only get controls once the signed-in patient's id is known; until then they render without them.

/** The signed-in patient's system id, or null while it is loading or when it could not be read. */
function useOwnSystemId(): string | null {
  const { user } = useCurrentUser();
  return user?.system_id ?? null;
}

/** Display name of an imaging study for assistive technology ("MRI, left knee"). */
function imagingLabel(item: ImagingResultRecord): string {
  const type = (IMAGING_TYPE_LABELS[item.imaging_type] ?? IMAGING_TYPE_LABELS.XRAY).label;
  return item.body_part ? `${type}, ${item.body_part}` : type;
}

function consultationLabel(item: Consultation): string {
  return item.chief_complaint || item.diagnosis || 'Consultation';
}

export function PatientLabResultsSection(props: Partial<SectionProps>) {
  const own = useOwnSystemId();
  const cardActions = useCallback((item: LabResult) => (own ? (
    <>
      <AttachmentButton
        linkType="LAB_RESULT"
        linkId={item.result_id}
        patientSystemId={own}
        documentId={item.document_id}
        label={item.document_id ? 'View report' : 'Attachments'}
      />
      <RequestCorrectionButton
        patientSystemId={own}
        resourceType="LAB_RESULT"
        resourceId={item.result_id}
        recordLabel={item.test_name}
      />
    </>
  ) : null), [own]);
  return <PatientWebLabResults {...props} cardActions={cardActions} />;
}

export function PatientImagingResultsSection(props: Partial<SectionProps>) {
  const own = useOwnSystemId();
  const cardActions = useCallback((item: ImagingResultRecord) => (own ? (
    <>
      <AttachmentButton
        linkType="IMAGING_RESULT"
        linkId={item.imaging_id}
        patientSystemId={own}
        documentId={item.document_id}
        label={item.document_id ? 'View images' : 'Attachments'}
      />
      <RequestCorrectionButton
        patientSystemId={own}
        resourceType="IMAGING_RESULT"
        resourceId={item.imaging_id}
        recordLabel={imagingLabel(item)}
      />
    </>
  ) : null), [own]);
  return <PatientWebImagingResults {...props} cardActions={cardActions} />;
}

export function PatientConsultationHistorySection(props: Partial<SectionProps>) {
  const own = useOwnSystemId();
  const cardActions = useCallback((item: Consultation) => (own ? (
    <>
      <AttachmentButton linkType="CONSULTATION" linkId={item.consultation_id} patientSystemId={own} />
      <RequestCorrectionButton
        patientSystemId={own}
        resourceType="CONSULTATION"
        resourceId={item.consultation_id}
        recordLabel={consultationLabel(item)}
      />
    </>
  ) : null), [own]);
  return <PatientWebConsultationHistory {...props} cardActions={cardActions} />;
}

/** The Corrections tab of Medical Records: the patient's correction requests, the linked one (`id`) first. */
function CorrectionsTab({ highlightId }: { highlightId: string | undefined }) {
  const { user, loading, error, refetch } = useCurrentUser();
  if (!user?.system_id) {
    return loading
      ? <LoadingView label="Loading your correction requests" />
      : <ErrorView message={error ?? 'Your account could not be loaded.'} onRetry={refetch} />;
  }
  return <PatientCorrectionRequestsList patientSystemId={user.system_id} highlightId={highlightId ?? null} />;
}

export function PatientMedicalRecordsSection(props: Partial<SectionProps>) {
  const own = useOwnSystemId();
  const nav = useSectionNav();
  const params = props.params ?? nav.params;
  // With tab=corrections the `id` URL param is a correction request id (the screen leaves it to this tab).
  const highlightId = params.tab === 'corrections' ? params.id : undefined;

  const cardActions = useCallback((item: MedicalRecord) => (own ? (
    <>
      <AttachmentButton linkType="MEDICAL_RECORD" linkId={item.record_id} patientSystemId={own} />
      <RequestCorrectionButton
        patientSystemId={own}
        resourceType="MEDICAL_RECORD"
        resourceId={item.record_id}
        recordLabel={item.title}
      />
    </>
  ) : null), [own]);

  const extraTabs = useMemo<RecordTab[]>(() => [
    { key: 'corrections', label: 'Corrections', render: () => <CorrectionsTab highlightId={highlightId} /> },
  ], [highlightId]);

  return <PatientWebMedicalRecords {...props} cardActions={cardActions} extraTabs={extraTabs} />;
}
