import { test, expect } from '@playwright/test';
import { createModel, deleteModel, openModel, switchTab, uid } from './helpers';
import { SIMPLE_SPEC } from './fixtures';

test.describe('Spec tab', () => {
  let modelId: string;

  test.beforeEach(async ({ request }) => {
    modelId = uid('e2e-spec');
    await createModel(request, SIMPLE_SPEC, modelId);
  });

  test.afterEach(async ({ request }) => {
    await deleteModel(request, modelId);
  });

  test('shows Model Spec card with the model id', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'spec');

    await expect(page.getByText('Model Spec', { exact: true })).toBeVisible();
    await expect(page.locator('.model-badge')).toContainText(modelId);
  });

  test('Download button is enabled after spec loads', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'spec');

    const btn = page.getByRole('button', { name: '↓ Download' });
    await expect(btn).toBeEnabled();
  });

  test('spec shows derivation path', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'spec');

    // The spec JSON renders the derivation path as the quoted string node `"$.total"`. Match that
    // exact form: a bare `$.total` also appears as help text in the (hidden, inactive) Explain
    // tab's `<code>` example, so an unquoted match tripped strict mode and `.first()` landed on the
    // hidden one. The quoted string only exists in the spec viewer, which is on screen here.
    await expect(page.getByText('"$.total"', { exact: true })).toBeVisible();
  });
});

test.describe('Create panel', () => {
  test('shows New Model header and example buttons', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: '+ New Model' }).click();

    await expect(page.getByText('New Model', { exact: true })).toBeVisible();
    await expect(page.getByText('Examples')).toBeVisible();
    // At least one example card rendered
    await expect(page.locator('.card').filter({ hasText: 'Examples' }).getByRole('button').first()).toBeVisible();
  });

  test('clicking an example loads its JSON into the textarea', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: '+ New Model' }).click();

    // Click the first example button
    await page.locator('.card').filter({ hasText: 'Examples' }).getByRole('button').first().click();

    const textarea = page.locator('.card').filter({ hasText: 'Model Spec JSON' }).locator('textarea');
    await expect(textarea).not.toBeEmpty();
  });

  test('Cancel button closes the panel', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: '+ New Model' }).click();
    await expect(page.getByText('New Model', { exact: true })).toBeVisible();

    await page.getByRole('button', { name: 'Cancel' }).click();

    await expect(page.getByText('Valem DevTools')).toBeVisible();
  });

  test('invalid JSON shows error banner', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: '+ New Model' }).click();

    const textarea = page.locator('.card').filter({ hasText: 'Model Spec JSON' }).locator('textarea');
    await textarea.fill('{ this is not json }');
    await page.getByRole('button', { name: 'Create Model' }).click();

    await expect(page.locator('.banner-error')).toBeVisible();
  });

  test('creating a valid spec registers the model and opens it', async ({ page, request }) => {
    const newId = uid('e2e-create');
    const spec = { ...SIMPLE_SPEC, id: newId };

    await page.goto('/');
    await page.getByRole('button', { name: '+ New Model' }).click();

    const textarea = page.locator('.card').filter({ hasText: 'Model Spec JSON' }).locator('textarea');
    await textarea.fill(JSON.stringify(spec, null, 2));
    await page.getByRole('button', { name: 'Create Model' }).click();

    // Model should now be selected (its id appears as model badge)
    await expect(page.locator('.model-badge')).toContainText(newId);

    // Cleanup
    await deleteModel(request, newId);
  });

  test('duplicate id shows conflict error', async ({ page, request }) => {
    const dupId = uid('e2e-dup');
    await createModel(request, SIMPLE_SPEC, dupId);

    await page.goto('/');
    await page.getByRole('button', { name: '+ New Model' }).click();

    const spec = { ...SIMPLE_SPEC, id: dupId };
    const textarea = page.locator('.card').filter({ hasText: 'Model Spec JSON' }).locator('textarea');
    await textarea.fill(JSON.stringify(spec, null, 2));
    await page.getByRole('button', { name: 'Create Model' }).click();

    await expect(page.locator('.banner-error')).toBeVisible();

    await deleteModel(request, dupId);
  });
});
