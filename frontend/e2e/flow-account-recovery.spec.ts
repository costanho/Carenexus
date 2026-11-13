import { expect, test } from '@playwright/test';
import { registerAccount, type RegisteredUser, throwawayPassword } from './support/accounts';
import { closeOutboxReader, passwordResetLink, waitForOutboxMessage } from './support/outbox';
import { LOGIN_PATH, login, sectionUrl } from './support/ui';
import {
  closeAll, FORGOT_PASSWORD_PATH, flowPage, type FlowPage, openSession, publicPost, refreshStatus, storedTokens,
  tryLogin, uniqueSurname,
} from './flows/account-helpers';

// Account recovery: a throwaway patient asks for a reset link on the forgot-password page, reads it from the
// outbox, sets a new password, and every earlier session ends; an older link stops working once a newer one is
// sent, and a used link cannot be used again. Then the patient changes the password from Profile & Settings: the
// session that made the change stays, every other one ends.
//
// Tables written: users, patients, user_settings, legal_acceptances, refresh_tokens, password_reset_tokens,
// outbound_messages, audit_log, idempotency_keys (none expected). All rows belong to the throwaway account
// e2e-…@carenexus.test and are removed by scripts/dev-reset-e2e-data.sh.
//
// Rate limits: forgot-password allows 3 requests per address and 10 per IP per hour, so this spec sends exactly
// two (one by API, one through the page). Several runs within an hour from one machine can hit the IP bucket.

test.describe.configure({ mode: 'serial' });

test.describe('flow: account recovery', () => {
  let user: RegisteredUser;
  const pages: FlowPage[] = [];

  test.beforeAll(async () => {
    user = await registerAccount({ role: 'patient', lastName: uniqueSurname('Recovery') });
  });

  test.afterAll(async () => {
    await closeAll(pages);
    await closeOutboxReader();
  });

  test('forgot password: the newest emailed link sets a new password and ends every earlier session', async ({ browser }) => {
    // Two sessions that exist before the reset: one through the API, one in a browser.
    const apiSession = await openSession(user);
    const before = await flowPage(browser, user);
    pages.push(before);
    const [, browserRefresh] = await storedTokens(before.page);
    expect(browserRefresh, 'the browser session stored a refresh token').toBeTruthy();

    // An earlier link, sent through the API.
    const firstSince = new Date();
    const first = await publicPost('/api/auth/password/forgot', { email: user.email });
    expect(first.status, 'forgot-password answers 200 for a known address').toBe(200);
    const olderLink = await passwordResetLink(user.email, firstSince);

    const p = await flowPage(browser);
    pages.push(p);
    await test.step('the sign-in page links to the forgot-password page', async () => {
      await p.page.goto(LOGIN_PATH);
      await p.page.getByRole('link', { name: 'Forgot password?' }).click();
      await expect(p.page).toHaveURL(new RegExp(`${FORGOT_PASSWORD_PATH}$`));
      await expect(p.page.getByRole('heading', { name: 'Forgot your password?' })).toBeVisible();
    });

    const since = new Date();
    await test.step('asking for a link shows the same neutral confirmation, without the address', async () => {
      // The sign-in screen stays mounted (hidden) in the router stack after the client-side navigation, so its
      // own "Email address" field is still in the DOM; the role query only resolves the visible field.
      await p.page.getByRole('textbox', { name: 'Email address', exact: true }).fill(user.email);
      const res = p.page.waitForResponse(r => r.url().endsWith('/api/auth/password/forgot') && r.request().method() === 'POST');
      await p.page.getByRole('button', { name: 'Send reset link' }).click();
      expect((await res).status(), 'forgot-password request accepted (429 means the hourly bucket is used up)').toBe(200);
      await expect(p.page.getByRole('heading', { name: 'Check your inbox' })).toBeVisible();
      await expect(p.page.getByText(user.email)).toHaveCount(0);
    });

    const link = await passwordResetLink(user.email, since);
    expect(link.token, 'a newer link was sent').not.toBe(olderLink.token);

    await test.step('the older link no longer works', async () => {
      await p.page.goto(olderLink.path);
      await expect(p.page.getByRole('heading', { name: 'Set a new password' })).toBeVisible();
      const throwaway = throwawayPassword();
      await p.page.getByLabel('New password', { exact: true }).fill(throwaway);
      await p.page.getByLabel('Confirm new password', { exact: true }).fill(throwaway);
      const res = p.page.waitForResponse(r => r.url().endsWith('/api/auth/password/reset'));
      await p.page.getByRole('button', { name: 'Save new password' }).click();
      const response = await res;
      expect(response.status()).toBe(400);
      expect((await response.json()).code).toBe('PASSWORD_RESET_TOKEN_INVALID');
      await expect(p.page.getByRole('heading', { name: 'This link is invalid or has expired' })).toBeVisible();
    });

    const oldPassword = user.password;
    const newPassword = throwawayPassword();
    const changedSince = new Date();
    await test.step('the newest link sets the new password', async () => {
      await p.page.goto(link.path);
      await expect(p.page.getByRole('heading', { name: 'Set a new password' })).toBeVisible();
      await p.page.getByLabel('New password', { exact: true }).fill(newPassword);
      await p.page.getByLabel('Confirm new password', { exact: true }).fill(newPassword);
      const res = p.page.waitForResponse(r => r.url().endsWith('/api/auth/password/reset'));
      await p.page.getByRole('button', { name: 'Save new password' }).click();
      expect((await res).status()).toBe(200);
      await expect(p.page.getByRole('heading', { name: 'Your password has been set' })).toBeVisible();
      user.password = newPassword;
      await p.page.getByRole('button', { name: 'Go to sign in' }).click();
      await expect(p.page).toHaveURL(new RegExp(`${LOGIN_PATH}$`));
    });

    await test.step('every earlier session ended, the old password is refused, a confirmation email was sent', async () => {
      expect(await refreshStatus(apiSession), 'API session from before the reset').toBe(401);
      const browserCheck = await publicPost('/api/auth/refresh', { refreshToken: browserRefresh });
      expect(browserCheck.status, 'browser session from before the reset').toBe(401);
      expect((await tryLogin(user.email, oldPassword)).status, 'old password').toBe(401);
      expect((await tryLogin(user.email, newPassword)).status, 'new password').toBe(200);
      const mail = await waitForOutboxMessage({ to: user.email, template: 'account-password-changed', channel: 'EMAIL', since: changedSince });
      expect(mail.body, 'the confirmation carries no link').not.toMatch(/reset-password\?token=/);
    });

    await test.step('a used link cannot be used again', async () => {
      await p.page.goto(link.path);
      const again = throwawayPassword();
      await p.page.getByLabel('New password', { exact: true }).fill(again);
      await p.page.getByLabel('Confirm new password', { exact: true }).fill(again);
      await p.page.getByRole('button', { name: 'Save new password' }).click();
      await expect(p.page.getByRole('heading', { name: 'This link is invalid or has expired' })).toBeVisible();
      expect((await tryLogin(user.email, again)).status).toBe(401);
    });

    await test.step('a link without a token shows the invalid-link state, and the new password signs in on the web', async () => {
      await p.page.goto(link.path.replace(/token=[^&]+/, 'token='));
      await expect(p.page.getByRole('heading', { name: 'This link is invalid or has expired' })).toBeVisible();
      await expect(p.page.getByRole('button', { name: 'Request a new link' })).toBeVisible();
      await login(p.page, user);
    });
  });

  test('change password in Profile & Settings keeps this session and ends the others', async ({ browser }) => {
    const other = await openSession(user);
    const p = await flowPage(browser, user);
    pages.push(p);
    await p.page.goto(sectionUrl('patient', 'profile-settings', { tab: 'security' }));
    const card = p.page.locator('div')
      .filter({ hasText: 'Changing your password signs out every other device that is using your account.' })
      .filter({ has: p.page.getByRole('button', { name: 'Change password', exact: true }) })
      .last();
    await expect(card).toBeVisible({ timeout: 30_000 });

    await test.step('a wrong current password is refused on its field', async () => {
      const wrong = throwawayPassword();
      await card.getByLabel('Current password', { exact: true }).fill(`${user.password}-wrong`);
      await card.getByLabel('New password', { exact: true }).fill(wrong);
      await card.getByLabel('Confirm new password', { exact: true }).fill(wrong);
      const res = p.page.waitForResponse(r => r.url().endsWith('/api/users/me/password'));
      await card.getByRole('button', { name: 'Change password', exact: true }).click();
      const response = await res;
      expect(response.status(), 'a wrong current password is a 400, never a 401').toBe(400);
      expect((await response.json()).code).toBe('CURRENT_PASSWORD_INCORRECT');
      await expect(card.getByText('Current password is incorrect')).toBeVisible();
      expect(await refreshStatus(other), 'nothing changed: the other session lives').toBe(200);
    });

    const oldPassword = user.password;
    const newPassword = throwawayPassword();
    await test.step('the right current password changes it; this device stays signed in', async () => {
      await card.getByLabel('Current password', { exact: true }).fill(oldPassword);
      await card.getByLabel('New password', { exact: true }).fill(newPassword);
      await card.getByLabel('Confirm new password', { exact: true }).fill(newPassword);
      const req = p.page.waitForRequest(r => r.url().endsWith('/api/users/me/password') && r.method() === 'POST');
      const res = p.page.waitForResponse(r => r.url().endsWith('/api/users/me/password'));
      await card.getByRole('button', { name: 'Change password', exact: true }).click();
      expect((await req).postDataJSON().keepSessionId, 'the web names this session as the one to keep').toBeTruthy();
      expect((await res).status()).toBe(200);
      await expect(p.page.getByText(/You stay signed in on this device; every other device was signed out\./)).toBeVisible();
      user.password = newPassword;
    });

    await test.step('this session still refreshes, the other one ended, and only the new password signs in', async () => {
      // The kept session is refreshed first and the rotated tokens are handed back to the page. Presenting the
      // ended session's token afterwards counts as token reuse, which ends every session of the account, so
      // that check comes last.
      const [, keptRefresh] = await storedTokens(p.page);
      const kept = await publicPost('/api/auth/refresh', { refreshToken: keptRefresh });
      expect(kept.status, 'the session that made the change').toBe(200);
      await p.page.evaluate(([access, refresh]) => {
        sessionStorage.setItem('carenexus_access_token', access);
        sessionStorage.setItem('carenexus_refresh_token', refresh);
      }, [kept.body.tokens.accessToken as string, kept.body.tokens.refreshToken as string]);
      await p.page.reload();
      await expect(p.page).toHaveURL(/\/dashboard\/patient/);
      await expect(p.page.getByRole('main').first()).toBeVisible();

      expect(await refreshStatus(other), 'session opened elsewhere before the change').toBe(401);
      expect((await tryLogin(user.email, oldPassword)).status).toBe(401);
      expect((await tryLogin(user.email, newPassword)).status).toBe(200);
    });
  });
});
