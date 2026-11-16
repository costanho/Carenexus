import { Fragment } from 'react';
import { Linking, StyleSheet, Text, View } from 'react-native';
import {
  AccountPage, HomeLink, PageLink, PRIVACY_ROUTE, TERMS_ROUTE, useAccountSession,
} from '@/app/auth/forgot-password/web/forgot-password-web';
import {
  ariaLevel, Banner, colors, decorative, ErrorView, fontSize, IS_WEB, LoadingView, space, useUiTheme,
} from '@/components/web/ui';
import { useResource } from '@/hooks/use-resource';
import { getLegalDocument, type LegalDocType, type LegalDocumentSummary } from '@/lib/auth/auth-flows-api';

// ─────────────────────────────────────────────────────────────────────────────
// Shared by /legal/terms, /legal/privacy and /auth/accept-terms
//
// Exported from this page's module for the same reason as the account page frame: every file under app/ is a
// page, so a separate file for these pieces would add an address that serves nothing.
// ─────────────────────────────────────────────────────────────────────────────

/** Shown with a document whose text has not been through legal review (the server decides, not the text). */
export const PENDING_LEGAL_REVIEW_NOTICE =
  'This text is a placeholder that is pending legal review. It is not the final wording.';

const FALLBACK_TITLE: Record<LegalDocType, string> = {
  TERMS:   'Terms of Service',
  PRIVACY: 'Privacy Policy',
};

/** "Version 2, effective 20 November 2026" (the date in the reader's own time zone). */
export function legalVersionLine(doc: Pick<LegalDocumentSummary, 'version' | 'effectiveAt'>): string {
  const at = new Date(doc.effectiveAt);
  if (Number.isNaN(at.getTime())) return `Version ${doc.version}`;
  const date = at.toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: 'numeric' });
  return `Version ${doc.version}, effective ${date}`;
}

// ── The Markdown subset of legal documents ────────────────────────────────────
// Headings (#, ##, ###), paragraphs separated by a blank line, "- " bullets, **bold** and
// [text](https://...) links. Everything is drawn as text, so anything outside the subset, raw HTML included,
// appears literally and is never interpreted.

interface Inline {
  text:  string;
  bold:  boolean;
  /** Set for a link; only https addresses become links. */
  href?: string;
}

type Block =
  | { kind: 'heading'; level: 1 | 2 | 3; inlines: Inline[] }
  | { kind: 'paragraph'; inlines: Inline[] }
  | { kind: 'list'; items: Inline[][] };

const BOLD = /\*\*(.+?)\*\*/g;
const LINK = /\[([^\]\n]+)\]\((https:\/\/[^\s)]+)\)/g;

function parseInline(source: string): Inline[] {
  const out: Inline[] = [];
  const addWithLinks = (text: string, bold: boolean) => {
    let last = 0;
    for (const match of text.matchAll(LINK)) {
      const start = match.index ?? 0;
      if (start > last) out.push({ text: text.slice(last, start), bold });
      out.push({ text: match[1], bold, href: match[2] });
      last = start + match[0].length;
    }
    if (last < text.length) out.push({ text: text.slice(last), bold });
  };
  let last = 0;
  for (const match of source.matchAll(BOLD)) {
    const start = match.index ?? 0;
    if (start > last) addWithLinks(source.slice(last, start), false);
    addWithLinks(match[1], true);
    last = start + match[0].length;
  }
  if (last < source.length) addWithLinks(source.slice(last), false);
  return out;
}

function plainText(inlines: Inline[]): string {
  return inlines.map(part => part.text).join('').trim();
}

export function parseLegalMarkdown(source: string): Block[] {
  const blocks: Block[] = [];
  let paragraph: string[] = [];
  let items: string[] = [];
  const endParagraph = () => {
    if (paragraph.length > 0) blocks.push({ kind: 'paragraph', inlines: parseInline(paragraph.join(' ')) });
    paragraph = [];
  };
  const endList = () => {
    if (items.length > 0) blocks.push({ kind: 'list', items: items.map(parseInline) });
    items = [];
  };

  for (const raw of source.replace(/\r\n?/g, '\n').split('\n')) {
    const line = raw.trim();
    if (!line) { endParagraph(); endList(); continue; }
    const heading = /^(#{1,3})\s+(.+)$/.exec(line);
    if (heading) {
      endParagraph(); endList();
      blocks.push({ kind: 'heading', level: heading[1].length as 1 | 2 | 3, inlines: parseInline(heading[2]) });
    } else if (line.startsWith('- ')) {
      endParagraph();
      items.push(line.slice(2).trim());
    } else {
      endList();
      paragraph.push(line);
    }
  }
  endParagraph(); endList();
  return blocks;
}

function linkProps(href: string, text: string): object {
  // Web: a real anchor that opens in a new tab, so the page being read (or accepted) stays open.
  return IS_WEB
    ? { href, hrefAttrs: { target: '_blank', rel: 'noopener noreferrer' }, 'aria-label': `${text} (opens in a new tab)` }
    : { onPress: () => { void Linking.openURL(href).catch(() => undefined); } };
}

function InlineRun({ inlines }: { inlines: Inline[] }) {
  const { accent } = useUiTheme();
  return (
    <>
      {inlines.map((part, index) => {
        if (part.href) {
          return (
            <Text
              key={index}
              accessibilityRole="link"
              style={[styles.link, { color: accent }, part.bold && styles.bold]}
              {...linkProps(part.href, part.text)}
            >
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

function clampLevel(level: number): 1 | 2 | 3 | 4 | 5 | 6 {
  return Math.min(6, Math.max(1, level)) as 1 | 2 | 3 | 4 | 5 | 6;
}

/**
 * The text of a legal document. `headingLevel` is the level its top headings (`#`) get on the page, one below
 * the heading that names the document. A leading `#` heading that only repeats `title` is left out, since the
 * page already shows the title.
 */
export function LegalDocumentBody({ markdown, title, headingLevel = 2 }: {
  markdown:      string;
  title?:        string;
  headingLevel?: 2 | 3;
}) {
  const blocks = parseLegalMarkdown(markdown);
  const repeated = (title ?? '').trim().toLowerCase();
  const firstHeading = blocks.findIndex(block => block.kind === 'heading');
  return (
    <View style={styles.body}>
      {blocks.map((block, index) => {
        if (block.kind === 'heading') {
          if (index === firstHeading && block.level === 1 && repeated && plainText(block.inlines).toLowerCase() === repeated) {
            return null;
          }
          return (
            <Text
              key={index}
              style={[styles.heading, block.level === 1 && styles.headingTop, block.level === 3 && styles.headingMinor]}
              accessibilityRole="header"
              {...ariaLevel(clampLevel(headingLevel + block.level - 1))}
            >
              <InlineRun inlines={block.inlines} />
            </Text>
          );
        }
        if (block.kind === 'list') {
          return (
            <View key={index} style={styles.list} role="list">
              {block.items.map((item, itemIndex) => (
                <View key={itemIndex} style={styles.listItem} role="listitem">
                  <Text style={styles.bullet} {...decorative}>{'•'}</Text>
                  <Text style={[styles.paragraph, styles.listText]}><InlineRun inlines={item} /></Text>
                </View>
              ))}
            </View>
          );
        }
        return <Text key={index} style={styles.paragraph}><InlineRun inlines={block.inlines} /></Text>;
      })}
    </View>
  );
}

/**
 * A public page showing the current version of one legal document, loaded from the server so the page always
 * matches the version people are asked to accept.
 */
export function LegalDocumentPage({ docType }: { docType: LegalDocType }) {
  const session = useAccountSession();
  const doc = useResource(() => getLegalDocument(docType), [docType], {
    errorMessage: 'We could not load this document. Please try again.',
  });
  const title = doc.data?.title ?? FALLBACK_TITLE[docType];
  const other = docType === 'TERMS'
    ? <PageLink href={PRIVACY_ROUTE} label={FALLBACK_TITLE.PRIVACY} />
    : <PageLink href={TERMS_ROUTE} label={FALLBACK_TITLE.TERMS} />;

  return (
    <AccountPage
      title={title}
      heading={title}
      intro={doc.data ? legalVersionLine(doc.data) : undefined}
      role={session.status === 'signed-in' ? session.user.role : undefined}
      wide
      footer={(
        <>
          {other}
          <HomeLink session={session} />
        </>
      )}
    >
      {doc.loading ? <LoadingView label="Loading document…" /> : null}
      {!doc.loading && doc.error && !doc.data ? <ErrorView message={doc.error} onRetry={doc.refetch} /> : null}
      {doc.data ? (
        <>
          {doc.data.pendingLegalReview ? <Banner tone="warning" text={PENDING_LEGAL_REVIEW_NOTICE} /> : null}
          <LegalDocumentBody markdown={doc.data.contentMd} title={doc.data.title} />
        </>
      ) : null}
    </AccountPage>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// /legal/terms
// ─────────────────────────────────────────────────────────────────────────────

export default function TermsPage() {
  return <LegalDocumentPage docType="TERMS" />;
}

const styles = StyleSheet.create({
  body:         { gap: space.md },
  heading:      { fontSize: fontSize.lg, fontWeight: '700', color: colors.text, lineHeight: 24, marginTop: space.sm },
  headingTop:   { fontSize: fontSize.xl, lineHeight: 26 },
  headingMinor: { fontSize: fontSize.md, lineHeight: 21 },
  paragraph:    { fontSize: fontSize.md, lineHeight: 22, color: colors.text },
  bold:         { fontWeight: '700' },
  link:         { fontWeight: '600', textDecorationLine: 'underline' },
  list:         { gap: space.xs },
  listItem:     { flexDirection: 'row', alignItems: 'flex-start', gap: space.sm, paddingLeft: space.xs },
  bullet:       { fontSize: fontSize.md, lineHeight: 22, color: colors.textMuted },
  listText:     { flex: 1, minWidth: 0 },
});
