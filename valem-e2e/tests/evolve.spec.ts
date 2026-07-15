import { test, expect } from '@playwright/test';
import { createModel, deleteModel, openModel, switchTab, uid } from './helpers';
import { SIMPLE_SPEC } from './fixtures';

test.describe('Evolve tab', () => {
  let modelId: string;

  test.beforeEach(async ({ request }) => {
    modelId = uid('e2e-evolve');
    await createModel(request, SIMPLE_SPEC, modelId);
  });

  test.afterEach(async ({ request }) => {
    await deleteModel(request, modelId);
  });

  test('version bump succeeds and shows new version', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'evolve');

    const textarea = page.locator('.card').filter({ hasText: 'Spec Evolution Diff' }).locator('textarea');
    await textarea.fill(JSON.stringify({ newVersion: '2.0.0' }));
    await page.getByRole('button', { name: '▶ Apply Evolution' }).click();

    await expect(page.getByText('✓ Spec evolved')).toBeVisible();
    await expect(page.getByText('2.0.0', { exact: true })).toBeVisible();
  });

  test('adding a new derivation succeeds', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'evolve');

    const evolution = {
      upsertDerivations: [{ path: '$.priceDoubled', expr: 'price * 2', evaluation: 'eager' }],
      newVersion: '1.1.0',
    };
    const textarea = page.locator('.card').filter({ hasText: 'Spec Evolution Diff' }).locator('textarea');
    await textarea.fill(JSON.stringify(evolution));
    await page.getByRole('button', { name: '▶ Apply Evolution' }).click();

    await expect(page.getByText('✓ Spec evolved')).toBeVisible();
    await expect(page.getByText('1.1.0', { exact: true })).toBeVisible();
  });

  test('invalid JSON shows error', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'evolve');

    const textarea = page.locator('.card').filter({ hasText: 'Spec Evolution Diff' }).locator('textarea');
    await textarea.fill('{ not valid json }');
    await page.getByRole('button', { name: '▶ Apply Evolution' }).click();

    await expect(page.locator('.banner-error').first()).toBeVisible();
  });

  test('evolution that would create a cycle is rejected', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'evolve');

    // total depends on price and qty; making price depend on total would create a cycle
    const evolution = {
      upsertDerivations: [{ path: '$.price', expr: 'total / qty', evaluation: 'eager' }],
    };
    const textarea = page.locator('.card').filter({ hasText: 'Spec Evolution Diff' }).locator('textarea');
    await textarea.fill(JSON.stringify(evolution));
    await page.getByRole('button', { name: '▶ Apply Evolution' }).click();

    await expect(page.locator('.banner-error').first()).toBeVisible();
  });

  test('"Use" button on Add derivation example fills the textarea', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'evolve');

    // Click the first "Use" button (Add derivation example)
    await page.locator('.card').filter({ hasText: 'Examples' })
      .getByRole('button', { name: 'Use' }).first().click();

    const textarea = page.locator('.card').filter({ hasText: 'Spec Evolution Diff' }).locator('textarea');
    await expect(textarea).toContainText('upsertDerivations');
  });

  test('Reset button restores default template', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'evolve');

    const textarea = page.locator('.card').filter({ hasText: 'Spec Evolution Diff' }).locator('textarea');
    await textarea.fill('{}');
    await page.getByRole('button', { name: 'Reset' }).click();

    await expect(textarea).toContainText('upsertDerivations');
  });
});
