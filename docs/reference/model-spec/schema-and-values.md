---
title: Schema, constants, library & defaults
parent: Model spec format
grandparent: Reference
nav_order: 1
description: "The base document shape, named constants, the shared JSONata library, and the rules that seed newly-created containers."
---

# `schema`, `constants`, `library`, `defaultValues`

The writable half of a model: the shape of the base document, the immutable values and shared
functions every expression can read, and what fills a container the moment it is created.

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

## `library`

Named **JSONata functions** (and derived values) callable from every expression in the model as
`$name(...)`. Where [`constants`](#constants) shares *values*, a library shares *computation* —
through the same binding seam, so it reaches derivations, metaDerivations, constraints,
defaultValues, effects, embedded tests, and view expressions alike.

Use one when the same calculation shape appears in **three or more** expressions — a bracket walk, a
proration, a rounding convention. Do not wrap a one-line expression in a function: `$total()` is
worse than `price * qty`.

```json
"library": {
  "description": "Money rounding and the excess-over-threshold rule.",
  "define": "( $money := function($n) { $round($n, 2) }; $excessOver := function($value, $threshold) { ( $x := $value - $threshold; $money($x > 0 ? $x : 0) ) }; [\"money\", \"excessOver\"] )"
}
```

A bare string is shorthand for `{ "define": ... }`.

### The definition expression

`define` is **plain JSONata** — no dialect extension. It binds names and **returns the list of names
to export** as its last value, so evaluating it in any JSONata engine simply yields
`["money", "excessOver"]`. Names it binds but does not export (`$x` above, or any helper) stay
internal and remain reachable from the exported functions; mutual recursion between exports works.

An export that evaluates to a function is callable as `$name(...)` and usable as a **value**
(`$map(items, $money)`, `total ~> $money`). An export that evaluates to anything else is a value,
computed **once**, when the library is compiled.

### A library cannot read the model document

This is the one rule to internalise. A library function computes **only from its arguments and
`$const`**. A field name inside a function body always evaluates to *nothing* — the runtime never
roots a library's closures at the calling expression's document.

```jsonc
// WRONG — returns nothing, and is rejected at validation
"library":     "( $netTotal := function() { order.subtotal - order.discount }; [\"netTotal\"] )",
"derivations": [ { "path": "$.total", "expr": "$netTotal()" } ]

// RIGHT — the document values are passed in at the call site
"library":     "( $netTotal := function($sub, $disc) { $sub - $disc }; [\"netTotal\"] )",
"derivations": [ { "path": "$.total", "expr": "$netTotal(order.subtotal, order.discount)" } ]
```

That restriction is what keeps the reactive graph correct. `ExpressionPathExtractor` cannot see
inside a callee, so a library that could read the document would create dependencies nothing
recorded, and the derived value would go silently stale. Because every document value arrives as an
argument, the field names stay in the *derivation's own* expression, where the extractor sees them
and produces edges exactly as if the logic were inlined.

### Rules the validator enforces

| Rule | Severity |
|---|---|
| The definition compiles and exports at least one name | ERROR |
| No reference to the model document (`order.total`, `$`, `$$`, `**`, `*`) | ERROR |
| No export named after a JSONata built-in (`$sum`, `$round`, …) — the parser resolves built-ins first, so it could never be called | ERROR |
| No export colliding with a name Valem binds: `$const`, `$parent`, `$self`, `$response`, `$now`, `$status` | ERROR |
| Export names are simple identifiers (a bound function name has no quoted form) | ERROR |
| No `$now` / `$millis` / `$random` in a definition — it runs once, at compile time, so the value would be frozen into every call | ERROR |
| Definition ≤ `valem.limits.library-max-chars`; exports ≤ `valem.limits.library-max-exports` | ERROR / WARNING |

Every `$name` used anywhere in the spec is also resolved against the built-ins, the library's
exports, and Valem's own bindings: an unresolvable **call** is an ERROR (it would fail at runtime and
leave a blank field), and an unresolvable **value reference** is a WARNING (an unbound variable is
legal JSONata that evaluates to *undefined*).

### Syntax gotcha

A lambda body is a **single expression**. A multi-statement body must be parenthesised:

```jsonata
function($x) { $a := $x * 2; $a }      // SYNTAX ERROR
function($x) { ( $a := $x * 2; $a ) }  // correct
```

### `$const` inside a library

`$const` works inside a definition, and resolves against the **calling** evaluation — so a library
function reading `$const.vatRate` sees the constants of whichever model calls it. Every evaluation
path Valem drives binds `$const`, so this is transparent in practice.

An exported *value* is different: it is computed when the library is compiled, against the constants
in force then. `( $maxRate := $max($const.brackets.rate); ["maxRate"] )` freezes one number.

### Layers

Internally a library is an ordered list of **layers**. A model you author has exactly one; a branch
that inherits a template's library gains its ancestor's layers first. Layers bind in order and a
later layer wins a name collision, so a branch can both call and override an inherited function. Only
your own layer is editable — see [tests-and-evolution.md](tests-and-evolution.md#newlibrary).

Inspect the merged vocabulary at runtime with `GET /models/{id}/library` (or the `get_library` MCP
tool): every export with its kind, signature, arity, and originating layer.

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
