// Core
export { ViewRenderer } from './ViewRenderer';
export type { ViewRendererProps } from './ViewRenderer';
export { ViewContext, useViewContext } from './ViewContext';
export type { ViewContextValue } from './ViewContext';
export { ComponentRenderer } from './ComponentRenderer';
export type { ComponentRendererProps, BaseComponentProps } from './ComponentRenderer';

// Layout — shared by ViewRenderer and every container component; implements tabs and wizard
export { LayoutContainer, flowStyle, componentLabel } from './aggregates/LayoutContainer';
export type { LayoutContainerProps } from './aggregates/LayoutContainer';

// Hooks
export { useJSONata, useJSONataBoolean, useJSONataText, useJSONataLiteral } from './hooks/useJSONata';
export { useCountries } from './hooks/useCountries';
export type { Country } from './hooks/useCountries';
export { useRegions } from './hooks/useRegions';
export type { Region } from './hooks/useRegions';
export { useResolvedOptions } from './hooks/useResolvedOptions';

// Formatting — the `format` / `currency` fields on inputs, tiles and summary rows
export { formatValue, currencySymbol } from './format';

// Types
export type {
  OptionSpec,
  ColumnSpec,
  ChartSeriesSpec,
  MenuItemSpec,
  EventHandler,
  ComponentSpec,
  ViewSpec,
  ViewDefinition,
  EvaluatedComponent,
  EvaluatedKeyValueItem,
  EvaluatedView,
  ModelState,
  MetaCache,
  MutationMap,
} from './types';

// ComponentSpec union — the per-type variants, for narrowing and custom renderers
export type {
  ComponentSpecBase,
  KnownComponentSpec,
  UnknownComponentSpec,
  NestingComponentSpec,
  BasicInputType,
  BasicInputSpec,
  TextAreaSpec,
  ChoiceInputSpec,
  DependentSelectorSpec,
  SliderSpec,
  DateRangeSpec,
  FileUploadSpec,
  LabelSpec,
  StaticTextSpec,
  BadgeSpec,
  SeparatorLineSpec,
  ImageSpec,
  LinkSpec,
  ProgressBarSpec,
  DataTableSpec,
  DataChartSpec,
  KeyValueItemSpec,
  KeyValueListSpec,
  StatTileSpec,
  JsonViewerSpec,
  TracePanelSpec,
  ValidationSummarySpec,
  EffectStatusSpec,
  ContainerSpec,
  SectionListSpec,
  ButtonSpec,
  MenuSpec,
} from './types';
export { KNOWN_COMPONENT_TYPES, isKnownComponent, hasChildComponents } from './types';
