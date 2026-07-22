// Captures the screenshots used in docs/getting-started/sandbox.md from the public sandbox,
// so they can be refreshed rather than re-shot by hand when the UI changes.
//
//   cd valem-e2e && node ../docs/assets/capture-screenshots.mjs
//   cd valem-e2e && node ../docs/assets/capture-screenshots.mjs http://localhost:5173
//
// Uses a ready-made example rather than LLM generation: no API budget, and the result is the
// same model every time. Writes into docs/assets/img/.
import { createRequire } from 'node:module';
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const { chromium } = createRequire(join(process.cwd(), 'package.json'))('playwright');

const BASE = process.argv[2] || 'https://valem.onrender.com';
const OUT = join(dirname(fileURLToPath(import.meta.url)), 'img');
mkdirSync(OUT, { recursive: true });

const shot = (page, name) =>
  page.screenshot({ path: join(OUT, name), animations: 'disabled' });

const browser = await chromium.launch();
const page = await browser.newPage({
  viewport: { width: 1280, height: 860 },
  deviceScaleFactor: 1.5,
});

// A free-tier host may be cold; give the first paint room.
await page.goto(BASE, { waitUntil: 'domcontentloaded', timeout: 120_000 });
await page.getByRole('button', { name: /ready-made example/i }).waitFor({ timeout: 120_000 });
await page.waitForTimeout(500);
await shot(page, 'sandbox-describe.png');

await page.getByRole('button', { name: /ready-made example/i }).click();
await page.locator('.card', { hasText: 'Car Loan Calculator' }).waitFor({ timeout: 60_000 });
await page.locator('.card', { hasText: 'Car Loan Calculator' })
  .getByRole('button', { name: /^Load/ }).click();
// Creating the model on a cold free-tier host can take a while.
await page.getByRole('button', { name: /Advanced/i }).waitFor({ timeout: 180_000 });

// The first-run coach mark covers a field; dismiss it before shooting.
const nudge = page.getByTestId('nudge-dismiss');
if (await nudge.count()) await nudge.click();
await page.waitForTimeout(2000);
await shot(page, 'sandbox-view.png');

// Advanced mode swaps the single view for the raw panels; `state` is the one that shows
// base and derived fields side by side.
await page.getByRole('button', { name: /Advanced/i }).click();
await page.getByRole('button', { name: /^state$/i }).first().click();
await page.waitForTimeout(1500);
await shot(page, 'sandbox-state.png');

await browser.close();
console.log('wrote screenshots to', OUT);
