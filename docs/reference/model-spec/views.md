---
title: View definition & components
parent: Model spec format
grandparent: Reference
nav_order: 5
description: "ViewDefinition, ViewSpec, ComponentSpec, event handlers, and the EvaluatedView response."
---

# `viewDefinition`

The UI tree a spec can carry: views, their layout, and the components inside them — plus the
evaluated form a renderer receives back. The per-type field lists live in the
[component catalog](component-catalog.md); for the engine and the React renderer see the
[view system reference](../view-system.md).

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
| `onChange` | `EventHandler` | Fired when the bound value changes |
| `onOpen` | `EventHandler` | Fired when a sub-panel opens |
| `onClose` | `EventHandler` | Fired when a sub-panel closes |

### Dynamic fields

`visible`, `enabled`, `readOnly`, `required`, and `text` all accept either:
- A JSON boolean (`true` / `false`) — used as-is
- A JSONata string — evaluated against the merged model document
- `null` / absent — falls back to meta cache inheritance (see below)

`className` is **dead** — no evaluator or renderer reads it. It is no longer modelled on the
`ComponentSpec` hierarchy in Java or in the TypeScript mirror. A `className` in an existing spec
is still stored and served verbatim, but nothing acts on it. Don't rely on it.

Not every common field applies to every type — each component type binds to the `ComponentSpec`
record carrying the fields it uses, so e.g. a `separatorLine` has no `placeholder` and a `badge`
has no `enabled`. See the record table in
[view-system.md](../view-system.md#viewmodel-package) for the exact per-type field sets.

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
