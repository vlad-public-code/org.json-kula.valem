---
title: Examples gallery
parent: Guides
nav_order: 2
description: "Ready-to-run ModelSpec documents that double as demos and starting points."
---

# Example gallery

Ready-to-run `ModelSpec` documents that double as demos, integration fixtures, and starting points
for your own models. The canonical copies live in [`valem-ui/src/examples/`](https://github.com/vlad-public-code/org.json-kula.valem/blob/main/valem-ui/src/examples/)
and on the console test classpath. Load one into a running backend with:

```bash
curl -X POST localhost:8080/models -H 'Content-Type: application/json' \
     -d @valem-ui/src/examples/insurance-quote.json
```

(or pick it from the **Load an example** panel in the management UI).

> The specs carry two underscore-prefixed metadata fields, `_name` and `_description`, that are not
> part of the `ModelSpec` schema. The management UI strips them before `POST /models`; a raw `curl`
> works because unknown top-level fields are ignored on deserialization.

## Featured: quoting & eligibility models

### `insurance-quote.json` — Term Life Insurance Quote

The reference model for Valem's core use case: **LLM-driven quoting / eligibility tools over
structured data**, where a team would otherwise hand-wire the same compute-and-validate layer per
project. It exercises every pillar of the runtime on one realistic domain:

| Pillar | In this model |
|---|---|
| **Constants** | An actuarial rate table (`baseRatePer1000`, `smokerLoading`, coverage bounds) bound as `$const`. |
| **Chained derivations** | `units → baseAnnual → annualPremium → monthlyPremium`, plus age/smoker risk factors, recomputed reactively (change coverage, every downstream value updates). |
| **Constraints** | `coverage-positive` (rollback invariant) and `coverage-in-range` (soft flag). |
| **Meta-derivation** | Age-dependent `maximum` coverage overlaid on the field schema. |
| **Effects (all three shell kinds)** | `server` fetches a live regional rate multiplier and folds it into the premium; `timer` expires a priced quote after 15 minutes; `caller` surfaces a "quote ready" command. |
| **View** | A vertical quote form bound to the reactive fields. |

**Certification, not homework.** The spec's embedded `tests` array is a **golden dataset whose
expected premiums are hand-derived from the rate table** — an *independent oracle*, not assertions
the LLM wrote about its own formula. The suite runs through the full
`ModelService` reactive pipeline in
[`InsuranceQuoteGoldenTest`](https://github.com/vlad-public-code/org.json-kula.valem/blob/main/valem-console/src/test/java/org/json_kula/valem/console/InsuranceQuoteGoldenTest.java),
which is the concrete shape of the **draft → review → certify → promote** governance lifecycle: a
model is not trusted until an independent golden suite passes.

Worked example (from the golden set): a non-smoker aged 35 with $100,000 coverage in a neutral region
→ `units = 100`, `baseAnnual = 100 × 1.5 = 150`, `ageFactor = 1.0`, `smokerFactor = 1.0`,
`regionMultiplier = 1.0` ⟹ **$150/yr, $12.50/mo, decision `quoted`**.

### `benefits-eligibility.json` — Benefits Eligibility

A second model in the same family (eligibility over structured data): an assistance-program calculator
whose federal-poverty-line threshold scales with household size (constants), whose income-to-poverty
ratio drives an eligibility tier and monthly benefit (chained derivations), with a positive-household
rollback invariant plus an over-max **soft flag**, an external income-verification `server` effect, a
caller notification, and a submission-expiry `timer`. Its golden suite
([`BenefitsEligibilityGoldenTest`](https://github.com/vlad-public-code/org.json-kula.valem/blob/main/valem-console/src/test/java/org/json_kula/valem/console/BenefitsEligibilityGoldenTest.java))
is hand-derived from the poverty table — e.g. a household of 4 earning $45,000 → poverty line
`15000 + 3×5000 = 30000`, ratio `1.5` ⟹ **partial tier, $300/mo**.

Together the two featured models split the teaching surface: insurance-quote demonstrates a
meta-derivation and a rollback constraint; benefits-eligibility demonstrates a soft-flag constraint
and household-driven threshold scaling.

## The rest of the gallery

| File | Demonstrates |
|---|---|
| `car-loan-calculator.json` | Multi-level derivation chain; a full 60-row amortization schedule via array derivations. |
| `order-items-price-total.json` | Array **wildcard** derivations (`$.items[*].lineTotal`); add/remove rows react. |
| `customer-satisfaction-survey.json` | Conditional field visibility (`relevant` meta) + a rollback constraint. |
| `personal-budget-tracker.json` | Multiple named views; income-vs-spending breakdown. |
| `energy-consumption-heating.json` | A four-level mathematical derivation pipeline. |
| `daily-wellness.json` | Conditionally read-only fields; per-field gating. |
| `support-ticket-triage.json` | All four effect executors together (`llm` classify, `server` SLA lookup, `caller` alert, `timer` auto-escalate). |
| `world-clock.json` | A minimal, focused pair: only `server` (http) and `timer` effects composing into a **self-refreshing poll** — the http effect fetches the current date/time for a chosen country from a real public API ([timeapi.io](https://timeapi.io)), and the timer re-arms every 10s by bumping a tick the http effect is keyed to, so the clock keeps updating itself. |

Each spec ships its own `tests`; the console module runs them through the real pipeline in
`ConsoleExamplesIntegrationTest`, so the gallery is also a regression suite. `world-clock.json`
additionally has its own end-to-end test,
[`WorldClockExampleTest`](https://github.com/vlad-public-code/org.json-kula.valem/blob/main/valem-api/src/test/java/org/json_kula/valem/api/effects/WorldClockExampleTest.java),
which drives it through the real REST API — creating the model, evaluating its `viewDefinition` form via
`GET /models/{id}/view`, picking a country as a form mutation would, and confirming both the initial
time fetch and the recurring re-fetch driven by the timer (the http lookup re-firing each tick).
