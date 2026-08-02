---
title: Copy-paste spec examples
parent: Reference
nav_order: 6
description: "Minimal, self-contained ModelSpec documents you can paste verbatim and run — the fastest correct starting point."
---

# Copy-paste spec examples
{: .no_toc }

Three minimal, self-contained `ModelSpec` documents, each with the exact command to create and drive
it. They are deliberately small — no `viewDefinition`, no effects — so you can paste one, watch it
compute, and grow it from there. For the full field reference see
[Model spec format]({% link reference/model-spec-format.md %}); for larger, view-rendered specs see
the [Examples gallery]({% link usage-scenarios/examples-gallery.md %}).
{: .fs-5 .fw-300 }

1. TOC
{:toc}

---

## The shape of every spec

A `ModelSpec` is a JSON document. The three fields that matter for a first model:

- **`schema`** — a JSON Schema `object` naming your fields. Mark derived fields `"readOnly": true`;
  you never write them.
- **`derivations`** — formulas that compute a field from others, addressed by
  [JSON Path](model-spec/schema-and-values.md) and expressed in [JSONata](https://jsonata.org). Valem
  recomputes them in dependency order whenever an input changes.
- **`constraints`** — boolean invariants checked after derivations settle; a `rollback` policy
  reverts the whole mutation, a `flag` policy commits but reports the violation.

You write only base fields; Valem maintains the rest.

## 1 — One derivation (the smallest useful model)

An invoice whose `total` is always `subtotal × (1 + taxRate)`. Write `subtotal` and `taxRate`; read a
`total` that is never stale.

```json
{
  "id": "invoice",
  "version": "1.0.0",
  "schema": {
    "type": "object",
    "properties": {
      "subtotal": { "type": "number", "minimum": 0 },
      "taxRate":  { "type": "number", "minimum": 0 },
      "total":    { "type": "number", "readOnly": true }
    }
  },
  "derivations": [
    { "path": "$.total", "expr": "subtotal * (1 + taxRate)" }
  ]
}
```

Create it and drive it over the REST API:

```bash
# 1. create the model
curl -X POST localhost:8080/models -H 'Content-Type: application/json' -d @invoice.json

# 2. write base fields
curl -X POST localhost:8080/models/invoice/mutations \
     -H 'Content-Type: application/json' \
     -d '{ "$.subtotal": 100, "$.taxRate": 0.2 }'

# 3. read the merged, consistent state
curl localhost:8080/models/invoice/state
#  → { "subtotal": 100, "taxRate": 0.2, "total": 120 }
```

## 2 — Add a constraint

The same pattern with an invariant that cannot be violated. A budget where `remaining = income −
spending` and spending is never allowed to exceed income: the `rollback` policy makes the offending
mutation fail with `409 Conflict` and leave the model untouched.

```json
{
  "id": "budget",
  "version": "1.0.0",
  "schema": {
    "type": "object",
    "properties": {
      "income":    { "type": "number", "minimum": 0 },
      "spending":  { "type": "number", "minimum": 0 },
      "remaining": { "type": "number", "readOnly": true }
    }
  },
  "derivations": [
    { "path": "$.remaining", "expr": "income - spending" }
  ],
  "constraints": [
    {
      "id": "no-overspend",
      "expr": "remaining >= 0",
      "message": "Spending cannot exceed income",
      "policy": "rollback"
    }
  ]
}
```

```bash
curl -X POST localhost:8080/models -H 'Content-Type: application/json' -d @budget.json
curl -X POST localhost:8080/models/budget/mutations \
     -H 'Content-Type: application/json' \
     -d '{ "$.income": 3000, "$.spending": 3200 }'
#  → 409 Conflict — "Spending cannot exceed income"; the model stays as it was.
```

Switch `"policy"` to `"flag"` and the mutation commits instead, with the violation reported under
`flaggedConstraints` in the response — commit-but-warn rather than block.

## 3 — An array with per-row and rollup derivations

Line items with a per-row `lineTotal` and a grand `total`. `$parent` in a row derivation refers to the
enclosing array element; `$sum(...)` rolls the rows up.

```json
{
  "id": "order",
  "version": "1.0.0",
  "schema": {
    "type": "object",
    "properties": {
      "items": {
        "type": "array",
        "items": {
          "type": "object",
          "properties": {
            "name":      { "type": "string" },
            "price":     { "type": "number", "minimum": 0 },
            "qty":       { "type": "integer", "minimum": 1 },
            "lineTotal": { "type": "number", "readOnly": true }
          },
          "required": ["name", "price", "qty"]
        }
      },
      "total": { "type": "number", "readOnly": true }
    }
  },
  "derivations": [
    { "path": "$.items[*].lineTotal", "expr": "$parent.price * $parent.qty" },
    { "path": "$.total",              "expr": "$sum(items.(price * qty))" }
  ]
}
```

```bash
curl -X POST localhost:8080/models -H 'Content-Type: application/json' -d @order.json
curl -X POST localhost:8080/models/order/mutations \
     -H 'Content-Type: application/json' \
     -d '{
           "$.items[0].name": "Apple", "$.items[0].price": 1.5, "$.items[0].qty": 4,
           "$.items[1].name": "Bread", "$.items[1].price": 2.75, "$.items[1].qty": 2
         }'
curl localhost:8080/models/order/state
#  → items[0].lineTotal = 6, items[1].lineTotal = 5.5, total = 11.5
```

## Create the same model from an AI agent (MCP)

Every spec above is the `spec` argument to the [`create_model`](mcp-tools.md) MCP tool, so an agent
paired via [`valem-mcp`]({% link deployment/mcp-server.md %}) creates and drives it with the same JSON:

```jsonc
// tools/call → create_model
{ "name": "create_model", "arguments": { "spec": { /* any ModelSpec above */ } } }

// then mutate and read back
{ "name": "mutate",    "arguments": { "id": "invoice", "mutations": { "$.subtotal": 100, "$.taxRate": 0.2 } } }
{ "name": "get_state", "arguments": { "id": "invoice" } }
```

Before pushing a spec, an agent can vet it offline with the pure authoring tools — `validate_spec`
(structural check), `test_spec` (run embedded `tests`), and `dry_run` (apply mutations without
committing). These always run against local core, even in remote mode. See the
[MCP tools reference](mcp-tools.md).

## Where to go next

- Add a UI: give the spec a [`viewDefinition`](model-spec/views.md) and it renders as a reactive form.
- Add reactions: [effects](model-spec/effects.md) fire HTTP/LLM/timer calls post-commit and fold the
  result back into state.
- Let an LLM write the whole spec from a sentence: [Generating specs with an
  LLM]({% link model-guide/generating-specs-with-llm.md %}), or just describe it in the
  [sandbox](https://valem.onrender.com).
