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
