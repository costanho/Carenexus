import { useMemo, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { Banner, NoAccessView, SectionCard, space } from '@/components/web/ui';
import { useDocuments } from '@/hooks/use-documents';
import { type CaregiverGrant, effectivePermission, isGrantActive } from '@/lib/caregiver/permissions';
import {
  CAREGIVER_MANAGE_DOCUMENTS_FLAG, caregiverReadFlag, DOCUMENT_TYPES, type DocumentDto, type DocumentType,
} from '@/lib/documents/types';
import { DocumentList } from './document-list';
import { DocumentTypeFilter } from './document-type-filter';
import { UploadDocumentButton } from './upload-document-button';

// A loved one's documents for the caregiver dashboard (Documents & Results). What is shown follows the grant,
// decided with the shared permission rule; the server checks every request again and its refusal is shown as sent.
//
//   viewing documents            canViewRecords (lab reports and imaging files also need their own flag)
//   uploading and deleting       canManageCarePlan (the backend permission MANAGE_DOCUMENTS)

/** The caregiver's grant for the loved one: `CaregiverDependent` from use-caregiver-dependents fits. */
export interface DocumentsDependent extends CaregiverGrant {
  patientSystemId: string;
  patientName?:    string | null;
}

export interface DependentDocumentsPanelProps {
  dependent: DocumentsDependent;
}

export function DependentDocumentsPanel({ dependent }: DependentDocumentsPanelProps) {
  // Keyed by the person: the filter and the "added" notice of one loved one never carry over to another.
  return <Panel key={dependent.patientSystemId} dependent={dependent} />;
}

function Panel({ dependent }: DependentDocumentsPanelProps) {
  const active    = isGrantActive(dependent);
  const canView   = effectivePermission(dependent, 'canViewRecords');
  const canManage = effectivePermission(dependent, CAREGIVER_MANAGE_DOCUMENTS_FLAG);
  const canViewLabs    = effectivePermission(dependent, 'canViewLabResults');
  const canViewImaging = effectivePermission(dependent, 'canViewImaging');

  const name = dependent.patientName?.trim() || 'your loved one';
  const patientSystemId = dependent.patientSystemId;

  // Only the types this grant may read are offered: filtering by another one would be refused by the server.
  const types = useMemo<DocumentType[]>(() => DOCUMENT_TYPES.filter(type => {
    const flag = caregiverReadFlag(type);
    if (flag === 'canViewLabResults') return canViewLabs;
    if (flag === 'canViewImaging') return canViewImaging;
    return canView;
  }), [canView, canViewLabs, canViewImaging]);

  const [type, setType] = useState<DocumentType | null>(null);
  const activeType = type && types.includes(type) ? type : null;
  const [uploaded, setUploaded] = useState<DocumentDto | null>(null);

  const documents = useDocuments(patientSystemId, { type: activeType }, { enabled: canView });

  const onUploaded = (doc: DocumentDto) => {
    setUploaded(doc);
    // Show the new document: drop a filter that would hide it, otherwise reload the list.
    if (activeType && activeType !== doc.documentType) setType(null);
    else documents.refetch();
  };

  const upload = canManage ? (
    <UploadDocumentButton
      patientSystemId={patientSystemId}
      patientName={dependent.patientName}
      defaultType={activeType}
      onUploaded={onUploaded}
      size="sm"
    />
  ) : null;

  const uploadedNotice = uploaded ? (
    <Banner
      tone="success"
      text={`"${uploaded.title}" was added to ${name}'s documents.`}
      onDismiss={() => setUploaded(null)}
    />
  ) : null;

  if (!canView) {
    return (
      <SectionCard title="Documents" subtitle={`Files in ${name}'s health record`} action={upload}>
        {uploadedNotice}
        <NoAccessView
          compact
          message={active
            ? `Your access for ${name} does not include viewing documents.`
            : `You don't have active access to ${name}'s documents.`}
          sub={!active
            ? `Ask ${name} to renew your caregiver access.`
            : canManage
              ? `You can still add a document for ${name}. To see their documents, ask ${name} to allow viewing records.`
              : `Ask ${name} to allow viewing records in their caregiver access settings.`}
        />
      </SectionCard>
    );
  }

  return (
    <SectionCard title="Documents" subtitle={`Files in ${name}'s health record`} action={upload}>
      <View style={styles.body}>
        {uploadedNotice}
        <DocumentList
          state={documents}
          // Deleting needs the manage permission as well as being the uploader (`canDelete` from the server).
          allowDelete={canManage}
          header={<DocumentTypeFilter value={activeType} onChange={setType} types={types} />}
          label={`${name}'s documents`}
          empty={{
            title: activeType ? 'No documents of this type' : 'No documents yet',
            sub: activeType
              ? 'Choose another type or show all types.'
              : canManage
                ? `Documents added for ${name} will appear here.`
                : `Documents in ${name}'s record will appear here.`,
          }}
        />
      </View>
    </SectionCard>
  );
}

const styles = StyleSheet.create({
  body: { gap: space.md, minWidth: 0 },
});
