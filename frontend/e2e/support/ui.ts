import AxeBuilder from '@axe-core/playwright';
import { type Browser, type BrowserContext, type BrowserContextOptions, expect, type Locator, type Page } from '@playwright/test';
import { type DemoUser, type LoginRole, ROLE_HOME, ROLE_LOGIN_LABEL } from './env';

// UI helpers. Each user gets their own browser context (separate sessionStorage = separate session).

export interface UserPage { context: BrowserContext; page: Page; user: DemoUser }

export const LOGIN_PATH = '/auth/login/web/login-web';
export const ACCEPT_TERMS_PATH = '/auth/accept-terms';
export const CHANGE_PASSWORD_PATH = '/auth/change-password';

/**
 * Camera and microphone permissions where the browser can grant them (Chromium; the call specs run there with
 * a fake device). Firefox and WebKit, used for the cross-browser smoke and accessibility runs, get none.
 */
export function mediaPermissions(browser: Browser): string[] {
  return browser.browserType().name() === 'chromium' ? ['camera', 'microphone'] : [];
}

export async function newUserPage(browser: Browser, user: DemoUser, options: BrowserContextOptions = {}): Promise<UserPage> {
  const context = await browser.newContext({
    permissions: mediaPermissions(browser),
    ...options,
  });
  const page = await context.newPage();
  // confirmAction() uses window.confirm on web: accept "End call for everyone?" and similar prompts.
  page.on('dialog', d => { d.accept().catch(() => {}); });
  await login(page, user);
  return { context, page, user };
}

/**
 * Signs in through the sign-in page and waits for the role home. When the account still has to accept the
 * current Terms or Privacy Policy (sign-in then opens /auth/accept-terms) they are accepted on the way.
 */
export async function login(page: Page, user: DemoUser): Promise<void> {
  await submitLogin(page, user);
  await finishSignIn(page, user);
}

/** Opens the sign-in page (unless `goto` is false), fills it in for `user` and presses Sign in. */
export async function submitLogin(page: Page, user: DemoUser, { goto = true }: { goto?: boolean } = {}): Promise<void> {
  if (goto) await page.goto(LOGIN_PATH);
  await page.getByRole('radio', { name: ROLE_LOGIN_LABEL[user.role], exact: true }).click();
  await page.getByLabel('Email address').fill(user.email);
  await page.getByLabel('Password', { exact: true }).fill(user.password);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
}

/**
 * After Sign in: accepts pending legal documents if the app asks for them, then waits until the URL satisfies
 * `landing` (default: exactly the role home). A forced password change is not something a helper can answer,
 * so it fails with a clear message.
 */
export async function finishSignIn(page: Page, user: DemoUser, landing: (url: URL) => boolean = homeOf(user.role)): Promise<void> {
  const home = ROLE_HOME[user.role];
  await page.waitForURL(u => landing(u) || u.pathname === home || u.pathname === ACCEPT_TERMS_PATH || u.pathname === CHANGE_PASSWORD_PATH, { timeout: 30_000 });
  const path = new URL(page.url()).pathname;
  if (path === CHANGE_PASSWORD_PATH) {
    throw new Error(`${user.email} must change its password before signing in (flags.mustChangePassword)`);
  }
  if (path === ACCEPT_TERMS_PATH) await acceptTermsInterstitial(page);
  await page.waitForURL(landing, { timeout: 30_000 });
}

function homeOf(role: LoginRole): (url: URL) => boolean {
  return url => url.pathname === ROLE_HOME[role] && url.search === '';
}

/** Accepts each document /auth/accept-terms shows (Terms, then Privacy) until the page moves on. */
export async function acceptTermsInterstitial(page: Page): Promise<void> {
  for (let i = 0; i < 4 && new URL(page.url()).pathname === ACCEPT_TERMS_PATH; i++) {
    const accept = page.getByRole('button', { name: /^Accept / }).first();
    const shown = await accept.waitFor({ state: 'visible', timeout: 20_000 }).then(() => true, () => false);
    if (!shown) break;
    const name = (await accept.getAttribute('aria-label')) ?? (await accept.innerText());
    await accept.click();
    // Wait for the next document (another name) or for the page to hand over to the dashboard.
    await expect.poll(async () => {
      if (new URL(page.url()).pathname !== ACCEPT_TERMS_PATH) return true;
      const next = page.getByRole('button', { name: /^Accept / }).first();
      if (!(await next.isVisible().catch(() => false))) return false;
      const nextName = (await next.getAttribute('aria-label').catch(() => null)) ?? (await next.innerText().catch(() => ''));
      return nextName !== name;
    }, { message: 'accept-terms moves on after Accept', timeout: 20_000 }).toBe(true);
  }
  await page.waitForURL(u => u.pathname !== ACCEPT_TERMS_PATH, { timeout: 30_000 });
}

/** Opens a dashboard section from the left sidebar (label may carry an unread suffix). */
export async function openSection(page: Page, label: string): Promise<void> {
  await page.getByRole('button', { name: new RegExp(`^${escapeRe(label)}(, \\d+ unread)?$`) }).first().click();
}

export type SectionParamsInput = Record<string, string | number | undefined | null>;

/**
 * The URL of a dashboard section (CONTRACTS 12.1): `/dashboard/<role>?section=<key>&<param>=<value>`; the home
 * section ('dashboard') is the bare role path. Mirrors buildSectionHref in src/lib/nav/action-url.ts.
 */
export function sectionUrl(role: LoginRole, section?: string, params: SectionParamsInput = {}, homeKey = 'dashboard'): string {
  const parts: string[] = [];
  if (section && section !== homeKey) parts.push(`section=${encodeURIComponent(section)}`);
  for (const [name, value] of Object.entries(params)) {
    if (name === 'section' || value === undefined || value === null || value === '') continue;
    parts.push(`${encodeURIComponent(name)}=${encodeURIComponent(String(value))}`);
  }
  return parts.length ? `${ROLE_HOME[role]}?${parts.join('&')}` : ROLE_HOME[role];
}

/** The area a section renders in: a main landmark named after the section's label. */
export function sectionMain(page: Page, label: string): Locator {
  return page.getByRole('main', { name: label, exact: true });
}

/** Whether the page URL shows `section` (the home section when `section` is 'dashboard' or empty). */
export function urlShowsSection(url: string | URL, section: string | undefined, params: SectionParamsInput = {}): boolean {
  const u = typeof url === 'string' ? new URL(url) : url;
  const shown = u.searchParams.get('section');
  if (!section || section === 'dashboard') {
    if (shown !== null) return false;
  } else if (shown !== section) {
    return false;
  }
  return Object.entries(params).every(([name, value]) =>
    value === undefined || value === null || value === '' || u.searchParams.get(name) === String(value));
}

/**
 * Opens a section by URL (`?section=` with params) in an already signed-in page, and waits until the section's
 * main landmark is shown (named `label` when given). Use it instead of the sidebar for hidden sections and
 * deep links. The params are checked only for presence in the URL; a screen may drop one-shot params such as
 * `thread` once it has used them.
 */
export async function openSectionAt(
  page: Page, role: LoginRole, section: string, params: SectionParamsInput = {}, label?: string,
): Promise<void> {
  await page.goto(sectionUrl(role, section, params));
  await page.waitForURL(u => urlShowsSection(u, section), { timeout: 30_000 });
  await expect(label ? sectionMain(page, label) : page.getByRole('main').first()).toBeVisible();
}

export async function openMessages(page: Page): Promise<void> {
  await openSection(page, 'Messages');
  await expect(page.getByRole('heading', { name: 'Messages', exact: true })).toBeVisible();
}

export function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** Serious/critical axe violations (WCAG 2.1 A/AA rules), formatted for a readable failure. */
export async function seriousA11yViolations(page: Page, include?: string): Promise<string[]> {
  // label-content-name-mismatch (visible text must be part of the accessible name, WCAG 2.5.3) is enabled
  // explicitly: axe versions that tag it experimental leave it out of a tag-only run (gap G12).
  let builder = new AxeBuilder({ page })
    .options({ rules: { 'label-content-name-mismatch': { enabled: true } } })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']);
  if (include) builder = builder.include(include);
  const results = await builder.analyze();
  const violations = await dropRnWebNameMismatchFalsePositives(page, results.violations);
  return violations
    .filter(v => v.impact === 'serious' || v.impact === 'critical')
    .map(v => `${v.impact} ${v.id}: ${v.help}\n${v.nodes.slice(0, 6).map(n =>
      `    - ${n.html.replace(/\s+/g, ' ').slice(0, 160)}${n.failureSummary ? `\n      ${n.failureSummary.split('\n').slice(-1)[0].trim().slice(0, 160)}` : ''}`).join('\n')}`);
}

type AxeViolation = Awaited<ReturnType<AxeBuilder['analyze']>>['violations'][number];

/**
 * Re-checks axe's label-content-name-mismatch nodes with block-aware visible text and drops the false positives.
 *
 * axe builds an element's visible text by joining its child text nodes with no separator. react-native-web renders
 * every <Text> as its own block <div> with no whitespace between siblings, so "In Person" + "Visit the clinic"
 * becomes "In PersonVisit the clinic", which tokenises to "personvisit" and never matches the aria-label. axe also
 * counts aria-hidden decorations (an avatar initial, an arrow glyph) as visible text. Each flagged node is
 * evaluated again here: visible text skips aria-hidden and hidden subtrees and puts a space around every
 * non-inline element; both texts are tokenised the way axe does (NFKD, parentheticals removed, anything but
 * letters and digits as a separator, lower case). A node stays a violation only when its visible words are not a
 * contiguous run of the accessible name's words; a violation with no nodes left is dropped. Nodes that cannot be
 * located again (shadow DOM or iframe targets) are kept.
 */
async function dropRnWebNameMismatchFalsePositives(page: Page, violations: AxeViolation[]): Promise<AxeViolation[]> {
  const RULE = 'label-content-name-mismatch';
  const mismatch = violations.find(v => v.id === RULE);
  if (!mismatch) return violations;
  const selectors = mismatch.nodes.map(n =>
    n.target.length === 1 && typeof n.target[0] === 'string' ? n.target[0] : null);
  const keep = await page.evaluate((sels: (string | null)[]) => {
    const tokens = (s: string) => s.normalize('NFKD')
      .replace(/\([^()]*\)/g, ' ')
      .replace(/[^\p{L}\p{N}]+/gu, ' ')
      .trim().toLowerCase().split(/\s+/).filter(Boolean);
    const visibleText = (node: Node): string => {
      if (node.nodeType === Node.TEXT_NODE) return node.nodeValue ?? '';
      if (node.nodeType !== Node.ELEMENT_NODE) return '';
      const el = node as Element;
      if (el.getAttribute('aria-hidden') === 'true') return '';
      const cs = getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden') return '';
      const inner = Array.from(el.childNodes).map(visibleText).join('');
      return cs.display === 'inline' || cs.display === 'contents' ? inner : ` ${inner} `;
    };
    const accessibleName = (el: Element): string => {
      const labelledBy = el.getAttribute('aria-labelledby');
      if (labelledBy) {
        const text = labelledBy.split(/\s+/).map(id => document.getElementById(id)?.textContent ?? '').join(' ').trim();
        if (text) return text;
      }
      return el.getAttribute('aria-label') ?? '';
    };
    const containsRun = (hay: string[], run: string[]) => {
      if (run.length === 0) return true;
      for (let i = 0; i + run.length <= hay.length; i++) if (run.every((w, j) => w === hay[i + j])) return true;
      return false;
    };
    return sels.map(sel => {
      const el = sel ? document.querySelector(sel) : null;
      if (!el) return true;
      return !containsRun(tokens(accessibleName(el)), tokens(visibleText(el)));
    });
  }, selectors);
  const nodes = mismatch.nodes.filter((_, i) => keep[i]);
  return nodes.length
    ? violations.map(v => (v === mismatch ? { ...v, nodes } : v))
    : violations.filter(v => v !== mismatch);
}

/**
 * Horizontal overflow at the current viewport: the document itself, any horizontally scrollable
 * element, and visible elements whose box sticks out past the right edge.
 */
export async function horizontalOverflow(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const vw = document.documentElement.clientWidth;
    const out: string[] = [];
    const doc = document.scrollingElement ?? document.documentElement;
    if (doc.scrollWidth > vw + 1) out.push(`document scrollWidth ${doc.scrollWidth} > ${vw}`);
    const describe = (el: Element) => {
      const h = el as HTMLElement;
      const label = h.getAttribute('aria-label') ?? (h.innerText ?? '').trim().slice(0, 40);
      return `${el.tagName.toLowerCase()}${label ? ` "${label}"` : ''}`;
    };
    for (const el of Array.from(document.querySelectorAll('body *'))) {
      const h = el as HTMLElement;
      const cs = getComputedStyle(h);
      if (cs.display === 'none' || cs.visibility === 'hidden') continue;
      const r = h.getBoundingClientRect();
      if (r.width === 0 || r.height === 0) continue;
      if ((cs.overflowX === 'auto' || cs.overflowX === 'scroll') && h.scrollWidth > h.clientWidth + 1) {
        out.push(`scrollable ${describe(el)} scrollWidth ${h.scrollWidth} > ${h.clientWidth}`);
      }
      // Only report the outermost offender (skip descendants of an element already reported).
      if (r.right > vw + 1 && r.left < vw) {
        const parent = h.parentElement?.getBoundingClientRect();
        if (!parent || parent.right <= vw + 1) out.push(`${describe(el)} right ${Math.round(r.right)} > ${vw}`);
      }
    }
    return out.slice(0, 15);
  });
}

/** Video elements that currently render frames (decoded, non-zero size). */
export async function liveVideoCount(page: Page): Promise<number> {
  return page.evaluate(() =>
    Array.from(document.querySelectorAll('video')).filter(v => v.readyState >= 2 && v.videoWidth > 0 && !v.paused).length);
}

/** Participant tiles in the call room, by their accessibility label prefix. */
export function tile(page: Page, label: string) {
  return page.locator(`[aria-label^="${label.replace(/"/g, '\\"')}"]`).filter({ hasText: label });
}
