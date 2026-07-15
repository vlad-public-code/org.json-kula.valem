import { test, expect } from '@playwright/test';
import { createModel, deleteModel, openModel, switchTab, uid, BACKEND } from './helpers';
import { SIMPLE_SPEC } from './fixtures';

test.describe('Explain tab', () => {
  let modelId: string;

  test.beforeEach(async ({ request }) => {
    modelId = uid('e2e-explain');
    await createModel(request, SIMPLE_SPEC, modelId);
    // Trigger constraint evaluation so traces exist
    await request.post(`${BACKEND}/models/${modelId}/mutations`, {
      data: { '$.price': 2, '$.qty': 3 },
    });
  });

  test.afterEach(async ({ request }) => {
    await deleteModel(request, modelId);
  });

  test('constraint trace shows expression and result', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'explain');

    await page.getByPlaceholder('e.g. $constraint:credit-check').fill('$constraint:qty-positive');
    await page.getByRole('button', { name: 'Explain', exact: true }).click();

    await expect(page.getByText(/Traces \(\d+\)/)).toBeVisible();
    await expect(page.locator('.trace-card').first()).toBeVisible();
    await expect(page.locator('.trace-expr')).toContainText('qty > 0');
  });

  test('Enter key submits the explain query', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'explain');

    const input = page.getByPlaceholder('e.g. $constraint:credit-check');
    await input.fill('$constraint:qty-positive');
    await input.press('Enter');

    await expect(page.getByText(/Traces \(\d+\)/)).toBeVisible();
  });

  test('unknown path shows empty traces message', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'explain');

    await page.getByPlaceholder('e.g. $constraint:credit-check').fill('$constraint:does-not-exist');
    await page.getByRole('button', { name: 'Explain', exact: true }).click();

    await expect(page.getByText('No traces found')).toBeVisible();
  });
});

test.describe('Schema tab', () => {
  let modelId: string;

  test.beforeEach(async ({ request }) => {
    modelId = uid('e2e-schema');
    await createModel(request, SIMPLE_SPEC, modelId);
  });

  test.afterEach(async ({ request }) => {
    await deleteModel(request, modelId);
  });

  test('effective schema for base field shows type', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'schema');

    await page.getByPlaceholder('e.g. $.order.total').fill('$.price');
    await page.getByRole('button', { name: 'Get Schema' }).click();

    await expect(page.getByText('Effective Schema — $.price')).toBeVisible();
    await expect(page.getByText('number')).toBeVisible();
  });

  test('Enter key submits the schema query', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'schema');

    const input = page.getByPlaceholder('e.g. $.order.total');
    await input.fill('$.qty');
    await input.press('Enter');

    await expect(page.getByText('Effective Schema — $.qty')).toBeVisible();
  });

  test('derived field schema shows readOnly', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'schema');

    await page.getByPlaceholder('e.g. $.order.total').fill('$.total');
    await page.getByRole('button', { name: 'Get Schema' }).click();

    await expect(page.getByText('Effective Schema — $.total')).toBeVisible();
    await expect(page.locator('.json-key').filter({ hasText: 'readOnly' })).toBeVisible();
  });
});
