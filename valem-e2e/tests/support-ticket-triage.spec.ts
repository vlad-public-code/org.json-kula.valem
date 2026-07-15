import { test, expect } from '@playwright/test';
import { createModel, deleteModel, fillAndBlur, openModel, switchTab, uid, BACKEND } from './helpers';
import * as fs from 'fs';
import * as path from 'path';

const specPath = path.join(__dirname, '../../valem-ui/src/examples/support-ticket-triage.json');
const baseSpec = JSON.parse(fs.readFileSync(specPath, 'utf8'));

// This example's category/urgency fields are normally filled by an LLM effect, which this test
// environment has no API key configured for. So instead of typing a description and waiting on a
// live LLM call, these tests mutate ticket.urgency directly via the API (standing in for what the
// LLM fold-back would produce) and verify the already-open page updates itself over the WebSocket
// push, without a reload — the same live-update mechanism the LLM/server/timer effects rely on.
test.describe('Support Ticket Triage view', () => {
  let modelId: string;

  test.beforeEach(async ({ request }) => {
    modelId = uid('e2e-ticket');
    await createModel(request, baseSpec, modelId);
  });

  test.afterEach(async ({ request }) => {
    await deleteModel(request, modelId);
  });

  test('view tab renders the description and status fields', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await expect(page.getByLabel('Describe your issue')).toBeVisible();
    await expect(page.getByLabel('Status')).toBeVisible();
  });

  test('typing a description persists through the deferred mutation', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await fillAndBlur(page, 'Describe your issue', 'I was charged twice for my order');
    await expect(page.getByLabel('Describe your issue')).toHaveValue('I was charged twice for my order');
  });

  test('urgency >= 4 flags high priority and the open page updates live over WebSocket', async ({ page, request }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await expect(page.getByTestId('highPriority')).toContainText('false');

    // Simulate the LLM effect's fold-back landing while the page is already open.
    const r = await request.post(`${BACKEND}/models/${modelId}/mutations`, {
      data: { '$.ticket.urgency': 5 },
    });
    expect(r.ok()).toBeTruthy();

    await expect(page.getByTestId('highPriority')).toContainText('true', { timeout: 10000 });
  });

  test('urgency below 4 is not high priority', async ({ page, request }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    const r = await request.post(`${BACKEND}/models/${modelId}/mutations`, {
      data: { '$.ticket.urgency': 2 },
    });
    expect(r.ok()).toBeTruthy();

    await expect(page.getByTestId('highPriority')).toContainText('false', { timeout: 10000 });
  });
});
