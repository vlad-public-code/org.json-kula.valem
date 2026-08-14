import { expect, type Page, type APIRequestContext } from '@playwright/test';

/**
 * The backend under test. Overridable so the suite can be pointed at a specific build without
 * stopping whatever is already on 8080; `playwright.config.ts` passes the same value through to
 * the Vite dev server's proxy so the browser and these direct API calls always agree.
 */
export const BACKEND = process.env.VALEM_BACKEND ?? 'http://localhost:8080';

export function uid(prefix: string) {
  return `${prefix}-${Date.now()}`;
}

export async function createModel(
  request: APIRequestContext,
  spec: Record<string, unknown>,
  overrideId: string,
): Promise<string> {
  const payload = { ...spec, id: overrideId };
  const r = await request.post(`${BACKEND}/models`, { data: payload });
  if (!r.ok()) throw new Error(`POST /models failed (${r.status()}): ${await r.text()}`);
  return overrideId;
}

export async function deleteModel(request: APIRequestContext, id: string): Promise<void> {
  await request.delete(`${BACKEND}/models/${id}`);
}

export async function openModel(page: Page, id: string): Promise<void> {
  await page.goto('/');
  // Refresh the model list so the just-created model appears
  await page.getByRole('button', { name: '↻ Refresh' }).click();
  await page.getByRole('button', { name: id }).click();
}

export async function switchTab(page: Page, tab: string): Promise<void> {
  await page.getByRole('button', { name: tab, exact: true }).click();
}

/** Fill an input and immediately blur it to trigger the deferred mutate flush. */
export async function fillAndBlur(
  page: Page,
  label: string,
  value: string,
  options?: { exact?: boolean },
): Promise<void> {
  const input = page.getByLabel(label, options);
  await input.fill(value);
  await input.blur();
}

/**
 * Asserts that a stat tile displays {@link value}, whatever the runtime locale does to it.
 *
 * Tiles carrying a `format` render through `Intl.NumberFormat`, so the digits arrive grouped and
 * decorated: 30000 shows as `$30,000.00` here, `30 000,00 $` elsewhere. Matching the literal string
 * would pin the test to one machine's locale, and matching bare digits fails the moment a grouping
 * separator lands mid-number — which is what happened when the examples became stat tiles.
 *
 * So compare digit sequences: strip everything that is not a digit or a decimal mark, normalise the
 * mark, and require the expected digits to appear. Pass the value as the tile shows it — rounded to
 * the format's precision — and pin the unrounded model value with {@link expectStateNumber}.
 */
export async function expectTile(page: Page, testId: string, value: string): Promise<void> {
  const expected = digitsOf(value);
  await expect
    .poll(async () => digitsOf(await page.getByTestId(testId).innerText()),
          { message: `tile "${testId}" should show ${value}` })
    .toContain(expected);
}

/** Digits and decimal marks only, with the mark normalised to '.' — locale-independent. */
function digitsOf(text: string): string {
  return text
    .replace(/[^\d.,]/g, '')            // drop currency symbols, labels, spaces, unit suffixes
    .replace(/,(?=\d{3}\b)/g, '')       // 30,000 -> 30000 (grouping, not a decimal mark)
    .replace(/\.(?=\d{3}\b)/g, '')      // 30.000 -> 30000 for locales that group with dots
    .replace(',', '.');                 // 0,47 -> 0.47
}

/**
 * Asserts the value the model actually holds at {@code pointer}, unrounded.
 *
 * A stat tile shows two decimals, so a derivation that produces 65.625 reads as `$65.63` on screen.
 * Asserting only the tile would leave the arithmetic unverified — the whole point of these golden
 * cases — so precision is checked here, against the state the backend returns.
 */
export async function expectStateNumber(
  request: APIRequestContext,
  modelId: string,
  pointer: string,
  expected: number,
): Promise<void> {
  const state = await (await request.get(`${BACKEND}/models/${modelId}/state`)).json();
  const actual = pointer.split('/').filter(Boolean)
    .reduce<unknown>((node, key) => (node as Record<string, unknown>)?.[key], state);
  expect(actual, `${pointer} in model ${modelId}`).toBeCloseTo(expected, 6);
}
