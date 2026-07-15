import { test, expect } from '@playwright/test';
import { createModel, deleteModel, fillAndBlur, openModel, switchTab, uid } from './helpers';
import * as fs from 'fs';
import * as path from 'path';

const specPath = path.join(__dirname, '../../valem-ui/src/examples/insurance-quote.json');
const baseSpec = JSON.parse(fs.readFileSync(specPath, 'utf8'));

// insurance-quote has 8 derivations + 3 effects; ExpressionCache is per-runtime (CLAUDE.md), so a
// brand-new model pays a cold JSONata->Java compile for every expression on first creation
// (documented cross-cutting cost, ~35s worst case) — give it more headroom than the 30s default.
test.describe.configure({ timeout: 60_000 });

test.describe('Term Life Insurance Quote view', () => {
  let modelId: string;

  test.beforeEach(async ({ request }) => {
    modelId = uid('e2e-quote');
    await createModel(request, baseSpec, modelId);
  });

  test.afterEach(async ({ request }) => {
    await deleteModel(request, modelId);
  });

  test('view tab renders input fields', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await expect(page.getByLabel('Age', { exact: true })).toBeVisible();
    await expect(page.getByLabel('Smoker')).toBeVisible();
    await expect(page.getByLabel('Region')).toBeVisible();
    await expect(page.getByLabel('Coverage amount')).toBeVisible();
  });

  test('healthy 35-year-old non-smoker, $100k coverage -> $150/yr, $12.50/mo, quoted', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await fillAndBlur(page, 'Age', '35', { exact: true });
    await fillAndBlur(page, 'Coverage amount', '100000');

    await expect(page.getByTestId('decision')).toContainText('quoted');
    await expect(page.getByTestId('annual')).toContainText('150');
    await expect(page.getByTestId('monthly')).toContainText('12.5');
  });

  test('smoker aged 55, $250k -> $787.50/yr', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await fillAndBlur(page, 'Age', '55', { exact: true });
    await page.getByLabel('Smoker').click();
    await fillAndBlur(page, 'Coverage amount', '250000');

    await expect(page.getByTestId('annual')).toContainText('787.5');
    await expect(page.getByTestId('monthly')).toContainText('65.625');
  });

  test('applicant over the maximum insurable age is declined', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await fillAndBlur(page, 'Age', '80', { exact: true });
    await fillAndBlur(page, 'Coverage amount', '100000');

    await expect(page.getByTestId('decision')).toContainText('declined');
  });

  test('applicant below the minimum insurable age is declined', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await fillAndBlur(page, 'Age', '16', { exact: true });
    await fillAndBlur(page, 'Coverage amount', '100000');

    await expect(page.getByTestId('decision')).toContainText('declined');
  });

  test('premium updates immediately when coverage changes', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await fillAndBlur(page, 'Age', '35', { exact: true });
    await fillAndBlur(page, 'Coverage amount', '100000');
    await expect(page.getByTestId('annual')).toContainText('150');

    // Doubling coverage doubles the annual premium
    await fillAndBlur(page, 'Coverage amount', '200000');
    await expect(page.getByTestId('annual')).toContainText('300');
  });
});
