import { test, expect } from '@playwright/test';
import { createModel, deleteModel, fillAndBlur, openModel, switchTab, uid } from './helpers';
import * as fs from 'fs';
import * as path from 'path';

/*
 * The order example edits its line items inline in an `editableTable`: one row per element of
 * $.items, the product name as an editable row header, price and qty as input cells, and the
 * derived lineTotal as a read-only cell. Each cell input is named "<column>, row <n>".
 */

const specPath = path.join(__dirname, '../../valem-ui/src/examples/order-items-price-total.json');
const baseSpec = JSON.parse(fs.readFileSync(specPath, 'utf8'));

async function fillRow(page: import('@playwright/test').Page, row: number, name: string, price: string, qty: string) {
  await fillAndBlur(page, `Product, row ${row}`, name);
  await fillAndBlur(page, `Unit Price, row ${row}`, price);
  await fillAndBlur(page, `Qty, row ${row}`, qty);
}

test.describe('Order Items view (editableTable)', () => {
  let modelId: string;

  test.beforeEach(async ({ request }) => {
    modelId = uid('e2e-order');
    await createModel(request, baseSpec, modelId);
  });

  test.afterEach(async ({ request }) => {
    await deleteModel(request, modelId);
  });

  test('renders a table with column and row headers, empty to start', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    const table = page.getByRole('table', { name: 'Order Items' });
    await expect(table).toBeVisible();
    for (const header of ['Product', 'Unit Price', 'Qty', 'Line Total']) {
      await expect(table.getByRole('columnheader', { name: header })).toBeVisible();
    }
    await expect(page.getByText('No items yet. Add one to start the order.')).toBeVisible();
    await expect(page.getByRole('button', { name: '+ Add Item' })).toBeVisible();
  });

  test('adding a row gives editable cells and no constraint violation', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await page.getByRole('button', { name: '+ Add Item' }).click();

    await expect(page.getByLabel('Product, row 1')).toBeEditable();
    await expect(page.getByLabel('Unit Price, row 1')).toBeEditable();
    await expect(page.getByLabel('Qty, row 1')).toBeEditable();
    await expect(page.getByRole('rowheader')).toHaveCount(1);
    await expect(page.getByRole('button', { name: 'Remove row 1' })).toBeVisible();
    await expect(page.getByText('Total must be non-negative')).not.toBeVisible();
    await expect(page.getByTestId('totalLabel')).toContainText('0');
  });

  test('editing a row in place updates its line total and the grand total', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await page.getByRole('button', { name: '+ Add Item' }).click();
    await fillRow(page, 1, 'Apple', '1.5', '4');

    await expect(page.getByTestId('itemLineTotal-r0')).toContainText('6');
    await expect(page.getByTestId('totalLabel')).toContainText('6');
  });

  test('two rows: each keeps its own values, the total is their sum', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await page.getByRole('button', { name: '+ Add Item' }).click();
    await fillRow(page, 1, 'Apple', '1.5', '4');
    await page.getByRole('button', { name: '+ Add Item' }).click();
    await fillRow(page, 2, 'Bread', '2.75', '2');

    await expect(page.getByTestId('itemLineTotal-r0')).toContainText('6');
    await expect(page.getByTestId('itemLineTotal-r1')).toContainText('5.5');
    await expect(page.getByTestId('totalLabel')).toContainText('11.5');

    // Changing row 1's quantity in its cell moves only row 1's line total.
    await fillAndBlur(page, 'Qty, row 1', '10');
    await expect(page.getByTestId('itemLineTotal-r0')).toContainText('15');
    await expect(page.getByTestId('itemLineTotal-r1')).toContainText('5.5');
    await expect(page.getByTestId('totalLabel')).toContainText('20.5');
    await expect(page.getByLabel('Product, row 2')).toHaveValue('Bread');
  });

  test('removing a row updates the grand total', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await page.getByRole('button', { name: '+ Add Item' }).click();
    await fillRow(page, 1, 'Widget', '10', '3');
    await expect(page.getByTestId('totalLabel')).toContainText('30');

    await page.getByRole('button', { name: 'Remove row 1' }).click();

    await expect(page.getByText('No items yet. Add one to start the order.')).toBeVisible();
    await expect(page.getByTestId('totalLabel')).toContainText('0');
  });
});
