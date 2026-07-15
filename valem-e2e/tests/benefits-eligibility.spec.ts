import { test, expect } from '@playwright/test';
import { createModel, deleteModel, fillAndBlur, openModel, switchTab, uid } from './helpers';
import * as fs from 'fs';
import * as path from 'path';

const specPath = path.join(__dirname, '../../valem-ui/src/examples/benefits-eligibility.json');
const baseSpec = JSON.parse(fs.readFileSync(specPath, 'utf8'));

// Multiple derivations + effects; a brand-new model pays a cold JSONata->Java compile for every
// expression on first creation (documented cross-cutting cost) — give it more headroom than 30s.
test.describe.configure({ timeout: 60_000 });

test.describe('Benefits Eligibility view', () => {
  let modelId: string;

  test.beforeEach(async ({ request }) => {
    modelId = uid('e2e-benefits');
    await createModel(request, baseSpec, modelId);
  });

  test.afterEach(async ({ request }) => {
    await deleteModel(request, modelId);
  });

  test('view tab renders input fields', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await expect(page.getByLabel('Annual household income ($)')).toBeVisible();
    await expect(page.getByLabel('Household size')).toBeVisible();
    await expect(page.getByLabel('Age', { exact: true })).toBeVisible();
  });

  test('household of 1, $12k income, adult -> full tier, $500/mo', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await fillAndBlur(page, 'Household size', '1');
    await fillAndBlur(page, 'Annual household income ($)', '12000');
    await fillAndBlur(page, 'Age', '30', { exact: true });

    await expect(page.getByTestId('poverty')).toContainText('15000');
    await expect(page.getByTestId('ratio')).toContainText('0.8');
    await expect(page.getByTestId('tier')).toContainText('full');
    await expect(page.getByTestId('eligible')).toContainText('true');
    await expect(page.getByTestId('benefit')).toContainText('500');
  });

  test('household of 4, $45k income -> partial tier, $300/mo', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await fillAndBlur(page, 'Household size', '4');
    await fillAndBlur(page, 'Annual household income ($)', '45000');
    await fillAndBlur(page, 'Age', '40', { exact: true });

    await expect(page.getByTestId('poverty')).toContainText('30000');
    await expect(page.getByTestId('ratio')).toContainText('1.5');
    await expect(page.getByTestId('tier')).toContainText('partial');
    await expect(page.getByTestId('benefit')).toContainText('300');
  });

  test('income above 200% of the poverty line is ineligible', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await fillAndBlur(page, 'Household size', '2');
    await fillAndBlur(page, 'Annual household income ($)', '50000');
    await fillAndBlur(page, 'Age', '45', { exact: true });

    await expect(page.getByTestId('tier')).toContainText('none');
    await expect(page.getByTestId('eligible')).toContainText('false');
    await expect(page.getByTestId('benefit')).toContainText('0');
  });

  test('a minor is ineligible even below the poverty line', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await fillAndBlur(page, 'Household size', '1');
    await fillAndBlur(page, 'Annual household income ($)', '10000');
    await fillAndBlur(page, 'Age', '16', { exact: true });

    await expect(page.getByTestId('eligible')).toContainText('false');
    await expect(page.getByTestId('benefit')).toContainText('0');
  });

  test('benefit recomputes immediately when income changes', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await fillAndBlur(page, 'Household size', '1');
    await fillAndBlur(page, 'Age', '30', { exact: true });
    await fillAndBlur(page, 'Annual household income ($)', '12000');
    await expect(page.getByTestId('tier')).toContainText('full');

    // Raise income past 200% of the poverty line ($15,000) -> tier drops to none
    await fillAndBlur(page, 'Annual household income ($)', '40000');
    await expect(page.getByTestId('tier')).toContainText('none');
    await expect(page.getByTestId('benefit')).toContainText('0');
  });
});
