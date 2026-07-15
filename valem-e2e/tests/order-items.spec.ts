import { test, expect } from '@playwright/test';
import { createModel, deleteModel, fillAndBlur, openModel, switchTab, uid } from './helpers';
import * as fs from 'fs';
import * as path from 'path';

const specPath = path.join(__dirname, '../../valem-ui/src/examples/order-items-price-total.json');
const baseSpec = JSON.parse(fs.readFileSync(specPath, 'utf8'));

test.describe('Order Items view', () => {
  let modelId: string;

  test.beforeEach(async ({ request }) => {
    modelId = uid('e2e-order');
    await createModel(request, baseSpec, modelId);
  });

  test.afterEach(async ({ request }) => {
    await deleteModel(request, modelId);
  });

  test('view tab renders with empty items list', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');
    await expect(page.getByText('No items yet.')).toBeVisible();
    await expect(page.getByRole('button', { name: '+ Add Item' })).toBeVisible();
  });

  test('adding an item does NOT trigger constraint violation', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await page.getByRole('button', { name: '+ Add Item' }).click();

    // The constraint "Total must be non-negative" must NOT appear
    await expect(page.getByText('Total must be non-negative')).not.toBeVisible();

    // Adding auto-opens the inline editor (Collapse button visible, not Edit)
    await expect(page.getByRole('button', { name: 'Collapse' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Remove' })).toBeVisible();
  });

  test('grand total stays 0 with an empty item', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await page.getByRole('button', { name: '+ Add Item' }).click();
    await expect(page.getByText('Total must be non-negative')).not.toBeVisible();

    // Grand Total label should show 0 (empty item contributes nothing)
    const totalEl = page.getByTestId('totalLabel');
    await expect(totalEl).toContainText('0');
  });

  test('filling in item fields updates line total and grand total', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    // Add Item auto-opens the inline editor
    await page.getByRole('button', { name: '+ Add Item' }).click();

    // Fill Product Name
    await fillAndBlur(page, 'Product Name', 'Apple');

    // Fill Unit Price: 1.5
    await fillAndBlur(page, 'Unit Price', '1.5');

    // Fill Quantity: 4
    await fillAndBlur(page, 'Quantity', '4');

    // Grand Total = 1.5 * 4 = 6
    const totalEl = page.getByTestId('totalLabel');
    await expect(totalEl).toContainText('6');

    // Summary table should show lineTotal 6
    await expect(page.getByRole('cell', { name: '6' })).toBeVisible();
  });

  test('two items: grand total equals sum of line totals', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    // Add first item: price=1.5, qty=4 → lineTotal=6 (editor auto-opens)
    await page.getByRole('button', { name: '+ Add Item' }).click();
    await fillAndBlur(page, 'Product Name', 'Apple');
    await fillAndBlur(page, 'Unit Price', '1.5');
    await fillAndBlur(page, 'Quantity', '4');

    // Collapse first, add second item: price=2.75, qty=2 → lineTotal=5.5
    await page.getByRole('button', { name: 'Collapse' }).click();
    await page.getByRole('button', { name: '+ Add Item' }).click();
    await fillAndBlur(page, 'Product Name', 'Bread');
    await fillAndBlur(page, 'Unit Price', '2.75');
    await fillAndBlur(page, 'Quantity', '2');

    // Grand Total = 6 + 5.5 = 11.5
    const totalEl = page.getByTestId('totalLabel');
    await expect(totalEl).toContainText('11.5');
  });

  test('removing an item updates the grand total', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    // Add item: price=10, qty=3 → total=30 (editor auto-opens)
    await page.getByRole('button', { name: '+ Add Item' }).click();
    await fillAndBlur(page, 'Product Name', 'Widget');
    await fillAndBlur(page, 'Unit Price', '10');
    await fillAndBlur(page, 'Quantity', '3');
    await expect(page.getByTestId('totalLabel')).toContainText('30');

    // Remove the item
    await page.getByRole('button', { name: 'Collapse' }).click();
    await page.getByRole('button', { name: 'Remove' }).click();

    // List is empty again, total is 0
    await expect(page.getByText('No items yet.')).toBeVisible();
    await expect(page.getByTestId('totalLabel')).toContainText('0');
  });
});
