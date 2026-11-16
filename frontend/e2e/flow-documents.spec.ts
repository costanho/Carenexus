import { readFile } from 'node:fs/promises';
import { expect, test } from '@playwright/test';
import { Api } from './support/api';
import { JAMES, THOMAS, runTag } from './support/env';
import { type MonitoredPage, signedInPage } from './support/smoke';
import { apiResponse, dialog, firstPage, openAt, samplePdf } from './flows/clinical-helpers';

// Flow: documents.
// Thomas uploads a PDF into his record on the web and downloads it again (same bytes, same file name); his doctor
// can read it. A file whose content is not an allowed type is refused by the server even with a .pdf name, and a
// file with a disallowed extension is refused in the browser before anything is sent. Thomas finally deletes the
// uploaded document.
//
// Tables written (for scripts/dev-reset-e2e-data.sh): documents (title "e2e…", soft-deleted at the end) and its
// stored file under documents/<yyyy>/<mm>/<uuid>, idempotency_keys, audit_log.

test.describe('flow: documents', () => {
  const tag = runTag();
  const pages: MonitoredPage[] = [];
  let james: Api;
  let thomas: Api;

  test.beforeAll(async () => { [james, thomas] = await Promise.all([Api.login(JAMES), Api.login(THOMAS)]); });
  test.afterAll(async () => {
    for (const p of pages) await p.context.close().catch(() => {});
    await Promise.all([james?.dispose(), thomas?.dispose()]);
  });

  const thomasDocuments = async () => firstPage(thomas, `/api/documents/patient/${THOMAS.systemId}`, {}, 100);

  test('the patient uploads, downloads and deletes a PDF; wrong file types are refused', async ({ browser }) => {
    const patient = await signedInPage(browser, THOMAS);
    pages.push(patient);
    const { page, monitor } = patient;
    monitor.allow({ method: 'POST', url: /^\/api\/documents$/, status: 400, why: 'the server refuses the fake PDF on purpose (FILE_REJECTED)' });

    const fileName = `${tag}-discharge-letter.pdf`;
    const title = `${tag} discharge letter`;
    const bytes = samplePdf(title);
    let documentId = 0;

    await test.step('upload a PDF', async () => {
      await openAt(page, 'patient', 'documents', 'Documents');
      await page.getByRole('button', { name: 'Upload document', exact: true }).first().click();
      const form = dialog(page, 'Upload document');
      await form.getByTestId('document-file-input').setInputFiles({ name: fileName, mimeType: 'application/pdf', buffer: bytes });
      await expect(form.getByText(new RegExp(`^${fileName} · `))).toBeVisible();
      await form.getByLabel('Title', { exact: true }).fill(title);
      await form.getByLabel('Document type', { exact: true }).selectOption('DISCHARGE');
      const uploaded = apiResponse(page, 'POST', /^\/api\/documents$/);
      await form.getByRole('button', { name: 'Upload', exact: true }).click();
      const res = await uploaded;
      expect(res.status(), 'document uploaded').toBe(201);
      const body = await res.json();
      documentId = body.documentId;
      expect(body.contentType).toBe('application/pdf');
      expect(body.sizeBytes).toBe(bytes.length);
      expect(body.originalFilename).toBe(fileName);
      expect(body.documentType).toBe('DISCHARGE');
      await expect(form).toHaveCount(0);
      await expect(page.getByText(title, { exact: true }).first()).toBeVisible();
    });

    await test.step('download it again', async () => {
      const download = page.waitForEvent('download');
      await page.getByRole('button', { name: `Download ${title}`, exact: true }).click();
      const file = await download;
      expect(file.suggestedFilename()).toBe(fileName);
      const saved = await readFile(await file.path());
      expect(saved.equals(bytes), 'the downloaded bytes are the uploaded bytes').toBe(true);
    });

    await test.step('his doctor can read it', async () => {
      const forDoctor = await firstPage(james, `/api/documents/patient/${THOMAS.systemId}`, {}, 100);
      expect(forDoctor.some(d => d.documentId === documentId)).toBe(true);
      const file = await james.get(`/api/documents/${documentId}/download`);
      expect(file.status, 'doctor downloads the file').toBe(200);
    });

    await test.step('a file that is not really a PDF is refused by the server', async () => {
      const fakeTitle = `${tag} not a pdf`;
      await page.getByRole('button', { name: 'Upload document', exact: true }).first().click();
      const form = dialog(page, 'Upload document');
      await form.getByTestId('document-file-input').setInputFiles({
        name: `${tag}-notes.pdf`, mimeType: 'application/pdf', buffer: Buffer.from('Plain text notes, not a PDF document.\n'),
      });
      await form.getByLabel('Title', { exact: true }).fill(fakeTitle);
      const refused = apiResponse(page, 'POST', /^\/api\/documents$/);
      await form.getByRole('button', { name: 'Upload', exact: true }).click();
      const res = await refused;
      expect(res.status(), 'upload refused').toBe(400);
      expect((await res.json()).code).toBe('FILE_REJECTED');
      await expect(form.getByText('This file type is not allowed. Upload a PDF, JPEG, PNG or DICOM file.', { exact: true })).toBeVisible();

      await test.step('a file with a disallowed extension is refused before it is sent', async () => {
        let sent = 0;
        const count = (r: { url(): string; method(): string }) => {
          if (r.method() === 'POST' && new URL(r.url()).pathname === '/api/documents') sent++;
        };
        page.on('request', count);
        await form.getByTestId('document-file-input').setInputFiles({
          name: `${tag}-setup.exe`, mimeType: 'application/x-msdownload', buffer: Buffer.from('MZ not allowed'),
        });
        await expect(form.getByText('This file type is not allowed. Upload a PDF, JPEG, PNG or DICOM file.', { exact: true })).toBeVisible();
        await expect(form.getByText('No file chosen', { exact: true })).toBeVisible();
        await form.getByRole('button', { name: 'Upload', exact: true }).click();
        await expect(form).toBeVisible();
        page.off('request', count);
        expect(sent, 'no upload request for a refused file').toBe(0);
      });

      // Discarding the form asks for confirmation in a browser dialog, which the page accepts.
      await form.getByRole('button', { name: 'Cancel', exact: true }).click();
      await expect(form).toHaveCount(0);
      expect((await thomasDocuments()).some(d => d.title === fakeTitle), 'nothing stored for the refused file').toBe(false);
    });

    await test.step('delete the uploaded document', async () => {
      const removed = apiResponse(page, 'DELETE', new RegExp(`^/api/documents/${documentId}$`));
      await page.getByRole('button', { name: `Delete ${title}`, exact: true }).click();
      expect((await removed).status(), 'document deleted').toBe(204);
      await expect(page.getByRole('button', { name: `Download ${title}`, exact: true })).toHaveCount(0);
      expect((await thomasDocuments()).some(d => d.documentId === documentId)).toBe(false);
      const gone = await thomas.get(`/api/documents/${documentId}`);
      expect(gone.status, 'a deleted document is not found').toBe(404);
      monitor.expectClean('documents');
    });
  });
});
