import { StyleSheet, Text, View } from 'react-native';
import {
  Button, Card, colors, fontSize, Icon, type IoniconsName, radius, space, StatusBadge, useUiTheme,
} from '@/components/web/ui';
import {
  contentTypeLabel, type DocumentDto, documentLinkLabel, documentTypeLabel, formatFileSize, uploaderRoleLabel,
} from '@/lib/documents/types';
import { formatClinicDateTime } from '@/lib/time/clinic';

// One document of a list: what it is, who added it and when, and the Download and Delete controls.

const CONTENT_ICON: Record<string, IoniconsName> = {
  'application/pdf':   'document-text-outline',
  'image/jpeg':        'image-outline',
  'image/png':         'image-outline',
  'application/dicom': 'scan-outline',
};

export interface DocumentRowProps {
  document:     DocumentDto;
  onDownload:   (doc: DocumentDto) => void;
  downloading?: boolean;
  /** Shows the Delete control. Pass it only when the person may delete this document. */
  onDelete?:    (doc: DocumentDto) => void;
  deleting?:    boolean;
  /** Draws the row in the accent colour with this note above the title (a document opened from a link). */
  highlight?:   string | null;
  /** Leaves out the description and the "attached to" line (rows inside the attachments dialog). */
  compact?:     boolean;
}

function uploadedLine(doc: DocumentDto): string {
  const name = doc.uploadedByName?.trim();
  const role = uploaderRoleLabel(doc.uploadedByRole);
  // A doctor's name already carries "Dr."; other uploaders get their role so a relative is not mistaken for staff.
  const who = name
    ? (role && doc.uploadedByRole !== 'DOCTOR' ? `${name} (${role.toLowerCase()})` : name)
    : role ?? '';
  const when = formatClinicDateTime(doc.uploadedAt);
  if (who && when) return `Added by ${who} on ${when}`;
  if (who) return `Added by ${who}`;
  return when ? `Added on ${when}` : '';
}

export function DocumentRow({
  document: doc, onDownload, downloading = false, onDelete, deleting = false, highlight, compact = false,
}: DocumentRowProps) {
  const { accent, accentBg } = useUiTheme();
  const marked = !!highlight;
  const title = doc.title?.trim() || doc.originalFilename || 'Untitled document';
  const fileLine = [doc.originalFilename, contentTypeLabel(doc.contentType), formatFileSize(doc.sizeBytes)]
    .filter(Boolean)
    .join(' · ');
  const uploaded = uploadedLine(doc);
  const link = compact ? null : documentLinkLabel(doc.linkType);
  const description = compact ? null : doc.description?.trim() || null;

  return (
    <Card style={[styles.card, marked && { borderColor: accent, backgroundColor: accentBg }]}>
      <View style={styles.row}>
        <View style={[styles.iconWrap, { backgroundColor: marked ? colors.surface : accentBg }]}>
          <Icon name={CONTENT_ICON[doc.contentType] ?? 'document-outline'} size={20} color={accent} />
        </View>

        <View style={styles.main}>
          {marked ? <Text style={[styles.note, { color: accent }]}>{highlight}</Text> : null}
          <Text style={styles.title}>{title}</Text>
          <View style={styles.badges}>
            <StatusBadge label={documentTypeLabel(doc.documentType)} tone="neutral" icon={null} />
            {link ? <Text style={styles.meta}>Attached to: {link.toLowerCase()}</Text> : null}
          </View>
          {fileLine ? <Text style={styles.meta}>{fileLine}</Text> : null}
          {uploaded ? <Text style={styles.meta}>{uploaded}</Text> : null}
          {description ? <Text style={styles.description}>{description}</Text> : null}
        </View>

        <View style={styles.actions}>
          <Button
            label="Download"
            icon="download-outline"
            variant="secondary"
            size="sm"
            onPress={() => onDownload(doc)}
            loading={downloading}
            disabled={deleting}
            accessibilityLabel={`Download ${title}`}
          />
          {onDelete ? (
            <Button
              label="Delete"
              icon="trash-outline"
              variant="ghost"
              size="sm"
              onPress={() => onDelete(doc)}
              loading={deleting}
              disabled={downloading}
              accessibilityLabel={`Delete ${title}`}
            />
          ) : null}
        </View>
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  card:    { padding: space.md },
  // The actions wrap under the text when they do not fit beside it (a phone, a narrow dialog).
  row:     { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: space.md },
  iconWrap: {
    width: 40, height: 40, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center', flexShrink: 0,
  },
  main:    { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0, gap: space.xs },
  note:    { fontSize: fontSize.xs, fontWeight: '700' },
  title:   { fontSize: fontSize.lg, fontWeight: '600', color: colors.text },
  badges:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', columnGap: space.sm, rowGap: space.xs },
  meta:    { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  description: { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  actions: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, marginLeft: 'auto' },
});
