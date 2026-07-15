import { test, expect } from '@playwright/test';
import { createModel, deleteModel, openModel, switchTab, uid, BACKEND } from './helpers';
import * as fs from 'fs';
import * as path from 'path';

const specPath = path.join(__dirname, '../../valem-ui/src/examples/customer-satisfaction-survey.json');
const baseSpec = JSON.parse(fs.readFileSync(specPath, 'utf8'));

test.describe('Customer Satisfaction Survey view', () => {
  let modelId: string;

  test.beforeEach(async ({ request }) => {
    modelId = uid('e2e-survey');
    await createModel(request, baseSpec, modelId);

    // Apply initialState manually if the backend doesn't do it automatically
    const spec = baseSpec as { initialState?: Record<string, unknown> };
    if (spec.initialState) {
      await request.post(`${BACKEND}/models/${modelId}/mutations`, {
        data: spec.initialState,
      });
    }
  });

  test.afterEach(async ({ request }) => {
    await deleteModel(request, modelId);
  });

  test('view tab renders survey fields', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await expect(page.getByRole('group', { name: 'Overall Satisfaction' })).toBeVisible();
    await expect(page.getByLabel('I would recommend this product/service')).toBeVisible();
    await expect(page.getByLabel('I encountered an issue')).toBeVisible();
  });

  test('initial state: rating=5, recommend=true gives sentiment 100', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    const sentimentEl = page.getByTestId('sentimentLabel');
    await expect(sentimentEl).toContainText('100');
  });

  test('priority badge shows Normal when no issue and high rating', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    const badgeEl = page.getByTestId('priorityBadge');
    await expect(badgeEl).toContainText('Normal');
  });

  test('checking issue encountered reveals issue fields', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    // Initially, issue category is hidden (issueEncountered=false → relevant=false via meta)
    await expect(page.getByLabel('Issue Category')).not.toBeVisible();

    // Click "I encountered an issue" (use .click() for React controlled inputs)
    await page.getByLabel('I encountered an issue').click();

    // Issue category should now be visible and enabled
    await expect(page.getByLabel('Issue Category')).toBeVisible({ timeout: 15000 });
    await expect(page.getByLabel('Issue Category')).toBeEnabled();
  });

  test('low rating + issue → priority badge shows Needs follow-up', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    // Set rating to 2 — use .click() for React controlled radio buttons
    await page.getByLabel('2 — Dissatisfied').click();
    // Wait for the mutation to propagate (radio becomes checked)
    await expect(page.getByLabel('2 — Dissatisfied')).toBeChecked({ timeout: 10000 });

    // Report an issue
    await page.getByLabel('I encountered an issue').click();
    await expect(page.getByLabel('I encountered an issue')).toBeChecked({ timeout: 10000 });

    // priorityFlag = overallRating <= 2 AND issueEncountered = true → true
    const badgeEl = page.getByTestId('priorityBadge');
    await expect(badgeEl).toContainText('Needs follow-up');
  });

  test('sentiment score decreases when issue is reported', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    // Initial: rating=5, recommend=true, noIssue → 100
    await expect(page.getByTestId('sentimentLabel')).toContainText('100');

    // Report an issue (score -= 20 → 80); use .click() for controlled input
    await page.getByLabel('I encountered an issue').click();
    await expect(page.getByTestId('sentimentLabel')).toContainText('80', { timeout: 10000 });
  });

  test('contact permission shown only when issue + rating ≤ 3', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    const contactCheckbox = page.getByLabel('You may contact me about this issue');

    // Initially hidden (issueEncountered=false → contactPermission not relevant)
    await expect(contactCheckbox).not.toBeVisible();

    // Set rating=3 and report issue (click, then wait for each to propagate)
    await page.getByLabel('3 — Neutral').click();
    await expect(page.getByLabel('3 — Neutral')).toBeChecked({ timeout: 10000 });

    await page.getByLabel('I encountered an issue').click();

    // Contact permission should now be visible and enabled
    await expect(contactCheckbox).toBeVisible({ timeout: 15000 });
    await expect(contactCheckbox).toBeEnabled();
  });
});
