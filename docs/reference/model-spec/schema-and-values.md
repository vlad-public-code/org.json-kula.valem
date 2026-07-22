---
title: Schema, constants & defaults
parent: Model spec format
grandparent: Reference
nav_order: 1
description: "The base document shape, named constants, and the rules that seed newly-created containers."
---

# `schema`, `constants`, `defaultValues`

The writable half of a model: the shape of the base document, the immutable values every
expression can read, and what fills a container the moment it is created.

---

## `schema`

Standard JSON Schema (Draft 2020-12) document that describes the shape and constraints of the
**base document** — the writable portion of the model state. The runtime uses it for
structural validation at mutation time.

Use `"readOnly": true` in property definitions to mark fields that are populated by
derivations; the mutation endpoint rejects attempts to write these directly.

```json
"schema": {
  "type": "object",
  "properties": {
    "loanAmount":     { "type": "number",  "minimum": 0 },
    "monthlyPayment": { "type": "number",  "readOnly": true },
    "items": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "name":      { "type": "string" },
          "lineTotal": { "type": "number", "readOnly": true }
        },
        "required": ["name"]
      }
    }
  },
  "required": ["loanAmount"]
}
```

### Reusable definitions (`$defs` / `$ref`)

Large schemas can factor shared shapes into `$defs` and reference them with a **local**
`$ref`. Only the form `{"$ref": "#/$defs/<Name>"}` pointing at a top-level `$defs` entry is
supported; references resolve lazily, so recursive types work:

```json
"schema": {
  "type": "object",
  "properties": {
    "billTo": { "$ref": "#/$defs/Address" },
    "shipTo": { "$ref": "#/$defs/Address" }
  },
  "$defs": {
    "Address": {
      "type": "object",
      "properties": { "street": { "type": "string" }, "zip": { "type": "string" } },
      "required": ["street"]
    }
  }
}
```

Validation **rejects** non-local ref forms (external URIs, `#/definitions/…`, `#/properties/…`)
and dangling refs, and **warns** on a `$defs` entry no `$ref` uses or on keywords placed
alongside a `$ref` (which are ignored). To change a shared shape during evolution, upsert its
definition once via `upsertSchemaDefs` — it fans out to every usage.

---

## `constants`

A map of **named immutable values** — a value may be any JSON type (primitive, array, or object).
The whole map is bound as **`$const`** in every JSONata expression the model evaluates (derivations,
metaDerivations, constraints, effects, defaultValues), so you can factor magic numbers and lookup
tables out of expression bodies:

```json
"constants": {
  "vatRate":  0.22,
  "brackets": [ { "upTo": 10000, "rate": 0.1 }, { "upTo": null, "rate": 0.2 } ],
  "limits":   { "maxQty": 99 }
}
```

Reference them with `$const.<name>` navigation: `subtotal * $const.vatRate`,
`$const.brackets[0].rate`, `qty <= $const.limits.maxQty`. A name that is not a simple identifier
(e.g. `odd-name`) triggers a validation **warning** and must be referenced as `$const."odd-name"`.

Constants never change, so they carry **no dependency edge**. A value derived *purely* from constants
would therefore never be re-evaluated (the same as a literal derivation) — reference a constant
**alongside an input field**, or inline the literal, when you need the field populated.

---

## `defaultValues`

A list of `(path, expr)` rules that fill default values into a **newly-created container** — a
new array element, a previously-absent object, or the whole document at creation. When a container
matching `path` is first created during a mutation, `expr` is evaluated and the resulting object is
**deep-merged into the container, filling only fields the caller did not provide** (fill-absent). The
filled fields become ordinary editable base values, and — because they are written before the
reactive pipeline runs — derivations, constraints, and effect triggers see them in the same cycle.

`initialState` (a flat `$.path → value` seed map) has been **removed**; declare a rule with
`path: "$"` instead — its `expr` returns the seed object and fires once when the document is created.
A spec that still carries an `initialState` key is rejected.

| Field | Required | Description |
|---|---|---|
| `path` | yes | Container address: root `$`, an object (`$.customer`), or an array-element pattern (`$.items[*]`). Canonical JSON Path; see [Path notation](../model-spec-format.md#path-notation). |
| `expr` | yes | JSONata expression returning an **object**. A non-object result is ignored. |
| `description` | no | Human-readable note. |

**Expression bindings.** The expression is evaluated against the full document, with:
- `$parent` — the container's JSON-tree parent (the array for a new element, the object for a new
  object, the root for `$`). Use it for sibling context, e.g. `$max($parent.id) + 1` or `$count($parent)`.
- `$self` — the new container as populated by the caller (before defaults), so container-local
  defaults like `$self.qty * $self.unitPrice` are expressible.

```json
"defaultValues": [
  { "path": "$",          "expr": "{ \"loanAmount\": 20000, \"annualRatePercent\": 6, \"termMonths\": 60 }" },
  { "path": "$.items[*]", "expr": "{ \"status\": \"pending\", \"qty\": 1, \"lineNo\": $count($parent) }" }
]
```

Rules fire **once per container creation** — never re-applied to existing containers, and not
fired retroactively on spec evolution. Because a container is detected from the indexed/keyed leaf
paths written, a whole-container *replacement* (writing `$.items` or `$.customer` as one value) does
not trigger per-child defaults for the children it contains; write child leaf paths to seed them.

---
