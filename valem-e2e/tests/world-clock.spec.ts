import { test, expect } from '@playwright/test';
import { createModel, deleteModel, openModel, switchTab, uid, BACKEND } from './helpers';
import * as fs from 'fs';
import * as path from 'path';

const specPath = path.join(__dirname, '../../valem-ui/src/examples/world-clock.json');
const baseSpec = JSON.parse(fs.readFileSync(specPath, 'utf8'));

// The timer effect re-arms every 10s (real wall-clock delay, not simulated), so these tests need
// more headroom than the 30s default, especially the pause/resume test which waits out two ticks.
test.describe.configure({ timeout: 90_000 });

test.describe('World Clock view', () => {
  let modelId: string;

  test.beforeEach(async ({ request }) => {
    modelId = uid('e2e-clock');
    await createModel(request, baseSpec, modelId);
  });

  test.afterEach(async ({ request }) => {
    await deleteModel(request, modelId);
  });

  test('view tab renders the country selector', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await expect(page.getByLabel('Country')).toBeVisible();
  });

  test('picking a country fetches the live time from timeapi.io', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await page.getByLabel('Country').selectOption('Europe/London');
    await expect(page.getByTestId('zoneLabel')).toContainText('Europe/London');

    // Real external HTTP fetch — generous timeout, not asserting an exact time value. The label's
    // own caption text is always "Date & time", so assert on the ISO date pattern the fetched value
    // adds after it, not on the absence of the caption.
    await expect(page.getByTestId('dateTimeLabel')).toContainText(/\d{4}-\d{2}-\d{2}T/, { timeout: 15000 });
  });

  test('the refresh tick advances on its own while the page stays open (WS live update)', async ({ page }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');

    await page.getByLabel('Country').selectOption('America/New_York');
    await expect(page.getByTestId('tickLabel')).toContainText('0');

    // The timer fires ~10s after the model was created; never reload the page — a passing assertion
    // here can only be explained by the WebSocket push driving the re-render.
    await expect(page.getByTestId('tickLabel')).toContainText('1', { timeout: 20000 });
  });

  test('the timer pauses while nobody is watching and resumes when a client reconnects', async ({ page, request, context }) => {
    await openModel(page, modelId);
    await switchTab(page, 'view');
    await page.getByLabel('Country').selectOption('Asia/Tokyo');
    // Wait for the mutation to actually land (and the timer to be scheduled+tracked) before closing —
    // selectOption() only waits for the DOM change event, not the async mutation round-trip.
    await expect(page.getByTestId('zoneLabel')).toContainText('Asia/Tokyo');

    // Closing the page drops the WebSocket subscription -> onLastUnsubscribe -> the executor cancels
    // the not-yet-fired scheduled tick instead of letting it fire into the void.
    await page.close();

    // Give it well past the 10s tick interval; if the pause didn't work, tick would now be >= 1.
    await new Promise(r => setTimeout(r, 13000));
    const stateWhileUnwatched = await (await request.get(`${BACKEND}/models/${modelId}/state`)).json();
    expect(stateWhileUnwatched.clock.tick).toBe(0);

    // Reopening the model (a fresh page/subscription) is the first subscriber again -> reconcileEffects()
    // re-arms the timer, since the trigger (a timezone is selected) still holds.
    const page2 = await context.newPage();
    await openModel(page2, modelId);
    await switchTab(page2, 'view');

    await expect(page2.getByTestId('tickLabel')).toContainText('1', { timeout: 20000 });
  });
});
