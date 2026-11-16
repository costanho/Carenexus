import { type ReactNode, useCallback, useEffect, useMemo } from 'react';
import { StyleSheet, View } from 'react-native';
import { Banner, type EmptyAction, PagedList, type PagedState, space } from '@/components/web/ui';
import { useDeleteDocument, useDownloadDocument } from '@/hooks/use-documents';
import type { DocumentDto } from '@/lib/documents/types';
import { confirmAction } from '@/lib/ui/dialogs';
import { DocumentRow } from './document-row';

// A patient's documents with their Download and Delete controls, in every list state (loading, failed with
// Retry, no access with the server's reason, empty, rows with "Load more").

export interface DocumentListProps {
  /** The result of `useDocuments`. The caller keeps it so it can refetch after an upload. */
  state:        PagedState<DocumentDto>;
  /**
   * Whether Delete may be offered at all. Default true. A row shows it only when the server also says
   * `canDelete`; a caregiver screen passes the grant's manage permission here.
   */
  allowDelete?: boolean;
  /** Called after a document was deleted (the list has already been asked to reload). */
  onDeleted?:   (doc: DocumentDto) => void;
  /** A document to show first, highlighted (opened from a notification). It is left out of the rows below. */
  pinned?:      DocumentDto | null;
  /** The note above the pinned document's title. */
  pinnedNote?:  string;
  /** Filters shown above the list in every state; they stay mounted while the list under them changes. */
  header?:      ReactNode;
  empty?:       { title: string; sub?: string; action?: EmptyAction | ReactNode };
  /** Names the list for assistive technology. Default "Documents". */
  label?:       string;
}

export function DocumentList({
  state, allowDelete = true, onDeleted, pinned = null, pinnedNote = 'Opened from your notification',
  header, empty, label = 'Documents',
}: DocumentListProps) {
  const downloads = useDownloadDocument();
  const deletion  = useDeleteDocument();
  const { refetch } = state;
  const { download } = downloads;
  const { remove } = deletion;

  const onDownload = useCallback((doc: DocumentDto) => { void download(doc); }, [download]);

  // "Not found" on a download means the document was deleted meanwhile: reload so the row goes away.
  const downloadFailure = downloads.failure;
  useEffect(() => {
    if (downloadFailure?.error.status === 404) refetch();
  }, [downloadFailure, refetch]);

  const onDelete = useCallback(async (doc: DocumentDto) => {
    const name = doc.title?.trim() || doc.originalFilename || 'this document';
    const confirmed = await confirmAction(
      'Delete document',
      `Delete "${name}"? It will be removed from the record for everyone who can see it. This cannot be undone.`,
      'Delete',
      true,
    );
    if (!confirmed) return;
    if (await remove(doc)) {
      refetch();
      onDeleted?.(doc);
    }
  }, [remove, onDeleted, refetch]);

  // The pinned document is drawn once, above the rows; the count under the list follows what is drawn.
  const shown = useMemo<PagedState<DocumentDto>>(() => {
    if (!pinned) return state;
    const items = state.items.filter(doc => doc.documentId !== pinned.documentId);
    const hidden = state.items.length - items.length;
    return hidden === 0 ? state : { ...state, items, total: Math.max(0, state.total - hidden) };
  }, [state, pinned]);

  const row = (doc: DocumentDto, highlight?: string) => (
    <DocumentRow
      document={doc}
      onDownload={onDownload}
      downloading={downloads.isDownloading(doc.documentId)}
      onDelete={allowDelete && doc.canDelete ? onDelete : undefined}
      deleting={deletion.pendingId === doc.documentId}
      highlight={highlight}
    />
  );

  const failure = deletion.failure ?? downloads.failure;
  const dismissFailure = () => { deletion.reset(); downloads.reset(); };
  // With a pinned document on screen, "no documents" under it would be wrong when it is the only one.
  const onlyPinned = !!pinned && !shown.loading && !shown.error && shown.items.length === 0;

  return (
    <View style={styles.wrap}>
      {failure ? (
        <Banner
          tone="danger"
          text={`${failure.document.title?.trim() || 'Document'}: ${failure.error.message}`}
          onDismiss={dismissFailure}
        />
      ) : null}
      {header ?? null}
      {pinned ? row(pinned, pinnedNote) : null}
      {onlyPinned ? null : (
        <PagedList
          state={shown}
          keyOf={doc => doc.documentId}
          renderItem={doc => row(doc)}
          label={label}
          // The server's sentence says why (no care relationship, the grant does not cover documents, ...).
          noAccessMessage={shown.errorStatus === 403 ? shown.error ?? undefined : undefined}
          empty={{
            icon: 'document-outline',
            title: empty?.title ?? 'No documents yet',
            sub: empty?.sub,
            action: empty?.action,
          }}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: space.md, minWidth: 0 },
});
