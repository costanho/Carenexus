import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { DocumentList, DocumentTypeFilter, UploadDocumentButton } from '@/components/web/documents';
import { Banner, ErrorView, LoadingView, Screen, space } from '@/components/web/ui';
import { useCurrentUser } from '@/hooks/use-current-user';
import { useDocument, useDocuments } from '@/hooks/use-documents';
import { useSectionNav } from '@/hooks/use-section-nav';
import { type DocumentDto, type DocumentType, isDocumentType } from '@/lib/documents/types';
import type { SectionProps } from '@/lib/nav/sections';

// Patient dashboard, section "documents": the files in the patient's own record. The patient can filter them
// by type, add a file, download any of them and delete them. A notification link
// (/dashboard/patient?section=documents&id=<documentId>) shows that document first.
//
// URL params: `type` (document type filter), `id` (document opened from a link).

function documentIdParam(raw: string | undefined): number | null {
  const value = raw?.trim() ?? '';
  if (!/^\d{1,9}$/.test(value)) return null;
  const id = Number(value);
  return id > 0 ? id : null;
}

export default function PatientWebDocuments({ params, theme, role = 'patient' }: Partial<SectionProps>) {
  const nav = useSectionNav();
  const urlParams = params ?? nav.params;

  const { user, loading: userLoading, error: userError, refetch: refetchUser } = useCurrentUser();
  const patientSystemId = user?.system_id ?? null;

  const typeParam = urlParams.type?.trim().toUpperCase();
  const type: DocumentType | null = isDocumentType(typeParam) ? typeParam : null;
  const linkedId = documentIdParam(urlParams.id);

  const documents = useDocuments(patientSystemId, { type });
  const linked = useDocument(linkedId, { enabled: !!patientSystemId });
  const [uploaded, setUploaded] = useState<DocumentDto | null>(null);

  const setType = (next: DocumentType | null) => nav.setParams({ type: next ?? undefined });
  const closeLinked = () => nav.setParams({ id: undefined });

  const onUploaded = (doc: DocumentDto) => {
    setUploaded(doc);
    // Show the new document: drop a filter that would hide it, otherwise reload the list.
    if (type && type !== doc.documentType) setType(null);
    else documents.refetch();
  };

  const onDeleted = (doc: DocumentDto) => {
    if (doc.documentId === linkedId) closeLinked();
    if (uploaded?.documentId === doc.documentId) setUploaded(null);
  };

  let linkedNotice = null;
  if (linkedId !== null && patientSystemId) {
    if (linked.loading) {
      linkedNotice = <LoadingView compact label="Opening the document…" />;
    } else if (linked.error && linked.errorStatus === 404) {
      linkedNotice = <Banner tone="warning" text="This document is no longer available" onDismiss={closeLinked} />;
    } else if (linked.error && linked.errorStatus === 403) {
      linkedNotice = <Banner tone="danger" text={linked.error} onDismiss={closeLinked} />;
    } else if (linked.error) {
      linkedNotice = <Banner tone="danger" text={linked.error} action={{ label: 'Retry', onPress: linked.refetch }} onDismiss={closeLinked} />;
    }
  }

  return (
    <Screen
      title="Documents"
      subtitle="Files in your health record: reports, letters, consent forms and insurance papers."
      theme={theme}
      role={role}
      actions={patientSystemId ? (
        <UploadDocumentButton
          patientSystemId={patientSystemId}
          defaultType={type}
          onUploaded={onUploaded}
          // The server's refusal (403) is on screen in place of the list; uploading would be refused too.
          disabled={documents.errorStatus === 403 && documents.items.length === 0}
        />
      ) : undefined}
    >
      {userLoading ? (
        <LoadingView />
      ) : !patientSystemId ? (
        <ErrorView message={userError ?? 'Your account could not be loaded.'} onRetry={refetchUser} />
      ) : (
        <View style={styles.body}>
          {uploaded ? (
            <Banner
              tone="success"
              text={`"${uploaded.title}" was added to your documents.`}
              onDismiss={() => setUploaded(null)}
            />
          ) : null}
          {linkedNotice}
          <DocumentList
            state={documents}
            pinned={linkedId !== null ? linked.data : null}
            onDeleted={onDeleted}
            header={<DocumentTypeFilter value={type} onChange={setType} />}
            label="Your documents"
            empty={{
              title: type ? 'No documents of this type' : 'No documents yet',
              sub: type
                ? 'Choose another type or show all types.'
                : 'Upload a file, or wait for your care team to add one. Accepted files: PDF, JPEG, PNG or DICOM, up to 10 MB.',
            }}
          />
        </View>
      )}
    </Screen>
  );
}

const styles = StyleSheet.create({
  body: { gap: space.md, minWidth: 0 },
});
