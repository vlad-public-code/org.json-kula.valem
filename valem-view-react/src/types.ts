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

/** Flat discriminated-union component spec. Only fields relevant to the type are populated. */
export interface ComponentSpec {
  id: string;
  type: string;
  label?: string;
  visible?: boolean | string;
  enabled?: boolean | string;
  readOnly?: boolean | string;
  required?: boolean | string;
  bind?: string;
  placeholder?: string;
  helperText?: string;
  tooltip?: string;
  className?: string;
  options?: OptionSpec[];
  optionsExpr?: string;
  optionsUrl?: string;
  optionsPath?: string;
  dependsOn?: string;
  rows?: number;
  components?: ComponentSpec[];
  layout?: string;
  columns?: number;
  legend?: string;
  itemView?: string;
  canAdd?: boolean | string;
  canRemove?: boolean | string;
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
