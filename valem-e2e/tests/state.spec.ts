import { test, expect } from '@playwright/test';
import { createModel, deleteModel, openModel, uid, BACKEND } from './helpers';
import { SIMPLE_SPEC } from './fixtures';

test.describe('State tab', () => {
  let modelId: string;

  test.beforeEach(async ({ request }) => {
    modelId = uid('e2e-state');
    await createModel(request, SIMPLE_SPEC, modelId);
  });

  test.afterEach(async ({ request }) => {
    await deleteModel(request, modelId);
  });

  test('shows model info grid with correct counts', async ({ page }) => {
    await openModel(page, modelId);

    await expect(page.getByText('Version', { exact: true })).toBeVisible();
    await expect(page.getByText('1.0.0', { exact: true })).toBeVisible();
    await expect(page.getByText('Derivations', { exact: true })).toBeVisible();
    // 1 derivation, 1 constraint
    const derivationsCell = page.locator('.info-cell').filter({ hasText: /^Derivations/ });
    await expect(derivationsCell.locator('.info-cell-value')).toHaveText('1');
    const constraintsCell = page.locator('.info-cell').filter({ hasText: /^Constraints/ });
    await expect(constraintsCell.locator('.info-cell-value')).toHaveText('1');
  });

  test('shows Merged State card', async ({ page }) => {
    await openModel(page, modelId);

    await expect(page.getByText('Merged State')).toBeVisible();
  });

  test('state reflects mutation applied via API', async ({ page, request }) => {
    await request.post(`${BACKEND}/models/${modelId}/mutations`, {
      data: { '$.price': 5, '$.qty': 3 },
    });

    await openModel(page, modelId);

    // total = 5 * 3 = 15, should appear in merged state. Scoped to the JSON viewer — a plain
    // page-wide getByText('15') can also match digits inside another model's timestamped id
    // in the sidebar list.
    await expect(page.locator('.json-viewer').getByText('15', { exact: true })).toBeVisible();
  });

  test('Take Snapshot shows snapshot/restore area', async ({ page }) => {
    await openModel(page, modelId);

    await page.getByRole('button', { name: 'Take Snapshot' }).click();

    await expect(page.getByText('Snapshot taken.')).toBeVisible();
    await expect(page.locator('.card').filter({ hasText: 'Snapshot / Restore' }).locator('textarea')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Restore' })).toBeVisible();
  });

  test('Restore from snapshot shows Restored message', async ({ page, request }) => {
    // Set initial state
    await request.post(`${BACKEND}/models/${modelId}/mutations`, {
      data: { '$.price': 10, '$.qty': 2 },
    });

    await openModel(page, modelId);

    // Take snapshot at price=10, qty=2, total=20
    await page.getByRole('button', { name: 'Take Snapshot' }).click();
    await expect(page.getByText('Snapshot taken.')).toBeVisible();

    // Apply a new mutation
    await request.post(`${BACKEND}/models/${modelId}/mutations`, {
      data: { '$.price': 99 },
    });

    // Restore
    await page.getByRole('button', { name: 'Restore' }).click();
    await expect(page.getByText('Restored.')).toBeVisible();
  });

  test('Delete Model button triggers confirmation and removes model', async ({ page }) => {
    await openModel(page, modelId);

    page.on('dialog', d => d.accept());
    await page.getByRole('button', { name: 'Delete Model' }).click();

    // After deletion the sidebar should no longer show this model
    await expect(page.getByRole('button', { name: modelId })).not.toBeVisible();
    modelId = ''; // prevent afterEach from trying to delete again
  });
});
