/**
 * Generate panel + AI Interaction Log e2e tests.
 *
 * These tests require the backend to be started with an LLM configured, e.g. the mock:
 *
 *   mvn spring-boot:run -pl valem-api -Dspring-boot.run.jvmArguments="-Dvalem.llm.mock=true"
 *
 * or a real provider (e.g. -Dvalem.llm.provider=mistral -Dvalem.llm.api-key=...).
 * Tests are skipped automatically when the LLM is not available (backend returns 503).
 */
import { test, expect } from '@playwright/test';
import { deleteModel, uid, BACKEND } from './helpers';

// Cache the availability probe for the whole file: it issues a real generate call, so repeating it
// per test wastes the (rate-limited) LLM budget. With fullyParallel:false the worker is shared, so
// the probe runs at most once.
let llmAvailableCache: boolean | undefined;
async function isLlmAvailable(request: import('@playwright/test').APIRequestContext): Promise<boolean> {
  if (llmAvailableCache !== undefined) return llmAvailableCache;
  const r = await request.post(`${BACKEND}/models/generate`, {
    data: { modelId: 'probe', prompt: 'probe' },
  });
  llmAvailableCache = r.status() !== 503;
  return llmAvailableCache;
}

test.describe('Generate panel', () => {
  let modelId: string;

  test.beforeEach(async ({ request }) => {
    if (!await isLlmAvailable(request)) test.skip();
    modelId = uid('e2e-gen');
  });

  test.afterEach(async ({ request }) => {
    if (modelId) await deleteModel(request, modelId).catch(() => {});
  });

  test('Preview Prompt shows system context and model ID', async ({ page, request }) => {

    await page.goto('/');
    await page.getByRole('button', { name: '✦ Generate' }).click();

    await page.getByLabel('Model ID').fill(modelId);
    await page.getByLabel('Domain Description').fill('A simple counter model.');
    await page.getByRole('button', { name: 'Preview Prompt →' }).click();

    // The assembled prompt must contain the model ID and the domain description
    const textarea = page.locator('textarea');
    await expect(textarea).toContainText(modelId);
    await expect(textarea).toContainText('simple counter');
    await expect(textarea).toContainText('Valem');
  });

  test('Send to LLM generates a valid spec', async ({ page, request }) => {
    test.setTimeout(90_000); // real LLM generation can exceed the 30s default

    await page.goto('/');
    await page.getByRole('button', { name: '✦ Generate' }).click();

    await page.getByLabel('Model ID').fill(modelId);
    await page.getByLabel('Domain Description').fill('A counter model.');
    await page.getByRole('button', { name: 'Preview Prompt →' }).click();
    await page.getByRole('button', { name: 'Send to LLM →' }).click();

    // A real LLM call — allow generous time for spec generation to reach the "generated" phase.
    await expect(page.getByText('Spec generated')).toBeVisible({ timeout: 60_000 });
    await expect(page.getByRole('button', { name: 'Register Model' })).toBeVisible();
  });

  test('Register Model creates the model and navigates to it', async ({ page, request }) => {
    test.setTimeout(90_000); // real LLM generation can exceed the 30s default

    await page.goto('/');
    await page.getByRole('button', { name: '✦ Generate' }).click();

    await page.getByLabel('Model ID').fill(modelId);
    await page.getByLabel('Domain Description').fill('A counter model.');
    await page.getByRole('button', { name: 'Preview Prompt →' }).click();
    await page.getByRole('button', { name: 'Send to LLM →' }).click();
    await expect(page.getByRole('button', { name: 'Register Model' })).toBeVisible({ timeout: 60_000 });

    await page.getByRole('button', { name: 'Register Model' }).click();

    // Model badge appears in the tab bar
    await expect(page.locator('.model-badge')).toContainText(modelId, { timeout: 10_000 });
  });

  test('Back button returns to input phase', async ({ page, request }) => {

    await page.goto('/');
    await page.getByRole('button', { name: '✦ Generate' }).click();

    await page.getByLabel('Model ID').fill(modelId);
    await page.getByLabel('Domain Description').fill('A counter.');
    await page.getByRole('button', { name: 'Preview Prompt →' }).click();

    await page.getByRole('button', { name: '← Back' }).click();

    await expect(page.getByLabel('Model ID')).toBeVisible();
    await expect(page.getByLabel('Domain Description')).toBeVisible();
  });
});

test.describe('AI Interaction Log', () => {
  test('AI Log panel opens and shows header', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: '⌥ AI Log' }).click();

    await expect(page.getByText('AI Interaction Log')).toBeVisible();
    await expect(page.getByText('entries (newest first, last 50)')).toBeVisible();
    await expect(page.locator('.main').getByRole('button', { name: '↻ Refresh' })).toBeVisible();
  });

  test('AI Log shows empty state when no interactions recorded', async ({ page, request }) => {
    // This test passes regardless of LLM config — an empty log is valid
    await page.goto('/');
    await page.getByRole('button', { name: '⌥ AI Log' }).click();

    // Either "No LLM interactions recorded yet" (0 entries) or a list of entries is shown
    const emptyMsg = page.getByText('No LLM interactions recorded yet.');
    const firstEntry = page.locator('.card').filter({ hasText: /OK|ERR/ }).first();
    await expect(emptyMsg.or(firstEntry)).toBeVisible();
  });

  test('AI Log shows interaction after generate (LLM required)', async ({ page, request }) => {
    if (!await isLlmAvailable(request)) test.skip();
    test.setTimeout(90_000); // real LLM generation can exceed the 30s default

    const modelId = uid('e2e-ailog');

    // Trigger a generate call via the API directly (faster than driving through the UI)
    const previewRes = await request.post(`${BACKEND}/models/generate/preview`, {
      data: { modelId, domainDescription: 'A simple counter.' },
    });
    expect(previewRes.ok()).toBeTruthy();
    const { prompt } = await previewRes.json() as { prompt: string };

    const genRes = await request.post(`${BACKEND}/models/generate`, {
      data: { modelId, prompt },
    });
    expect(genRes.ok()).toBeTruthy();
    const genBody = await genRes.json() as { valid: boolean; spec?: { id: string } };
    expect(genBody.valid).toBe(true);

    // Register the generated model
    if (genBody.spec) {
      await request.post(`${BACKEND}/models`, { data: genBody.spec });
    }

    // Open AI Log in the UI
    await page.goto('/');
    await page.getByRole('button', { name: '⌥ AI Log' }).click();

    // At least one OK entry should be visible
    const okBadge = page.locator('.card').filter({ hasText: 'OK' }).first();
    await expect(okBadge).toBeVisible({ timeout: 10_000 });

    // Expand the entry and verify prompt + response are shown
    await okBadge.click();
    await expect(page.getByText('Prompt')).toBeVisible();
    await expect(page.getByText('Response')).toBeVisible();

    // Prompt should mention the model ID
    const promptPre = page.locator('pre').first();
    await expect(promptPre).toContainText(modelId);

    // Cleanup
    await deleteModel(request, modelId).catch(() => {});
  });

  test('Close button returns to the empty state', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: '⌥ AI Log' }).click();
    await expect(page.getByText('AI Interaction Log')).toBeVisible();

    await page.getByRole('button', { name: 'Close' }).click();

    await expect(page.getByText('Valem DevTools')).toBeVisible();
  });
});
