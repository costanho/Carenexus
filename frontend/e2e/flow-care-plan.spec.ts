import { expect, test } from '@playwright/test';
import { Api } from './support/api';
import { JAMES, SARAH, THOMAS, runTag } from './support/env';
import { type MonitoredPage, signedInPage } from './support/smoke';
import { apiResponse, dialog, openAt } from './flows/clinical-helpers';

// Flow: care plan.
// Dr. Banda writes a care plan for Thomas on the web (one goal, one intervention). Thomas sees it and updates the
// goal's progress; Sarah (caregiver with full access) sees the plan and the progress; the doctor reads the progress
// back from the server.
//
// Every free text (title, description, goal, intervention with its frequency and responsible party, progress note)
// starts with the run's e2e marker.
//
// Tables written (for scripts/dev-reset-e2e-data.sh): care_plans (title "e2e…"), care_plan_goals and
// care_plan_interventions (of that plan), notifications (care plan notices about the plan), outbound_messages (their
// fan-out), idempotency_keys, audit_log.

test.describe('flow: care plan', () => {
  test.describe.configure({ mode: 'serial' });

  const tag = runTag();
  const title = `${tag} Blood pressure plan`;
  const goal = `${tag} walk for 30 minutes on five days a week`;
  const intervention = `${tag} home blood pressure readings`;
  const progressNote = `${tag} walking three days a week so far.`;
  const pages: MonitoredPage[] = [];
  let james: Api;
  let planId = 0;
  let goalId = 0;

  test.beforeAll(async () => { james = await Api.login(JAMES); });
  test.afterAll(async () => {
    for (const p of pages) await p.context.close().catch(() => {});
    await james?.dispose();
  });

  test('the doctor creates a care plan with a goal and an intervention', async ({ browser }) => {
    const doctor = await signedInPage(browser, JAMES);
    pages.push(doctor);
    const { page, monitor } = doctor;

    await openAt(page, 'doctor', 'care-plans', 'Care Plans');
    await page.getByRole('button', { name: 'New care plan', exact: true }).first().click();
    const form = dialog(page, 'New care plan');
    await form.getByLabel('Patient', { exact: true }).selectOption(THOMAS.systemId);
    await form.getByLabel('Title', { exact: true }).fill(title);
    await form.getByLabel('Description', { exact: true }).fill(`${tag} agreed after the blood pressure review.`);
    await form.getByRole('button', { name: 'Add goal', exact: true }).click();
    await form.getByRole('group', { name: 'Goal 1', exact: true }).getByLabel('Goal', { exact: true }).fill(goal);
    await form.getByRole('button', { name: 'Add intervention', exact: true }).click();
    const row = form.getByRole('group', { name: 'Intervention 1', exact: true });
    await row.getByLabel('Intervention', { exact: true }).fill(intervention);
    await row.getByLabel('Frequency', { exact: true }).fill(`${tag} daily`);
    await row.getByLabel('Responsible', { exact: true }).fill(`${tag} patient`);

    const created = apiResponse(page, 'POST', /^\/api\/care-plans$/);
    await form.getByRole('button', { name: 'Create care plan', exact: true }).click();
    const res = await created;
    expect(res.status(), 'care plan created').toBe(201);
    const body = await res.json();
    planId = body.planId;
    expect(body.status).toBe('ACTIVE');
    expect(body.patientSystemId).toBe(THOMAS.systemId);
    expect(body.goals.map((g: any) => `${g.description} ${g.status}`)).toEqual([`${goal} OPEN`]);
    expect(body.interventions.map((i: any) => i.description)).toEqual([intervention]);
    goalId = body.goals[0].goalId;
    await expect(form).toHaveCount(0);
    await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible();
    monitor.expectClean('create care plan');
  });

  test('the patient sees the plan and updates the goal\'s progress', async ({ browser }) => {
    test.skip(!planId, 'needs the plan of the previous test');
    const patient = await signedInPage(browser, THOMAS);
    pages.push(patient);
    const { page, monitor } = patient;

    await openAt(page, 'patient', 'care-plans', 'Care Plans');
    await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible();
    await page.getByRole('button', { name: `Show details, ${title}`, exact: true }).click();
    await expect(page.getByText(goal, { exact: true }).first()).toBeVisible();
    await expect(page.getByText(intervention, { exact: true }).first()).toBeVisible();

    await page.getByRole('button', { name: `Update progress, ${goal}`, exact: true }).click();
    const editor = page.getByRole('group', { name: `Update progress, ${goal}`, exact: true });
    await editor.getByLabel('Goal status', { exact: true }).selectOption('IN_PROGRESS');
    await editor.getByLabel('Progress note', { exact: true }).fill(progressNote);
    const saved = apiResponse(page, 'PATCH', new RegExp(`^/api/care-plans/${planId}/goals/${goalId}$`));
    await editor.getByRole('button', { name: 'Save progress', exact: true }).click();
    expect((await saved).status(), 'progress saved').toBe(200);
    await expect(page.getByText('Progress saved', { exact: true })).toBeVisible();
    await expect(page.getByText(`Progress: ${progressNote}`, { exact: true }).first()).toBeVisible();
    monitor.expectClean('goal progress');
  });

  test('the caregiver sees the plan and the progress, and the doctor reads it back', async ({ browser }) => {
    test.skip(!planId, 'needs the plan of the first test');
    const carer = await signedInPage(browser, SARAH);
    pages.push(carer);
    const { page, monitor } = carer;

    await openAt(page, 'proxy', 'care-plans', 'Care Plans', { patient: THOMAS.systemId });
    await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible();
    await page.getByRole('button', { name: `Show details, ${title}`, exact: true }).click();
    await expect(page.getByText(goal, { exact: true }).first()).toBeVisible();
    await expect(page.getByText(`Progress: ${progressNote}`, { exact: true }).first()).toBeVisible();
    await expect(page.getByText('In progress', { exact: true }).first()).toBeVisible();
    monitor.expectClean('caregiver care plan');

    const plan = await james.ok(james.get(`/api/care-plans/${planId}`), 'doctor reads the plan');
    expect(plan.goals[0].status).toBe('IN_PROGRESS');
    expect(plan.goals[0].progressNote).toBe(progressNote);
  });
});
