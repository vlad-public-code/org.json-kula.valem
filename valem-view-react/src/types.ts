/** TypeScript mirror of Java model classes in valem-view */

export interface OptionSpec {
  value: string;
  label: string;
}

export interface ColumnSpec {
  field: string;
  header?: string;
  format?: string;
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

/** Input types that need nothing beyond the common input fields. */
export type BasicInputType =
  | 'textField'
  | 'numericField'
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
  onChange?: EventHandler;
}

export interface TextAreaSpec extends ComponentSpecBase {
  type: 'textAreaField';
  label?: string;
  placeholder?: string;
  helperText?: string;
  tooltip?: string;
  rows?: number;
  onChange?: EventHandler;
}

/**
 * selectField, radioField, multiSelectField. Only `options` is resolved server-side;
 * `optionsExpr` / `optionsUrl` / `optionsPath` are resolved here, in the client.
 */
export interface ChoiceInputSpec extends ComponentSpecBase {
  type: 'selectField' | 'radioField' | 'multiSelectField';
  label?: string;
  placeholder?: string;
  helperText?: string;
  tooltip?: string;
  options?: OptionSpec[];
  optionsExpr?: string;
  optionsUrl?: string;
  optionsPath?: string;
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

export interface SliderSpec extends ComponentSpecBase {
  type: 'sliderField';
  label?: string;
  helperText?: string;
  tooltip?: string;
  min?: number;
  max?: number;
  step?: number;
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

export interface StaticTextSpec extends ComponentSpecBase {
  type: 'staticText';
  text?: string;
}

/**
 * `variant` may be a JSONata expression, but only this renderer evaluates it — the server
 * passes it through unevaluated, so it does not resolve through a raw GET /models/{id}/view.
 */
export interface BadgeSpec extends ComponentSpecBase {
  type: 'badge';
  label?: string;
  text?: string;
  variant?: string;
}

export interface SeparatorLineSpec extends ComponentSpecBase {
  type: 'separatorLine';
}

export interface ProgressBarSpec extends ComponentSpecBase {
  type: 'progressBar';
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

export interface DataChartSpec extends ComponentSpecBase {
  type: 'dataChart';
  label?: string;
  chartType?: string;
  chartX?: string;
  chartSeries?: ChartSeriesSpec[];
}

/** group, fieldSet and sectionItem — plain containers. `bind` is used by sectionItem. */
export interface ContainerSpec extends ComponentSpecBase {
  type: 'group' | 'fieldSet' | 'sectionItem';
  label?: string;
  layout?: string;
  columns?: number;
  legend?: string;
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

export interface MenuSpec extends ComponentSpecBase {
  type: 'menu';
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
  | FileUploadSpec
  | LabelSpec
  | StaticTextSpec
  | BadgeSpec
  | SeparatorLineSpec
  | ProgressBarSpec
  | DataTableSpec
  | DataChartSpec
  | ContainerSpec
  | SectionListSpec
  | ButtonSpec
  | MenuSpec;

/**
 * Discriminated on `type`, so narrowing a component gives you exactly the fields that type
 * has — a `badge` has no `pageSize`. Mirrors the Java sealed `ComponentSpec` hierarchy.
 */
export type ComponentSpec = KnownComponentSpec | UnknownComponentSpec;

/** The `type` values {@link KnownComponentSpec} covers — mirrors Java's `@JsonSubTypes`. */
export const KNOWN_COMPONENT_TYPES = [
  'textField', 'numericField', 'passwordField', 'emailField', 'phoneNumberField',
  'checkboxField', 'toggleField', 'dateField', 'dateTimeField', 'timeField',
  'countrySelector', 'textAreaField', 'selectField', 'radioField', 'multiSelectField',
  'countryRegionSelector', 'sliderField', 'fileUploadField', 'label', 'staticText',
  'badge', 'separatorLine', 'progressBar', 'dataTable', 'dataChart', 'group',
  'fieldSet', 'sectionItem', 'sectionList', 'button', 'menu',
] as const;

const KNOWN_TYPE_SET: ReadonlySet<string> = new Set(KNOWN_COMPONENT_TYPES);

/** Narrows a component to the union the renderer can dispatch on. */
export function isKnownComponent(c: ComponentSpec): c is KnownComponentSpec {
  return KNOWN_TYPE_SET.has(c.type);
}

/** Components that nest a child component list. */
export type NestingComponentSpec = ContainerSpec | SectionListSpec;

export function hasChildComponents(c: ComponentSpec): c is NestingComponentSpec {
  return c.type === 'group' || c.type === 'fieldSet'
      || c.type === 'sectionItem' || c.type === 'sectionList';
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
  onClick?: EventHandler;
  onChange?: EventHandler;
  onOpen?: EventHandler;
  onClose?: EventHandler;
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
