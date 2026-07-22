---
title: Model spec format
parent: Reference
nav_order: 1
has_children: true
description: "The authoritative field-by-field ModelSpec reference."
---

# Model spec format

A **ModelSpec** is the declarative JSON document that describes a Valem model. It is
typically produced by an LLM (via `SpecGenerator`) and then compiled, validated, and executed
by the runtime. The spec is stored verbatim; it is reloaded and recompiled whenever
`valem.persistence-dir` is set.

This page is the map: the top-level structure, worked examples, and the path notation that runs
through every section. Each section has its own page, because a single 1,400-line reference is
harder to search than five focused ones.

| Section pages | Covers |
|---|---|
| [Schema, constants & defaults](model-spec/schema-and-values.md) | `schema` (with `$defs` / `$ref`), `constants`, `defaultValues` |
| [Derivations, meta & constraints](model-spec/derivations-and-constraints.md) | `derivations`, `metaDerivations`, `constraints` |
| [Effects](model-spec/effects.md) | `effects` — executors, triggers, dedupe, status paths, fold-back |
| [Tests & spec evolution](model-spec/tests-and-evolution.md) | `tests`, `SpecEvolution`, targeted section diffs |
| [View definition & components](model-spec/views.md) | `viewDefinition`, `ViewSpec`, `ComponentSpec`, `EventHandler`, `EvaluatedView` |
| [Component type catalog](model-spec/component-catalog.md) | Every built-in component `type` and the fields it accepts |

Prefer prose first? The [Model guide](../model-guide/index.md) explains the same material by
concept rather than by field.

---

## Top-level structure

```json
{
  "id":               "my-model",
  "version":          "1.0.0",
  "template":         { "ref": "<coordinate>" },
  "lineage":          [ ... ],
  "schema":           { ... },
  "constants":        { "name": <any JSON value>, ... },
  "defaultValues":    [ ... ],
  "derivations":      [ ... ],
  "metaDerivations":  [ ... ],
  "constraints":      [ ... ],
  "effects":          [ ... ],
  "tests":            [ ... ],
  "viewDefinition":   { ... }
}
```

| Field | Required | Default | Description |
|---|---|---|---|
| `id` | yes | — | Model identifier, validated as a `ModelCoordinate`: `[namespace/]name`, each segment starting with a letter (letters/digits/`-`/`_`), no `@`. |
| `version` | no | `"1.0.0"` | Version string; when non-blank must be valid semver (`MAJOR.MINOR.PATCH`) — a non-semver value is rejected at validation. |
| `template` | no | `null` | `{ "ref": "<coordinate>" }` — branches this model from another owner's template. `POST /models` flattens (materializes) the referenced template into a self-contained inlined spec before creation; see composition/branching below. |
| `lineage` | no | `[]` | Read-only, materializer-written: the pinned ancestor chain this model was branched from (`[]` for a non-branch model). Validated acyclic. Exposed at `GET /models/{id}/lineage`. |
| `schema` | yes | — | JSON Schema (Draft 2020-12) for the base document |
| `constants` | no | `{}` | Named immutable values (any JSON type), bound as `$const` in every expression |
| `defaultValues` | no | `[]` | Default rules for newly-created containers; a `$` rule seeds at creation |
| `derivations` | no | `[]` | Computed read-only fields |
| `metaDerivations` | no | `[]` | Per-field metadata (min/max/required/…) |
| `constraints` | no | `[]` | Boolean invariants with violation policy |
| `effects` | no | `[]` | Effect requests emitted by the core, run by a shell (`caller`/`server`/`llm`/`timer`/plugin); replaces the removed `actions` |
| `tests` | no | `[]` | Embedded spec-level test cases |
| `viewDefinition` | no | `null` | Declarative UI form definition |

**Composition/branching.** A model created with `template` set is a *branch*: the API materializes
(flattens) the referenced template's spec into the new model's own inlined spec, pins the ancestor
chain into `lineage`, and validates the result acyclic. Branching across an ownership boundary (the
new model's owner differs from `lineage`'s `fromOwner`) quarantines any inherited non-`caller` effect
until approved — see [security-model.md](../deployment/security-model.md) and
`valem.authz.inherited-effects` in [configuration.md](../deployment/configuration.md). Models can also be
**promoted** between repositories (`POST /models/{id}/promote`) and queried for cross-model topology
(`GET /composition/graph`). The end-to-end lifecycle (branch → approve inherited effects → promote,
plus links between models) is walked through in the
[composition & branching guide](../model-guide/composition-and-branching.md).

> **No per-field access control.** Valem has no `fieldAccess`/roles. Any caller with model
> access reads/mutates/evolves every field; access is a single coarse gate (`valem.api.key`).
> See [security-model.md](../deployment/security-model.md).

---

---

## Complete examples

### Customer Satisfaction Survey

Demonstrates:
- `radioField` for a 1–5 rating
- `checkboxField` for boolean questions
- `selectField` with static options
- `textAreaField` for free text
- `emailField`
- `separatorLine`
- `group` with horizontal layout
- `label` and `badge` with JSONata `text` and `variant` expressions
- `metaDerivations` that automatically hide/lock fields (`relevant`, `readOnly`)

Key pattern — view components have **no `visible` or `readOnly` expressions**. Visibility
is driven entirely by `metaDerivations` on `$.issueCategory` and `$.contactEmail`:

```json
"metaDerivations": [
  { "path": "$.issueCategory", "property": "relevant",
    "expr": "issueEncountered = true" },
  { "path": "$.issueCategory", "property": "readOnly",
    "expr": "issueEncountered != true" }
]
```

The `issueCategoryField` component has no `visible` field:

```json
{
  "id": "issueCategoryField", "type": "selectField",
  "label": "Issue Category", "bind": "$.issueCategory",
  "options": [ ... ]
}
```

When `issueEncountered = false`, the `relevant` meta value is `false` → the evaluator
sets `visible: false` automatically.

### Order Line Items

Demonstrates:
- `dataTable` with `tableColumns` including `"format": "currency"`
- `separatorLine`
- `label` showing the derived `$.total`
- Wildcard derivation `$.items[*].lineTotal` (per-row computation via `$parent`)

---

## Path notation

There are **two** notations, and which one applies depends on where the path appears:

**Addresses — JSON Path (RFC 9535).** Anywhere a path is used as *data* — spec `path` fields
(derivations, metaDerivations, defaultValues), mutation/patch keys, the path portion of a meta
key, and view `bind` — use canonical JSON Path: **`$.`-rooted, with bracket array indices**.

| Notation | Meaning | Example |
|---|---|---|
| `$.field` | Top-level field | `$.total` |
| `$.parent.child` | Nested field | `$.order.status` |
| `$.items[0].name` | Array element by index | First item's name |
| `$.items[*].lineTotal` | Wildcard — all elements | Per-row derivation path |

**Expressions — JSONata.** Inside a JSONata body (`expr`, `trigger`, `payload`/`emit`, and the
JSONata view fields), use whatever navigation JSONata accepts; the `$.` prefix is omitted and
dot-notation is used directly (e.g. `items.(price * qty)`). Expression bodies are **never** rewritten
or constrained — only compiled.

> **Address dialect is enforced.** The validator **rejects** non-canonical addresses as
> errors: the legacy dot-index form (`$.items.0.name`) and unrooted forms (`items.0.name`) are not
> accepted — use the bracket form (`$.items[0].name`). The error message includes the canonical
> rewrite. Expression bodies are unaffected (any JSONata navigation is allowed).
