import { expect, test } from '@playwright/test';
import { registerAccount, registrationBody, throwawayPassword } from './support/accounts';
import { Api, currentLegalVersions, type LegalVersions } from './support/api';
import { ROLE_HOME, runTag, throwawayEmail } from './support/env';
import { closeOutboxReader, emailVerificationLink } from './support/outbox';
import { ACCEPT_TERMS_PATH, acceptTermsInterstitial } from './support/ui';
import {
  closeAll, flowPage, type FlowPage, publicPost, registerThroughUi, submitSignIn, tryLogin, uniqueSurname, VERIFY_EMAIL_PATH,
} from './flows/account-helpers';

// Email verification and registration with the legal versions: a person registers on the web form, which sends
// the current Terms and Privacy versions; the verification link is read from the outbox; asking for the email
// again makes only the newest link work; a used or superseded link shows the invalid state with a way to ask
// again. A registration naming a version that is no longer current is refused and saves nothing; one without
// versions signs in to the accept-terms page first.
//
// Tables written: users, patients, user_settings, legal_acceptances, email_verification_tokens, refresh_tokens,
// outbound_messages, audit_log. All rows belong to throwaway accounts e2e-…@carenexus.test and are removed by
// scripts/dev-reset-e2e-data.sh.
//
// Rate limits: resend allows 20 requests per IP per hour (no per-address cool-down); this spec sends two.

test.describe.configure({ mode: 'serial' });

test.describe('flow: email verification and terms at registration', () => {
  const pages: FlowPage[] = [];
  let legal: LegalVersions;

  test.beforeAll(async () => {
    const current = await currentLegalVersions();
    expect(current, 'GET /api/legal/current answers (the legal documents are seeded)').not.toBeNull();
    legal = current!;
  });

  test.afterAll(async () => {
    await closeAll(pages);
    await closeOutboxReader();
  });

  test('register on the web, resend from the confirmation, verify with the newest link', async ({ browser }) => {
    const p = await flowPage(browser);
    pages.push(p);
    const since = new Date();
    const { user, sent } = await registerThroughUi(p.page, { role: 'patient', tag: runTag(), lastName: uniqueSurname('Verify') });

    await test.step('the form sent the current Terms and Privacy versions, and they were recorded', async () => {
      expect(sent.acceptedTermsVersion).toBe(legal.terms);
      expect(sent.acceptedPrivacyVersion).toBe(legal.privacy);
      const api = await Api.login(user);
      try {
        expect(api.flags.legalActionRequired ?? false, 'nothing left to accept').toBe(false);
        expect(api.flags.emailVerified ?? false, 'not verified yet').toBe(false);
        const status = await api.ok(api.get('/api/users/me/legal'), 'legal status');
        expect(status.actionRequired).toBe(false);
        expect(status.pending).toEqual([]);
        const accepted = (status.accepted as { docType: string; version: string; current: boolean }[])
          .map(a => `${a.docType}:${a.version}:${a.current}`).sort();
        expect(accepted).toEqual([`PRIVACY:${legal.privacy}:true`, `TERMS:${legal.terms}:true`]);
      } finally {
        await api.dispose();
      }
    });

    const first = await emailVerificationLink(user.email, since);
    expect(first.path.startsWith(`${VERIFY_EMAIL_PATH}?token=`)).toBe(true);

    const resentSince = new Date();
    await test.step('"Resend verification email" on the confirmation sends a newer link', async () => {
      const res = p.page.waitForResponse(r => r.url().endsWith('/api/auth/email/resend') && r.request().method() === 'POST');
      await p.page.getByRole('button', { name: 'Resend verification email' }).click();
      expect((await res).status()).toBe(200);
      await expect(p.page.getByText(/we have sent a new link/i)).toBeVisible();
    });
    const newest = await emailVerificationLink(user.email, resentSince);
    expect(newest.token, 'the resend issued a new token').not.toBe(first.token);

    await test.step('the first link was superseded', async () => {
      await p.page.goto(first.path);
      await expect(p.page.getByRole('heading', { name: 'This verification link is invalid or has expired' })).toBeVisible();
      expect((await tryLogin(user.email, user.password)).body?.flags?.emailVerified ?? false).toBe(false);
    });

    await test.step('the newest link verifies the address', async () => {
      const verified = p.page.waitForResponse(r => r.url().endsWith('/api/auth/email/verify'));
      await p.page.goto(newest.path);
      expect((await verified).status()).toBe(200);
      await expect(p.page.getByRole('heading', { name: 'Your email address has been verified' })).toBeVisible();
      const attempt = await tryLogin(user.email, user.password);
      expect(attempt.status).toBe(200);
      expect(attempt.body?.flags?.emailVerified, 'flags.emailVerified after verification').toBe(true);
    });

    await test.step('a used link cannot be used again; the page offers to send a new one', async () => {
      await p.page.goto(newest.path);
      await expect(p.page.getByRole('heading', { name: 'This verification link is invalid or has expired' })).toBeVisible();
      await expect(p.page.getByRole('button', { name: 'Send a new link' })).toBeVisible();
      await p.page.getByRole('link', { name: 'Go to sign in' }).click();
      await expect(p.page).toHaveURL(/\/auth\/login\/web\/login-web$/);
    });
  });

  test('ask for a new link from an invalid link page, then verify with it', async ({ browser }) => {
    const since = new Date();
    const user = await registerAccount({ role: 'patient', lastName: uniqueSurname('Resend') });
    const first = await emailVerificationLink(user.email, since);

    const p = await flowPage(browser);
    pages.push(p);
    await p.page.goto(`${VERIFY_EMAIL_PATH}?token=e2e-not-a-real-token`);
    await expect(p.page.getByRole('heading', { name: 'This verification link is invalid or has expired' })).toBeVisible();

    const resentSince = new Date();
    await p.page.getByLabel('Email address', { exact: true }).fill(user.email);
    const res = p.page.waitForResponse(r => r.url().endsWith('/api/auth/email/resend') && r.request().method() === 'POST');
    await p.page.getByRole('button', { name: 'Send a new link' }).click();
    expect((await res).status()).toBe(200);
    await expect(p.page.getByText(/Only the newest link works\./)).toBeVisible();

    const newest = await emailVerificationLink(user.email, resentSince);
    expect(newest.token).not.toBe(first.token);
    expect((await publicPost('/api/auth/email/verify', { token: first.token })).body?.code, 'the first link was superseded')
      .toBe('TOKEN_INVALID');

    await p.page.goto(newest.path);
    await expect(p.page.getByRole('heading', { name: 'Your email address has been verified' })).toBeVisible();
    expect((await tryLogin(user.email, user.password)).body?.flags?.emailVerified).toBe(true);
  });

  test('a registration naming an old version is refused; one without versions accepts the terms at first sign-in', async ({ browser }) => {
    await test.step('a version that is not current aborts the registration and saves nothing', async () => {
      const tag = runTag();
      const password = throwawayPassword();
      const body = registrationBody({ role: 'patient', tag, password, lastName: uniqueSurname('Stale') }, { terms: `old-${tag.slice(-4)}`, privacy: legal.privacy });
      // A version is at most 20 characters (RegisterRequest); a longer one is refused as VALIDATION_FAILED instead.
      expect(String(body.acceptedTermsVersion).length).toBeLessThanOrEqual(20);
      const res = await publicPost('/api/auth/register', body);
      expect(res.status).toBe(400);
      expect(res.body?.code).toBe('LEGAL_ACCEPTANCE_REQUIRED');
      expect((await tryLogin(throwawayEmail(tag), password)).status, 'no account was created').toBe(401);
    });

    const user = await registerAccount({ role: 'patient', acceptLegal: false, lastName: uniqueSurname('Terms') });
    const first = await tryLogin(user.email, user.password);
    expect(first.body?.flags?.legalActionRequired, 'the first session asks for the legal documents').toBe(true);

    const p = await flowPage(browser);
    pages.push(p);
    const answer = await submitSignIn(p.page, user);
    expect(answer.status).toBe(200);
    await p.page.waitForURL(u => u.pathname === ACCEPT_TERMS_PATH, { timeout: 30_000 });
    await acceptTermsInterstitial(p.page);
    await p.page.waitForURL(u => u.pathname === ROLE_HOME.patient, { timeout: 30_000 });

    const api = await Api.login(user);
    try {
      const status = await api.ok(api.get('/api/users/me/legal'), 'legal status after accepting');
      expect(status.actionRequired).toBe(false);
      expect((status.accepted as { docType: string; version: string }[]).map(a => `${a.docType}:${a.version}`).sort())
        .toEqual([`PRIVACY:${legal.privacy}`, `TERMS:${legal.terms}`]);
    } finally {
      await api.dispose();
    }
  });
});
