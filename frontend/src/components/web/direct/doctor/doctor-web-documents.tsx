import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { DocumentList, DocumentTypeFilter, UploadDocumentButton } from '@/components/web/documents';
import { PatientPicker } from '@/components/web/pickers';
import { Banner, Card, EmptyView, Screen, space } from '@/components/web/ui';
import { useDocuments } from '@/hooks/use-documents';
import { useSectionNav } from '@/hooks/use-section-nav';
import { type DocumentDto, type DocumentType, isDocumentType } from '@/lib/documents/types';
import type { SectionProps } from '@/lib/nav/sections';

// Doctor dashboard, section "documents": choose a patient, then see, add and download the files in that
// patient's record. There is no list across patients. A doctor can delete only the files they uploaded
// (the server marks those rows with `canDelete`).
//
// URL params: `patient` (the selected patient, PAT-...), `type` (document type filter).

const PATIENT_ID = /^PAT-\d{5,}$/;

export default function DoctorWebDocuments({ params, theme, role = 'doctor' }: Partial<SectionProps>) {
  const nav = useSectionNav();
  const urlParams = params ?? nav.params;

  const patientParam = urlParams.patient?.trim().toUpperCase() ?? '';
  const patientSystemId = PATIENT_ID.test(patientParam) ? patientParam : null;
  const typeParam = urlParams.type?.trim().toUpperCase();
  const type: DocumentType | null = isDocumentType(typeParam) ? typeParam : null;

  // The name is known once the patient was chosen in the picker; a patient that came with the link is named by
  // the picker itself.
  const [picked, setPicked] = useState<{ id: string; name: string } | null>(null);
  const patientName = picked && picked.id === patientSystemId ? picked.name : null;
  const [uploaded, setUploaded] = useState<DocumentDto | null>(null);

  const documents = useDocuments(patientSystemId, { type });

  const setType = (next: DocumentType | null) => nav.setParams({ type: next ?? undefined });

  const onUploaded = (doc: DocumentDto) => {
    setUploaded(doc);
    // Show the new document: drop a filter that would hide it, otherwise reload the list.
    if (type && type !== doc.documentType) setType(null);
    else documents.refetch();
  };

  const refused = documents.errorStatus === 403 && documents.items.length === 0;

  return (
    <Screen
      title="Documents"
      subtitle="Files in a patient's record: reports, letters, consent forms and insurance papers."
      theme={theme}
      role={role}
      actions={patientSystemId ? (
        <UploadDocumentButton
          patientSystemId={patientSystemId}
          patientName={patientName}
          defaultType={type}
          onUploaded={onUploaded}
          // Without a care relationship the server refuses the upload as it refused the list.
          disabled={refused}
        />
      ) : undefined}
    >
      <Card style={styles.picker}>
        <PatientPicker
          value={patientSystemId}
          onChange={(id, patient) => {
            setPicked(id && patient ? { id, name: patient.full_name } : null);
            setUploaded(null);
            nav.setParams({ patient: id ?? undefined });
          }}
          allowEmpty
          placeholder="Select a patient"
          hint="Documents are shown for one patient at a time."
        />
      </Card>

      {!patientSystemId ? (
        <EmptyView
          icon="people-outline"
          title="Choose a patient"
          sub="Select a patient to see the documents in their record and to add a file."
        />
      ) : (
        <View style={styles.body}>
          {uploaded && uploaded.patientSystemId === patientSystemId ? (
            <Banner
              tone="success"
              text={patientName
                ? `"${uploaded.title}" was added to ${patientName}'s documents.`
                : `"${uploaded.title}" was added to the patient's documents.`}
              onDismiss={() => setUploaded(null)}
            />
          ) : null}
          <DocumentList
            state={documents}
            onDeleted={doc => { if (uploaded?.documentId === doc.documentId) setUploaded(null); }}
            header={<DocumentTypeFilter value={type} onChange={setType} />}
            label={patientName ? `${patientName}'s documents` : 'Patient documents'}
            empty={{
              title: type ? 'No documents of this type' : 'No documents yet',
              sub: type
                ? 'Choose another type or show all types.'
                : 'Files you or the patient add to this record will appear here.',
            }}
          />
        </View>
      )}
    </Screen>
  );
}

const styles = StyleSheet.create({
  picker: { maxWidth: 480, width: '100%' },
  body:   { gap: space.md, minWidth: 0 },
});
