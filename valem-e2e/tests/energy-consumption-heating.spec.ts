import { test, expect } from '@playwright/test';
import { createModel, deleteModel, fillAndBlur, openModel, switchTab, uid } from './helpers';
import * as fs from 'fs';
import * as path from 'path';

const specPath = path.join(__dirname, '../../valem-ui/src/examples/energy-consumption-heating.json');
const baseSpec = JSON.parse(fs.readFileSync(specPath, 'utf8'));

test.describe('House Heating Energy view', () => {
  let modelId: string;

  test.beforeEach(async ({ request }) => {
    modelId = uid('e2e-heating');
    await createModel(request, baseSpec, modelId);
  });

  test.afterEach(async ({ request }) => {
    await deleteModel(request, modelId);
  });

  test('view tab renders input fields', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await expect(page.getByLabel('Floor Area (m²)')).toBeVisible();
    await expect(page.getByLabel('Indoor Temperature (°C)')).toBeVisible();
    await expect(page.getByLabel('Outdoor Temperature (°C)')).toBeVisible();
    await expect(page.getByLabel('Insulation U-value W/(m²·K)')).toBeVisible();
  });

  test('default values (from defaultValues seed) derive the documented golden result', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    // 120m², 21C indoor, -5C outdoor, U=0.3, 100% eff, 16h/day, 30 days, 0.28 EUR/kWh
    await expect(page.getByTestId('deltaLabel')).toContainText('26');
    await expect(page.getByTestId('demandLabel')).toContainText('0.936');
    await expect(page.getByTestId('kwhLabel')).toContainText('449.3');
    await expect(page.getByTestId('costLabel')).toContainText('125.8');
  });

  test('improving insulation (lower U-value) reduces monthly cost', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await expect(page.getByTestId('costLabel')).toContainText('125.8');

    // Halve the U-value -> heat demand and everything downstream halves too
    await fillAndBlur(page, 'Insulation U-value W/(m²·K)', '0.15');
    await expect(page.getByTestId('demandLabel')).toContainText('0.468');
    await expect(page.getByTestId('kwhLabel')).toContainText('224.6');
    await expect(page.getByTestId('costLabel')).toContainText('62.89');
  });

  test('raising heating efficiency (heat pump) reduces monthly consumption', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    // 300% efficiency (COP 3 heat pump) -> a third of the direct-electric consumption
    await fillAndBlur(page, 'Heating Efficiency (%)', '300');
    await expect(page.getByTestId('kwhLabel')).toContainText('149.8');
    await expect(page.getByTestId('costLabel')).toContainText('41.9');
  });

  test('outdoor temperature change updates the temperature delta immediately', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await expect(page.getByTestId('deltaLabel')).toContainText('26');

    await fillAndBlur(page, 'Outdoor Temperature (°C)', '0');
    await expect(page.getByTestId('deltaLabel')).toContainText('21');
  });
});
