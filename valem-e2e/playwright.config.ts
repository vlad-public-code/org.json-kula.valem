import { defineConfig, devices } from '@playwright/test';

/**
 * The UI under test, and the backend it talks to.
 *
 * Both are overridable so the suite can be pointed at a specific build — a second server on
 * another port — without stopping whatever already occupies 8080/5173. The backend value is
 * forwarded to the Vite dev server so its proxy and `helpers.ts`'s direct API calls always reach
 * the *same* server; when they diverge, the failures look like application bugs rather than a
 * misrouted test.
 */
const uiPort  = process.env.VALEM_UI_PORT ?? '5173';
const uiUrl   = process.env.VALEM_UI_URL  ?? `http://localhost:${uiPort}`;
const backend = process.env.VALEM_BACKEND;

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  // All spec files share one backend and one Vite dev server; running files across
  // multiple workers causes real resource contention (cold JSONata->Java compiles, LLM
  // serialization) that manifests as spurious timeouts, not app bugs.
  workers: 1,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: uiUrl,
    headless: true,
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: {
    command: 'npm run dev',
    cwd: '../valem-ui',
    url: uiUrl,
    reuseExistingServer: true,
    timeout: 30_000,
    env: {
      ...(backend ? { VALEM_BACKEND: backend } : {}),
      VALEM_UI_PORT: uiPort,
    },
  },
});
