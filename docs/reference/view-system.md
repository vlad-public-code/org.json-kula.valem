---
title: View system
parent: Reference
nav_order: 5
description: "The view evaluation engine, ViewDefinition format, and React renderer."
---

# Pluggable Model Web Renderer — View System Reference

> This describes the **shipped, as-built** view system: the `valem-view` evaluation engine, the
> `ViewDefinition`/`ComponentSpec` format, the `EvaluatedView` contract, and the built-in
> `valem-view-react` renderer.

## Context

Valem models expose data and logic through REST/WebSocket/console APIs. A **View Definition** —
a declarative JSON artifact embedded in `ModelSpec` — describes how that data is presented as a UI
form: component layout, field bindings, visibility rules, and event handlers. The definition is
renderer-agnostic; the built-in renderer is a React UI backed by Spring. AI agents and other clients
(mobile, Angular, CLI) can implement their own renderers against the same `EvaluatedView`
REST/console contract.

Two modules implement the feature: **`valem-view`** (Java, no Spring) holds the data model and
evaluation engine; **`valem-view-react`** (npm library) holds the React component library, hooks,
and TypeScript types. The `valem-ui` app depends on `valem-view-react` as a local workspace package.

---

## Architecture Overview

```
ModelSpec.viewDefinition (JsonNode, raw)
        │
        │ parsed by valem-view (Maven)
        ▼
ViewDefinition ─► ViewSpec ─► ComponentSpec[]

                    ┌──────────────────────┐
                    │    ViewEvaluator      │  valem-view/engine
                    │                      │
ViewSpec ──────────►│ + mergedDocument     │──► EvaluatedView
metaCache ─────────►│ + metaCache          │       └─ EvaluatedComponent[]
ExpressionCache ───►│ + ExpressionCache    │           (all dynamics resolved)
                    └──────────────────────┘

REST (valem-api)
  GET /models/{id}/view           → EvaluatedView
  GET /models/{id}/view/{viewId}  → EvaluatedView

Console (valem-console)
  {"cmd":"get-view","id":"..."}   → EvaluatedView

Built-in renderer (valem-ui + valem-view-react)
  <ViewPanel>
    <ViewRenderer spec={viewDef} state={mergedDoc} onMutate={mutate} />
      └─ <ComponentRenderer component={c} />
           ├─ <TextField/>  <SelectField/>  <CountrySelector/> …
           ├─ <DataTable/>  <DataChart/>
           ├─ <Group/>  <SectionList/>
           └─ <Button/>  <Menu/>
```

**Pluggability contract**: `ViewDefinition` (JSON) is the renderer-agnostic spec.
`EvaluatedView` (JSON from REST) is the computed snapshot any renderer can consume.
The React library is one implementation; future renderers just need to parse either.

**Dependency chain** (no circular deps):
- `valem-view` → `valem-core` (ExpressionCache, ObjectNode, JSONata)
- `valem-service` → `valem-core` + `valem-view`
- `valem-api` → `valem-service` (valem-view transitive)
- `ModelSpec.viewDefinition` stays as raw `JsonNode` in `valem-core` (same pattern as `schema`)

---

## Maven Module: `valem-view`

**Package:** `org.json_kula.valem.view`

**`valem-view/pom.xml`** dependencies:
- `valem-core`
- `jackson-databind`
- `junit-jupiter`, `assertj-core` (test scope)

### `view/model` package

`ViewDefinition`, `ViewSpec` and the supporting records use `@JsonCreator` factory methods; the
`ComponentSpec` records bind through their canonical constructors. All of it is read with
`FAIL_ON_UNKNOWN_PROPERTIES = false`.

**Write-time validation.** A `viewDefinition` is validated when a model is created or evolved,
not deferred to first render: `ModelSpecValidator` enforces (structurally, over the raw JSON)
that view ids are unique, component ids are unique within a view, and `defaultView` /
`sectionList.itemView` name existing views; the service additionally parses the definition into
these records. Any failure is a `422` at write time rather than a `500` at render. Views and
components are therefore addressable by `id`, which is what makes the `upsertViews` /
`upsertComponents` evolution tiers (see [model-spec-format.md](model-spec-format.md#targeted-schema-view-and-constants-diffs)) well-defined.

**`ViewDefinition`**
```java
record ViewDefinition(
    String renderer,         // optional, reserved; default "builtin"
    List<ViewSpec> views,
    String defaultView
)
```

**`ViewSpec`**
```java
record ViewSpec(
    String id,
    String label,
    String layout,           // vertical | horizontal | grid | tabs | wizard
    Integer columns,         // for grid layout
    List<ComponentSpec> components,
    EventHandler onOpen,
    EventHandler onClose
)
```

**`ComponentSpec`** — a **sealed interface**, not a flat record. `type` is the discriminator
(`@JsonTypeInfo(use = NAME, include = EXISTING_PROPERTY, property = "type", visible = true)`), so
the JSON stays flat and unchanged while each component type binds to a record carrying only the
fields it actually uses. A `badge` with a `pageSize` is not representable.

The interface itself declares only what the generic evaluation pipeline reads; every other field is
reachable exclusively by pattern-matching the concrete record, which is what makes `ViewEvaluator`'s
switch exhaustive:

```java
sealed interface ComponentSpec permits BasicInputSpec, TextAreaSpec, /* … */ UnknownComponentSpec {
    String id();
    String type();
    default String   bind()     { return null; }  // $.path — also the meta-inheritance anchor
    default JsonNode visible()  { return null; }  // Boolean | String (JSONata) | null → inherit meta
    default JsonNode enabled()  { return null; }  // Boolean | String (JSONata) | null → !readOnly
    default JsonNode readOnly() { return null; }  // Boolean | String (JSONata) | null → inherit meta
    default JsonNode required() { return null; }  // Boolean | String (JSONata) | null → `#required`
}
```

`id` and `type` are required on every component — a spec missing either is rejected at parse time
(a `422` at write, not a nameless component at render).

Records are grouped by **field shape**, not one per type — the same grouping `EvaluatedComponent`
uses on the output side:

| Record | `type` values | Fields beyond `id`/`type`/`bind`/`visible` |
|---|---|---|
| `BasicInputSpec` | `textField`, `numericField`, `passwordField`, `emailField`, `phoneNumberField`, `checkboxField`, `toggleField`, `dateField`, `dateTimeField`, `timeField`, `countrySelector` | `label`, `enabled`, `readOnly`, `required`, `placeholder`, `helperText`, `tooltip`, `onChange` |
| `TextAreaSpec` | `textAreaField` | basic-input fields + `rows` |
| `ChoiceInputSpec` | `selectField`, `radioField`, `multiSelectField` | basic-input fields + `options`, `optionsExpr`, `optionsUrl`, `optionsPath`, `onOpen`, `onClose` |
| `DependentSelectorSpec` | `countryRegionSelector` | basic-input fields + `options`, `dependsOn` |
| `SliderSpec` | `sliderField` | `label`, `enabled`, `readOnly`, `required`, `helperText`, `tooltip`, `min`, `max`, `step`, `onChange` |
| `FileUploadSpec` | `fileUploadField` | `label`, `enabled`, `readOnly`, `required`, `helperText`, `tooltip`, `accept`, `multiple`, `minFiles`, `maxFiles`, `minSize`, `maxSize`, `allowedMediaTypes`, `onChange` |
| `LabelSpec` | `label` | `label`, `text` |
| `StaticTextSpec` | `staticText` | `text` |
| `BadgeSpec` | `badge` | `label`, `text`, `variant` |
| `SeparatorLineSpec` | `separatorLine` | — |
| `ProgressBarSpec` | `progressBar` | `label`, `min`, `max`, `showValue`, `format`, `helperText`, `tooltip` |
| `DataTableSpec` | `dataTable` | `label`, `tableColumns`, `pageSize`, `tooltip` |
| `DataChartSpec` | `dataChart` | `label`, `chartType`, `chartX`, `chartSeries` |
| `ContainerSpec` | `group`, `fieldSet`, `sectionItem` | `label`, `layout`, `columns`, `legend`, `components` |
| `SectionListSpec` | `sectionList` | `label`, `itemView`, `canAdd`, `canRemove`, `addLabel`, `removeLabel`, `layout`, `columns`, `components`, `onChange` |
| `ButtonSpec` | `button` | `label`, `enabled`, `variant`, `icon`, `onClick` |
| `MenuSpec` | `menu` | `orientation`, `menuItems` |
| `UnknownComponentSpec` | anything else | *(no fixed shape — see below)* |

`bind` is on every record, not only the value-carrying ones, because it is the anchor for the
meta-driven `visible`/`readOnly`/`required` inheritance described below — not just a value locator.

**Unknown types.** A `type` none of the built-in records claim binds to `UnknownComponentSpec`,
which keeps the component's **raw JSON verbatim** — a custom type may carry any property at all,
including ones no built-in type declares, and nothing is dropped at parse time. The common fields
the pipeline reads are projected out of that node; everything else is reachable via
`property(String)`, and the record serializes back to exactly the JSON it was parsed from.
`ViewEvaluator` renders these as a basic input, which is what an unrecognised `type` has always
resolved to.

> `className` was declared on the old flat record but read by nothing — no evaluator, no renderer.
> It is not part of any record, nor of the TypeScript mirror. Since specs are stored and served as
> raw JSON, a `className` in an existing spec still reaches the client untouched; it simply is not
> modelled anywhere.

**Supporting records:**
```java
record OptionSpec(String value, String label)
record ColumnSpec(String field, String header, String format, String width)
record ChartSeriesSpec(String field, String label, String color)
record MenuItemSpec(String label, String targetView, String icon)
record EventHandler(String mutations, String navigate)
  // mutations: JSONata → {"$.path": value, ...}
  // navigate:  view id to activate
```

### Component Type Catalog

**Input fields** (`bind`, `label`, `visible`, `enabled`, `readOnly`, `required`,
`placeholder`, `helperText`, `onChange` on all):

| `type` | Extra fields | Notes |
|---|---|---|
| `textField` | — | single-line text |
| `textAreaField` | `rows` | multiline |
| `numericField` | — | number input; min/max from meta |
| `passwordField` | — | masked |
| `emailField` | — | email validation |
| `phoneNumberField` | — | built-in country code picker (restcountries.com IDD) |
| `checkboxField` | — | boolean |
| `toggleField` | — | boolean switch |
| `selectField` | `options`, `optionsExpr`, `optionsUrl`, `optionsPath` | dropdown |
| `radioField` | `options`, `optionsExpr` | radio group |
| `multiSelectField` | `options`, `optionsExpr`, `optionsUrl`, `optionsPath` | |
| `dateField` | — | date picker |
| `dateTimeField` | — | date + time picker |
| `timeField` | — | time-only picker (HH:mm) |
| `sliderField` | `min`, `max`, `step` | range slider; dragging only updates the local draft — the mutation is sent when the thumb is released (pointer-up / key-up), or on blur/debounce as a backstop |
| `fileUploadField` | `accept`, `multiple`, `minFiles`/`maxFiles`, `minSize`/`maxSize`, `allowedMediaTypes` | POSTs multipart to `/blobs`; stores `BlobRef` `{$blobId,$mediaType,$bytes}` in bound field. `minFiles`/`maxFiles`/`minSize`/`maxSize`/`allowedMediaTypes` fall back to a `minItems`/`maxItems`/`minSize`/`maxSize`/`allowedMediaTypes` metaDerivation on the bind path when unset on the component (`ViewEvaluator` meta-cache lookup, same pattern as `readOnly`/`required`) |
| `countrySelector` | — | fetches from restcountries.com automatically |
| `countryRegionSelector` | `dependsOn` | `dependsOn` = bind path of countrySelector |

**Data output** (`bind`, `label`, `visible` on all):

| `type` | Extra fields | Notes |
|---|---|---|
| `label` | `text` | dynamic text or bound value |
| `staticText` | `text` | markdown/HTML literal |
| `badge` | `text`, `variant` | status indicator. `variant` is a plain string, **not** JSONata-capable server-side — `ViewEvaluator` passes it through unevaluated. The bundled React renderer re-evaluates a JSONata `variant` expression client-side, so it only resolves correctly through the built-in UI, not through the raw `GET /models/{id}/view` (or MCP/console) response. |
| `separatorLine` | — | horizontal rule |
| `dataTable` | `tableColumns`, `pageSize` | tabular view of array |
| `dataChart` | `chartType`, `chartX`, `chartSeries` | recharts chart |
| `progressBar` | `min`, `max`, `showValue`, `format` | numeric value as filled bar; `format`: `percent` (default) or `value` (`75 / 100`) |

**Aggregates**:

| `type` | Key fields | Notes |
|---|---|---|
| `group` | `layout`, `columns`, `components` | layout container |
| `fieldSet` | `legend`, `components` | HTML `<fieldset>` |
| `sectionList` | `bind`, `itemView`, `canAdd`, `canRemove`, labels | array add/remove |
| `sectionItem` | `bind`, `components` | single element editor (sub-view); evaluates to an `EvaluatedContainer` carrying `bind` |

**Actions**:

| `type` | Key fields | Notes |
|---|---|---|
| `button` | `variant`, `icon`, `onClick`, `enabled` | |
| `menu` | `menuItems`, `orientation` | view navigation |

### `view/engine` package

**`EvaluatedComponent`** — a **sealed interface**, not a flat record. Each component type
serializes as one of 17 concrete records, each carrying only the fields relevant to that type
(`@JsonInclude(NON_NULL)`, with a `BooleanTrueFilter` suppressing default `true`/`false`
booleans): `EvaluatedBasicInput`, `EvaluatedTextArea`, `EvaluatedSelectField`,
`EvaluatedDependentSelector`, `EvaluatedSlider`, `EvaluatedFileUpload`, `EvaluatedLabel`,
`EvaluatedStaticText`, `EvaluatedBadge`, `EvaluatedProgressBar`, `EvaluatedDataTable`,
`EvaluatedDataChart`, `EvaluatedContainer`, `EvaluatedSectionList`, `EvaluatedButton`,
`EvaluatedMenu`, `EvaluatedSeparatorLine`. For example `EvaluatedBadge` carries only
`id, type, visible, variant, text, label` — no `bind`/`value`/`enabled`. This mirrors the
`ComponentSpec` hierarchy on the input side one-for-one, minus `UnknownComponentSpec` (an
unrecognised `type` evaluates to an `EvaluatedBasicInput`).

`EvaluatedContainer` covers `group`, `fieldSet` **and** `sectionItem`; its `bind` is populated
only by `sectionItem`, and is omitted from the JSON otherwise.

The interface exposes ~25 `default` methods (returning `null`/`false` for fields a given
subtype doesn't carry) so callers can treat any `EvaluatedComponent` uniformly:
```java
sealed interface EvaluatedComponent permits EvaluatedBasicInput, EvaluatedTextArea, /* … */ {
    String id(); String type();
    default String label()          { return null; }
    default boolean visible()       { return true; }
    default boolean enabled()       { return true; }
    default boolean readOnly()      { return false; }
    default boolean required()      { return false; }
    default String bind()           { return null; }
    default JsonNode value()        { return null; }
    // ...+ options/text/components/tableColumns/chartX/menuItems/variant/min/max/step/
    //     accept/multiple/minFiles/maxFiles/minSize/maxSize/allowedMediaTypes/showValue/
    //     format/onClick/onChange/onOpen/onClose, each with a type-appropriate default
}
```

**`EvaluatedView`**:
```java
record EvaluatedView(
    String modelId, String viewId, String title, String layout,
    List<EvaluatedComponent> components
)
```

**`ViewEvaluator`** — a stateless utility class (private constructor, static methods only):
```java
public final class ViewEvaluator {
    public static EvaluatedView evaluate(
        String modelId,
        ViewSpec view,
        ObjectNode mergedDocument,
        Map<String, JsonNode> metaCache,
        ExpressionCache exprCache
    )

    // Overload: also binds the model's named constants as $const in every view expression.
    public static EvaluatedView evaluate(
        String modelId, ViewSpec view, ObjectNode mergedDocument,
        Map<String, JsonNode> metaCache, ExpressionCache exprCache,
        ObjectNode constants   // nullable — null means no $const binding
    )
}
```

After resolving the common dynamics, the evaluator dispatches with a `switch` **pattern-matching
the sealed `ComponentSpec`**, not on the `type` string. The switch is exhaustive over the permits
list, so a new component record does not compile until it is handled — adding a type can no longer
fall through to the generic input branch unnoticed.

Per-component evaluation steps:
1. Resolve `visible` → null: check `metaCache["$.bind#relevant"]` (absent → true); bool/JSONata
2. Resolve `readOnly` → null: check `metaCache["$.bind#read_only"]` (absent → false); bool/JSONata
3. Resolve `required` → null: check `metaCache["$.bind#required"]` (absent → false); bool/JSONata.
   **There is no JSON-Schema-`required`-array fallback** — `ViewEvaluator` never receives the
   schema (only `mergedDocument`/`metaCache`/`exprCache`/`constants`); a spec that relies solely
   on the schema's `required` array (no explicit `#required` metaDerivation) renders
   `required=false` in the view.
4. Resolve `enabled` → null: `!effectiveReadOnly`; bool/JSONata
5. Resolve `text` → if String with `$` or operators: evaluate as JSONata; else literal
6. Look up `bind` path in `mergedDocument` → `value`
7. Resolve `options` — static list passthrough only. `optionsExpr`/`optionsUrl`/`optionsPath`
   are **never read by the server** — they are declared on `ComponentSpec` but dead from the
   engine's perspective; resolution happens entirely client-side (see the Explainability
   boundary note below).
8. Recurse into `components` for aggregates

---

## Default Visibility / ReadOnly Inheritance

| ComponentSpec field | Null (default) source |
|---|---|
| `visible` | `metaCache["$.bind#relevant"]` → false = hidden; absent = visible |
| `readOnly` | `metaCache["$.bind#read_only"]` → true = read-only; absent = editable |
| `required` | `metaCache["$.bind#required"]` → absent = false. **Not** derived from the JSON Schema `required` array — you must add an explicit `#required` metaDerivation. |
| `enabled` | `!effectiveReadOnly` |

The survey example's `issueCategory` (driven by `relevant` and `readOnly` metaDerivations)
becomes hidden/disabled with zero extra ViewDefinition config.

> **Wiring.** `ModelSpec.viewDefinition` and `SpecEvolution.newViewDefinition` are raw nullable
> `JsonNode`s in `valem-core` (same pattern as `schema`), parsed by `valem-view`. `ModelService`
> exposes `getEvaluatedView(id, viewId)`; `valem-api` serves it via `ViewController`
> (`GET /models/{id}/view[/{viewId}]`, 404 through `ModelNotFoundException`) and `valem-console` via
> the `get-view` command. There is no role/access parameter anywhere in the view evaluation path,
> consistent with there being no per-field authorization in Valem (see [security-model.md](../deployment/security-model.md)).

---

## npm Library: `valem-view-react`

**Location:** `valem-view-react/` (repo root sibling to `valem-ui`)

**`package.json`** peer dependencies: `react ^18`, `react-dom ^18`

**Runtime dependencies:** `jsonata`, `recharts`

**Build tooling:** Vite in lib mode, TypeScript

**Public exports (`index.ts`):**
- `ViewRenderer` — main entry component
- `ViewContext`, `ViewContextProvider`
- All component types (re-exported for custom composition)
- TypeScript types: `ViewDefinition`, `ViewSpec`, `ComponentSpec`, `EvaluatedView`,
  `EvaluatedComponent`, `OptionSpec`, `EventHandler`, etc.
- The `ComponentSpec` variants (`BasicInputSpec`, `SliderSpec`, `ContainerSpec`, …),
  `KnownComponentSpec`, `UnknownComponentSpec`, plus the `isKnownComponent` /
  `hasChildComponents` narrowing guards and the `KNOWN_COMPONENT_TYPES` list
- Hooks: `useJSONata`, `useCountries`, `useRegions`

**`ComponentSpec` is a discriminated union here too**, mirroring the Java sealed hierarchy
variant-for-variant: same grouping, same field sets, same `UnknownComponentSpec` fallback (typed
with an index signature, so a custom type may carry any property). Consequences in the renderer:

- `BaseComponentProps<C>` is generic, so each implementation receives only its own variant —
  `SliderField` takes a `SliderSpec`. Reading `c.pageSize` inside `Badge` is a compile error.
- `ComponentRenderer` narrows with `isKnownComponent(c)` before dispatching, so the unknown-type
  branch is explicit rather than a `default:` fallthrough, and the `switch` over
  `KnownComponentSpec` is exhaustiveness-checked by its `ReactElement` return type — a missing
  case fails `tsc` with "function lacks ending return statement".

The one place the mirror is deliberately looser: the TS base carries `enabled`/`readOnly`/
`required` for every variant (matching the Java *interface*'s default methods), whereas the Java
*records* only bind them where the type is interactive.

**`ViewRenderer` props:**
```typescript
interface ViewRendererProps {
  modelId: string;
  viewDef: ViewDefinition;           // raw spec — client evaluates dynamics
  state: Record<string, unknown>;    // merged model state
  meta: Record<string, unknown>;     // meta cache
  onMutate: (mutations: Record<string, unknown>) => Promise<void>;
  onNavigate?: (viewId: string) => void;
  activeViewId?: string;
}
```

**Rendering strategy (hybrid, not purely client-side):**
- `ViewRenderer`/`ComponentRenderer` take the *raw* `ViewDefinition`/`ComponentSpec` and
  evaluate `visible`/`enabled`/`readOnly`/`required`/`text` JSONata expressions client-side via
  `jsonata` npm, against the fetched `state` + `meta` maps — this is the primary render path.
- The server-evaluated `EvaluatedView` (`GET /models/{id}/view[/{viewId}]`) is *also* fetched by
  `ViewPanel` on mount, purely to seed/refresh the `meta` map (`readOnly`/`visible`/`required`)
  that the client evaluator reads — the client re-derives the actual displayed values from the
  raw spec rather than consuming the server's already-resolved `EvaluatedComponent`s directly.
- After every mutation (`POST /models/{id}/mutations[/patch]` with an `X-View` header), the
  server returns a `viewDelta` — a `Map<String, EvaluatedComponent>` keyed by component id,
  containing only the components whose `bind` path was mutated or re-derived
  (`mutatedPaths ∪ derivedUpdated`). `ViewPanel` applies this delta to patch local view state
  optimistically instead of doing a full view re-fetch. See [api-reference.md](api-reference.md)
  for the `MutationResponse.viewDelta` shape.
- On WebSocket `ChangeEvent`: `ViewPanel` re-fetches state, passes to `ViewRenderer`
- `onClick` / `onChange`: evaluate `mutations` JSONata → call `onMutate()` → parent POSTs mutation

> **Explainability boundary (client-side evaluation).** `onClick`/`onChange` `mutations`
> JSONata are evaluated **in the browser**, not on the server, and so do not appear in the
> server-side derivation/constraint trace (`GET /models/{id}/explain/{path}`) until the
> resulting mutation is POSTed (which then runs the full audited pipeline). `optionsExpr`/
> `optionsUrl`/`optionsPath` are likewise resolved **only client-side today** — `ViewEvaluator`
> does not evaluate any of them (see evaluation step 7 above), so `optionsExpr` currently
> provides **no more server-side auditability than `optionsUrl`**, and neither is subject to
> the server's SSRF controls. Where auditability matters, compute the option-relevant value via
> a `derivations` field and bind the component to that instead.

**Public REST APIs (fetched client-side, cached in module-level maps):**

| Component | URL |
|---|---|
| `countrySelector` | `https://restcountries.com/v3.1/all?fields=name,cca2` |
| `countryRegionSelector` | `https://raw.githubusercontent.com/dr5hn/countries-states-cities-database/master/states.json` |
| `phoneNumberField` | `https://restcountries.com/v3.1/all?fields=name,cca2,idd` |

**Source tree:**
```
src/
  index.ts
  types.ts
  ViewRenderer.tsx
  ViewContext.tsx
  ComponentRenderer.tsx
  hooks/
    useJSONata.ts, useCountries.ts, useRegions.ts
  fields/
    TextField.tsx, TextAreaField.tsx, NumericField.tsx, PasswordField.tsx,
    EmailField.tsx, CheckboxField.tsx, ToggleField.tsx,
    SelectField.tsx, RadioField.tsx, MultiSelectField.tsx,
    DateField.tsx, DateTimeField.tsx, TimeField.tsx,
    SliderField.tsx, FileUploadField.tsx,
    CountrySelector.tsx, CountryRegionSelector.tsx, PhoneNumberField.tsx
  output/
    LabelComponent.tsx, StaticText.tsx, Badge.tsx, SeparatorLine.tsx,
    DataTable.tsx, DataChart.tsx, ProgressBar.tsx
  aggregates/
    GroupComponent.tsx, FieldSetComponent.tsx, SectionList.tsx, SectionItem.tsx
  actions/
    ButtonComponent.tsx, MenuComponent.tsx
```

---

## Built-in UI integration

In the built-in UI, `valem-ui` consumes `valem-view-react` as a workspace package: it fetches a
model's spec, renders a "View" tab (shown only when `spec.viewDefinition` is set) via
`<ViewRenderer>`, and re-fetches state on each WebSocket `ChangeEvent`. The bundled example specs
(`customer-satisfaction-survey.json`, `order-items-price-total.json`, and others) ship a
`viewDefinition` — see the [examples gallery](../usage-scenarios/examples-gallery.md).