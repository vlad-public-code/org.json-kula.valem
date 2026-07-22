---
title: Anatomy of a model
parent: Model guide
nav_order: 1
description: "The sections of a ModelSpec, what each one is for, and the two path dialects that run through all of them."
---

# Anatomy of a model
{: .no_toc }

A **ModelSpec** is a declarative JSON document — often LLM-generated — that fully describes a
reactive model: its shape, its formulas, its invariants, and its side effects. Valem compiles it
into a live runtime.
{: .fs-5 .fw-300 }

1. TOC
{:toc}

---

## A complete small spec

```json
{
  "id": "order",
  "version": "1.0.0",
  "schema": { "type": "object" },
  "constants":      { "taxRate": 0.08 },
  "defaultValues":  [ { "path": "$", "expr": "{ 'currency': 'USD' }" } ],
  "derivations":    [ { "path": "$.tax",   "expr": "subtotal * $const.taxRate" },
                      { "path": "$.total", "expr": "subtotal + tax" } ],
  "metaDerivations":[ { "path": "$.subtotal", "property": "minimum", "expr": "0" } ],
  "constraints":    [ { "id": "max-order", "expr": "total <= 5000",
                        "message": "Order exceeds the cap", "policy": "rollback" } ],
  "effects":        [ { "id": "large-order-alert", "executor": "caller", "trigger": "total > 1000",
                        "emit": "large-order", "payload": { "total": "total" } } ],
  "tests":          [ ]
}
```

Nothing here is procedural. Each section states a fact about the domain; the runtime works out when
to apply it.

## The sections

| Field | What it does |
|---|---|
| `id`, `version` | Identity of the model and its spec version. |
| `schema` | JSON Schema for the base (writable) document. Local `$defs` / `$ref` are supported. |
| `constants` | Named immutable values (any JSON type), bound as `$const.<name>` in every expression. No dependency edge — a derivation reading *only* `$const` never recomputes, so reference an input alongside it. |
| `defaultValues` | `(path, expr)` rules that deep-merge into a **newly-created** container (array element, object, or root `$`), filling only caller-absent fields. A `$` rule seeds the root at creation. |
| `derivations` | Read-only computed fields. Evaluated in topological level order, so a later level can read an earlier one. Wildcard paths (`$.items[*].lineTotal`) evaluate once per element with `$parent` bound to that element. |
| `metaDerivations` | Live per-field metadata (min / max / required / …) overlaying the effective schema — so a limit can depend on state. |
| `constraints` | Boolean invariants with a `rollback` (reject the mutation) or `flag` (record a violation) policy. |
| `effects` | Requests the pure core emits as **data**, executed post-commit by a shell: `caller` (returned inline), `server` (HTTP behind an SSRF guard), `llm`, `timer`, or a custom plugin kind. Replay never re-runs I/O. See [Effects](effects.md). |
| `tests` | Embedded spec-level test cases the runtime can execute — versioned with the rules they check. |
| `viewDefinition` | An optional renderer-agnostic UI component tree. See [Views](views.md). |

Field-by-field detail for every one of them — including every effect option and the full view
component catalog — is in the
[model spec format reference]({% link reference/model-spec-format.md %}).

## Base vs derived vs meta

Three kinds of node, and the distinction matters everywhere else:

- **[BASE](../glossary.md#node-kinds)** — writable. The only thing a mutation may target.
- **DERIVED** — computed by a derivation. Read-only; writing one is an error, not an override.
- **META** — meta-derivations, plus synthetic nodes for each constraint and effect, which is how
  those participate in the same dependency graph.

Reads return the **[merged document](../glossary.md#merged-document-mergeddocument)**: the base
document with all derived values spliced in. Callers generally shouldn't care which is which —
that's the point.

## Addresses vs expressions

Two distinct dialects, and Valem keeps them strictly separate. Mixing them up is the single most
common authoring mistake.

**[Addresses](../glossary.md#address-vs-expression-path)** — paths used *as data*: `path` fields, `defaultValues` paths, mutation keys, view
`bind`. These must be canonical **JSON Path**: `$.`-rooted, bracket array indices.

```
$.order.items[0].qty        ✅
$.order.items[*].qty        ✅  (wildcard — evaluated per element)
$.items.0.x                 ❌  rejected (legacy dot-index)
order.items[0].qty          ❌  rejected (not rooted)
```

**Expressions** — the bodies of `expr`, `trigger`, `payload`, and the view's dynamic properties.
These are **[JSONata](https://jsonata.org)**, evaluated against the merged document, and are never
rewritten:

```jsonata
order.subtotal + order.tax
items[qty > 0].(price * qty) ~> $sum()
riskBand = 'high' ? 250000 : 1000000
```

Useful bindings inside expressions: `$const.<name>` for constants, `$parent` for the containing
element of a wildcard derivation, and `$self` for a container's caller-provided fields in
`defaultValues`.

## What compilation checks

Creating (or evolving) a model is a validation gate, not just a parse:

- Every address is canonical and resolvable; expressions compile.
- The dependency graph is acyclic — a cycle is rejected, not detected at runtime.
- The schema's local `$ref` / `$defs` resolve; non-local or dangling refs are rejected.
- The `viewDefinition` parses, ids are unique, and `defaultView` / `itemView` name existing views.
- A spec still carrying the removed `initialState` or `actions` sections is rejected with a pointer
  to its replacement (`defaultValues` and `effects` respectively).

Failures come back as a `422` with locations — which is exactly what makes
[generate-then-repair]({% link model-guide/generating-specs-with-llm.md %}) work, and what the MCP
`validate_spec` tool exposes to an agent before anything is created.

## Next

- [The reactive pipeline](reactive-pipeline.md) — what happens when you mutate.
- [Model spec format]({% link reference/model-spec-format.md %}) — the authoritative reference.
- [Examples gallery]({% link usage-scenarios/examples-gallery.md %}) — working specs to start from.
