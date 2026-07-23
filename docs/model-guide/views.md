---
title: Views
parent: Model guide
nav_order: 4
description: "The viewDefinition UI tree: what it is, how it's evaluated, and how a renderer consumes it."
---

# Views
{: .no_toc }

A spec can carry its own UI. `viewDefinition` describes a component tree; the server evaluates it
against live state and hands any renderer a fully-resolved snapshot.
{: .fs-5 .fw-300 }

1. TOC
{:toc}

---

## Why the view lives in the spec

A model that is generated in seconds but takes a sprint to put a form around isn't much use. Keeping
the presentation next to the rules means a generated or evolved model is immediately usable, and a
field can't appear in the data without appearing in the UI.

It is presentation only. Hiding a component does not protect a field —
[there is no per-field authorization]({% link deployment/security-model.md %}) anywhere in Valem.

## The three pieces

```
ModelSpec.viewDefinition        declarative, renderer-agnostic JSON (in the spec)
        │
        │  ViewEvaluator  (valem-view, no Spring)
        ▼
EvaluatedView                   every binding resolved, every expression evaluated
        │
        │  GET /models/{id}/view[/{viewId}]   ·   {"cmd":"get-view"}
        ▼
any renderer                    valem-view-react is the built-in one
```

**`ViewDefinition`** holds `views` (one or more screens) and a `defaultView`. Each **`ViewSpec`** has
a `layout` (`vertical`, `horizontal`, `grid`, `tabs`, `wizard`), optional `columns`, and a list of
**`ComponentSpec`s**.

## Components

Every component has an `id` and a `type`; most also have a `bind` — the canonical address of the
value it shows or edits.

| Group | Types |
|---|---|
| Inputs | `textField`, `textAreaField`, `richTextField`, `numericField`, `currencyField`, `percentField`, `passwordField`, `emailField`, `phoneNumberField`, `checkboxField`, `toggleField`, `dateField`, `dateTimeField`, `timeField`, `dateRangeField`, `sliderField`, `ratingField`, `numericStepper`, `fileUploadField`, `countrySelector`, `countryRegionSelector` |
| Choice | `selectField`, `radioField`, `multiSelectField`, `autocompleteField`, `comboBox`, `tagsField` |
| Display | `label`, `staticText`, `badge`, `alert`, `callout`, `separatorLine`, `spacer`, `image`, `link`, `progressBar`, `gauge` |
| Data | `dataTable`, `dataChart`, `sparkline`, `keyValueList`, `summaryList`, `statTile`, `metric` |
| Diagnostics | `explainPanel`, `auditTimeline`, `validationSummary`, `effectStatus`, `jsonViewer` |
| Containers | `group`, `fieldSet`, `card`, `toolbar`, `buttonGroup`, `tabs`, `tabItem`, `accordion`, `collapsible`, `sectionItem`, `sectionList` |
| Actions | `button`, `menu`, `stepper`, `breadcrumb` |

Many of these are the same record under a second name — a `ratingField` is a slider's
`min`/`max`/`step` drawn as stars, an `alert` is a badge's `label`/`text`/`variant` as a block, a
`card` is a group with a heading. Picking the closest name costs nothing and reads better; nothing
about the evaluation changes.

The **Diagnostics** row is the one group with no equivalent in an ordinary form library: those
components surface Valem's own derivation trace, audit trail, flagged constraints and effect
status inside the view. See [component-catalog.md](../reference/model-spec/component-catalog.md)
for what each of them does and does not resolve server-side.

An unrecognised `type` is rejected at write time with the closest legal spelling — `ModelSpec`
validation checks it against this vocabulary, so a typo is a 422 rather than an error box in the
browser. (A component that *reaches* a renderer with an unknown type still keeps its raw JSON
verbatim, so a custom renderer can define its own types.)

## Dynamic properties

`visible`, `enabled`, `readOnly`, and `required` each accept a boolean **or a JSONata expression**
evaluated against the merged document:

```json
{ "id": "referral", "type": "badge", "text": "Referred to underwriting",
  "visible": "payable > 10000" }
```

Left absent, they **inherit from the model's meta-derivations** through the component's `bind` — so a
field whose `maximum` depends on state gets that limit in the UI without anyone restating it. That
inheritance is the reason `bind` exists on every component record, not just value-carrying ones.

Interaction is declarative too: `onChange` / `onClick` / `onOpen` / `onClose` handlers carry a
`mutations` expression (JSONata producing `{"$.path": value}`) and/or a `navigate` target view.

## Validation happens at write time

A `viewDefinition` is checked when the model is created or evolved, not at first render: view ids
unique, component ids unique within a view, `defaultView` and `sectionList.itemView` naming views
that exist, `id`/`type` present on every component. A bad view is a `422` on the write — never a
`500` when a user opens the screen.

Because views and components are addressable by id, evolution can target them individually
(`upsertViews`, `upsertComponents` with `parentId`/`beforeId` placement) instead of replacing the
whole tree.

## Keeping a screen live

`GET /models/{id}/view` returns a snapshot. For a live screen, subscribe to the model's WebSocket
`ChangeEvent` and either re-fetch the view or patch the affected bindings optimistically. The
[client SDKs]({% link extending/client-sdks.md %}) handle the reconnecting subscription for you.

## Next

- [View system reference]({% link reference/view-system.md %}) — every component's exact field set,
  the `EvaluatedView` contract, and the React renderer.
- [Model-driven UIs]({% link usage-scenarios/model-driven-ui.md %}) — when to reach for this.
- [Examples gallery]({% link usage-scenarios/examples-gallery.md %}) — specs that ship with views.
