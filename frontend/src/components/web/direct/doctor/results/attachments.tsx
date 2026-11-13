import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { AttachmentButton, UploadDocumentButton } from '@/components/web/documents';
import { Button, colors, fontSize, Icon, space } from '@/components/web/ui';
import { useLinkedDocuments } from '@/hooks/use-documents';
import type { DocumentDto } from '@/lib/documents/types';
import {
  DetailBlock, type ResultAttachmentSlot, type ResultAttachmentSlotProps, type ResultAttachmentViewProps,
  type ResultAttachmentViewSlot, type ResultKind,
} from './shared';

// The documents integration of the lab and imaging screens (CONTRACTS 12.5, web-documents): the record form's
// attachment slot uploads the report through POST /api/documents with the type the result kind requires, and
// the detail panel's view slot lists the attached file with an authenticated download.

const LINK_TYPE: Record<ResultKind, 'LAB_RESULT' | 'IMAGING_RESULT'> = {
  LAB:     'LAB_RESULT',
  IMAGING: 'IMAGING_RESULT',
};

/** Upload control inside the lab or imaging record form. */
function ResultAttachmentField({ kind, patientSystemId, documentId, onChange, disabled }: ResultAttachmentSlotProps) {
  // The document this form was opened with (an existing result's attachment) cannot be detached, only replaced;
  // a file uploaded in this form can be taken back before saving.
  const [initialId] = useState(documentId);
  const [uploaded, setUploaded] = useState<DocumentDto | null>(null);
  const current = uploaded && uploaded.documentId === documentId ? uploaded : null;
  const noun = kind === 'LAB' ? 'lab report' : 'imaging report';

  const onUploaded = (doc: DocumentDto) => {
    setUploaded(doc);
    onChange(doc.documentId);
  };
  const takeBack = () => {
    setUploaded(null);
    onChange(initialId ?? null);
  };

  return (
    <View style={styles.field}>
      <Text style={styles.label}>Report file</Text>
      {current ? (
        <View style={styles.line}>
          <Icon name="document-attach-outline" size={16} color={colors.textMuted} />
          <Text style={styles.text}>{`"${current.title}" will be attached when you save.`}</Text>
          <Button label="Remove file" variant="ghost" size="sm" onPress={takeBack} disabled={disabled} accessibilityLabel={`Remove file, ${current.title}`} />
        </View>
      ) : documentId != null ? (
        <View style={styles.line}>
          <Icon name="document-attach-outline" size={16} color={colors.textMuted} />
          <Text style={styles.text}>A report file is attached. Upload another file to replace it.</Text>
        </View>
      ) : (
        <Text style={styles.hint}>{`Optional: attach the ${noun} (PDF or image).`}</Text>
      )}
      {patientSystemId ? (
        <UploadDocumentButton
          patientSystemId={patientSystemId}
          linkType={LINK_TYPE[kind]}
          label={documentId != null ? 'Replace report' : 'Attach report'}
          variant="secondary"
          size="sm"
          onUploaded={onUploaded}
          disabled={disabled}
        />
      ) : (
        <Text style={styles.hint}>Choose the patient first to attach a file.</Text>
      )}
    </View>
  );
}

/** The record form's attachment slot: upload with the result's document type, then hand the id to the form. */
export const resultAttachmentSlot: ResultAttachmentSlot = props => <ResultAttachmentField {...props} />;

/**
 * The detail panel's "Attachment" block: its heading and a button listing the file with Download. The document
 * is looked up first, and nothing is rendered, heading included, while that runs or when the document is gone
 * (404). A failed lookup still shows the button, whose dialog reports the error with Retry.
 */
function ResultAttachmentView({ kind, documentId, patientSystemId }: ResultAttachmentViewProps) {
  const probe = useLinkedDocuments(patientSystemId, LINK_TYPE[kind], null, { documentId });
  const found = (probe.data?.length ?? 0) > 0;
  if (probe.loading || (!found && !probe.error)) return null;
  return (
    <DetailBlock title="Attachment">
      <View style={styles.line}>
        <AttachmentButton
          linkType={LINK_TYPE[kind]}
          patientSystemId={patientSystemId}
          documentId={documentId}
          label="View report"
        />
      </View>
    </DetailBlock>
  );
}

/** The detail panel's attachment slot; it draws its own "Attachment" heading (see ResultAttachmentView). */
export const resultAttachmentViewSlot: ResultAttachmentViewSlot = (props: ResultAttachmentViewProps) => (
  <ResultAttachmentView {...props} />
);

/** The attachments of a result on its list card (loaded when pressed, so a long list costs no requests). */
export function ResultAttachmentsButton({ kind, resultId, patientSystemId, documentId }: {
  kind:            ResultKind;
  resultId:        number;
  patientSystemId: string;
  documentId:      number | null;
}) {
  return (
    <AttachmentButton linkType={LINK_TYPE[kind]} linkId={resultId} patientSystemId={patientSystemId} documentId={documentId} />
  );
}

const styles = StyleSheet.create({
  field: { gap: space.sm, minWidth: 0 },
  label: { fontSize: fontSize.sm, fontWeight: '600', color: colors.text },
  line:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.xs, minWidth: 0 },
  text:  { flexShrink: 1, minWidth: 0, fontSize: fontSize.sm, color: colors.text, lineHeight: 19 },
  hint:  { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
});
