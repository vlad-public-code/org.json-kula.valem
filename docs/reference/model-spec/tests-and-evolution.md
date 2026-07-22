---
title: Tests & spec evolution
parent: Model spec format
grandparent: Reference
nav_order: 4
description: "Embedded test cases, and the SpecEvolution diff format including targeted section diffs."
---

# `tests` and spec evolution

How a spec proves itself, and how it changes without losing live state.

---

## `tests`

Embedded test cases. Each test sets a given state and asserts expected derived values or
meta values. Tests are executed by `ModelRuntime` during spec validation and can be run
via the `mvn test` build.

```json
{
  "description": "high rating with no issues — high score, no priority flag",
  "given": {
    "$.overallRating":    5,
    "$.wouldRecommend":   true,
    "$.issueEncountered": false
  },
  "expect": {
    "$.sentimentScore": 100,
    "$.priorityFlag":   false
  }
}
```

| Field | Required | Description |
|---|---|---|
| `description` | no | Human-readable label for the test case |
| `given` | yes | Map of `$.path → value` mutations to apply before asserting |
| `expect` | yes | Map of `$.path → expected value`; values can be concrete or meta assertion objects |

### Meta assertions in `expect`

An expected value may be a `{"$meta": {...}}` object to assert meta cache values rather
than base/derived field values:

```json
"expect": {
  "$.issueCategory": { "$meta": { "relevant": false } },
  "$.contactEmail":  { "$meta": { "relevant": true,  "readOnly": true } }
}
```

---

## Spec Evolution (`POST /models/{id}/spec/evolve`)

A `SpecEvolution` document applies incremental changes to an existing spec. Each section
supports independent `upsert*` and `remove*` operations. The evolved spec is validated and
recompiled; if validation fails the request is rejected with `422`.

```json
{
  "newVersion":            "2.0.0",
  "newSchema":             { ... },
  "newViewDefinition":     { ... },
  "upsertDerivations":     [ { "path": "$.vat", "expr": "total * 0.2" } ],
  "removeDerivations":     ["$.oldField"],
  "upsertConstraints":     [ { "id": "c1", "expr": "qty > 0", "message": "...", "policy": "flag" } ],
  "removeConstraints":     ["oldConstraint"],
  "upsertEffects":         [ ... ],
  "removeEffects":         ["oldEffect"],
  "upsertMetaDerivations": [ ... ],
  "removeMetaDerivations": ["$.field#relevant"],
  "upsertDefaultValues":   [ { "path": "$.items[*]", "expr": "{ \"qty\": 1 }" } ],
  "removeDefaultValues":   ["$.customer"],
  "newConstants":          { "vatRate": 0.25 },
  "backfill":              { "$.shipping": 0 }
}
```

| Field | Description |
|---|---|
| `newVersion` | Replace the version string |
| `newSchema` | Replace the entire JSON Schema |
| `newViewDefinition` | Replace the entire view definition; if absent, existing view is preserved |
| `upsertDerivations` | Add or replace derivations matched by `path` |
| `removeDerivations` | Remove derivations by `path` |
| `upsertConstraints` | Add or replace constraints matched by `id` |
| `removeConstraints` | Remove constraints by `id` |
| `upsertEffects` | Add or replace effects matched by `id` |
| `removeEffects` | Remove effects by `id` |
| `upsertMetaDerivations` | Add or replace meta derivations matched by `path#property` key |
| `removeMetaDerivations` | Remove meta derivations by `path#property` key |
| `upsertDefaultValues` | Add or replace default-value rules matched by `path` |
| `removeDefaultValues` | Remove default-value rules by `path` |
| `newConstants` | Replace the entire `constants` map (omit to keep existing) |
| `backfill` | `$.path → value` map applied to the existing instance for new fields it lacks |
| `expectedVersion` | Optimistic-concurrency precondition — apply only if the live version still equals this; otherwise `409` |

All fields are optional. An evolution with only `{"newVersion": "2.0.0"}` updates only the
version and leaves all other sections unchanged.

### Targeted schema, view, and constants diffs

`newSchema`, `newViewDefinition`, and `newConstants` each replace a whole section — safe for
restructuring, but at scale (a large schema, a multi-view UI) resending the entire section is
error-prone (an LLM may silently drop siblings) and wasteful. Each of those three sections also
supports **targeted diffs**. Within one evolution the wholesale field and its diff fields are
**mutually exclusive** (`newSchema` XOR the schema diffs, etc.); different sections may combine.

**Schema tiers**

| Field | Description |
|---|---|
| `upsertSchemaDefs` | Map of `$defs` name → definition schema; replaces (or adds) that definition wholesale. One edit fans out to every `$ref` usage. |
| `removeSchemaDefs` | Drop `$defs` entries by name. Rejected if the definition is still referenced (the referencing locations are listed). |
| `upsertSchemaNodes` | `[{ path, schema, required? }]` — replace the node at a **canonical data path** (e.g. `$.order.items[*].qty`) wholesale; create intermediate containers when the path is new. `required` (tri-state) adds/removes the field from its parent's `required` list. A path may not traverse a `$ref` — edit the shared definition instead. |
| `removeSchemaNodes` | Drop nodes by canonical data path (also removes the parent `required` entry). |

**View tiers** (see [view-system.md](../view-system.md))

| Field | Description |
|---|---|
| `newDefaultView` | Set `viewDefinition.defaultView`. |
| `upsertViews` / `removeViews` | Add/replace/remove a whole view by its `id`. |
| `upsertComponents` | `[{ viewId, component, parentId?, beforeId? }]` — replace a component in place (by id), or place/move it: `parentId` (absent = view root) and `beforeId` (absent = append). |
| `removeComponents` | `[{ viewId, componentId }]` — remove a component and its subtree. |

**Constants tiers**

| Field | Description |
|---|---|
| `upsertConstants` | Map of name → value; replaces (or adds) each named constant wholesale. |
| `removeConstants` | Drop constants by name. Rejected if a `$const.<name>` reference is found in any expression (textual scan; dynamic `$const[...]` access is not detected). |

Note the near-homophone pair: `upsertConstants` changes named **values**; `upsertConstraints`
changes boolean **invariants**.

**JSON Schema `$defs`/`$ref`.** The engine resolves **local** definition references of the form
`{"$ref": "#/$defs/<Name>"}` (lazily, so recursive types work). Non-local ref forms (external
URIs, `#/definitions/…`, `#/properties/…`) and dangling refs are rejected at validation; a
definition no `$ref` uses produces a warning. Before this support, fields behind a `$ref`
silently skipped validation — such specs are now rejected loudly. See the [`schema`](schema-and-values.md#schema)
section.

**Data migration with `backfill`.** When an evolution adds a (possibly required) base field, existing
instances would otherwise have no value for it — leaving derivations that read it computing against
`null`. The optional `backfill` map seeds those fields: for each `$.path`, if the instance does
**not** already have a value there, the value is written before re-derivation (existing values are
never overwritten). Backfill targets must be base (writable) fields and satisfy the new schema. After
an evolution, all EAGER derivations are recomputed, so derivations added by the evolution are
populated immediately without requiring a subsequent mutation.

**State-compatibility guard.** When an evolution changes the schema, the carried-forward
(post-backfill) state is validated against the new schema. If a change would strand existing
values (e.g. retyping `number → string` while the document still holds numbers), the evolution is
rejected with `422` listing the incompatible paths — rather than committing state that fails its
own schema. `backfill` only fills *absent* fields, so it cannot convert an existing value; that is
out of scope (a `migrate` transform is a planned follow-up).

---
