---
title: Model-driven UIs
parent: Usage scenarios
nav_order: 4
description: "Ship a form or dashboard that follows the model: a declarative view tree, evaluated server-side, rendered by any client."
---

# Model-driven UIs
{: .no_toc }

A `viewDefinition` travels with the spec. The server evaluates it against live state and hands any
renderer a fully-resolved `EvaluatedView` — so the UI follows the model instead of chasing it.
{: .fs-5 .fw-300 }

1. TOC
{:toc}

---

## The problem

A model that changes weekly needs a UI that changes weekly. Hand-built forms drift: a new field
appears in the API and nowhere in the form, a limit tightens in the rules and the input still accepts
the old range, a section should be hidden for one customer type and someone has to remember why.

It's worse when the model is **generated**. If an agent can produce a working spec in seconds but
the UI takes a sprint, the spec isn't really usable.

## The shape of the fix

Put the presentation in the spec, next to the rules:

```json
"viewDefinition": {
  "defaultView": "quote",
  "views": [{
    "id": "quote",
    "layout": "grid",
    "columns": 2,
    "components": [
      { "id": "cov",   "type": "numericField", "bind": "$.coverage", "label": "Coverage" },
      { "id": "band",  "type": "selectField",  "bind": "$.riskBand", "label": "Risk band",
        "options": [{ "value": "std", "label": "Standard" }, { "value": "high", "label": "High" }] },
      { "id": "prem",  "type": "label",        "bind": "$.payable",  "label": "Premium",
        "readOnly": true },
      { "id": "note",  "type": "badge",        "text": "Referred to underwriting",
        "visible": "payable > 10000" }
    ]
  }]
}
```

`GET /models/{id}/view` returns an **`EvaluatedView`**: every binding resolved against the merged
document, every JSONata `visible`/`enabled`/`readOnly`/`required` expression evaluated, and the
live meta-derived limits (min, max, required) folded in. The client renders a snapshot — it does not
re-derive anything.

## What Valem provides

| Need | Mechanism |
|---|---|
| A renderer-agnostic UI tree | `ViewDefinition` → `ViewSpec` → `ComponentSpec`, embedded in the spec |
| Layouts | `vertical`, `horizontal`, `grid` (with `columns`), `tabs`, `wizard` |
| Components | Inputs (text, numeric, date, select, multi-select, slider, file upload, country/region), display (label, static text, badge, progress bar, separator), data (`dataTable`, `dataChart`), containers (`group`, `fieldSet`, `sectionList`), actions (`button`, `menu`) |
| Conditional UI | `visible` / `enabled` / `readOnly` / `required` as booleans **or** JSONata expressions |
| Repeating sections | `sectionList` with an `itemView`, `canAdd` / `canRemove` |
| Limits without duplication | Meta-derived min/max/required inherited through a component's `bind` |
| Multiple screens per model | Several `views` plus a `defaultView`; `menu` and `navigate` handlers move between them |
| A built-in renderer | `valem-view-react` (npm) — used by the `valem-ui` management SPA |
| Live updates | The WebSocket `ChangeEvent` after each mutation; re-fetch or patch optimistically |
| Custom types | An unrecognised `type` keeps its raw JSON verbatim, so your renderer can define its own |

Invalid views fail **at write time** (`422`), not at render: view and component ids must be unique,
and `defaultView` / `sectionList.itemView` must name views that exist.

## Who this is for

- **You're generating models.** An LLM or agent that produces a spec produces the UI with it. The
  [sandbox]({% link getting-started/sandbox.md %}) does exactly this — everything you see rendered
  there came out of the generated `viewDefinition`.
- **You have many similar models.** Per-tenant or per-product variants share one renderer; the
  differences live in specs.
- **You're not writing React.** `EvaluatedView` is plain JSON over REST (and the console). A mobile,
  Angular, or terminal client implements the same contract — that's the pluggability seam.

## Worth knowing before you build on it

- The view layer is **presentation, not authorization**. Hiding a component doesn't protect a field;
  there is no per-field access control anywhere in Valem. See
  [security model]({% link deployment/security-model.md %}).
- Evaluation happens server-side against the merged document, so a view is only as fresh as the
  snapshot you fetched — subscribe to changes for a live screen.
- The component catalog is deliberately finite. Bespoke widgets belong in your own renderer, keyed
  off a custom `type`.

## Next

- [View system reference]({% link reference/view-system.md %}) — every component type, field, and the
  `EvaluatedView` contract.
- [Model spec format]({% link reference/model-spec-format.md %}) — where `viewDefinition` sits in the
  spec, and how to evolve it with targeted component diffs.
- [Examples gallery](examples-gallery.md) — specs that ship with views, including a chart.
