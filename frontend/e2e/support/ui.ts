import AxeBuilder from '@axe-core/playwright';
import { type Browser, type BrowserContext, type BrowserContextOptions, expect, type Page } from '@playwright/test';
import { type DemoUser, ROLE_HOME, ROLE_LOGIN_LABEL } from './env';

// UI helpers. Each user gets their own browser context (separate sessionStorage = separate session).

export interface UserPage { context: BrowserContext; page: Page; user: DemoUser }

export async function newUserPage(browser: Browser, user: DemoUser, options: BrowserContextOptions = {}): Promise<UserPage> {
  const context = await browser.newContext({
    permissions: ['camera', 'microphone'],
    ...options,
  });
  const page = await context.newPage();
  // confirmAction() uses window.confirm on web: accept "End call for everyone?" and similar prompts.
  page.on('dialog', d => { d.accept().catch(() => {}); });
  await login(page, user);
  return { context, page, user };
}

export async function login(page: Page, user: DemoUser): Promise<void> {
  await page.goto('/auth/login/web/login-web');
  await page.getByRole('radio', { name: ROLE_LOGIN_LABEL[user.role], exact: true }).click();
  await page.getByLabel('Email address').fill(user.email);
  await page.getByLabel('Password', { exact: true }).fill(user.password);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.waitForURL(`**${ROLE_HOME[user.role]}`, { timeout: 30_000 });
}

/** Opens a dashboard section from the left sidebar (label may carry an unread suffix). */
export async function openSection(page: Page, label: string): Promise<void> {
  await page.getByRole('button', { name: new RegExp(`^${escapeRe(label)}(, \\d+ unread)?$`) }).first().click();
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
  let builder = new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']);
  if (include) builder = builder.include(include);
  const results = await builder.analyze();
  return results.violations
    .filter(v => v.impact === 'serious' || v.impact === 'critical')
    .map(v => `${v.impact} ${v.id}: ${v.help}\n${v.nodes.slice(0, 6).map(n =>
      `    - ${n.html.replace(/\s+/g, ' ').slice(0, 160)}${n.failureSummary ? `\n      ${n.failureSummary.split('\n').slice(-1)[0].trim().slice(0, 160)}` : ''}`).join('\n')}`);
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
