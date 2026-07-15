import { test, expect } from '@playwright/test';
import { createModel, deleteModel, fillAndBlur, openModel, switchTab, uid, BACKEND } from './helpers';
import * as fs from 'fs';
import * as path from 'path';

const specPath = path.join(__dirname, '../../valem-ui/src/examples/personal-budget-tracker.json');
const baseSpec = JSON.parse(fs.readFileSync(specPath, 'utf8'));

test.describe('Personal Budget Tracker — multi-view navigation', () => {
  let modelId: string;

  test.beforeEach(async ({ request }) => {
    modelId = uid('e2e-budget');
    await createModel(request, baseSpec, modelId);

    // Apply initialState (backend ignores this field; apply it as a mutation)
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

  // ── View rendering ────────────────────────────────────────────────────────

  test('default view is Income: shows income list and Add Income button', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await expect(page.getByRole('button', { name: '+ Add Income' })).toBeVisible();
    await expect(page.getByRole('button', { name: '+ Add Expense' })).not.toBeVisible();
    await expect(page.getByTestId('totalIncomeLabel')).not.toBeVisible();
  });

  // ── Menu navigation ───────────────────────────────────────────────────────

  test('menu: clicking Expenses switches to the Expenses view', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await page.getByRole('button', { name: 'Expenses' }).first().click();

    await expect(page.getByRole('button', { name: '+ Add Expense' })).toBeVisible();
    await expect(page.getByRole('button', { name: '+ Add Income' })).not.toBeVisible();
  });

  test('menu: clicking Summary switches to the Summary view', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await page.getByRole('button', { name: 'Summary' }).first().click();

    await expect(page.getByTestId('totalIncomeLabel')).toBeVisible();
    await expect(page.getByTestId('totalExpensesLabel')).toBeVisible();
    await expect(page.getByTestId('balanceLabel')).toBeVisible();
    await expect(page.getByRole('button', { name: '+ Add Income' })).not.toBeVisible();
  });

  test('menu: full cycle Income → Expenses → Summary → Income', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    // Income → Expenses
    await page.getByRole('button', { name: 'Expenses' }).first().click();
    await expect(page.getByRole('button', { name: '+ Add Expense' })).toBeVisible();

    // Expenses → Summary
    await page.getByRole('button', { name: 'Summary' }).first().click();
    await expect(page.getByTestId('totalIncomeLabel')).toBeVisible();

    // Summary → Income
    await page.getByRole('button', { name: 'Income' }).first().click();
    await expect(page.getByRole('button', { name: '+ Add Income' })).toBeVisible();
  });

  // ── Button navigation ─────────────────────────────────────────────────────

  test('"Expenses →" button navigates from Income to Expenses', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await page.getByRole('button', { name: 'Expenses →' }).click();

    await expect(page.getByRole('button', { name: '+ Add Expense' })).toBeVisible();
    await expect(page.getByRole('button', { name: '+ Add Income' })).not.toBeVisible();
  });

  test('"← Income" button returns from Expenses to Income', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await page.getByRole('button', { name: 'Expenses →' }).click();
    await expect(page.getByRole('button', { name: '+ Add Expense' })).toBeVisible();

    await page.getByRole('button', { name: '← Income' }).click();
    await expect(page.getByRole('button', { name: '+ Add Income' })).toBeVisible();
  });

  test('"Summary →" button navigates from Expenses to Summary', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await page.getByRole('button', { name: 'Expenses →' }).click();
    await page.getByRole('button', { name: 'Summary →' }).click();

    await expect(page.getByTestId('totalIncomeLabel')).toBeVisible();
  });

  test('"← Expenses" button returns from Summary to Expenses', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await page.getByRole('button', { name: 'Summary' }).first().click();
    await page.getByRole('button', { name: '← Expenses' }).click();

    await expect(page.getByRole('button', { name: '+ Add Expense' })).toBeVisible();
  });

  // ── Cross-view state: totals update in Summary ────────────────────────────

  test('income entered in Income view appears as totalIncome in Summary', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await page.getByRole('button', { name: '+ Add Income' }).click();
    await fillAndBlur(page, 'Description', 'Salary');
    await fillAndBlur(page, 'Amount', '3000');

    await page.getByRole('button', { name: 'Summary' }).first().click();

    await expect(page.getByTestId('totalIncomeLabel')).toContainText('3000', { timeout: 10000 });
    await expect(page.getByTestId('totalExpensesLabel')).toContainText('0');
  });

  test('balance = totalIncome − totalExpenses across views', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    // Add income
    await page.getByRole('button', { name: '+ Add Income' }).click();
    await fillAndBlur(page, 'Amount', '5000');

    // Navigate to Expenses view and add an expense
    await page.getByRole('button', { name: 'Expenses' }).first().click();
    await page.getByRole('button', { name: '+ Add Expense' }).click();
    await fillAndBlur(page, 'Amount', '1500');

    // Navigate to Summary
    await page.getByRole('button', { name: 'Summary' }).first().click();

    await expect(page.getByTestId('totalIncomeLabel')).toContainText('5000', { timeout: 10000 });
    await expect(page.getByTestId('totalExpensesLabel')).toContainText('1500', { timeout: 10000 });
    await expect(page.getByTestId('balanceLabel')).toContainText('3500', { timeout: 10000 });
  });

  // ── Status badge ──────────────────────────────────────────────────────────

  test('status badge shows Balanced when income equals expenses (zero balance)', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await page.getByRole('button', { name: 'Summary' }).first().click();

    const badge = page.getByTestId('statusBadge');
    await expect(badge).toContainText('Balanced', { timeout: 10000 });
  });

  test('status badge shows Surplus when income exceeds expenses', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await page.getByRole('button', { name: '+ Add Income' }).click();
    await fillAndBlur(page, 'Amount', '2000');

    await page.getByRole('button', { name: 'Summary' }).first().click();

    await expect(page.getByTestId('statusBadge')).toContainText('Surplus', { timeout: 10000 });
  });

  test('status badge shows Deficit when expenses exceed income', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    // Small income
    await page.getByRole('button', { name: '+ Add Income' }).click();
    await fillAndBlur(page, 'Amount', '100');

    // Large expense
    await page.getByRole('button', { name: 'Expenses' }).first().click();
    await page.getByRole('button', { name: '+ Add Expense' }).click();
    await fillAndBlur(page, 'Amount', '500');

    await page.getByRole('button', { name: 'Summary' }).first().click();

    await expect(page.getByTestId('statusBadge')).toContainText('Deficit', { timeout: 10000 });
  });
});
