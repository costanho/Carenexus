import type { ReactNode } from 'react';
import { StyleSheet, View } from 'react-native';
import { PatientCorrectionRequestsList, RequestCorrectionButton } from '@/components/web/corrections';
import { AttachmentButton, DependentDocumentsPanel, DocumentRow } from '@/components/web/documents';
import { DependentReferralsPanel } from '@/components/web/referrals';
import { Banner, Button, LoadingView, NoAccessView, SectionCard, space } from '@/components/web/ui';
import type { CaregiverDependent } from '@/hooks/use-caregiver-dependents';
import { useDocument, useDownloadDocument } from '@/hooks/use-documents';
import { type CaregiverFlag, effectivePermission, isGrantActive } from '@/lib/caregiver/permissions';
import type { CorrectionResourceType } from '@/lib/corrections/types';
import type { DocumentLinkType } from '@/lib/documents/types';
import type { SectionProps } from '@/lib/nav/sections';
import { parseReferralId } from '@/lib/referrals/params';
import PatientProxyDocuments, {
  type ProxyDocumentsExtraTab, type ProxyDocumentsTabContext,
} from '../patient-proxy-documents';
import {
  type AttachmentRenderer, type DependentRecordCardActions, dependentName, imagingTitle, parseId,
  type RecordAttachment,
} from '../dependent-records';

// The caregiver's Documents & Results section as the dashboard mounts it: the four built-in record tabs plus
//   tab=documents    the loved one's documents (DOCUMENTS notifications), upload with "manage care plan"
//   tab=referrals    their referrals, `id` brings one forward (REFERRALS notifications)
//   tab=corrections  the correction requests about their records, `id` marks one (CORRECTIONS notifications)
// and, on the record cards, "Request a correction" when the grant allows managing the care plan. Every tab
// reads the loved one from the `patient` URL param; without it each loved one in the list gets a block.

/** Whether a link's `id` belongs to this loved one: the link names them, or they are the only one in view. */
function linkedTo(context: ProxyDocumentsTabContext, dependent: CaregiverDependent): boolean {
  return context.patientSystemId === dependent.patientSystemId
    || (context.patientSystemId === null && context.dependents.length === 1);
}

/** One titled block per loved one in view (the same layout as the built-in tabs). */
function PerDependent({ dependents, render }: {
  dependents: readonly CaregiverDependent[];
  render:     (dependent: CaregiverDependent) => ReactNode;
}) {
  return (
    <View style={styles.blocks}>
      {dependents.map(dependent => (
        <SectionCard key={dependent.patientSystemId} title={dependentName(dependent)}>
          {render(dependent)}
        </SectionCard>
      ))}
    </View>
  );
}

function CorrectionsBlock({ dependent, highlightId }: { dependent: CaregiverDependent; highlightId: string | undefined }) {
  const name = dependentName(dependent);
  // Listing and reading requests needs the records permission; filing one needs "manage care plan" only, so a
  // caregiver may see the button on a card and still have no access here.
  if (!effectivePermission(dependent, 'canViewRecords')) {
    return (
      <NoAccessView
        compact
        message={isGrantActive(dependent)
          ? `Correction requests for ${name} are not shared with you.`
          : `Your access to ${name} has ended.`}
        sub="Seeing correction requests needs permission to view records. Your loved one can change this in their caregiver settings."
      />
    );
  }
  return (
    <PatientCorrectionRequestsList
      patientSystemId={dependent.patientSystemId}
      highlightId={highlightId}
      noAccessMessage={`Correction requests for ${name} are not shared with you.`}
    />
  );
}

/**
 * The document a DOCUMENTS notification points at (`tab=documents&patient=<PAT id>&id=<documentId>`), shown
 * above the lists with a download control. The server decides whether the caregiver may read it.
 */
function LinkedDocument({ documentId, patientSystemId, onClose }: {
  documentId:      number;
  patientSystemId: string;
  onClose:         () => void;
}) {
  const linked = useDocument(documentId);
  const downloads = useDownloadDocument();
  const doc = linked.data && linked.data.patientSystemId === patientSystemId ? linked.data : null;

  let body: ReactNode;
  if (linked.loading) {
    body = <LoadingView compact label="Loading the document from your notification" />;
  } else if (doc) {
    body = (
      <>
        {downloads.failure ? (
          <Banner tone="danger" text={downloads.failure.error.message} onDismiss={downloads.reset} />
        ) : null}
        <DocumentRow
          document={doc}
          onDownload={d => { void downloads.download(d); }}
          downloading={downloads.isDownloading(doc.documentId)}
          highlight="From your notification"
        />
      </>
    );
  } else if (linked.errorStatus === 403) {
    body = <Banner tone="warning" text="The document from your notification is not shared with you." />;
  } else if (linked.errorStatus === 404 || linked.data) {
    body = <Banner tone="info" text="The document from your notification is no longer available." />;
  } else {
    body = (
      <Banner
        tone="danger"
        text={linked.error ?? 'The document could not be loaded. Please try again.'}
        action={{ label: 'Retry', onPress: linked.refetch }}
      />
    );
  }
  return (
    <View style={styles.linked}>
      {body}
      <View style={styles.linkedActions}>
        <Button label="Dismiss" variant="ghost" size="sm" accessibilityLabel="Dismiss the document from your notification" onPress={onClose} />
      </View>
    </View>
  );
}

const EXTRA_TABS: readonly ProxyDocumentsExtraTab[] = [
  {
    key: 'documents',
    label: 'Documents',
    // The panel draws its own card, titled with the loved one's name in the subtitle, and does its own gating.
    render: context => {
      const linkedId = parseId(context.params.id);
      const owner = linkedId === null ? undefined : context.dependents.find(d => linkedTo(context, d));
      return (
        <View style={styles.blocks}>
          {linkedId !== null && owner ? (
            <LinkedDocument
              key={linkedId}
              documentId={linkedId}
              patientSystemId={owner.patientSystemId}
              onClose={() => context.setParams({ id: undefined })}
            />
          ) : null}
          {context.dependents.map(dependent => (
            <DependentDocumentsPanel key={dependent.patientSystemId} dependent={dependent} />
          ))}
        </View>
      );
    },
  },
  {
    key: 'referrals',
    label: 'Referrals',
    render: context => (
      <PerDependent
        dependents={context.dependents}
        render={dependent => (
          <DependentReferralsPanel
            dependent={dependent}
            highlightId={linkedTo(context, dependent) ? parseReferralId(context.params.id) : null}
            theme={context.theme}
          />
        )}
      />
    ),
  },
  {
    key: 'corrections',
    label: 'Corrections',
    render: context => (
      <PerDependent
        dependents={context.dependents}
        render={dependent => (
          <CorrectionsBlock
            dependent={dependent}
            highlightId={linkedTo(context, dependent) ? context.params.id : undefined}
          />
        )}
      />
    ),
  },
];

/** "Request a correction" on a record card, offered when the grant allows managing the care plan. */
function correctionButton(
  dependent: CaregiverDependent, resourceType: CorrectionResourceType, resourceId: number, recordLabel: string,
): ReactNode {
  return (
    <RequestCorrectionButton
      patientSystemId={dependent.patientSystemId}
      resourceType={resourceType}
      resourceId={resourceId}
      canRequest={effectivePermission(dependent, 'canManageCarePlan')}
      recordLabel={`${recordLabel} for ${dependentName(dependent)}`}
    />
  );
}

const CARD_ACTIONS: DependentRecordCardActions = {
  labs:          (row, dependent) => correctionButton(dependent, 'LAB_RESULT', row.resultId, row.testName),
  imaging:       (row, dependent) => correctionButton(dependent, 'IMAGING_RESULT', row.imagingId, imagingTitle(row)),
  records:       (row, dependent) => correctionButton(dependent, 'MEDICAL_RECORD', row.recordId, row.title),
  consultations: (row, dependent) => correctionButton(
    dependent, 'CONSULTATION', row.consultationId, (row.chiefComplaint ?? '').trim() || 'Consultation',
  ),
};

const ATTACHMENT_LINK: Record<RecordAttachment['kind'], { linkType: DocumentLinkType; flag: CaregiverFlag }> = {
  labs:    { linkType: 'LAB_RESULT',     flag: 'canViewLabResults' },
  imaging: { linkType: 'IMAGING_RESULT', flag: 'canViewImaging' },
  records: { linkType: 'MEDICAL_RECORD', flag: 'canViewRecords' },
};

/**
 * Files attached to a result or record, inside its detail view: a download list, shown only when the grant
 * covers that kind of record and something is attached (loaded with the detail, one request per opened record).
 */
const renderAttachment: AttachmentRenderer = attachment => {
  const link = ATTACHMENT_LINK[attachment.kind];
  if (!effectivePermission(attachment.dependent, link.flag)) return null;
  return (
    <AttachmentButton
      linkType={link.linkType}
      linkId={attachment.resourceId}
      documentId={attachment.documentId}
      patientSystemId={attachment.patientSystemId}
      eager
    />
  );
};

/** `?section=documents-results`: see the header comment. */
export default function ProxyDocumentsSection(props: Partial<SectionProps>) {
  return (
    <PatientProxyDocuments
      {...props}
      extraTabs={EXTRA_TABS}
      cardActions={CARD_ACTIONS}
      renderAttachment={renderAttachment}
    />
  );
}

const styles = StyleSheet.create({
  blocks:        { gap: space.lg, minWidth: 0 },
  linked:        { gap: space.sm, minWidth: 0 },
  linkedActions: { flexDirection: 'row', justifyContent: 'flex-end' },
});
