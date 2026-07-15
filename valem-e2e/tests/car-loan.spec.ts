import { test, expect } from '@playwright/test';
import { createModel, deleteModel, fillAndBlur, openModel, switchTab, uid } from './helpers';
import * as fs from 'fs';
import * as path from 'path';

const specPath = path.join(__dirname, '../../valem-ui/src/examples/car-loan-calculator.json');
const baseSpec = JSON.parse(fs.readFileSync(specPath, 'utf8'));

test.describe('Car Loan Calculator view', () => {
  let modelId: string;

  test.beforeEach(async ({ request }) => {
    modelId = uid('e2e-loan');
    await createModel(request, baseSpec, modelId);
  });

  test.afterEach(async ({ request }) => {
    await deleteModel(request, modelId);
  });

  test('view tab renders input fields', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await expect(page.getByLabel('Loan Amount ($)')).toBeVisible();
    await expect(page.getByLabel('Annual Interest Rate (%)')).toBeVisible();
    await expect(page.getByLabel('Term (months)')).toBeVisible();
  });

  test('entering loan parameters derives monthly payment', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    // 20 000 @ 6% / 60 months → monthly payment = $386.66
    await fillAndBlur(page, 'Loan Amount ($)', '20000');
    await fillAndBlur(page, 'Annual Interest Rate (%)', '6');
    await fillAndBlur(page, 'Term (months)', '60');

    // Monthly Payment label should show 386.66
    const paymentEl = page.getByTestId('paymentLabel');
    await expect(paymentEl).toContainText('386.66');
  });

  test('total payment and total interest are derived', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await fillAndBlur(page, 'Loan Amount ($)', '20000');
    await fillAndBlur(page, 'Annual Interest Rate (%)', '6');
    await fillAndBlur(page, 'Term (months)', '60');

    // Total payment = 386.66 * 60 = 23 199.60
    await expect(page.getByTestId('totalLabel')).toContainText('23199.6');

    // Total interest = 23 199.60 - 20 000 = 3 199.60
    await expect(page.getByTestId('interestLabel')).toContainText('3199.6');
  });

  test('amortization schedule table has one row per month', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await fillAndBlur(page, 'Loan Amount ($)', '20000');
    await fillAndBlur(page, 'Annual Interest Rate (%)', '6');
    await fillAndBlur(page, 'Term (months)', '60');

    // The schedule dataTable should have 60 data rows (month 1–60)
    // DataTable renders in a <table>; rows excluding the header = 60 (or 12 if paged)
    const tableRows = page.locator('table tbody tr');
    // Default pageSize is 12 in the spec
    await expect(tableRows).toHaveCount(12);

    // First row should show month = 1
    await expect(tableRows.first().locator('td').first()).toHaveText('1');
  });

  test('changing loan amount updates payment immediately after blur', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await fillAndBlur(page, 'Loan Amount ($)', '10000');
    await fillAndBlur(page, 'Annual Interest Rate (%)', '6');
    await fillAndBlur(page, 'Term (months)', '60');

    const paymentEl = page.getByTestId('paymentLabel');
    await expect(paymentEl).toContainText('193.33');

    // Double the loan amount → payment doubles
    await fillAndBlur(page, 'Loan Amount ($)', '20000');
    await expect(paymentEl).toContainText('386.66');
  });
});
