import { type Page, type APIRequestContext } from '@playwright/test';

export const BACKEND = 'http://localhost:8080';

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
