// Core
export { ViewRenderer } from './ViewRenderer';
export type { ViewRendererProps } from './ViewRenderer';
export { ViewContext, useViewContext } from './ViewContext';
export type { ViewContextValue } from './ViewContext';
export { ComponentRenderer } from './ComponentRenderer';
export type { ComponentRendererProps, BaseComponentProps } from './ComponentRenderer';

// Hooks
export { useJSONata, useJSONataBoolean, useJSONataText } from './hooks/useJSONata';
export { useCountries } from './hooks/useCountries';
export type { Country } from './hooks/useCountries';
export { useRegions } from './hooks/useRegions';
export type { Region } from './hooks/useRegions';

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
  FileUploadSpec,
  LabelSpec,
  StaticTextSpec,
  BadgeSpec,
  SeparatorLineSpec,
  ProgressBarSpec,
  DataTableSpec,
  DataChartSpec,
  ContainerSpec,
  SectionListSpec,
  ButtonSpec,
  MenuSpec,
} from './types';
export { KNOWN_COMPONENT_TYPES, isKnownComponent, hasChildComponents } from './types';
