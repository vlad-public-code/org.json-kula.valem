---
title: Rules & calculations as data
parent: Usage scenarios
nav_order: 2
description: "Pricing, quoting, eligibility and scoring expressed as a spec you can change without a release."
---

# Rules & calculations as data
{: .no_toc }

Business arithmetic changes faster than software ships. Valem lets the arithmetic be **data** — with
the invariants enforced and every result explainable.
{: .fs-5 .fw-300 }

1. TOC
{:toc}

---

## The problem

Pricing rules, eligibility checks, discount tiers, risk scores, commission splits — the logic is
rarely complicated, but it is *volatile* and *auditable*. Encoded as Java or TypeScript it becomes:
scattered across services, hard to test in isolation, impossible to answer "why did this customer get
this number in March?" — and every tweak is a release.

## The shape of the fix

Put the calculation in a `ModelSpec`:

```json
{
  "id": "quote",
  "version": "2.1.0",
  "schema": { "type": "object" },
  "constants":   { "baseRate": 0.045, "loyaltyDiscount": 0.9 },
  "derivations": [
    { "path": "$.premium",  "expr": "coverage * $const.baseRate" },
    { "path": "$.payable",  "expr": "years > 3 ? premium * $const.loyaltyDiscount : premium" }
  ],
  "metaDerivations": [
    { "path": "$.coverage", "property": "maximum", "expr": "riskBand = 'high' ? 250000 : 1000000" }
  ],
  "constraints": [
    { "id": "min-premium", "expr": "payable >= 50",
      "message": "Premium below the underwriting floor", "policy": "rollback" }
  ],
  "tests": [ ]
}
```

Now the rules are a versioned document. Changing a rate is a spec evolution, not a deployment; the
model's `tests` run as part of that change; and every number the runtime produces carries a trace
explaining how it got there.

## What Valem provides

| Need | Mechanism |
|---|---|
| Values that follow their inputs | `derivations`, recomputed in topological order on every mutation |
| Rules that can't be bypassed | `constraints` with `rollback` (refuse) or `flag` (record) policies |
| Field limits that depend on state | `metaDerivations` — live min/max/required, readable via the effective schema |
| Shared magic numbers | `constants`, bound as `$const.<name>` — one place per rate or threshold |
| Sensible starting values | `defaultValues` — merged into newly-created containers only |
| Change a live model safely | Spec evolution with `expectedVersion` + a schema check that refuses to strand existing data |
| Prove a change is safe | Embedded `tests` run by `TestCaseRunner` — in the spec, versioned with the rules |
| Answer "why?" months later | `explain` traces plus the durable, tamper-evident [audit trail]({% link deployment/operations.md %}) |

## How it gets into your stack

- **In-process.** Depend on `valem-core` + `valem-service` and drive a model directly from your Java
  service — no server, no framework. See [embedding]({% link extending/embedding.md %}).
- **As a service.** Run [`valem-web`]({% link deployment/web-api.md %}) and call it over REST; the
  typed [client SDKs]({% link extending/client-sdks.md %}) (Java and TypeScript) cover lifecycle,
  mutation, reads and a reconnecting change subscription.
- **Per-tenant variants.** Branch a template model per customer, keep the lineage, and promote a
  tested branch back — see [composition & branching]({% link model-guide/composition-and-branching.md %}).

## A nice second-order win: form validation for free

`GET /models/{id}/schema/{path}` returns a field's schema **overlaid with its live meta-derived
constraints** — the maximum coverage *for this risk band, right now*. A form can drive its own
validation from that rather than duplicating the rules in the client, which is exactly the
duplication that drifts.

## Worth knowing before you build on it

- Expressions are [JSONata](https://jsonata.org), compiled once per runtime and cached. They are
  expressive, but they are not a general programming language — deliberately.
- Constraints are evaluated against the **merged** document (base + derived), so a constraint can
  reference derived values.
- There is no per-field authorization: if a caller can reach the model, it can write any base field.

## Next

- [Model guide]({% link model-guide/index.md %}) — how derivations, constraints and defaults behave.
- [Model spec format]({% link reference/model-spec-format.md %}) — the authoritative field reference.
- [Examples gallery](examples-gallery.md) — insurance quote, loan calculator, order totals.
