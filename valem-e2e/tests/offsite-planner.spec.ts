import { test, expect, type Page } from '@playwright/test';
import { createModel, deleteModel, openModel, switchTab, uid, BACKEND } from './helpers';
import * as fs from 'fs';
import * as path from 'path';

const specPath = path.join(__dirname, '../../valem-ui/src/examples/team-offsite-planner.json');
const baseSpec = JSON.parse(fs.readFileSync(specPath, 'utf8'));

/**
 * Drives the extended component catalog through the bundled Team Offsite Planner.
 *
 * The default plan (8 people, 2 nights) totals 5 016 against an approved budget of 12 000, so it
 * starts inside budget; raising the team size is what tips it over and brings the alert,
 * validation summary and effect status to life.
 */
/** The subset of fields these tests write, restored between them. */
const DEFAULTS = {
  '$.teamSize': 8,
  '$.nights': 2,
  '$.startDate': '2026-09-14',
  '$.endDate': '2026-09-16',
  '$.venueTags': ['coastal', 'quiet'],
  '$.venueRating': 4,
  '$.city': null,
  '$.roomStyle': null,
  '$.perPersonNightly': 140,
  '$.cateringPerDay': 55,
  '$.travelPerPerson': 180,
  '$.contingencyPercent': 10,
};

test.describe('Team Offsite Planner — extended component catalog', () => {
  let modelId: string;

  // One model for the file, not one per test. Creating this spec costs ~30s: it carries more
  // expressions than any other bundled example and every one is a cold javac round-trip through
  // the JSONata compiler (ExpressionCache is per-runtime, so a new model recompiles all of them).
  // Nineteen creations would be ten minutes of setup. The suite runs single-worker and
  // non-parallel, so one shared model is safe as long as each test starts from a known state.
  test.beforeAll(async ({ request }) => {
    test.setTimeout(180_000);
    modelId = uid('e2e-offsite');
    await createModel(request, baseSpec, modelId);
  });

  test.afterAll(async ({ request }) => {
    await deleteModel(request, modelId);
  });

  test.beforeEach(async ({ request }) => {
    const r = await request.post(`${BACKEND}/models/${modelId}/mutations`, { data: DEFAULTS });
    expect(r.ok(), 'failed to reset the model between tests').toBeTruthy();
  });

  /**
   * Types a team size into the stepper's number input and blurs to commit — a UI interaction, and
   * far fewer events than clicking increment 22 times. The stepper keeps a local draft, so the
   * typed value shows immediately (it does not snap back to the model value while the mutation
   * round-trips); blur then commits it. The downstream assertions each test makes still cover the
   * mutation → viewDelta → re-render loop.
   */
  async function setTeamSizeInUi(page: Page, size: number) {
    const input = page.locator('#teamSizeField');
    await input.fill(String(size));
    await expect(input).toHaveValue(String(size));
    await input.blur();
  }

  async function openView(page: Page) {
    await openModel(page, modelId);
    await switchTab(page, 'view');
  }

  // ── containers: tabs ──────────────────────────────────────────────────────

  test('tabs render a strip and show one panel at a time', async ({ page }) => {
    await openView(page);

    await expect(page.getByTestId('tab-teamTab')).toBeVisible();
    await expect(page.getByTestId('tab-costsTab')).toBeVisible();
    await expect(page.getByTestId('tab-venueTab')).toBeVisible();

    // First tab is active; the others' panels are not mounted.
    await expect(page.getByTestId('tabpanel-teamTab')).toBeVisible();
    await expect(page.getByTestId('tabpanel-costsTab')).toHaveCount(0);

    await page.getByTestId('tab-costsTab').click();
    await expect(page.getByTestId('tabpanel-costsTab')).toBeVisible();
    await expect(page.getByTestId('tabpanel-teamTab')).toHaveCount(0);
  });

  test('the active tab is marked for assistive technology', async ({ page }) => {
    await openView(page);
    await expect(page.getByTestId('tab-teamTab')).toHaveAttribute('aria-selected', 'true');
    await page.getByTestId('tab-venueTab').click();
    await expect(page.getByTestId('tab-venueTab')).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByTestId('tab-teamTab')).toHaveAttribute('aria-selected', 'false');
  });

  // ── inputs ────────────────────────────────────────────────────────────────

  test('numericStepper increments the bound value and recomputes the total', async ({ page }) => {
    await openView(page);

    const input = page.locator('#teamSizeField');
    await expect(input).toHaveValue('8');

    await page.getByTestId('teamSizeField-increment').click();
    await expect(input).toHaveValue('9');

    // 9 * 2 * 140 = 2520 accommodation → grand rises; check via the summary view's tile.
    await page.getByTestId('planSteps-step-summary').click();
    await expect(page.getByTestId('grandTile-value')).not.toHaveText('');
  });

  test('numericStepper will not go below its minimum', async ({ page }) => {
    await openView(page);
    const input = page.locator('#nightsField');
    await expect(input).toHaveValue('2');

    await page.getByTestId('nightsField-decrement').click();
    await expect(input).toHaveValue('1');
    // min is 1 — the control disables rather than writing an out-of-schema value.
    await expect(page.getByTestId('nightsField-decrement')).toBeDisabled();
  });

  test('currencyField shows a currency adornment and percentField a percent sign', async ({ page }) => {
    await openView(page);
    await page.getByTestId('tab-costsTab').click();

    await expect(page.getByTestId('nightlyField-prefix')).toBeVisible();
    await expect(page.getByTestId('nightlyField-prefix')).toContainText('€');
    await expect(page.getByTestId('contingencyField-suffix')).toContainText('%');

    // The stored value stays a plain number — the adornment is display only.
    await expect(page.locator('#nightlyField')).toHaveValue('140');
  });

  test('dateRangeField writes each end to its own path', async ({ page, request }) => {
    await openView(page);

    await expect(page.getByTestId('datesField-from')).toHaveValue('2026-09-14');
    await expect(page.getByTestId('datesField-to')).toHaveValue('2026-09-16');

    await page.getByTestId('datesField-to').fill('2026-09-18');

    await expect(async () => {
      const state = await (await request.get(`${BACKEND}/models/${modelId}/state`)).json();
      expect(state.endDate).toBe('2026-09-18');
      expect(state.startDate).toBe('2026-09-14');
    }).toPass({ timeout: 10_000 });
  });

  test('ratingField writes the clicked star', async ({ page, request }) => {
    await openView(page);
    await page.getByTestId('tab-venueTab').click();

    await page.getByTestId('venueRatingField-star-2').click();

    await expect(async () => {
      const state = await (await request.get(`${BACKEND}/models/${modelId}/state`)).json();
      expect(state.venueRating).toBe(2);
    }).toPass({ timeout: 10_000 });
  });

  test('tagsField adds and removes chips, writing the whole array', async ({ page, request }) => {
    await openView(page);
    await page.getByTestId('tab-venueTab').click();

    await expect(page.getByTestId('venueTagsField-tag-coastal')).toBeVisible();
    await expect(page.getByTestId('venueTagsField-tag-quiet')).toBeVisible();

    await page.locator('#venueTagsField').fill('projector');
    await page.locator('#venueTagsField').press('Enter');
    await expect(page.getByTestId('venueTagsField-tag-projector')).toBeVisible();

    await page.getByTestId('venueTagsField-remove-quiet').click();
    await expect(page.getByTestId('venueTagsField-tag-quiet')).toHaveCount(0);

    await expect(async () => {
      const state = await (await request.get(`${BACKEND}/models/${modelId}/state`)).json();
      expect(state.venueTags).toEqual(['coastal', 'projector']);
    }).toPass({ timeout: 10_000 });
  });

  test('a duplicate tag is ignored rather than written twice', async ({ page, request }) => {
    await openView(page);
    await page.getByTestId('tab-venueTab').click();

    await page.locator('#venueTagsField').fill('coastal');
    await page.locator('#venueTagsField').press('Enter');

    const state = await (await request.get(`${BACKEND}/models/${modelId}/state`)).json();
    expect(state.venueTags).toEqual(['coastal', 'quiet']);
  });

  // ── containers: collapsible, driven by a metaDerivation ───────────────────

  test('collapsible toggles its body open and closed', async ({ page }) => {
    await openView(page);
    await page.getByTestId('tab-venueTab').click();

    // notes#relevant is `venueRating <= 3`; the default rating is 4, so the panel is hidden.
    await expect(page.getByTestId('notesPanel')).toHaveCount(0);

    await page.getByTestId('venueRatingField-star-2').click();
    await expect(page.getByTestId('notesPanel')).toBeVisible({ timeout: 15_000 });

    await expect(page.locator('#notesField')).toBeVisible();
    await page.getByTestId('notesPanel-toggle').click();
    await expect(page.locator('#notesField')).toHaveCount(0);
  });

  // ── navigation: stepper across views ──────────────────────────────────────

  test('stepper navigates between views and marks the active step', async ({ page }) => {
    await openView(page);

    await expect(page.getByTestId('planSteps-step-plan')).toHaveAttribute('aria-current', 'step');

    await page.getByTestId('planSteps-step-summary').click();
    await expect(page.getByTestId('summaryCard')).toBeVisible();
    await expect(page.getByTestId('summarySteps-step-summary')).toHaveAttribute('aria-current', 'step');

    await page.getByTestId('summarySteps-step-diagnostics').click();
    await expect(page.getByTestId('stateViewer')).toBeVisible();
  });

  // ── output: stat tiles and summary list ───────────────────────────────────

  test('stat tiles show the derived totals formatted as currency', async ({ page }) => {
    await openView(page);
    await page.getByTestId('planSteps-step-summary').click();

    // 8 * 2 * 140 + 8 * 2 * 55 + 8 * 180 = 4560, +10% contingency = 5016
    await expect(page.getByTestId('grandTile-value')).toContainText('5,016');
    await expect(page.getByTestId('grandTile-value')).toContainText('€');
    await expect(page.getByTestId('perPersonTile-value')).toContainText('627');
  });

  test('summaryList renders one row per derived line', async ({ page }) => {
    await openView(page);
    await page.getByTestId('planSteps-step-summary').click();

    await expect(page.getByTestId('costSummary-row-0')).toContainText('2,240'); // accommodation
    await expect(page.getByTestId('costSummary-row-1')).toContainText('880');   // catering
    await expect(page.getByTestId('costSummary-row-2')).toContainText('1,440'); // travel
    await expect(page.getByTestId('costSummary-row-4')).toContainText('5,016'); // total
  });

  test('summary rows update when an input changes', async ({ page }) => {
    await openView(page);
    await page.getByTestId('planSteps-step-summary').click();
    await expect(page.getByTestId('costSummary-row-2')).toContainText('1,440');

    // Driven through the UI rather than a side-channel API call: this is the path a user takes,
    // and it exercises the mutation → viewDelta → re-render loop the components actually rely on.
    await page.getByTestId('summarySteps-step-plan').click();
    await page.getByTestId('teamSizeField-increment').click();
    await page.getByTestId('teamSizeField-increment').click();
    await expect(page.locator('#teamSizeField')).toHaveValue('10');

    await page.getByTestId('planSteps-step-summary').click();
    // travel = 10 * 180 = 1800
    await expect(page.getByTestId('costSummary-row-2')).toContainText('1,800', { timeout: 15_000 });
  });

  // ── output: alert and validation summary, driven by a flag constraint ─────

  test('over budget brings up the alert and the validation summary', async ({ page }) => {
    await openView(page);

    // Under budget to start: neither is shown.
    await page.getByTestId('planSteps-step-summary').click();
    await expect(page.getByTestId('overBudgetAlert')).toHaveCount(0);

    await page.getByTestId('summarySteps-step-plan').click();
    await setTeamSizeInUi(page, 30);

    await page.getByTestId('planSteps-step-summary').click();
    await expect(page.getByTestId('overBudgetAlert')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('overBudgetAlert')).toContainText('over the approved budget');

    // The `within-approved-budget` constraint has policy `flag`, so the mutation committed and
    // the violation surfaces in the summary rather than as a failed call.
    await page.getByTestId('summarySteps-step-plan').click();
    await expect(page.getByTestId('planIssues')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('planIssues-item').first()).toContainText('over the approved budget');
  });

  test('effectStatus reflects the effect the over-budget plan dispatches', async ({ page }) => {
    await openView(page);
    await setTeamSizeInUi(page, 30);

    await page.getByTestId('planSteps-step-summary').click();
    await expect(page.getByTestId('approvalStatus-state')).toBeVisible({ timeout: 15_000 });
    // The caller-executor effect resolves immediately; whichever terminal state it reaches, the
    // component must render the machine rather than an empty box.
    await expect(page.getByTestId('approvalStatus-state')).not.toHaveText('');
  });

  // ── the rest of the output catalog ────────────────────────────────────────

  test('gauge and sparkline render the derived figures', async ({ page }) => {
    await openView(page);
    await page.getByTestId('planSteps-step-summary').click();

    // A gauge shows the bound value's position in its min..max range, which is why it is bound to
    // the total against the budget rather than to a pre-computed percentage — binding a percent
    // to a gauge and then showing "percent of range" counts the percentage twice.
    const gauge = page.getByTestId('budgetGauge');
    await expect(gauge).toBeVisible();
    await expect(gauge).toHaveAttribute('role', 'meter');
    await expect(gauge).toHaveAttribute('aria-valuenow', '5016');
    await expect(gauge).toContainText('42%'); // 5016 / 12000

    await expect(page.getByTestId('breakdownSpark')).toBeVisible();
  });

  test('a percent row appends a sign without rescaling the stored number', async ({ page }) => {
    // Intl's percent style multiplies by 100; the catalog deliberately does not, so a stored
    // 41.8 reads as 41.8% rather than 4180%.
    await openView(page);
    await page.getByTestId('planSteps-step-summary').click();
    await page.getByTestId('perPersonPanel-toggle').click();
    await expect(page.getByTestId('perPersonList-row-2')).toContainText('41.8%');
  });

  test('callout, keyValueList and image render', async ({ page }) => {
    await openView(page);
    await page.getByTestId('planSteps-step-summary').click();

    await expect(page.getByTestId('planningNote')).toContainText('Confirm the venue');
    await expect(page.getByAltText('Venue placeholder')).toBeVisible();

    // keyValueList lives inside a collapsed collapsible inside the accordion.
    await expect(page.getByTestId('perPersonList')).toHaveCount(0);
    await page.getByTestId('perPersonPanel-toggle').click();
    await expect(page.getByTestId('perPersonList-row-1')).toContainText('€');
  });

  test('toolbar and buttonGroup wire their buttons up', async ({ page }) => {
    await openView(page);
    await setTeamSizeInUi(page, 14);
    await page.getByTestId('planSteps-step-summary').click();

    await expect(page.getByTestId('summaryActions')).toHaveAttribute('role', 'toolbar');
    await expect(page.getByTestId('planButtons')).toHaveAttribute('role', 'group');

    // The onClick mutation runs through the same handler path as any other button.
    await page.getByRole('button', { name: 'Reset team size' }).click();
    await page.getByTestId('summarySteps-step-plan').click();
    await expect(page.locator('#teamSizeField')).toHaveValue('8', { timeout: 15_000 });
  });

  test('breadcrumb marks the current view and navigates', async ({ page }) => {
    await openView(page);
    await page.getByTestId('planSteps-step-summary').click();

    await expect(page.getByTestId('summaryCrumbs-crumb-summary')).toHaveAttribute('aria-current', 'page');
    await page.getByTestId('summaryCrumbs-crumb-plan').click();
    await expect(page.getByTestId('planTabs')).toBeVisible();
  });

  test('autocompleteField filters and writes the chosen option', async ({ page, request }) => {
    await openView(page);
    await page.getByTestId('tab-venueTab').click();

    const input = page.locator('#cityField');
    await input.click();
    await input.fill('val');
    // Filtered down to the one match.
    await expect(page.getByTestId('cityField-option-valencia')).toBeVisible();
    await expect(page.getByTestId('cityField-option-lisbon')).toHaveCount(0);

    await page.getByTestId('cityField-option-valencia').click();
    await expect(async () => {
      const state = await (await request.get(`${BACKEND}/models/${modelId}/state`)).json();
      expect(state.city).toBe('valencia');
    }).toPass({ timeout: 10_000 });
  });

  test('comboBox accepts a value outside its options', async ({ page, request }) => {
    await openView(page);
    await page.getByTestId('tab-venueTab').click();

    const input = page.locator('#roomStyleField');
    await input.click();
    await input.fill('bunk');
    await input.blur();

    await expect(async () => {
      const state = await (await request.get(`${BACKEND}/models/${modelId}/state`)).json();
      expect(state.roomStyle).toBe('bunk');
    }).toPass({ timeout: 10_000 });
  });

  // ── diagnostics ───────────────────────────────────────────────────────────

  test('jsonViewer shows the merged document, derived values included', async ({ page }) => {
    await openView(page);
    await page.getByTestId('planSteps-step-diagnostics').click();

    // collapsed: true in the spec, so it starts folded.
    await expect(page.getByTestId('stateViewer')).toBeVisible();
    await page.getByTestId('stateViewer-toggle').click();

    const body = page.getByTestId('stateViewer').locator('pre');
    await expect(body).toContainText('teamSize');
    await expect(body).toContainText('totals');   // derived, spliced into the merged document
  });

  test('explainPanel fetches the derivation trace for the bound path', async ({ page }) => {
    await openView(page);
    // Make sure there is something to explain.
    await setTeamSizeInUi(page, 12);

    await page.getByTestId('planSteps-step-diagnostics').click();
    await expect(page.getByTestId('explainTotal')).toBeVisible();

    // Declared server-side, fetched client-side: rows appear only after the component calls
    // /explain itself.
    await expect(page.getByTestId('explainTotal-row').first()).toBeVisible({ timeout: 15_000 });
  });

  test('auditTimeline lists committed cycles once expanded', async ({ page, request }) => {
    await openView(page);
    await setTeamSizeInUi(page, 11);

    // `valem.storage.audit-type` defaults to `none` for in-memory storage, and the endpoint then
    // returns an empty list rather than an error. Assert the component against a backend that
    // actually keeps an audit trail; otherwise there is nothing to render and the test would be
    // asserting the absence of a feature that was simply switched off.
    const audit = await (await request.get(`${BACKEND}/models/${modelId}/audit?limit=5`)).json();
    test.skip(
      !Array.isArray(audit) || audit.length === 0,
      'no audit backend configured — run the server with -Dvalem.storage.audit-type=memory',
    );

    await page.getByTestId('planSteps-step-diagnostics').click();
    await page.getByTestId('history-toggle').click();

    await expect(page.getByTestId('history-row').first()).toBeVisible({ timeout: 15_000 });
  });
});
