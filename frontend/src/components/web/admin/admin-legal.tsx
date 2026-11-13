import { zodResolver } from '@hookform/resolvers/zod';
import { Fragment, useMemo, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { Linking, StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Banner, Button, colors, DateField, decorative, ErrorView, fontSize, FormActions, FormError, InfoRow,
  IS_WEB, LoadingView, radius, Screen, SectionCard, SelectField, SidePanel, space, STATUS_TONES, StatusBadge, Tabs,
  TextField, TimeField, webOnly, type SelectOption, type TabItem,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { useLegalCurrent, useLegalDocument } from '@/hooks/use-admin-ops-legal';
import { useSectionNav } from '@/hooks/use-section-nav';
import { publishLegalDocument } from '@/lib/admin/ops-api';
import {
  containsPlaceholderPhrase, formatInstant, LEGAL_CONTENT_MAX, LEGAL_DOC_LABEL, localDateTimeToDate,
  PUBLISH_LEGAL_FIELD_MAP, publishLegalSchema,
  type LegalDocType, type LegalDocument, type LegalDocumentSummary, type LegalEffectiveMode,
  type PublishLegalDocumentInput, type PublishLegalFormValues,
} from '@/lib/admin/ops-types';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { useIdempotencyKey } from '@/lib/api/idempotency';
import { ROLE_THEME, type SectionProps } from '@/lib/nav/sections';
import { confirmAction } from '@/lib/ui/dialogs';

// Admin section `legal`: the Terms of Service and the Privacy Policy. It shows the version of each that is in
// force and publishes a new one. A published version cannot be edited or removed and there is no scheduled
// version: publishing takes effect at once. `?tab=terms|privacy` selects the document.

type LegalTab = 'terms' | 'privacy';

const TABS: readonly TabItem<LegalTab>[] = [
  { key: 'terms',   label: LEGAL_DOC_LABEL.TERMS },
  { key: 'privacy', label: LEGAL_DOC_LABEL.PRIVACY },
];

const DOC_TYPE: Record<LegalTab, LegalDocType> = { terms: 'TERMS', privacy: 'PRIVACY' };
const PUBLIC_PATH: Record<LegalDocType, string> = { TERMS: '/legal/terms', PRIVACY: '/legal/privacy' };

const EFFECTIVE_OPTIONS: readonly SelectOption<LegalEffectiveMode>[] = [
  { value: 'now',     label: 'Now, when it is published' },
  { value: 'earlier', label: 'An earlier date and time' },
];

const PLACEHOLDER_WARNING =
  'This is placeholder text that is pending legal review. Publish the reviewed text before the service is offered to the public.';

// ─── Text rendering ───────────────────────────────────────────────────────────
// The documents use a small Markdown subset: #, ## and ### headings, paragraphs separated by a blank line,
// "- " bullets, **bold** and [text](https://...) links. It is rendered here as plain text nodes; anything else,
// raw HTML included, is shown exactly as typed and never interpreted.

type Block =
  | { kind: 'heading'; level: 1 | 2 | 3; text: string }
  | { kind: 'paragraph'; text: string }
  | { kind: 'list'; items: string[] };

interface Inline { text: string; bold: boolean; url?: string }

function parseBlocks(source: string): Block[] {
  const blocks: Block[] = [];
  let paragraph: string[] = [];
  let list: string[] | null = null;
  const endParagraph = () => {
    if (paragraph.length > 0) blocks.push({ kind: 'paragraph', text: paragraph.join(' ') });
    paragraph = [];
  };
  const endList = () => {
    if (list) blocks.push({ kind: 'list', items: list });
    list = null;
  };
  for (const raw of (source ?? '').replace(/\r\n?/g, '\n').split('\n')) {
    const line = raw.trim();
    if (line === '') { endParagraph(); endList(); continue; }
    const heading = /^(#{1,3})\s+(.+)$/.exec(line);
    if (heading) {
      endParagraph(); endList();
      blocks.push({ kind: 'heading', level: heading[1].length as 1 | 2 | 3, text: heading[2].trim() });
      continue;
    }
    if (line.startsWith('- ')) {
      endParagraph();
      list = [...(list ?? []), line.slice(2).trim()];
      continue;
    }
    endList();
    paragraph.push(line);
  }
  endParagraph();
  endList();
  return blocks;
}

function parseLinks(text: string, bold: boolean): Inline[] {
  const out: Inline[] = [];
  const pattern = /\[([^\]]+)\]\((https:\/\/[^\s)]+)\)/g;
  let last = 0;
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(text)) !== null) {
    if (match.index > last) out.push({ text: text.slice(last, match.index), bold });
    out.push({ text: match[1], bold, url: match[2] });
    last = pattern.lastIndex;
  }
  if (last < text.length) out.push({ text: text.slice(last), bold });
  return out;
}

function parseInline(text: string): Inline[] {
  const out: Inline[] = [];
  const pattern = /\*\*(.+?)\*\*/g;
  let last = 0;
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(text)) !== null) {
    if (match.index > last) out.push(...parseLinks(text.slice(last, match.index), false));
    out.push(...parseLinks(match[1], true));
    last = pattern.lastIndex;
  }
  if (last < text.length) out.push(...parseLinks(text.slice(last), false));
  return out;
}

function InlineText({ text }: { text: string }) {
  return (
    <>
      {parseInline(text).map((part, index) => {
        if (part.url) {
          const url = part.url;
          const linkProps = IS_WEB
            ? webOnly({ href: url, hrefAttrs: { target: '_blank', rel: 'noopener noreferrer' } })
            : { onPress: () => { void Linking.openURL(url); } };
          return (
            <Text key={index} accessibilityRole="link" style={[styles.link, part.bold && styles.bold]} {...linkProps}>
              {part.text}
            </Text>
          );
        }
        return part.bold
          ? <Text key={index} style={styles.bold}>{part.text}</Text>
          : <Fragment key={index}>{part.text}</Fragment>;
      })}
    </>
  );
}

/** Renders a legal text. Headings start two levels under the card heading they sit in. */
function LegalText({ source }: { source: string }) {
  const blocks = useMemo(() => parseBlocks(source), [source]);
  if (blocks.length === 0) return <Text style={styles.docEmpty}>The document has no text.</Text>;
  return (
    <View style={styles.doc}>
      {blocks.map((block, index) => {
        if (block.kind === 'heading') {
          return (
            <Text
              key={index}
              accessibilityRole="header"
              {...ariaLevel((block.level + 2) as 3 | 4 | 5)}
              style={[styles.docHeading, block.level === 1 && styles.docHeading1, block.level === 3 && styles.docHeading3]}
            >
              <InlineText text={block.text} />
            </Text>
          );
        }
        if (block.kind === 'list') {
          return (
            <View key={index} role="list" style={styles.docList}>
              {block.items.map((item, itemIndex) => (
                <View key={itemIndex} role="listitem" style={styles.docListItem}>
                  <Text style={styles.docBullet} {...decorative}>{'•'}</Text>
                  <Text style={styles.docParagraph}><InlineText text={item} /></Text>
                </View>
              ))}
            </View>
          );
        }
        return <Text key={index} style={styles.docParagraph}><InlineText text={block.text} /></Text>;
      })}
    </View>
  );
}

// ─── Publish form ─────────────────────────────────────────────────────────────

function todayLocal(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

function PublishPanel({ docType, currentDoc, currentSummary, onClose, onPublished }: {
  docType:        LegalDocType;
  /** The text in force, used as the starting point of the new version; null when it could not be loaded. */
  currentDoc:     LegalDocument | null;
  currentSummary: LegalDocumentSummary | null;
  onClose:        () => void;
  onPublished:    (published: LegalDocument) => void;
}) {
  const label = LEGAL_DOC_LABEL[docType];
  const currentVersion = currentDoc?.version ?? currentSummary?.version ?? null;
  const currentEffectiveAt = currentDoc?.effectiveAt ?? currentSummary?.effectiveAt ?? null;

  const key = useIdempotencyKey();
  const schema = useMemo(() => publishLegalSchema(currentEffectiveAt), [currentEffectiveAt]);
  const { control, handleSubmit, setError, formState: { isDirty } } = useForm<PublishLegalFormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      version: '',
      title: currentDoc?.title ?? currentSummary?.title ?? label,
      contentMd: currentDoc?.contentMd ?? '',
      effectiveMode: 'now',
      effectiveDate: '',
      effectiveTime: '',
    },
  });
  const contentMd = useWatch({ control, name: 'contentMd' }) ?? '';
  const effectiveMode = useWatch({ control, name: 'effectiveMode' });

  const [formError, setFormError] = useState<string | null>(null);
  const [preview, setPreview] = useState(false);
  const publish = useAction(
    (input: PublishLegalDocumentInput) => publishLegalDocument(input, key.current()),
    { errorMessage: 'The document could not be published.' },
  );

  const submit = handleSubmit(async values => {
    setFormError(null);
    const version = values.version.trim();
    if (currentVersion && version === currentVersion) {
      setError('version', { type: 'validate', message: `Version ${currentVersion} is the one in force. Use a new label.` }, { shouldFocus: true });
      return;
    }
    let effectiveAt: string | undefined;
    if (values.effectiveMode === 'earlier') {
      const at = localDateTimeToDate(values.effectiveDate, values.effectiveTime);
      if (!at) {
        setError('effectiveDate', { type: 'validate', message: 'Choose a valid date and time' }, { shouldFocus: true });
        return;
      }
      effectiveAt = at.toISOString();
    }
    const confirmed = await confirmAction(
      `Publish version ${version} of the ${label}?`,
      'It takes effect at once and cannot be edited or removed afterwards. People are asked to accept it the next '
        + 'time they sign in on the web.',
      'Publish',
      true,
    );
    if (!confirmed) return;
    // "Effective now" sends no time: the server uses its own clock, never the browser's.
    const input: PublishLegalDocumentInput = {
      docType, version, title: values.title.trim(), contentMd: values.contentMd,
      ...(effectiveAt ? { effectiveAt } : {}),
    };
    const result = await publish.run(input);
    if (result.ok) {
      key.renew();
      onPublished(result.data);
    } else if (result.error) {
      setFormError(applyFieldErrors(result.error, setError, PUBLISH_LEGAL_FIELD_MAP));
    }
  });

  const close = async () => {
    if (isDirty && !(await confirmAction('Discard this draft?', 'What you typed will not be saved.', 'Discard', true))) return;
    onClose();
  };

  return (
    <SidePanel
      open
      size="lg"
      title={`Publish a new version of the ${label}`}
      onClose={() => { void close(); }}
      dismissOnBackdrop={false}
      busy={publish.pending}
      footer={(
        <FormActions
          submitLabel="Publish"
          submitIcon="cloud-upload-outline"
          onSubmit={() => { void submit(); }}
          onCancel={() => { void close(); }}
          submitting={publish.pending}
        />
      )}
    >
      <Banner
        tone="info"
        text="Publishing takes effect at once. A published version cannot be edited or removed, and people are asked to accept the new version the next time they sign in on the web."
      />
      <FormError message={formError} />

      <TextField
        control={control}
        name="version"
        label="Version"
        required
        maxLength={20}
        autoCapitalize="none"
        hint={currentVersion
          ? `The version in force is ${currentVersion}. Use a label that was never used for this document: letters, digits, dots, dashes or underscores.`
          : 'A label that was never used for this document: letters, digits, dots, dashes or underscores.'}
      />
      <TextField control={control} name="title" label="Title" required maxLength={200} />
      <TextField
        control={control}
        name="contentMd"
        label="Text"
        required
        multiline
        rows={16}
        maxLength={LEGAL_CONTENT_MAX}
        showCount
        hint="Formatting: lines starting with #, ## or ### are headings; leave a blank line between paragraphs; start a bullet with a dash and a space; **bold**; [text](https://...) for a link. Anything else, HTML included, is shown as typed."
      />
      {containsPlaceholderPhrase(contentMd) ? (
        <Banner
          tone="warning"
          text="This text contains the words “pending legal review”. It will be published marked as placeholder text, and the screens that show it will say so. Remove the words once the text has been reviewed."
        />
      ) : null}
      <Button
        label={preview ? 'Hide preview' : 'Show preview'}
        icon={preview ? 'eye-off-outline' : 'eye-outline'}
        variant="secondary"
        size="sm"
        onPress={() => setPreview(p => !p)}
      />
      {preview ? (
        <View style={styles.previewBox}>
          <LegalText source={contentMd} />
        </View>
      ) : null}

      <SelectField
        control={control}
        name="effectiveMode"
        label="In force from"
        options={EFFECTIVE_OPTIONS}
        hint="A version cannot take effect in the future."
      />
      {effectiveMode === 'earlier' ? (
        <View style={styles.dateRow}>
          <View style={styles.dateField}>
            <DateField control={control} name="effectiveDate" label="Date" required max={todayLocal()} />
          </View>
          <View style={styles.dateField}>
            <TimeField control={control} name="effectiveTime" label="Time" required />
          </View>
          <Text style={styles.note}>
            {`In your own time zone. Not in the future${currentEffectiveAt ? ` and not before the version in force (${formatInstant(currentEffectiveAt)})` : ''}.`}
          </Text>
        </View>
      ) : null}
    </SidePanel>
  );
}

// ─── Screen ───────────────────────────────────────────────────────────────────

function VersionLine({ summary }: { summary: LegalDocumentSummary }) {
  return (
    <View style={styles.versionRow}>
      <View style={styles.versionTitles}>
        <Text style={styles.versionTitle}>{LEGAL_DOC_LABEL[summary.docType] ?? summary.title}</Text>
        <Text style={styles.versionSub}>
          {`Version ${summary.version}, in force since ${formatInstant(summary.effectiveAt, 'an unknown date')}`}
        </Text>
      </View>
      {summary.pendingLegalReview
        ? <StatusBadge label="Placeholder, pending legal review" tone="warning" />
        : <StatusBadge label="In force" tone="success" />}
    </View>
  );
}

export default function AdminLegal({ theme, params }: Partial<SectionProps>) {
  const nav = useSectionNav();
  const look = theme ?? ROLE_THEME.admin;
  const urlParams = params ?? nav.params;
  const tab: LegalTab = urlParams.tab === 'privacy' ? 'privacy' : 'terms';
  const docType = DOC_TYPE[tab];
  const label = LEGAL_DOC_LABEL[docType];

  const current = useLegalCurrent();
  const doc = useLegalDocument(docType);
  const [publishing, setPublishing] = useState(false);
  const [showText, setShowText] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const summary = current.data ? current.data[tab] : null;
  // The text request and the summary request can disagree for a moment after a publish; the text wins.
  const shown = doc.data && doc.data.docType === docType ? doc.data : null;

  const onPublished = (published: LegalDocument) => {
    setPublishing(false);
    doc.setData(published);
    current.refetch();
    setNotice(
      `Version ${published.version} of the ${LEGAL_DOC_LABEL[published.docType] ?? label} is in force.`
        + (published.pendingLegalReview ? ' It is marked as placeholder text pending legal review.' : ''),
    );
  };

  let overview;
  if (current.loading) {
    overview = <LoadingView compact label="Loading the versions in force…" />;
  } else if (!current.data) {
    overview = <ErrorView compact message={current.error ?? 'The legal documents could not be loaded.'} onRetry={current.refetch} />;
  } else {
    overview = (
      <>
        {current.error ? <Banner tone="danger" text={current.error} action={{ label: 'Retry', onPress: current.refetch }} /> : null}
        <VersionLine summary={current.data.terms} />
        <VersionLine summary={current.data.privacy} />
        {current.data.terms.pendingLegalReview || current.data.privacy.pendingLegalReview ? (
          <Banner tone="warning" text="At least one document is still placeholder text that is pending legal review." />
        ) : null}
      </>
    );
  }

  let detail;
  if (doc.loading) {
    detail = <LoadingView compact label={`Loading the ${label}…`} />;
  } else if (!shown) {
    detail = <ErrorView compact message={doc.error ?? 'The text of the document could not be loaded.'} onRetry={doc.refetch} />;
  } else {
    detail = (
      <>
        {doc.error ? <Banner tone="danger" text={doc.error} action={{ label: 'Retry', onPress: doc.refetch }} /> : null}
        {shown.pendingLegalReview ? <Banner tone="warning" text={PLACEHOLDER_WARNING} /> : null}
        <View>
          <InfoRow label="Title" value={shown.title} />
          <InfoRow label="Version" value={shown.version} />
          <InfoRow label="In force since" value={formatInstant(shown.effectiveAt)} emptyText="Unknown" />
          <InfoRow label="Public page" value={PUBLIC_PATH[docType]} />
        </View>
        <Button
          label={showText ? 'Hide the text' : 'Show the text'}
          icon={showText ? 'chevron-up' : 'chevron-down'}
          variant="secondary"
          size="sm"
          onPress={() => setShowText(s => !s)}
          accessibilityLabel={showText ? `Hide the text of the ${label}` : `Show the text of the ${label}`}
        />
        {showText ? (
          <View style={styles.previewBox}>
            <LegalText source={shown.contentMd} />
          </View>
        ) : null}
      </>
    );
  }

  return (
    <Screen
      title="Legal documents"
      subtitle="The Terms of Service and the Privacy Policy people accept. A published version is final: to change the text, publish a new version."
      theme={look}
    >
      {notice ? <Banner tone="success" text={notice} onDismiss={() => setNotice(null)} /> : null}

      <SectionCard title="Versions in force">{overview}</SectionCard>

      <Tabs<LegalTab>
        tabs={TABS}
        active={tab}
        label="Legal documents"
        onChange={next => { setShowText(false); nav.setParams({ tab: next === 'terms' ? undefined : next }); }}
      />

      <SectionCard
        title={label}
        action={(
          <Button
            label="Publish new version"
            icon="cloud-upload-outline"
            onPress={() => { setNotice(null); setPublishing(true); }}
            disabled={doc.loading || current.loading}
            accessibilityLabel={`Publish new version of the ${label}`}
          />
        )}
      >
        {detail}
      </SectionCard>

      {publishing ? (
        <PublishPanel
          key={docType}
          docType={docType}
          currentDoc={shown}
          currentSummary={summary}
          onClose={() => setPublishing(false)}
          onPublished={onPublished}
        />
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  versionRow:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  versionTitles: { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0, gap: 2 },
  versionTitle:  { fontSize: fontSize.md, fontWeight: '600', color: colors.text },
  versionSub:    { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },

  previewBox: {
    borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, backgroundColor: colors.bg,
    padding: space.lg, minWidth: 0,
  },

  doc:          { gap: space.md, minWidth: 0 },
  docEmpty:     { fontSize: fontSize.md, color: colors.textMuted },
  docHeading:   { fontSize: fontSize.lg, fontWeight: '700', color: colors.text, lineHeight: 24 },
  docHeading1:  { fontSize: fontSize.xl, lineHeight: 26 },
  docHeading3:  { fontSize: fontSize.md, lineHeight: 21 },
  docParagraph: { flexShrink: 1, minWidth: 0, fontSize: fontSize.md, color: colors.text, lineHeight: 22 },
  docList:      { gap: space.xs },
  docListItem:  { flexDirection: 'row', alignItems: 'flex-start', gap: space.sm },
  docBullet:    { fontSize: fontSize.md, color: colors.textMuted, lineHeight: 22 },
  bold:         { fontWeight: '700' },
  link:         { color: STATUS_TONES.info.fg, textDecorationLine: 'underline' },

  dateRow:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: space.md },
  dateField: { flexGrow: 1, flexShrink: 1, flexBasis: 160, minWidth: 0, maxWidth: 240 },
  note:      { flexBasis: '100%', fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
});
