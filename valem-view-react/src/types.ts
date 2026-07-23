/** TypeScript mirror of Java model classes in valem-view */

export interface OptionSpec {
  value: string;
  label: string;
}

/**
 * One column of a dataTable. `currency` is per column for the same reason it is per row on
 * KeyValueItemSpec: a table legitimately holds more than one, and a `currency` format with no
 * code renders in the renderer's default.
 */
export interface ColumnSpec {
  field: string;
  header?: string;
  format?: string;
  currency?: string;
  width?: string;
}

export interface ChartSeriesSpec {
  field: string;
  label?: string;
  color?: string;
}

export interface MenuItemSpec {
  label: string;
  targetView: string;
  icon?: string;
}

/** mutations: JSONata expression → {"$.path": value}; navigate: view id to activate */
export interface EventHandler {
  mutations?: string;
  navigate?: string;
}

/**
 * The fields every component carries — the mirror of the Java `ComponentSpec` sealed
 * interface, which declares exactly these because they are what the generic evaluation
 * pipeline reads. `bind` is on every type because it anchors the meta-driven
 * visible/readOnly/required inheritance, not only because it locates a value.
 *
 * Dynamic fields accept a boolean or a JSONata expression string.
 */
export interface ComponentSpecBase {
  id: string;
  type: string;
  bind?: string;
  visible?: boolean | string;
  enabled?: boolean | string;
  readOnly?: boolean | string;
  required?: boolean | string;
}

/**
 * Input types that need nothing beyond the common input fields.
 *
 * `currencyField` and `percentField` are `numericField` plus a display convention — the bound
 * value stays a plain number either way, so switching between them cannot change what the model
 * computes.
 */
export type BasicInputType =
  | 'textField'
  | 'numericField'
  | 'currencyField'
  | 'percentField'
  | 'passwordField'
  | 'emailField'
  | 'phoneNumberField'
  | 'checkboxField'
  | 'toggleField'
  | 'dateField'
  | 'dateTimeField'
  | 'timeField'
  | 'countrySelector';

export interface BasicInputSpec extends ComponentSpecBase {
  type: BasicInputType;
  label?: string;
  placeholder?: string;
  helperText?: string;
  tooltip?: string;
  /** `currency` | `percent` | `value`; defaults from the type. */
  format?: string;
  /** ISO-4217 code, e.g. `EUR`. Only read when the format resolves to `currency`. */
  currency?: string;
  onChange?: EventHandler;
}

/** textAreaField and richTextField — the latter stores markdown in the same plain string field. */
export interface TextAreaSpec extends ComponentSpecBase {
  type: 'textAreaField' | 'richTextField';
  label?: string;
  placeholder?: string;
  helperText?: string;
  tooltip?: string;
  rows?: number;
  /** `basic` | `full` | `none` — how much editing chrome a richTextField shows. */
  toolbar?: string;
  onChange?: EventHandler;
}

/**
 * selectField, radioField, multiSelectField, autocompleteField, comboBox, tagsField.
 * Only `options` is resolved server-side; `optionsExpr` / `optionsUrl` / `optionsPath` are
 * resolved here, in the client.
 */
export interface ChoiceInputSpec extends ComponentSpecBase {
  type:
    | 'selectField'
    | 'radioField'
    | 'multiSelectField'
    | 'autocompleteField'
    | 'comboBox'
    | 'tagsField';
  label?: string;
  placeholder?: string;
  helperText?: string;
  tooltip?: string;
  options?: OptionSpec[];
  optionsExpr?: string;
  optionsUrl?: string;
  optionsPath?: string;
  /** Accept a value outside `options`. Defaults true for tagsField and comboBox. */
  allowCustom?: boolean;
  onChange?: EventHandler;
  onOpen?: EventHandler;
  onClose?: EventHandler;
}

/** countryRegionSelector — `dependsOn` is the bind path of the driving countrySelector. */
export interface DependentSelectorSpec extends ComponentSpecBase {
  type: 'countryRegionSelector';
  label?: string;
  placeholder?: string;
  helperText?: string;
  tooltip?: string;
  options?: OptionSpec[];
  dependsOn?: string;
  onChange?: EventHandler;
}

/** sliderField, ratingField and numericStepper — three affordances over the same min/max/step. */
export interface SliderSpec extends ComponentSpecBase {
  type: 'sliderField' | 'ratingField' | 'numericStepper';
  label?: string;
  helperText?: string;
  tooltip?: string;
  min?: number;
  max?: number;
  step?: number;
  onChange?: EventHandler;
}

/**
 * dateRangeField — two coupled dates at two separate paths, because the ends almost always
 * already have their own derivations and constraints (`endDate >= startDate` is a constraint,
 * not a widget rule). `bind` is the meta-inheritance anchor only, not the value.
 */
export interface DateRangeSpec extends ComponentSpecBase {
  type: 'dateRangeField';
  label?: string;
  bindFrom?: string;
  bindTo?: string;
  fromLabel?: string;
  toLabel?: string;
  helperText?: string;
  tooltip?: string;
  minDate?: string;
  maxDate?: string;
  onChange?: EventHandler;
}

export interface FileUploadSpec extends ComponentSpecBase {
  type: 'fileUploadField';
  label?: string;
  helperText?: string;
  tooltip?: string;
  accept?: string;
  multiple?: boolean;
  minFiles?: number;
  maxFiles?: number;
  minSize?: number;
  maxSize?: number;
  allowedMediaTypes?: string;
  onChange?: EventHandler;
}

export interface LabelSpec extends ComponentSpecBase {
  type: 'label';
  label?: string;
  text?: string;
}

/**
 * staticText. `format` selects rendering: `markdown` (default — escapes then applies light
 * formatting; safe for content bound to model state), `text` (escaped, verbatim), or `html`
 * (unescaped — opt in only for authored, trusted content).
 */
export interface StaticTextSpec extends ComponentSpecBase {
  type: 'staticText';
  text?: string;
  format?: string;
}

/**
 * badge, alert and callout — the inline and block-level spellings of the same three fields:
 * `label` is the heading, `text` the body, `variant` the severity.
 *
 * `variant` may be a JSONata expression, but only this renderer evaluates it — the server
 * passes it through unevaluated, so it does not resolve through a raw GET /models/{id}/view.
 */
export interface BadgeSpec extends ComponentSpecBase {
  type: 'badge' | 'alert' | 'callout';
  label?: string;
  text?: string;
  variant?: string;
}

/** separatorLine — a rule — and spacer, the same gap without one. */
export interface SeparatorLineSpec extends ComponentSpecBase {
  type: 'separatorLine' | 'spacer';
  /** Gap in pixels for a spacer (default 16); a separatorLine ignores it. */
  size?: number;
}

/** image — from a literal `src`, a JSONata expression, or the bound value (e.g. an upload). */
export interface ImageSpec extends ComponentSpecBase {
  type: 'image';
  label?: string;
  src?: string;
  alt?: string;
  width?: string;
  height?: string;
  fit?: string;
}

/**
 * link — an anchor out of the model. Navigation *inside* the model is a button or menu with a
 * `navigate` handler, which switches view without discarding unsaved edits.
 */
export interface LinkSpec extends ComponentSpecBase {
  type: 'link';
  label?: string;
  href?: string;
  text?: string;
  target?: string;
  icon?: string;
}

/** progressBar and gauge — the same bound number as a bar or an arc. */
export interface ProgressBarSpec extends ComponentSpecBase {
  type: 'progressBar' | 'gauge';
  label?: string;
  helperText?: string;
  tooltip?: string;
  min?: number;
  max?: number;
  showValue?: boolean;
  format?: string;
}

export interface DataTableSpec extends ComponentSpecBase {
  type: 'dataTable';
  label?: string;
  tooltip?: string;
  tableColumns?: ColumnSpec[];
  pageSize?: number;
}

/** dataChart and sparkline — the latter stripped of axes, legend and grid to sit inline. */
export interface DataChartSpec extends ComponentSpecBase {
  type: 'dataChart' | 'sparkline';
  label?: string;
  chartType?: string;
  chartX?: string;
  chartSeries?: ChartSeriesSpec[];
}

/**
 * One caption/value row of a keyValueList. `bind` wins when both it and `text` are set.
 *
 * `currency` is per row because a summary legitimately mixes them — a quoted price beside its
 * converted equivalent. A `format` of `currency` with no code renders in the renderer's default.
 */
export interface KeyValueItemSpec {
  label?: string;
  bind?: string;
  text?: string;
  format?: string;
  currency?: string;
}

/**
 * keyValueList / summaryList — the read-only summary a derivation-heavy model ends on.
 * Rows come from `items`; `bind` is the meta-inheritance anchor, not the row source.
 */
export interface KeyValueListSpec extends ComponentSpecBase {
  type: 'keyValueList' | 'summaryList';
  label?: string;
  items?: KeyValueItemSpec[];
  columns?: number;
  tooltip?: string;
}

/**
 * statTile / metric — one headline number with its supporting text.
 *
 * `trend` is separate from the sign of `delta` on purpose: whether a rising number is good news
 * is a domain question (spend up is bad, savings up is good) that only the spec author can answer.
 */
export interface StatTileSpec extends ComponentSpecBase {
  type: 'statTile' | 'metric';
  label?: string;
  value?: string;
  delta?: string;
  caption?: string;
  trend?: string;
  format?: string;
  currency?: string;
  variant?: string;
  icon?: string;
  tooltip?: string;
}

/** jsonViewer — the bound subtree as formatted JSON; bind to `$` for the whole merged document. */
export interface JsonViewerSpec extends ComponentSpecBase {
  type: 'jsonViewer';
  label?: string;
  collapsed?: boolean | string;
  maxDepth?: number;
  tooltip?: string;
}

/**
 * explainPanel and auditTimeline — Valem's two path-scoped record trails.
 *
 * The server sends the declaration, not the rows: the evaluator has no access to the trace ring
 * buffer or the audit store, so this component fetches from `/explain/{path}` or `/audit` itself,
 * the same division of labour as `optionsUrl`.
 */
export interface TracePanelSpec extends ComponentSpecBase {
  type: 'explainPanel' | 'auditTimeline';
  label?: string;
  limit?: number;
  showConstraints?: boolean;
  collapsed?: boolean | string;
  tooltip?: string;
}

/**
 * validationSummary — the flagged constraints in one block.
 *
 * A `rollback` constraint surfaces as an error on the failed call; a `flag` constraint commits
 * and has nowhere to appear except beside a field bound to the same path — and one spanning
 * three fields is beside none of them.
 */
export interface ValidationSummarySpec extends ComponentSpecBase {
  type: 'validationSummary';
  label?: string;
  pathPrefix?: string;
  variant?: string;
  maxItems?: number;
  emptyText?: string;
}

/**
 * effectStatus — an effect's `statusPath` machine, drawn.
 *
 * `bind` is that status path, so the component reads pending → in_flight → applied | failed out
 * of ordinary model state and updates through the same viewDelta as everything else.
 */
export interface EffectStatusSpec extends ComponentSpecBase {
  type: 'effectStatus';
  label?: string;
  effectId?: string;
  errorPath?: string;
  showRetry?: boolean;
  retryLabel?: string;
  tooltip?: string;
  onRetry?: EventHandler;
}

/**
 * The plain containers. They differ only in chrome — a card is a group with a titled surface,
 * a toolbar lays children out in a row, tabs and accordion give each child its own panel — so
 * switching between them cannot change what the view computes. `bind` is used by sectionItem.
 */
export interface ContainerSpec extends ComponentSpecBase {
  type:
    | 'group'
    | 'fieldSet'
    | 'card'
    | 'toolbar'
    | 'buttonGroup'
    | 'tabs'
    | 'tabItem'
    | 'accordion'
    | 'collapsible'
    | 'sectionItem';
  label?: string;
  layout?: string;
  columns?: number;
  legend?: string;
  /** Initial fold state of a collapsible; the renderer owns it from then on. */
  collapsed?: boolean | string;
  components?: ComponentSpec[];
}

export interface SectionListSpec extends ComponentSpecBase {
  type: 'sectionList';
  label?: string;
  itemView?: string;
  canAdd?: boolean | string;
  canRemove?: boolean | string;
  addLabel?: string;
  removeLabel?: string;
  layout?: string;
  columns?: number;
  components?: ComponentSpec[];
  onChange?: EventHandler;
}

export interface ButtonSpec extends ComponentSpecBase {
  type: 'button';
  label?: string;
  variant?: string;
  icon?: string;
  onClick?: EventHandler;
}

/**
 * menu, stepper and breadcrumb — three drawings of the same `menuItems`. None holds a position
 * of its own: the step a wizard is "on" is the active view id, so it survives a reload.
 */
export interface MenuSpec extends ComponentSpecBase {
  type: 'menu' | 'stepper' | 'breadcrumb';
  orientation?: string;
  menuItems?: MenuItemSpec[];
}

/**
 * A `type` this renderer does not know. Mirrors the Java `UnknownComponentSpec`: it may
 * carry any property at all, including ones no built-in component type declares, and
 * nothing is dropped. Narrow with {@link isKnownComponent} before rendering.
 */
export interface UnknownComponentSpec extends ComponentSpecBase {
  type: string;
  [key: string]: unknown;
}

/** Every component type the built-in renderer implements. */
export type KnownComponentSpec =
  | BasicInputSpec
  | TextAreaSpec
  | ChoiceInputSpec
  | DependentSelectorSpec
  | SliderSpec
  | DateRangeSpec
  | FileUploadSpec
  | LabelSpec
  | StaticTextSpec
  | BadgeSpec
  | SeparatorLineSpec
  | ImageSpec
  | LinkSpec
  | ProgressBarSpec
  | DataTableSpec
  | DataChartSpec
  | KeyValueListSpec
  | StatTileSpec
  | JsonViewerSpec
  | TracePanelSpec
  | ValidationSummarySpec
  | EffectStatusSpec
  | ContainerSpec
  | SectionListSpec
  | ButtonSpec
  | MenuSpec;

/**
 * Discriminated on `type`, so narrowing a component gives you exactly the fields that type
 * has — a `badge` has no `pageSize`. Mirrors the Java sealed `ComponentSpec` hierarchy.
 */
export type ComponentSpec = KnownComponentSpec | UnknownComponentSpec;

/**
 * The `type` values {@link KnownComponentSpec} covers — mirrors Java's `@JsonSubTypes`, which in
 * turn mirrors `ViewComponentTypes` in valem-core. `ViewComponentTypesCoverageTest` reads this
 * array and fails if the three disagree, because each drifts silently on its own: a type missing
 * here validates and evaluates, then renders as an orange "Unknown component type" box.
 */
export const KNOWN_COMPONENT_TYPES = [
  // inputs
  'textField', 'textAreaField', 'richTextField', 'numericField', 'currencyField',
  'percentField', 'passwordField', 'emailField', 'phoneNumberField', 'checkboxField',
  'toggleField', 'selectField', 'radioField', 'multiSelectField', 'autocompleteField',
  'comboBox', 'tagsField', 'dateField', 'dateTimeField', 'timeField', 'dateRangeField',
  'sliderField', 'ratingField', 'numericStepper', 'fileUploadField', 'countrySelector',
  'countryRegionSelector',
  // output
  'label', 'staticText', 'badge', 'alert', 'callout', 'separatorLine', 'spacer',
  'image', 'link', 'dataTable', 'dataChart', 'sparkline', 'progressBar', 'gauge',
  'keyValueList', 'summaryList', 'statTile', 'metric', 'jsonViewer',
  'explainPanel', 'auditTimeline', 'validationSummary', 'effectStatus',
  // containers
  'group', 'fieldSet', 'card', 'toolbar', 'buttonGroup', 'tabs', 'tabItem',
  'accordion', 'collapsible', 'sectionList', 'sectionItem',
  // actions
  'button', 'menu', 'stepper', 'breadcrumb',
] as const;

type AssertEqual<A, B> = [A] extends [B] ? ([B] extends [A] ? true : never) : never;

/**
 * Compile-time proof that the runtime list and the union agree.
 *
 * They are two different things that both have to be right: `isKnownComponent` narrows using the
 * array, `ComponentRenderer` dispatches using the union, and tsc's exhaustiveness check only
 * covers the second. A type in the array but missing from the union passes the narrowing guard
 * and then reaches a switch with no case for it. Assigning `true` fails when the two diverge,
 * because `AssertEqual` collapses to `never`.
 */
const _typesInSync: AssertEqual<KnownComponentSpec['type'], (typeof KNOWN_COMPONENT_TYPES)[number]> = true;
void _typesInSync;

const KNOWN_TYPE_SET: ReadonlySet<string> = new Set(KNOWN_COMPONENT_TYPES);

/** Narrows a component to the union the renderer can dispatch on. */
export function isKnownComponent(c: ComponentSpec): c is KnownComponentSpec {
  return KNOWN_TYPE_SET.has(c.type);
}

/** Components that nest a child component list. */
export type NestingComponentSpec = ContainerSpec | SectionListSpec;

const NESTING_TYPES: ReadonlySet<string> = new Set([
  'group', 'fieldSet', 'card', 'toolbar', 'buttonGroup', 'tabs', 'tabItem',
  'accordion', 'collapsible', 'sectionItem', 'sectionList',
]);

export function hasChildComponents(c: ComponentSpec): c is NestingComponentSpec {
  return NESTING_TYPES.has(c.type);
}

export interface ViewSpec {
  id: string;
  label?: string;
  layout?: string;
  columns?: number;
  components: ComponentSpec[];
  onOpen?: EventHandler;
  onClose?: EventHandler;
}

export interface ViewDefinition {
  renderer?: string;
  views: ViewSpec[];
  defaultView?: string;
}

/** Server-evaluated component — all dynamics resolved to concrete values. */
export interface EvaluatedComponent {
  id: string;
  type: string;
  label?: string;
  visible: boolean;
  enabled: boolean;
  readOnly: boolean;
  required: boolean;
  bind?: string;
  value?: unknown;
  placeholder?: string;
  helperText?: string;
  tooltip?: string;
  options?: OptionSpec[];
  components?: EvaluatedComponent[];
  layout?: string;
  columns?: number;
  legend?: string;
  itemView?: string;
  canAdd?: boolean;
  canRemove?: boolean;
  addLabel?: string;
  removeLabel?: string;
  tableColumns?: ColumnSpec[];
  pageSize?: number;
  chartType?: string;
  chartX?: string;
  chartSeries?: ChartSeriesSpec[];
  text?: string;
  menuItems?: MenuItemSpec[];
  orientation?: string;
  variant?: string;
  icon?: string;
  min?: number;
  max?: number;
  step?: number;
  accept?: string;
  multiple?: boolean;
  minFiles?: number;
  maxFiles?: number;
  minSize?: number;
  maxSize?: number;
  allowedMediaTypes?: string;
  showValue?: boolean;
  format?: string;
  currency?: string;
  toolbar?: string;
  allowCustom?: boolean;
  size?: number;
  collapsed?: boolean;
  /** dateRangeField — both ends, resolved from their own paths. */
  bindFrom?: string;
  bindTo?: string;
  valueFrom?: unknown;
  valueTo?: unknown;
  fromLabel?: string;
  toLabel?: string;
  minDate?: string;
  maxDate?: string;
  /** image / link */
  src?: string;
  alt?: string;
  width?: string;
  height?: string;
  fit?: string;
  href?: string;
  target?: string;
  /** keyValueList — rows already resolved server-side. */
  items?: EvaluatedKeyValueItem[];
  /** statTile */
  delta?: string;
  caption?: string;
  trend?: string;
  /** jsonViewer / explainPanel / auditTimeline */
  maxDepth?: number;
  limit?: number;
  showConstraints?: boolean;
  /** validationSummary */
  pathPrefix?: string;
  maxItems?: number;
  emptyText?: string;
  /** effectStatus */
  effectId?: string;
  errorPath?: string;
  error?: string;
  showRetry?: boolean;
  retryLabel?: string;
  onClick?: EventHandler;
  onChange?: EventHandler;
  onOpen?: EventHandler;
  onClose?: EventHandler;
  onRetry?: EventHandler;
}

/** One resolved row of an evaluated keyValueList. */
export interface EvaluatedKeyValueItem {
  label?: string;
  bind?: string;
  value?: unknown;
  text?: string;
  format?: string;
  currency?: string;
}

/** Server-evaluated view — returned by GET /models/{id}/view */
export interface EvaluatedView {
  modelId: string;
  viewId: string;
  title?: string;
  layout?: string;
  components: EvaluatedComponent[];
}

export type ModelState = Record<string, unknown>;
export type MetaCache = Record<string, unknown>;
export type MutationMap = Record<string, unknown>;
