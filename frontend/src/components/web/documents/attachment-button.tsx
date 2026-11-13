import { type ReactNode, useCallback, useEffect, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import {
  Banner, Button, type ButtonVariant, EmptyView, ErrorView, LoadingView, Modal, NoAccessView, space,
} from '@/components/web/ui';
import { useDownloadDocument, useLinkedDocuments } from '@/hooks/use-documents';
import type { DocumentDto, DocumentLinkType } from '@/lib/documents/types';
import { DocumentRow } from './document-row';

// The files attached to one item (a lab or imaging result, a medical record, a consultation, a claim): a button
// on the item's card that opens the list, each file with an authenticated Download.

export interface AttachmentButtonProps {
  /** What kind of item the documents are attached to. */
  linkType: DocumentLinkType;
  /** The item's id. May be left out when only `documentId` is known. */
  linkId?:  number | null;
  /** The patient the item belongs to (PAT-...). */
  patientSystemId: string;
  /**
   * The item's own document reference (`documentId` of a lab or imaging result). It is listed first; a
   * reference to a document that was deleted since is skipped.
   */
  documentId?: number | null;
  /** Text of the button. Default "Attachments". */
  label?:   string;
  variant?: ButtonVariant;
  size?:    'sm' | 'md';
  /**
   * Load the attachments with the card instead of when the button is pressed. The button then shows the count
   * and is not rendered at all for an item without attachments. Costs one request per card, so use it on
   * detail views rather than long lists. Default false.
   */
  eager?:   boolean;
  testID?:  string;
}

export function AttachmentButton({
  linkType, linkId = null, patientSystemId, documentId = null, label = 'Attachments',
  variant = 'secondary', size = 'sm', eager = false, testID,
}: AttachmentButtonProps) {
  const [open, setOpen] = useState(false);
  // Once opened the list stays loaded, so reopening shows it at once and refreshes it in the background.
  const [wanted, setWanted] = useState(eager);
  useEffect(() => { if (eager) setWanted(true); }, [eager]);

  const attachments = useLinkedDocuments(patientSystemId, linkType, linkId, { documentId, enabled: wanted });
  const downloads = useDownloadDocument();
  const { download } = downloads;
  const { refetch } = attachments;

  const onDownload = useCallback((doc: DocumentDto) => { void download(doc); }, [download]);

  // "Not found" on a download means the file was deleted meanwhile: reload so it leaves the list.
  const downloadFailure = downloads.failure;
  useEffect(() => {
    if (downloadFailure?.error.status === 404) refetch();
  }, [downloadFailure, refetch]);

  const hasTarget = (typeof linkId === 'number' && linkId > 0 && !!patientSystemId) || (typeof documentId === 'number' && documentId > 0);
  if (!hasTarget) return null;

  const documents = attachments.data ?? [];
  const loaded = attachments.data !== null;
  // Loaded up front and nothing is attached: no control at all, rather than one that opens an empty list.
  if (eager && !open && loaded && documents.length === 0 && !attachments.error) return null;
  if (eager && !open && attachments.loading) return null;

  const buttonLabel = loaded && documents.length > 0 ? `${label} (${documents.length})` : label;

  const openDialog = () => {
    if (wanted) refetch(); // a file may have been added or removed since the list was last read
    setWanted(true);
    setOpen(true);
  };
  const closeDialog = () => {
    downloads.reset();
    setOpen(false);
  };

  let body: ReactNode;
  if (attachments.loading) {
    body = <LoadingView label="Loading attachments…" />;
  } else if (attachments.error && !loaded) {
    body = attachments.errorStatus === 403
      // The server's sentence says which permission is missing.
      ? <NoAccessView message={attachments.error} />
      : <ErrorView message={attachments.error} onRetry={refetch} />;
  } else if (documents.length === 0) {
    body = <EmptyView icon="attach-outline" title="No files attached" sub="Nothing has been attached to this item." />;
  } else {
    body = (
      <View style={styles.list} role="list" aria-label="Attached files" aria-busy={attachments.refreshing}>
        {documents.map(doc => (
          <View key={doc.documentId} role="listitem" style={styles.item}>
            <DocumentRow
              document={doc}
              onDownload={onDownload}
              downloading={downloads.isDownloading(doc.documentId)}
              compact
            />
          </View>
        ))}
      </View>
    );
  }

  return (
    <>
      <Button
        label={buttonLabel}
        icon="attach-outline"
        variant={variant}
        size={size}
        onPress={openDialog}
        testID={testID}
      />
      <Modal open={open} title="Attachments" onClose={closeDialog}>
        {downloadFailure ? (
          <Banner
            tone="danger"
            text={`${downloadFailure.document.title?.trim() || 'File'}: ${downloadFailure.error.message}`}
            onDismiss={downloads.reset}
          />
        ) : null}
        {attachments.error && loaded ? (
          <Banner tone="danger" text={attachments.error} action={{ label: 'Retry', onPress: refetch }} />
        ) : null}
        {body}
      </Modal>
    </>
  );
}

const styles = StyleSheet.create({
  list: { gap: space.sm, minWidth: 0 },
  item: { minWidth: 0 },
});
