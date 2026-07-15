import { test, expect } from '@playwright/test';
import { createModel, deleteModel, openModel, switchTab, uid } from './helpers';
import { SIMPLE_SPEC } from './fixtures';

test.describe('Mutate tab', () => {
  let modelId: string;

  test.beforeEach(async ({ request }) => {
    modelId = uid('e2e-mutate');
    await createModel(request, SIMPLE_SPEC, modelId);
  });

  test.afterEach(async ({ request }) => {
    await deleteModel(request, modelId);
  });

  test('valid path mutation commits and reports mutated + derived paths', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'mutate');

    const textarea = page.locator('.card').filter({ hasText: 'Mutation Input' }).locator('textarea');
    await textarea.fill(JSON.stringify({ '$.price': 4, '$.qty': 5 }));
    await page.getByRole('button', { name: '▶ Apply' }).click();

    await expect(page.getByText('✓ Committed')).toBeVisible();
    const resultCard = page.locator('.card').filter({ has: page.locator('.card-title').filter({ hasText: 'Result' }) });
    await expect(resultCard.locator('.badge').filter({ hasText: '$.price' })).toBeVisible();
    await expect(resultCard.locator('.badge').filter({ hasText: '$.qty' })).toBeVisible();
    await expect(resultCard.locator('.badge').filter({ hasText: '$.total' })).toBeVisible();
  });

  test('mutation that triggers constraint rolls back', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'mutate');

    const textarea = page.locator('.card').filter({ hasText: 'Mutation Input' }).locator('textarea');
    await textarea.fill(JSON.stringify({ '$.qty': -1 }));
    await page.getByRole('button', { name: '▶ Apply' }).click();

    await expect(page.getByText('✗ Rolled back')).toBeVisible();
  });

  test('derived field ($.total) is read-only — setting it is rejected', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'mutate');

    const textarea = page.locator('.card').filter({ hasText: 'Mutation Input' }).locator('textarea');
    await textarea.fill(JSON.stringify({ '$.total': 99 }));
    await page.getByRole('button', { name: '▶ Apply' }).click();

    await expect(page.getByText('HTTP 4')).toBeVisible();
  });

  test('switching to JSON Patch mode loads patch template', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'mutate');

    await page.getByRole('button', { name: 'JSON Patch' }).click();

    const textarea = page.locator('.card').filter({ hasText: 'Mutation Input' }).locator('textarea');
    await expect(textarea).toContainText('"op"');
  });

  test('valid JSON Patch mutation commits', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'mutate');

    await page.getByRole('button', { name: 'JSON Patch' }).click();

    const textarea = page.locator('.card').filter({ hasText: 'Mutation Input' }).locator('textarea');
    await textarea.fill(JSON.stringify([
      { op: 'add', path: '/price', value: 7 },
      { op: 'add', path: '/qty', value: 2 },
    ]));
    await page.getByRole('button', { name: '▶ Apply' }).click();

    await expect(page.getByText('✓ Committed')).toBeVisible();
  });

  test('Reset button restores the template', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'mutate');

    const textarea = page.locator('.card').filter({ hasText: 'Mutation Input' }).locator('textarea');
    await textarea.fill('{}');
    await page.getByRole('button', { name: 'Reset' }).click();

    await expect(textarea).toContainText('$.field.path');
  });

  test('mutation result shows traces', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'mutate');

    const textarea = page.locator('.card').filter({ hasText: 'Mutation Input' }).locator('textarea');
    await textarea.fill(JSON.stringify({ '$.price': 3, '$.qty': 4 }));
    await page.getByRole('button', { name: '▶ Apply' }).click();

    await expect(page.getByText('Traces', { exact: true })).toBeVisible();
    await expect(page.locator('.trace-card').first()).toBeVisible();
  });
});
