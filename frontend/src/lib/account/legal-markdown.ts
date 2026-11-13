// Parser for the restricted Markdown of the legal documents: #, ##, ### headings, paragraphs separated by a
// blank line, "- " bullet lists, **bold** and [text](https://...) links. Everything else, raw HTML included,
// stays literal text: the result is rendered as text nodes and never interpreted as markup.

export interface LegalInline {
  text:  string;
  bold?: boolean;
  /** Present for a link; always an https:// URL. */
  href?: string;
}

export type LegalBlock =
  | { kind: 'heading'; level: 1 | 2 | 3; inlines: LegalInline[] }
  | { kind: 'paragraph'; inlines: LegalInline[] }
  | { kind: 'list'; items: LegalInline[][] };

const LINK = /\[([^\]\n]+)\]\((https:\/\/[^\s)]+)\)/;
const BOLD = /\*\*([^*\n]+)\*\*/;

function parseBold(text: string, href?: string): LegalInline[] {
  const out: LegalInline[] = [];
  let rest = text;
  while (rest) {
    const m = BOLD.exec(rest);
    if (!m) { out.push({ text: rest, href }); break; }
    if (m.index > 0) out.push({ text: rest.slice(0, m.index), href });
    out.push({ text: m[1], bold: true, href });
    rest = rest.slice(m.index + m[0].length);
  }
  return out;
}

/** Splits one line of text into plain, bold and link runs. */
export function parseLegalInline(text: string): LegalInline[] {
  const out: LegalInline[] = [];
  let rest = text;
  while (rest) {
    const m = LINK.exec(rest);
    if (!m) { out.push(...parseBold(rest)); break; }
    if (m.index > 0) out.push(...parseBold(rest.slice(0, m.index)));
    out.push(...parseBold(m[1], m[2]));
    rest = rest.slice(m.index + m[0].length);
  }
  return out;
}

/** Splits a document into headings, paragraphs and bullet lists. */
export function parseLegalMarkdown(source: string): LegalBlock[] {
  const blocks: LegalBlock[] = [];
  let paragraph: string[] = [];
  let list: LegalInline[][] | null = null;

  const flushParagraph = () => {
    if (paragraph.length > 0) blocks.push({ kind: 'paragraph', inlines: parseLegalInline(paragraph.join(' ')) });
    paragraph = [];
  };
  const flushList = () => {
    if (list && list.length > 0) blocks.push({ kind: 'list', items: list });
    list = null;
  };

  for (const raw of (source ?? '').replace(/\r\n?/g, '\n').split('\n')) {
    const line = raw.trim();
    if (!line) { flushParagraph(); flushList(); continue; }

    const heading = /^(#{1,3})\s+(.+)$/.exec(line);
    if (heading) {
      flushParagraph(); flushList();
      blocks.push({ kind: 'heading', level: heading[1].length as 1 | 2 | 3, inlines: parseLegalInline(heading[2]) });
      continue;
    }
    if (line.startsWith('- ')) {
      flushParagraph();
      (list ??= []).push(parseLegalInline(line.slice(2).trim()));
      continue;
    }
    flushList();
    paragraph.push(line);
  }
  flushParagraph(); flushList();
  return blocks;
}
