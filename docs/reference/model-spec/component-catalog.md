---
title: Component type catalog
parent: Model spec format
grandparent: Reference
nav_order: 6
description: "Every built-in component type and the fields it accepts, grouped by input, display, data, container, and action."
---

# Component type catalog

Every built-in `type` and the fields it accepts. Common fields (`id`, `bind`, `visible`,
`enabled`, `readOnly`, `required`) are described once in
[View definition & components](views.md); this page lists what each type adds.

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

#### `currencyField` / `percentField`

`numericField` plus a display convention. The bound value stays a plain number — these change
what the input shows beside the box, never what the model stores, so swapping one spelling for
another cannot alter a derivation or a constraint.

| Extra field | Description |
|---|---|
| `format` | `"currency"`, `"percent"`, `"value"`; defaults from the type |
| `currency` | ISO-4217 code (`"EUR"`); read only when the format is `currency` |

`percent` appends a `%` and does **not** rescale: a stored `7.5` shows as `7.5%`. A model whose
derivation already computes `rate * 100` would otherwise display `750%`.

```json
{ "id": "premiumField", "type": "currencyField", "label": "Annual premium",
  "bind": "$.premium", "currency": "EUR" }
```

#### `richTextField`

`textAreaField` with a markdown toolbar. The value stored is markdown, not HTML — so it stays
something a derivation can read, a constraint can measure and a diff can show, with no
sanitising step between the model and storage.

| Extra field | Description |
|---|---|
| `rows` | Visible rows (integer) |
| `toolbar` | `"basic"` (default), `"full"`, or `"none"` |

#### `autocompleteField` / `comboBox`

A `selectField` that filters as you type — what a select degrades into past a few dozen options.
Same `options` / `optionsExpr` / `optionsUrl` / `optionsPath` contract.

| Extra field | Description |
|---|---|
| `allowCustom` | Accept a value outside `options`. Defaults **true** for `comboBox`, false otherwise |

`allowCustom` is a renderer affordance, not a validation rule: the schema still decides what the
model accepts, so a custom value can be rejected on mutate like any other.

```json
{ "id": "cityField", "type": "autocompleteField", "label": "City",
  "bind": "$.city", "optionsExpr": "cities.{ 'value': code, 'label': name }" }
```

#### `tagsField`

An array of scalars edited as chips. Writes the whole array at the bound path, so derivations see
one change per edit rather than one per element. Before this, a `string[]` needed a full
`sectionList`.

| Extra field | Description |
|---|---|
| `options` | Optional suggestions; `allowCustom` defaults **true** |

```json
{ "id": "tagsField", "type": "tagsField", "label": "Tags", "bind": "$.tags" }
```

#### `dateRangeField`

Two coupled dates written to **two separate paths**.

| Extra field | Description |
|---|---|
| `bindFrom` / `bindTo` | Paths for the two ends — these carry the value, not `bind` |
| `fromLabel` / `toLabel` | Captions (default `"From"` / `"To"`) |
| `minDate` / `maxDate` | Picker bounds (authored hints) |

Two paths rather than one object bind, because the ends almost always already have their own
derivations and constraints — `endDate >= startDate` is a constraint, not a widget rule. Each end
mutates independently so the audit record shows which one the user actually changed. `bind`, if
set, is only the meta-inheritance anchor.

```json
{
  "id": "coverPeriod", "type": "dateRangeField", "label": "Cover period",
  "bindFrom": "$.startDate", "bindTo": "$.endDate", "minDate": "2026-01-01"
}
```

#### `ratingField` / `numericStepper`

The same `min` / `max` / `step` as `sliderField`, drawn as stars or as −/+ buttons.

| Extra field | Description |
|---|---|
| `min` / `max` / `step` | Range and increment (`ratingField` defaults to 1–5 step 1) |

Unlike the slider these commit on click: there is no drag to wait out, so dependent derivations
recompute immediately.

```json
{ "id": "satisfaction", "type": "ratingField", "label": "How satisfied?",
  "bind": "$.rating", "min": 1, "max": 5 }
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

Non-reactive text block. `text` is a literal or a JSONata expression; no binding.

| Extra field | Description |
|---|---|
| `text` | Literal or JSONata expression |
| `format` | `"html"` (default), `"markdown"`, or `"text"` |

`html` is the default so existing specs are unaffected, but it is the one mode that injects its
content **unescaped** — the wrong choice for anything a user typed. `markdown` is the read half of
`richTextField`: it escapes the source first and then applies the subset that field's toolbar
produces, so a stored `<script>` renders as visible text rather than executing. `text` shows the
content verbatim.

```json
{ "id": "intro", "type": "staticText",
  "text": "Please fill in the form below. All fields marked * are required." }
```

```json
{ "id": "notesOut", "type": "staticText", "format": "markdown", "text": "$.notes" }
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
| `format` | `"currency"`, `"percent"`, `"number"`, `"integer"` |
| `currency` | ISO-4217 code. Per **column**, since a table legitimately holds more than one; without it a `currency` format falls back to the renderer's default |
| `width` | CSS width string (e.g. `"40%"`, `"120px"`) |

Formatting is shared with `keyValueList` and `statTile`, so a column, a summary row and a tile over
the same field always read the same. In particular `percent` **appends a sign and does not
rescale** — a stored `7.5` shows as `7.5%`, not `750%`.

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

#### `alert` / `callout`

The block-level spelling of `badge`: same `label` (heading), `text` (body) and `variant`
(severity). `danger` and `warning` render as `role="alert"`, so the problem is announced and not
only coloured.

```json
{ "id": "underwritingNotice", "type": "alert", "variant": "warning",
  "label": "Referral required", "text": "$.premium > $const.autoApproveLimit ? 'Manual review' : ''" }
```

#### `spacer`

`separatorLine` without the rule.

| Extra field | Description |
|---|---|
| `size` | Gap in pixels (default 16) |

#### `image`

| Extra field | Description |
|---|---|
| `src` | Literal URL or JSONata expression; falls back to the bound value |
| `alt` | Alternative text — supply it; an image without one is unreadable to a screen reader |
| `width` / `height` / `fit` | CSS sizing and `object-fit` |

A bound value is usually what a `fileUploadField` wrote — a `BlobRef` of shape
`{$blobId, $mediaType, $bytes}` — which the renderer resolves to `/blobs/{id}`. That makes an
upload and its preview two components over one path.

#### `link`

| Extra field | Description |
|---|---|
| `href` | Literal URL or JSONata expression; falls back to the bound value |
| `text` | Link caption (literal or expression); defaults to `label`, then `href` |
| `target` | `"_blank"` for a new tab — `rel="noopener noreferrer"` is added automatically |
| `icon` | Optional leading icon |

For navigation **inside** the model use a `button` or `menu` with a `navigate` handler, which
switches view without leaving the page or discarding unsaved edits.

#### `sparkline`

A `dataChart` stripped of axes, grid, legend and tooltip so it can sit inline beside a number.
Same `bind` / `chartType` / `chartSeries`; only the **first** series is drawn — a sparkline with
two series needs a legend, and once it has one it is a `dataChart`.

#### `gauge`

A `progressBar` drawn as a 180° arc. Same `min`, `max`, `showValue`, `format`.

#### `keyValueList` / `summaryList`

A read-only caption/value list — the review step of a form, and the shape a derivation-heavy
model most often ends on. Rows come from `items`, **not** from `bind`.

| Extra field | Description |
|---|---|
| `items` | Array of `KeyValueItemSpec` |
| `columns` | Caption/value column pairs per row (default 1) |

**`KeyValueItemSpec`**

| Field | Description |
|---|---|
| `label` | Row caption |
| `bind` | Path to read the value from |
| `text` | JSONata expression, for a row with no single path behind it |
| `format` | `"currency"`, `"percent"`, `"number"`, `"integer"` |
| `currency` | ISO-4217 code. Per **row**, because a summary legitimately mixes them — a quoted price beside its converted equivalent. A `currency` format with no code falls back to the renderer's default |

When both `bind` and `text` are set, **`bind` wins** — a row never shows two different things
depending on which field the reader looks at. Rows are resolved server-side, so a non-browser
consumer of `GET /models/{id}/view` gets a printable summary without evaluating anything.

```json
{
  "id": "quoteSummary", "type": "summaryList", "label": "Your quote",
  "items": [
    { "label": "Annual premium", "bind": "$.premium", "format": "currency" },
    { "label": "Excess",         "bind": "$.excess",  "format": "currency" },
    { "label": "Reference",      "bind": "$.quoteRef" }
  ]
}
```

#### `statTile` / `metric`

One headline number with its supporting text.

| Extra field | Description |
|---|---|
| `bind` | Path to the number, or … |
| `value` | … a JSONata expression, when no single path holds it |
| `delta` | Expression for the movement line |
| `caption` | Expression for the sub-caption |
| `trend` | `"up"`, `"down"`, `"flat"`, or an expression producing one |
| `format` / `currency` | As `currencyField` |
| `variant`, `icon`, `tooltip` | Presentation |

`trend` is authored separately from the sign of `delta` on purpose: whether a rising number is
good news is a domain question — spend up is bad, savings up is good — that only the spec knows.
Unset means the delta is shown without a verdict.

#### `jsonViewer`

The bound subtree as formatted JSON. Bind to `$` for the whole **merged** document — base fields
with derived values already spliced in, which is what expressions actually evaluate against.

| Extra field | Description |
|---|---|
| `collapsed` | Initial fold state (bool or expression) |
| `maxDepth` | Truncate deeper nodes to `…` |

#### `explainPanel` / `auditTimeline`

Valem's two path-scoped record trails: `explainPanel` shows *why* a field holds its value
(`GET /models/{id}/explain/{path}` — the live derivation and constraint traces),
`auditTimeline` shows *how it got there* (`GET /models/{id}/audit` — the durable, hash-chained
record of committed cycles).

| Extra field | Description |
|---|---|
| `bind` | Path to explain; for `auditTimeline` a path **prefix**, unset means the whole model |
| `limit` | Maximum rows (default 20) |
| `showConstraints` | Include constraint traces as well as derivations |
| `collapsed` | Initial fold state |

**The server sends the declaration, not the rows.** `ViewEvaluator` has no access to the trace
ring buffer or the audit store, and giving it one would put an unbounded read inside every view
evaluation and every `viewDelta`; the renderer fetches the rows itself, the same division of
labour as `optionsUrl`. A consumer of the raw `GET /models/{id}/view` response therefore gets the
declaration only.

#### `validationSummary`

Every flagged constraint in one block, each linking to the field it is about.

| Extra field | Description |
|---|---|
| `pathPrefix` | Narrow to one section; unset means the whole model |
| `variant` | `"danger"` (default), `"warning"`, `"info"` |
| `maxItems` | Cap the list |
| `emptyText` | Shown when there are none; omit to render nothing at all |

This exists because a `flag`-policy constraint **commits**. It records a violation and lets the
model stay editable while temporarily inconsistent — the point of the policy — but leaves the
violation with nowhere to appear except beside a field bound to the same path, and a constraint
spanning three fields is beside none of them. (A `rollback` constraint needs none of this: it
rejects the mutation and surfaces as an error on the failed call.)

Like the trace panels, the violations reach the renderer through its own violations map, not
through the view.

#### `effectStatus`

An effect's `statusPath` machine, drawn: `pending → in_flight → applied | failed`.

| Extra field | Description |
|---|---|
| `bind` | The effect's `statusPath` |
| `effectId` | The effect this reports on (documentation for the reader) |
| `errorPath` | Path holding the failure message |
| `showRetry` | Show a retry control (defaults to true when failed) |
| `retryLabel` | Retry button caption |
| `onRetry` | `EventHandler` — typically resets the status to `pending` |

The status is an ordinary model field, so it arrives through the same `viewDelta` as everything
else and needs no polling. Resetting `statusPath` re-arms the dedupe guard, which is what makes
the engine re-fire the effect — the component never dispatches one itself, because a view can
only ask the model to.

Without this the only way to show an in-flight effect is a `badge` with a JSONata `variant`,
which the server passes through unevaluated — so it renders correctly in the bundled UI and
nowhere else.

```json
{
  "id": "quoteEffect", "type": "effectStatus", "label": "Underwriting",
  "bind": "$.quote.status", "errorPath": "$.quote.error", "effectId": "fetchQuote",
  "onRetry": { "mutations": "{ '$.quote.status': 'pending' }" }
}
```

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

#### `card`

A `group` on a titled surface; `label` is the heading. Accepts the same `layout`, `columns` and
`components`.

#### `toolbar` / `buttonGroup`

A row of actions. They differ only in spacing — a toolbar separates its children, a buttonGroup
butts them into one control. Neither honours `layout`: a vertical toolbar is a `menu`.

#### `tabs` / `tabItem`

`tabs` gives each child its own panel, captioned by that child's `label`. The children are
ordinary containers — `tabItem` reads best, but any container works — so a tab body is authored
exactly like a `group`.

```json
{
  "id": "quoteTabs", "type": "tabs",
  "components": [
    { "id": "coverTab", "type": "tabItem", "label": "Cover",  "components": [] },
    { "id": "driverTab", "type": "tabItem", "label": "Driver", "components": [] }
  ]
}
```

The active tab is **local UI state and deliberately not in the model** — it is not something a
derivation, a constraint or an audit record should have an opinion about. When the position
*must* survive a reload, use separate views and navigate between them; the active view id is
then the step, and a `stepper` or `breadcrumb` renders it.

#### `collapsible` / `accordion`

`collapsible` is one foldable panel with `label` as its header; `accordion` is a bordered stack
of them.

| Extra field | Description |
|---|---|
| `collapsed` | **Initial** fold state (bool or expression) |

`collapsed` seeds the state and is not a live binding — the renderer owns it from then on.
"Starts closed until the user picks a plan" is expressible; "force closed whenever X" is not, and
would mean a section slamming shut mid-edit. Use `visible` for that.

The accordion does not coordinate its children: each `collapsible` owns its own state, so several
can be open at once. Single-open behaviour would mean the accordion closing a panel the user just
opened.

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

#### `stepper` / `breadcrumb`

The same `menuItems` as a `menu`, drawn as a numbered progression or a trail. Same
`orientation`.

None of the three holds a position of its own: "which step" is the **active view id**, so it
survives a reload, can be driven by a button's `navigate` handler, and is what `onNavigate`
reports to the host app. A stepper tracking its own index would disagree with the view on screen
the moment anything else navigated.

Steps before the active one are marked done — a positional claim, not a claim about validity. The
model's constraints decide whether a step's data is actually acceptable.

```json
{
  "id": "quoteSteps", "type": "stepper",
  "menuItems": [
    { "label": "Vehicle", "targetView": "vehicle" },
    { "label": "Driver",  "targetView": "driver" },
    { "label": "Quote",   "targetView": "quote" }
  ]
}
```

---
