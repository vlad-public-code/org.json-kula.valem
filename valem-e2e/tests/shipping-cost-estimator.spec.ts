import { test, expect } from '@playwright/test';
import { createModel, deleteModel, fillAndBlur, openModel, switchTab, uid } from './helpers';
import * as fs from 'fs';
import * as path from 'path';

const specPath = path.join(__dirname, '../../valem-ui/src/examples/shipping-cost-estimator.json');
const baseSpec = JSON.parse(fs.readFileSync(specPath, 'utf8'));

test.describe('Shipping Cost Estimator view', () => {
  let modelId: string;

  test.beforeEach(async ({ request }) => {
    modelId = uid('e2e-shipping');
    await createModel(request, baseSpec, modelId);
  });

  test.afterEach(async ({ request }) => {
    await deleteModel(request, modelId);
  });

  test('view tab renders input fields', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await expect(page.getByLabel('Package Weight (kg)')).toBeVisible();
    await expect(page.getByLabel('Distance (km)')).toBeVisible();
    await expect(page.getByLabel('Carrier')).toBeVisible();
  });

  test('default values (5kg, 250km, standard) derive the documented golden result', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await expect(page.getByTestId('subtotalLabel')).toContainText('25');
    await expect(page.getByTestId('totalLabel')).toContainText('25');
  });

  test('switching to express applies a 1.5x carrier surcharge', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await page.getByLabel('Carrier').selectOption('express');
    // subtotal unchanged (25), total = 25 * 1.5 = 37.5
    await expect(page.getByTestId('subtotalLabel')).toContainText('25');
    await expect(page.getByTestId('totalLabel')).toContainText('37.5');
  });

  test('changing weight updates the cost breakdown table immediately', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await fillAndBlur(page, 'Package Weight (kg)', '10');
    // baseCost = 10 * 2.5 = 25, distanceCost = 250 * 0.05 = 12.5, subtotal = 37.5
    await expect(page.getByTestId('totalLabel')).toContainText('37.5');

    const rows = page.locator('table tbody tr');
    await expect(rows).toHaveCount(3);
    await expect(rows.nth(0)).toContainText('Base cost');
    await expect(rows.nth(0)).toContainText('25');
    await expect(rows.nth(1)).toContainText('Distance cost');
    await expect(rows.nth(1)).toContainText('12.5');
  });

  test('overweight badge is hidden by default and appears past the 1000kg threshold', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await expect(page.getByTestId('overweightBadge')).not.toBeVisible();

    await fillAndBlur(page, 'Package Weight (kg)', '1500');
    await expect(page.getByTestId('overweightBadge')).toBeVisible();
    await expect(page.getByTestId('overweightBadge')).toContainText('Overweight surcharge may apply');
  });
});
