---
title: Derivations, meta & constraints
parent: Model spec format
grandparent: Reference
nav_order: 2
description: "Computed fields, live per-field metadata, and the invariants the runtime enforces."
---

# `derivations`, `metaDerivations`, `constraints`

The computed half of a model: values derived from other values, the live metadata that overlays
the schema, and the invariants a mutation must not break.

---

## `derivations`

Computed read-only fields whose values are re-evaluated whenever their dependencies change.

```json
{
  "path":        "$.total",
  "expr":        "$sum(items.(price * qty))",
  "evaluation":  "eager",
  "description": "Grand total of all line items"
}
```

| Field | Required | Default | Description |
|---|---|---|---|
| `path` | yes | — | Target field path, `$.`-prefixed |
| `expr` | yes | — | JSONata expression |
| `evaluation` | no | `"eager"` | `"eager"` or `"lazy"` |
| `description` | no | — | Human-readable explanation |

### Evaluation modes

- **`eager`** — evaluated synchronously during each mutation transaction, in topological
  dependency order. The derived value is written to the derivation cache and included in
  every `GET /state` response.
- **`lazy`** — marked stale during the transaction but not evaluated until requested.
  Evaluated on demand by `GET /models/{id}/state` or `GET /models/{id}/state/{path}`.

### Wildcard (per-element) derivations

A path containing `[*]` (e.g. `$.items[*].lineTotal`) evaluates the expression once for
each array element. Inside the expression, `$parent` is bound to the current element node;
the full merged document is always available as the root context.

```json
{
  "path": "$.items[*].lineTotal",
  "expr": "$parent.price * $parent.qty"
}
```

### Dependency tracking

The runtime automatically builds a dependency graph by extracting all JSONata identifier
references from each expression. Derivations are evaluated in topological order, so a
derivation can safely reference another derived field.

```json
[
  { "path": "$.monthlyRate",    "expr": "annualRatePercent / 1200" },
  { "path": "$.compoundFactor", "expr": "$power(1 + monthlyRate, termMonths)" },
  { "path": "$.monthlyPayment", "expr": "loanAmount * monthlyRate * compoundFactor / (compoundFactor - 1)" }
]
```

---

## `metaDerivations`

Per-field metadata that is re-evaluated like derivations but stored in a separate **meta
cache** (not the base document). The meta cache is used by the view evaluator to
automatically control component visibility, interactivity, and validation.

```json
{
  "path":        "$.contactEmail",
  "property":    "relevant",
  "expr":        "contactPermission = true",
  "description": "Email is only needed when contact permission is granted"
}
```

| Field | Required | Description |
|---|---|---|
| `path` | yes | Target field path, `$.`-prefixed |
| `property` | yes | Which metadata property to compute (see table below) |
| `expr` | yes | JSONata expression evaluated against the merged document |
| `description` | no | Human-readable explanation |

### Meta properties

| `property` | JSON Schema keyword | Description |
|---|---|---|
| `required` | `required` | Whether the field is mandatory |
| `minimum` | `minimum` | Numeric lower bound |
| `maximum` | `maximum` | Numeric upper bound |
| `multipleOf` | `multipleOf` | Numeric granularity (e.g. `0.01` for cent-precision) |
| `minLength` | `minLength` | String minimum length |
| `maxLength` | `maxLength` | String maximum length |
| `pattern` | `pattern` | Regex pattern |
| `enum` | `enum` | Allowed values array |
| `readOnly` | — | Whether the field is non-editable |
| `relevant` | — | Valem extension: whether the field is currently relevant/visible |

`relevant` has no JSON Schema equivalent. When `relevant` evaluates to `false` for a
field, the view evaluator hides the corresponding component (see [View Definition](views.md)).

> **Visibility is presentation-only — hidden fields still compute.** `relevant=false` (and a view
> component's `visible=false`) affect **rendering only**. The field's value remains in the document
> and **continues to participate fully** in derivations and constraints: a derivation that reads a
> hidden field still uses its value, and a constraint over a hidden field still fires (rolling back
> or flagging as configured). Valem does **not** treat "not relevant" as "absent from
> computation". If a value should not influence results when hidden, gate that explicitly in the
> expression (e.g. `relevantFlag ? hiddenValue : 0`) rather than relying on visibility.

### Meta cache key format

Meta values are stored as `"$.path#property_name"` where the property name is the enum
name in lowercase (e.g. `"$.contactEmail#relevant"`, `"$.score#read_only"`). This format
is used internally by `ViewEvaluator` and by tests that assert meta state.

---

## `constraints`

Boolean invariants evaluated after each mutation. A failed constraint triggers the
configured `policy`.

```json
{
  "id":      "rating-in-range",
  "path":    null,
  "expr":    "overallRating >= 1 and overallRating <= 5",
  "message": "Rating must be between 1 and 5",
  "policy":  "rollback"
}
```

| Field | Required | Description |
|---|---|---|
| `id` | yes | Unique constraint identifier |
| `path` | no | Scope path (see below) |
| `expr` | yes | JSONata boolean expression evaluated against the merged document |
| `message` | yes | Human-readable violation message |
| `policy` | yes | `"rollback"` or `"flag"` |

### `path` scoping

| Value | Behavior |
|---|---|
| `null` (omitted) | Global constraint — evaluated once against the full merged document |
| `"$.items"` (scalar string) | Single-target constraint scoped to one field |
| `["$.price", "$.qty"]` | Multi-target constraint: `path` field in JSON accepts either a string or an array of strings |
| `"$.items[*]"` | Array-scoped (per-element) constraint evaluated once per array element |

### Policies

- **`rollback`** — the entire mutation transaction is rolled back and a `409 Conflict`
  response is returned with the constraint message.
- **`flag`** — the mutation commits, but the violated constraints are included in the
  mutation response body under `"flaggedConstraints"`.

### Which mechanism enforces a bound? (schema vs constraint vs metaDerivation)

Three mechanisms can express "this field must stay within a range". They differ in **when** they
run and **what** the client sees — pick by the behaviour you want, and avoid encoding the *same*
bound in more than one place (the validator may warn on conflicting bounds for one field):

| Mechanism | When it runs | On violation | Use when… |
|---|---|---|---|
| **`schema`** (static JSON Schema `minimum`/`maximum`/`enum`/`required`/…) | Pre-transaction, before any write | **HTTP 422**, mutation never applied | The bound is **constant** and structural — the field can never validly hold the value. |
| **`metaDerivations`** (live `minimum`/`maximum`/`required`/`relevant`/…) | During the reactive pipeline; overlays the effective schema | Advisory — surfaces via `GET /schema/{path}` and drives the UI; also enforced as 422 on the *next* mutation against the overlaid schema | The bound is **dynamic** (depends on other fields) and you want the form/effective-schema to reflect it. |
| **`constraints`** (boolean invariant) | After derivations settle, against the merged document | **`rollback` → HTTP 409** (whole transaction reverts) or **`flag`** (commits, reported in `flaggedConstraints`) | The rule spans **multiple fields** / cross-field relationships, or you want a commit-but-flag policy. |

Rule of thumb: constant single-field limit → `schema`; field-dependent single-field limit you want
reflected in the UI → `metaDerivations`; cross-field invariant or flag-don't-block → `constraints`.

---
