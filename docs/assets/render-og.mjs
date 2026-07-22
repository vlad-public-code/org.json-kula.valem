// Renders assets/og.svg -> assets/og.png (1200x630), the social-preview card referenced by
// _includes/head_custom.html. og.svg is the source of truth; re-run this after editing it.
//
//   cd valem-e2e && node ../docs/assets/render-og.mjs      # uses the e2e module's Playwright
//
// Only the PNG is consumed by the site; the SVG and this script are kept so the card can be
// regenerated instead of hand-edited in a binary.
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

// Resolve Playwright from the working directory (valem-e2e) rather than from docs/,
// which has no package.json of its own.
const { chromium } = createRequire(join(process.cwd(), 'package.json'))('playwright');

const here = dirname(fileURLToPath(import.meta.url));
const svg = readFileSync(join(here, 'og.svg'), 'utf8');

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1200, height: 630 } });
await page.setContent(
  `<!doctype html><html><body style="margin:0">${svg}</body></html>`,
  { waitUntil: 'load' },
);
await page.screenshot({ path: join(here, 'og.png') });
await browser.close();
console.log('wrote', join(here, 'og.png'));
