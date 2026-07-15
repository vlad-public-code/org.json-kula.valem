# Valem Model Spec Format

A **ModelSpec** is the declarative JSON document that describes a Valem model. It is
typically produced by an LLM (via `SpecGenerator`) and then compiled, validated, and executed
by the runtime. The spec is stored verbatim; it is reloaded and recompiled whenever
`valem.persistence-dir` is set.

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
until approved — see [security-model.md](security-model.md) and
`valem.authz.inherited-effects` in [configuration.md](configuration.md). Models can also be
**promoted** between repositories (`POST /models/{id}/promote`) and queried for cross-model topology
(`GET /composition/graph`). The end-to-end lifecycle (branch → approve inherited effects → promote,
plus links between models) is walked through in the
[composition & branching guide](../guides/composition-and-branching.md).

> **No per-field access control.** Valem has no `fieldAccess`/roles. Any caller with model
> access reads/mutates/evolves every field; access is a single coarse gate (`valem.api.key`).
> See [security-model.md](security-model.md).

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
| `path` | yes | Container address: root `$`, an object (`$.customer`), or an array-element pattern (`$.items[*]`). Canonical JSON Path; see [Path notation](#path-notation). |
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
field, the view evaluator hides the corresponding component (see [View Definition](#viewdefinition)).

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

## `effects`

Effect requests the pure core emits as data when a boolean `trigger` becomes `true` after a mutation.
Each effect is executed by a shell selected by `executor`, and **replaces the removed `actions`
section** — a spec that still carries a non-empty `actions` array is rejected with a migration pointer.

- **`executor: "caller"`** — pure, in-core; computes `emit` + `payload` and returns them to the client
  in the mutation response as `dispatchedEffects` (also broadcast over WebSocket). No server egress.
  This is the direct successor to the old `actions`.
- **`executor: "server"`** — the api `HttpEffectExecutor` performs a spec-provided-URL HTTP request
  **asynchronously post-commit**, maps the response via `response.set`, and folds it back as a new
  mutation, driving a `statusPath` `pending→in_flight→applied|failed` machine.
- **`executor: "llm"`** — `LlmEffectExecutor` calls the configured `LlmClient` with a state-derived
  `prompt` (optional `responseSchema` for structured output), parses the JSON completion, and folds it
  back via `response.set` (`$response` bound). Replay never re-calls — the completion is a logged mutation.
- **`executor: "timer"`** — `TimerEffectExecutor` schedules the `response.set` fold-back at a future
  time: absolute `at` (epoch millis or ISO-8601, from state) or relative `afterMs` (delay). The clock
  lives in the shell; `response.set` is evaluated at fire time against current state.
- **`executor: "<plugin>"`** — any other string names a **pluggable** kind supplied by a jar on the
  classpath: a pure `EffectKind` (validate + resolve) discovered via `ServiceLoader`, plus a shell-side
  `EffectExecutor` bean that does the I/O and folds back. Adding a kind needs no core/api edits — just
  the jar. A deployment may restrict the active set with `valem.effects.kinds.enabled` (comma-list;
  unset = every discovered kind enabled); a spec selecting an unknown or disabled kind is rejected at
  validation.

```json
{
  "id":      "notify-on-priority",
  "executor": "caller",
  "trigger": "priorityFlag = true",
  "emit":    "priority-alert",
  "payload": { "score": "$string(sentimentScore)", "category": "issueCategory" }
}
```

### Effect field reference

**Common fields (all executors):**

| Field | Required | Description |
|---|---|---|
| `id` | yes | Unique effect identifier. |
| `executor` | no (default `"server"`) | Which shell runs the effect: `caller`, `server`, `llm`, `timer`, or a plugin kind name. |
| `trigger` | yes | JSONata boolean over the merged document; the effect is considered when it evaluates `true` after a mutation. |
| `dedupeKey` | no | JSONata expression producing the effect's **edge key**: the effect re-fires only when this value *transitions* (edge-triggered, not level-triggered). Also the key for the fold-back compare-and-swap — an in-flight effect whose key changed is superseded or cancelled rather than applied stale. |
| `statusPath` | no | Canonical address of the I/O status sub-document the runtime maintains for this effect (`{phase, key, at, error}`), driving the `pending → in_flight → applied \| failed \| cancelled` state machine and the in-flight guard. |
| `response.set` | server/llm/timer | Map of canonical JSON Path target → JSONata expression; how the executor's result **folds back** into state as an ordinary mutation. For `server` and `llm`, `$response` is bound to the parsed response/completion; for `timer`, the expressions are evaluated at fire time against current state. |

**`executor: "caller"`** — pure, no egress; surfaced as `dispatchedEffects` in the mutation response:

| Field | Required | Description |
|---|---|---|
| `emit` | yes | Event name surfaced to the client. |
| `payload` | no | Map of payload field → JSONata expression over the merged document. |

**`executor: "server"`** — an outbound HTTP request behind the SSRF egress guard. Exactly **one**
locator must be present: `request.url`, a `requests` fan-out, or a composition `target`.

| Field | Required | Description |
|---|---|---|
| `request.method` | no (default `GET`) | HTTP method. |
| `request.url` | one locator | Absolute URL; `{ expr }` segments interpolate JSONata over the merged document. |
| `request.headers` | no | Header map; values interpolate `{ expr }` segments. |
| `request.body` | no | A whole JSONata expression producing the JSON request body. |
| `requests` | one locator | JSONata expression producing an **array** of request descriptors — a fan-out of multiple HTTP calls from one trigger. |
| `target` | one locator | Composition link to another **model** (by coordinate, not URL): write-link `{ ref, path }` plus a sibling `body` (JSONata for the value written at `target.path`), or read-link `{ ref, read }` (no mutation of the target). See the [composition & branching guide](../guides/composition-and-branching.md). |
| `body` | with write-link `target` | JSONata → the value written at `target.path`. |
| `policy` | no | `{ timeoutMs (default 5000), retries (default 0), backoff, egressProfile }` — execution policy for the HTTP call. |

**`executor: "llm"`** — calls the configured `LlmClient` and folds the completion back:

| Field | Required | Description |
|---|---|---|
| `prompt` | yes | JSONata expression producing the prompt text from state. |
| `responseSchema` | no | JSON Schema for structured output; the parsed completion is bound as `$response` in `response.set`. |
| `policy.model` / `policy.temperature` | no | Per-effect override of the configured LLM model/temperature. |

**`executor: "timer"`** — schedules the fold-back; the clock lives in the shell:

| Field | Required | Description |
|---|---|---|
| `at` | one of | JSONata → absolute fire time (epoch millis or ISO-8601), typically read from state. |
| `afterMs` | one of | JSONata → relative delay in milliseconds. |

A spec that names an executor kind that is unknown, or disabled via `valem.effects.kinds.enabled`,
is rejected at validation. Inherited effects carry a read-only, materializer-written `origin`
(`{fromRef, fromOwner}`) recording which ancestor contributed them — the basis for cross-owner
approval (see [security-model.md](security-model.md)).

### Worked `server` + `timer` example

From the bundled `insurance-quote.json`: fetch a live regional rate multiplier whenever the region
changes, and expire a priced quote after 15 minutes.

```json
[
  {
    "id": "fetch-region-rate",
    "executor": "server",
    "trigger": "quote.applicant.region != null",
    "dedupeKey": "quote.applicant.region",
    "request": {
      "method": "GET",
      "url": "https://rates.example.com/regional?region={ quote.applicant.region }"
    },
    "response": { "set": { "$.quote.regionMultiplier": "$response.multiplier" } },
    "statusPath": "$.quote.ioRegionRate"
  },
  {
    "id": "expire-quote",
    "executor": "timer",
    "trigger": "quote.decision = 'quoted' and quote.state = 'quoted'",
    "dedupeKey": "quote.state",
    "afterMs": "900000",
    "response": { "set": { "$.quote.state": "'expired'" } },
    "statusPath": "$.quote.ioExpiry"
  }
]
```

The egress/SSRF controls for `server` effects (`valem.effects.allowed-hosts`,
`allow-private-ips`, `max-response-bytes`, …) are documented in
[security-model.md](security-model.md) and [configuration.md](configuration.md#effects-egress--pluggable-kinds).

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

## `viewDefinition`

The `viewDefinition` is a declarative JSON artifact embedded in `ModelSpec` that describes
component layout, field bindings, visibility rules, and event handlers. It is stored as a
raw `JsonNode` in `ModelSpec` and parsed by the `valem-view` module when a view is
evaluated.

The definition is renderer-agnostic. The built-in renderer (`valem-view-react`) is
one implementation; any client that can parse `EvaluatedView` JSON from the REST or console
endpoint can implement its own renderer.

### Top-level `ViewDefinition`

```json
"viewDefinition": {
  "renderer":    "builtin",
  "defaultView": "main",
  "views": [ ... ]
}
```

| Field | Required | Default | Description |
|---|---|---|---|
| `renderer` | no | `"builtin"` | Reserved for future renderer selection; always `"builtin"` |
| `defaultView` | yes | — | `id` of the view shown when no `viewId` is specified |
| `views` | yes | — | Array of `ViewSpec` objects |

### `ViewSpec`

```json
{
  "id":         "main",
  "label":      "Customer Survey",
  "layout":     "vertical",
  "columns":    null,
  "components": [ ... ],
  "onOpen":     null,
  "onClose":    null
}
```

| Field | Required | Default | Description |
|---|---|---|---|
| `id` | yes | — | Unique view identifier within this model |
| `label` | no | — | Display title for the view |
| `layout` | no | `"vertical"` | Layout mode: `"vertical"`, `"horizontal"`, `"grid"`, `"tabs"`, `"wizard"` |
| `columns` | no | — | Column count for `"grid"` layout |
| `components` | no | `[]` | Array of `ComponentSpec` objects |
| `onOpen` | no | — | `EventHandler` fired when the view becomes active |
| `onClose` | no | — | `EventHandler` fired when the view is navigated away from |

---

## `ComponentSpec`

A flat discriminated-union record. The `type` field identifies the component kind; all
other fields are nullable and interpreted only for the component types that use them.

### Required fields

| Field | Description |
|---|---|
| `id` | Unique component identifier within the view |
| `type` | Component type discriminator (see catalog below) |

### Common fields (all component types)

| Field | Type | Description |
|---|---|---|
| `label` | string | Display label |
| `visible` | bool \| JSONata string \| null | Visibility; null = inherit from `relevant` meta |
| `enabled` | bool \| JSONata string \| null | Interactivity; null = `!readOnly` |
| `readOnly` | bool \| JSONata string \| null | Non-editable; null = inherit from `readOnly` meta |
| `required` | bool \| JSONata string \| null | Mandatory; null = inherit from `required` meta |
| `bind` | `$.path` string | Model field path the component reads from / writes to |
| `placeholder` | string | Input placeholder text |
| `helperText` | string | Helper text shown below the component |
| `tooltip` | string | Tooltip on hover |
| `className` | string \| JSONata string | CSS class name(s), may be a dynamic expression |
| `onChange` | `EventHandler` | Fired when the bound value changes |
| `onOpen` | `EventHandler` | Fired when a sub-panel opens |
| `onClose` | `EventHandler` | Fired when a sub-panel closes |

### Dynamic fields

`visible`, `enabled`, `readOnly`, `required`, and `text` all accept either:
- A JSON boolean (`true` / `false`) — used as-is
- A JSONata string — evaluated against the merged model document
- `null` / absent — falls back to meta cache inheritance (see below)

`className` is declared on `ComponentSpec` (and the TS type) but is currently **dead** — no
evaluator or renderer reads it. Don't rely on it.

### Meta cache inheritance (null defaults)

When a dynamic field is absent or `null`, the view evaluator looks up the meta cache:

| Field | Meta cache key | Absent default |
|---|---|---|
| `visible` | `$.bind#relevant` — `false` means hidden; missing means visible | `true` |
| `readOnly` | `$.bind#read_only` — `true` means read-only; missing means editable | `false` |
| `required` | `$.bind#required` | `false` |
| `enabled` | — | `!effectiveReadOnly` |

This lets `metaDerivations` drive component visibility and interactivity with zero
extra view configuration. For example, if `issueCategory` has a `relevant` metaDerivation
that evaluates to `false` (because `issueEncountered = false`), the component is
automatically hidden — no `visible` expression needed in the view definition.

---

## Component Type Catalog

### Input fields

All input types support `bind`, `label`, `visible`, `enabled`, `readOnly`, `required`,
`placeholder`, `helperText`, `tooltip`, and `onChange`.

#### `textField`

Single-line text input.

```json
{ "id": "nameField", "type": "textField", "label": "Full Name", "bind": "$.name" }
```

#### `textAreaField`

Multi-line text input.

| Extra field | Description |
|---|---|
| `rows` | number of visible rows (integer) |

```json
{
  "id": "detailsField", "type": "textAreaField",
  "label": "Details", "bind": "$.details",
  "rows": 4, "placeholder": "Describe the issue..."
}
```

#### `numericField`

Number input. Min/max constraints are read from the JSON Schema `minimum` / `maximum`
properties of the bound field.

```json
{ "id": "qtyField", "type": "numericField", "label": "Quantity", "bind": "$.qty" }
```

#### `passwordField`

Masked text input.

```json
{ "id": "pwdField", "type": "passwordField", "label": "Password", "bind": "$.password" }
```

#### `emailField`

Email input with built-in format validation.

```json
{ "id": "emailField", "type": "emailField", "label": "Email", "bind": "$.email",
  "placeholder": "you@example.com" }
```

#### `checkboxField`

Boolean checkbox.

```json
{ "id": "termsField", "type": "checkboxField",
  "label": "I agree to the terms", "bind": "$.termsAccepted" }
```

#### `toggleField`

Boolean switch / toggle.

```json
{ "id": "darkMode", "type": "toggleField", "label": "Dark mode", "bind": "$.darkMode" }
```

#### `selectField`

Dropdown. Options come from one of three sources: static list, `optionsExpr`, or `optionsUrl`.
**All three are resolved client-side** by the bundled React renderer — the server-side
`ViewEvaluator` passes `options` through unchanged and never reads `optionsExpr`/`optionsUrl`/
`optionsPath`, so a raw `GET /models/{id}/view` response (or an MCP/console/custom-renderer
consumer) sees only the static `options` list, not resolved dynamic options.

| Extra field | Description |
|---|---|
| `options` | Static `[{value, label}]` list |
| `optionsExpr` | JSONata expression producing `[{value, label}]` from model state (client-evaluated) |
| `optionsUrl` | URL returning a JSON array (fetched client-side) |
| `optionsPath` | JSON path into the `optionsUrl` response to find the `[{value,label}]` array |

```json
{
  "id": "categoryField", "type": "selectField",
  "label": "Category", "bind": "$.category",
  "options": [
    { "value": "product",  "label": "Product" },
    { "value": "service",  "label": "Service" },
    { "value": "shipping", "label": "Shipping" }
  ]
}
```

#### `radioField`

Radio button group. Accepts the same `options` / `optionsExpr` fields as `selectField`.

```json
{
  "id": "ratingField", "type": "radioField",
  "label": "Overall Satisfaction", "bind": "$.overallRating",
  "options": [
    { "value": "1", "label": "1 — Very dissatisfied" },
    { "value": "5", "label": "5 — Very satisfied" }
  ]
}
```

#### `multiSelectField`

Multiple-choice dropdown. Accepts the same `options` / `optionsExpr` / `optionsUrl` /
`optionsPath` fields as `selectField`.

```json
{ "id": "tagsField", "type": "multiSelectField", "label": "Tags", "bind": "$.tags",
  "optionsUrl": "/api/tags", "optionsPath": "$.items" }
```

#### `dateField`

Date picker (date only, no time).

```json
{ "id": "dobField", "type": "dateField", "label": "Date of Birth", "bind": "$.dob" }
```

#### `dateTimeField`

Combined date and time picker.

```json
{ "id": "startField", "type": "dateTimeField", "label": "Start Time", "bind": "$.startAt" }
```

#### `timeField`

Time-only picker (`HH:mm`).

```json
{ "id": "startTimeField", "type": "timeField", "label": "Start Time", "bind": "$.startTime" }
```

#### `sliderField`

Range slider. Mutation is sent on blur/debounce, not on every drag step.

| Extra field | Description |
|---|---|
| `min` | Range minimum |
| `max` | Range maximum |
| `step` | Step increment |

```json
{ "id": "volumeField", "type": "sliderField", "label": "Volume",
  "bind": "$.volume", "min": 0, "max": 100, "step": 5 }
```

#### `fileUploadField`

Uploads a binary file to `POST /blobs` and stores the resulting `BlobRef`
(`{$blobId, $mediaType, $bytes}`) in the bound field.

| Extra field | Description |
|---|---|
| `accept` | MIME type filter, e.g. `"image/*"` |
| `multiple` | Allow multiple files |
| `minFiles` / `maxFiles` | File-count bounds (also resolvable from a `minItems`/`maxItems` metaDerivation on the bind path) |
| `minSize` / `maxSize` | Per-file byte-size bounds (also resolvable from a `minSize`/`maxSize` metaDerivation) |
| `allowedMediaTypes` | Allowed media types (also resolvable from an `allowedMediaTypes` metaDerivation) |

```json
{ "id": "attachmentField", "type": "fileUploadField", "label": "Attachment",
  "bind": "$.attachment", "accept": "image/*" }
```

#### `countrySelector`

Country dropdown pre-populated from `restcountries.com`. No extra configuration required.

```json
{ "id": "countryField", "type": "countrySelector", "label": "Country", "bind": "$.country" }
```

#### `countryRegionSelector`

Region / state / province selector. Depends on the country selected by a sibling
`countrySelector`. The `dependsOn` field must point to the bind path of the sibling.

| Extra field | Description |
|---|---|
| `dependsOn` | Bind path of the `countrySelector` this component reads the country code from |

```json
{
  "id": "regionField", "type": "countryRegionSelector",
  "label": "State / Region", "bind": "$.region",
  "dependsOn": "$.country"
}
```

#### `phoneNumberField`

International phone number input with a built-in country code picker (IDD dial codes from
`restcountries.com`).

```json
{ "id": "phoneField", "type": "phoneNumberField", "label": "Phone", "bind": "$.phone" }
```

---

### Data output

#### `label`

Displays a bound value or a dynamic text expression next to an optional label caption.

| Extra field | Description |
|---|---|
| `text` | String literal or JSONata expression; overrides the bound value for display |

```json
{
  "id": "totalLabel", "type": "label",
  "label": "Grand Total", "bind": "$.total"
}
```

```json
{
  "id": "greetingLabel", "type": "label",
  "text": "'Hello, ' & name & '!'"
}
```

#### `staticText`

Non-reactive text block. `text` is a static markdown or HTML literal; no binding.

```json
{ "id": "intro", "type": "staticText",
  "text": "Please fill in the form below. All fields marked * are required." }
```

#### `badge`

Status indicator chip.

| Extra field | Description |
|---|---|
| `text` | String literal or JSONata expression for the badge label |
| `variant` | `"primary"`, `"secondary"`, `"success"`, `"warning"`, `"danger"` |

```json
{
  "id": "statusBadge", "type": "badge", "label": "Priority",
  "bind": "$.priorityFlag",
  "text":    "priorityFlag = true ? 'Needs follow-up' : 'Normal'",
  "variant": "danger"
}
```

> **Known gap:** unlike `text`, `variant` is a plain string on `ComponentSpec` — the server-side
> `ViewEvaluator` passes it through unevaluated, it is not JSONata-capable server-side. The bundled
> React renderer masks this by re-evaluating `variant` as JSONata client-side, so a JSONata `variant`
> expression only resolves correctly through the built-in UI, not through the raw
> `GET /models/{id}/view` (or MCP/console) response.

#### `separatorLine`

Horizontal rule / divider. No extra fields.

```json
{ "id": "sep1", "type": "separatorLine" }
```

#### `progressBar`

Numeric value rendered as a filled bar.

| Extra field | Description |
|---|---|
| `min` | Range minimum |
| `max` | Range maximum |
| `showValue` | Display a numeric label alongside the bar |
| `format` | `"percent"` (default) or `"value"` (renders `75 / 100`) |

```json
{ "id": "uploadProgress", "type": "progressBar", "label": "Upload",
  "bind": "$.uploadPercent", "min": 0, "max": 100, "format": "percent" }
```

#### `dataTable`

Tabular view of an array field.

| Extra field | Description |
|---|---|
| `tableColumns` | Array of `ColumnSpec` objects defining column layout |
| `pageSize` | Rows per page (integer); omit for no pagination |

```json
{
  "id": "itemsTable", "type": "dataTable",
  "label": "Order Items", "bind": "$.items",
  "tableColumns": [
    { "field": "name",      "header": "Product",    "width": "40%" },
    { "field": "qty",       "header": "Qty",        "width": "15%" },
    { "field": "price",     "header": "Unit Price", "format": "currency", "width": "20%" },
    { "field": "lineTotal", "header": "Line Total", "format": "currency", "width": "25%" }
  ],
  "pageSize": 20
}
```

**`ColumnSpec`**

| Field | Description |
|---|---|
| `field` | Property name within each array element |
| `header` | Column header label |
| `format` | Optional display format: `"currency"`, `"number"`, `"percent"` |
| `width` | CSS width string (e.g. `"40%"`, `"120px"`) |

#### `dataChart`

Chart backed by [Recharts](https://recharts.org/).

| Extra field | Description |
|---|---|
| `chartType` | `"bar"`, `"line"`, `"area"`, `"pie"` |
| `chartX` | Property name used for the X axis (category key) |
| `chartSeries` | Array of `ChartSeriesSpec` objects |

```json
{
  "id": "salesChart", "type": "dataChart",
  "label": "Monthly Sales", "bind": "$.monthlySales",
  "chartType": "bar",
  "chartX": "month",
  "chartSeries": [
    { "field": "revenue", "label": "Revenue", "color": "#4f46e5" },
    { "field": "cost",    "label": "Cost",    "color": "#ef4444" }
  ]
}
```

**`ChartSeriesSpec`**

| Field | Description |
|---|---|
| `field` | Property name in each data point object |
| `label` | Legend label |
| `color` | CSS color string |

---

### Aggregates

#### `group`

Layout container. Components inside are rendered according to the `layout` setting.

| Extra field | Description |
|---|---|
| `layout` | `"vertical"`, `"horizontal"`, `"grid"` |
| `columns` | Column count for `"grid"` layout |
| `components` | Nested `ComponentSpec` array |

```json
{
  "id": "summaryGroup", "type": "group",
  "layout": "horizontal",
  "components": [
    { "id": "scoreLabel", "type": "label", "label": "Score", "bind": "$.score" },
    { "id": "statusBadge", "type": "badge", "text": "status" }
  ]
}
```

#### `fieldSet`

HTML `<fieldset>` with a legend caption. Functionally equivalent to `group`.

| Extra field | Description |
|---|---|
| `legend` | Text displayed as the fieldset legend |
| `components` | Nested `ComponentSpec` array |

```json
{
  "id": "contactGroup", "type": "fieldSet",
  "legend": "Contact Information",
  "components": [
    { "id": "emailField", "type": "emailField", "bind": "$.email" }
  ]
}
```

#### `sectionList`

Displays an array field with add/remove controls. Each item is edited in a separate view
identified by `itemView`.

| Extra field | Description |
|---|---|
| `itemView` | `id` of the `ViewSpec` used to edit a single array element |
| `canAdd` | bool or JSONata expression controlling whether Add is available |
| `canRemove` | bool or JSONata expression controlling whether Remove is available |
| `addLabel` | Label for the Add button (default `"Add"`) |
| `removeLabel` | Label for the Remove button (default `"Remove"`) |

```json
{
  "id": "itemsList", "type": "sectionList",
  "label": "Order Items", "bind": "$.items",
  "itemView": "item-editor",
  "canAdd": true, "canRemove": true,
  "addLabel": "Add Item", "removeLabel": "Remove"
}
```

#### `sectionItem`

Single-element editor for use inside a view named by a `sectionList`'s `itemView`. The
`bind` points to the specific array element path; `components` describes its fields.

| Extra field | Description |
|---|---|
| `components` | Nested `ComponentSpec` array for the element's fields |

```json
{
  "id": "itemEditor", "type": "sectionItem",
  "bind": "$.items[0]",
  "components": [
    { "id": "nameField",  "type": "textField",    "label": "Product Name", "bind": "$.items[0].name" },
    { "id": "priceField", "type": "numericField",  "label": "Price",        "bind": "$.items[0].price" }
  ]
}
```

---

### Actions

#### `button`

Clickable button.

| Extra field | Description |
|---|---|
| `variant` | String or JSONata: `"primary"`, `"secondary"`, `"danger"`, `"ghost"` |
| `icon` | Icon name string (renderer-specific) |
| `onClick` | `EventHandler` fired on click |

```json
{
  "id": "submitBtn", "type": "button",
  "label": "Submit", "variant": "primary",
  "onClick": {
    "mutations": "{'$.status': 'submitted'}",
    "navigate": "confirmation"
  }
}
```

#### `menu`

Navigation menu linking to other views.

| Extra field | Description |
|---|---|
| `menuItems` | Array of `MenuItemSpec` objects |
| `orientation` | `"horizontal"` or `"vertical"` |

```json
{
  "id": "sideNav", "type": "menu",
  "orientation": "vertical",
  "menuItems": [
    { "label": "Overview",  "targetView": "overview" },
    { "label": "Details",   "targetView": "detail",  "icon": "list" },
    { "label": "Charts",    "targetView": "charts",  "icon": "chart" }
  ]
}
```

**`MenuItemSpec`**

| Field | Description |
|---|---|
| `label` | Display text |
| `targetView` | `id` of the `ViewSpec` to navigate to |
| `icon` | Optional icon name (renderer-specific) |

---

## `EventHandler`

Defines what happens when a UI event fires. Used by `onClick`, `onChange`, `onOpen`,
`onClose` on `ComponentSpec`, and `onOpen`, `onClose` on `ViewSpec`.

```json
{
  "mutations": "{'$.status': 'submitted', '$.submittedAt': $now()}",
  "navigate":  "confirmation"
}
```

| Field | Description |
|---|---|
| `mutations` | JSONata expression evaluated against the model state; result must be a `{"$.path": value}` map |
| `navigate` | View `id` to activate after mutations are applied |

Both fields are optional (either, neither, or both may be present).

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

**View tiers** (see [view-system.md](view-system.md))

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
silently skipped validation — such specs are now rejected loudly. See the [`schema`](#schema)
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

## `EvaluatedView` — REST / Console response

`GET /models/{id}/view` and `GET /models/{id}/view/{viewId}` return an `EvaluatedView`
with all dynamic expressions already resolved. This is the renderer-agnostic contract; any
client (React, Angular, mobile, CLI) can consume it.

`EvaluatedComponent` is a **sealed interface**, not a flat record — each component type
serializes as one of 17 concrete records (`EvaluatedBasicInput`, `EvaluatedTextArea`,
`EvaluatedSelectField`, `EvaluatedDependentSelector`, `EvaluatedSlider`, `EvaluatedFileUpload`,
`EvaluatedLabel`, `EvaluatedStaticText`, `EvaluatedBadge`, `EvaluatedProgressBar`,
`EvaluatedDataTable`, `EvaluatedDataChart`, `EvaluatedContainer`, `EvaluatedSectionList`,
`EvaluatedButton`, `EvaluatedMenu`, `EvaluatedSeparatorLine`) carrying only the fields relevant
to that type (`@JsonInclude(NON_NULL)`); the JSON below shows the union of possible fields, not
a shape any single component actually emits in full.

```json
{
  "modelId":    "customer-satisfaction-survey",
  "viewId":     "main",
  "title":      "Survey",
  "layout":     "vertical",
  "components": [
    {
      "id":       "ratingField",
      "type":     "radioField",
      "label":    "Overall Satisfaction",
      "visible":  true,
      "enabled":  true,
      "readOnly": false,
      "required": true,
      "bind":     "$.overallRating",
      "value":    5,
      "options": [
        { "value": "1", "label": "1 — Very dissatisfied" },
        { "value": "5", "label": "5 — Very satisfied" }
      ],
      "placeholder": null,
      "helperText":  null,
      "tooltip":     null,
      "text":        null,
      "components":  null,
      ...
    }
  ]
}
```

### `EvaluatedComponent` fields

| Field | Type | Description |
|---|---|---|
| `id` | string | Component identifier |
| `type` | string | Component type |
| `label` | string | Resolved display label |
| `visible` | boolean | Whether the component should be shown |
| `enabled` | boolean | Whether the component is interactive |
| `readOnly` | boolean | Whether the component is non-editable |
| `required` | boolean | Whether the field is mandatory |
| `bind` | string | Bound model path |
| `value` | JSON value | Current value from the merged model document |
| `placeholder` | string | Placeholder text |
| `helperText` | string | Helper text |
| `tooltip` | string | Tooltip |
| `options` | `[{value, label}]` | Resolved options list |
| `text` | string | Resolved text (for `label`, `staticText`, `badge`) |
| `components` | `EvaluatedComponent[]` | Resolved sub-components (for aggregates) |
| `tableColumns` | `ColumnSpec[]` | Column definitions (for `dataTable`) |
| `pageSize` | integer | Rows per page (for `dataTable`) |
| `chartType` | string | Chart type (for `dataChart`) |
| `chartX` | string | X-axis field (for `dataChart`) |
| `chartSeries` | `ChartSeriesSpec[]` | Series definitions (for `dataChart`) |
| `menuItems` | `MenuItemSpec[]` | Navigation items (for `menu`) |
| `orientation` | string | Layout orientation (for `menu`) |
| `variant` | string | Visual variant (for `button`, `badge`); **unevaluated raw string for `badge`** — see the `badge` known-gap note above |
| `icon` | string | Icon name |
| `min` / `max` | number | Range bounds (for `sliderField`, `progressBar`) |
| `step` | number | Step increment (for `sliderField`) |
| `accept` | string | MIME filter (for `fileUploadField`) |
| `multiple` | boolean | Allow multiple files (for `fileUploadField`) |
| `minFiles` / `maxFiles` | integer | File-count bounds (for `fileUploadField`) |
| `minSize` / `maxSize` | number | Per-file byte-size bounds (for `fileUploadField`) |
| `allowedMediaTypes` | string | Allowed media types (for `fileUploadField`) |
| `showValue` | boolean | Display numeric label alongside the bar (for `progressBar`) |
| `format` | string | `"percent"` or `"value"` (for `progressBar`) |
| `onClick` | `EventHandler` | Click handler (passed through unevaluated for client execution) |
| `onChange` | `EventHandler` | Change handler |
| `onOpen` | `EventHandler` | Open handler |
| `onClose` | `EventHandler` | Close handler |

**Note:** `EventHandler` objects (`onClick`, `onChange`, etc.) in `EvaluatedComponent` are
passed through unevaluated — they contain the raw `mutations` JSONata string and `navigate`
id. The client renderer executes them locally when the event fires.

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
