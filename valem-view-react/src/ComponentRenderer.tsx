import type { ReactElement } from 'react';
import type { ComponentSpec, KnownComponentSpec, ModelState } from './types';
import { isKnownComponent } from './types';
import { useJSONataBoolean, useJSONataText } from './hooks/useJSONata';
import { useViewContext } from './ViewContext';

// Field components
import { TextField } from './fields/TextField';
import { TextAreaField } from './fields/TextAreaField';
import { NumericField } from './fields/NumericField';
import { PasswordField } from './fields/PasswordField';
import { EmailField } from './fields/EmailField';
import { CheckboxField } from './fields/CheckboxField';
import { ToggleField } from './fields/ToggleField';
import { SelectField } from './fields/SelectField';
import { RadioField } from './fields/RadioField';
import { MultiSelectField } from './fields/MultiSelectField';
import { DateField } from './fields/DateField';
import { DateTimeField } from './fields/DateTimeField';
import { TimeField } from './fields/TimeField';
import { SliderField } from './fields/SliderField';
import { RatingField } from './fields/RatingField';
import { NumericStepper } from './fields/NumericStepper';
import { AutocompleteField } from './fields/AutocompleteField';
import { TagsField } from './fields/TagsField';
import { RichTextField } from './fields/RichTextField';
import { DateRangeField } from './fields/DateRangeField';
import { FileUploadField } from './fields/FileUploadField';
import { CountrySelector } from './fields/CountrySelector';
import { CountryRegionSelector } from './fields/CountryRegionSelector';
import { PhoneNumberField } from './fields/PhoneNumberField';

// Output components
import { LabelComponent } from './output/LabelComponent';
import { StaticText } from './output/StaticText';
import { Badge } from './output/Badge';
import { Alert } from './output/Alert';
import { SeparatorLine } from './output/SeparatorLine';
import { ImageComponent } from './output/ImageComponent';
import { LinkComponent } from './output/LinkComponent';
import { DataTable } from './output/DataTable';
import { DataChart } from './output/DataChart';
import { Sparkline } from './output/Sparkline';
import { ProgressBar } from './output/ProgressBar';
import { Gauge } from './output/Gauge';
import { KeyValueList } from './output/KeyValueList';
import { StatTile } from './output/StatTile';
import { JsonViewer } from './output/JsonViewer';
import { TracePanel } from './output/TracePanel';
import { ValidationSummary } from './output/ValidationSummary';
import { EffectStatus } from './output/EffectStatus';

// Aggregate components
import { GroupComponent } from './aggregates/GroupComponent';
import { FieldSetComponent } from './aggregates/FieldSetComponent';
import { CardComponent } from './aggregates/CardComponent';
import { ToolbarComponent } from './aggregates/ToolbarComponent';
import { TabsComponent } from './aggregates/TabsComponent';
import { CollapsibleComponent } from './aggregates/CollapsibleComponent';
import { SectionList } from './aggregates/SectionList';
import { SectionItem } from './aggregates/SectionItem';

// Action components
import { ButtonComponent } from './actions/ButtonComponent';
import { MenuComponent } from './actions/MenuComponent';
import { StepperComponent } from './actions/StepperComponent';

export interface ComponentRendererProps {
  component: ComponentSpec;
  state: ModelState;
}

/**
 * Resolves dynamic properties and delegates to the concrete component by type.
 *
 * The switch runs on a `ComponentSpec` already narrowed to `KnownComponentSpec`, so each
 * case hands the concrete component exactly its own variant — a `SliderField` receives a
 * `SliderSpec`, not a union of every component's fields.
 */
export function ComponentRenderer({ component: c, state }: ComponentRendererProps) {
  const { fieldErrors, meta } = useViewContext();

  // Fall back to backend meta cache when spec doesn't set readOnly/visible explicitly
  const metaReadOnly = c.bind ? (meta[`${c.bind}#readOnly`] === true) : false;
  const metaVisible  = c.bind ? (meta[`${c.bind}#visible`] !== false) : true;

  const rawText = 'text' in c ? c.text : undefined;

  const visible  = useJSONataBoolean(c.visible,  state, metaVisible);
  const readOnly = useJSONataBoolean(c.readOnly,  state, metaReadOnly);
  const enabled  = useJSONataBoolean(c.enabled,   state, !readOnly);
  const required = useJSONataBoolean(c.required,  state, false);
  const text     = useJSONataText(typeof rawText === 'string' ? rawText : undefined, state);

  if (!visible) return null;

  const fieldError = c.bind ? fieldErrors[c.bind] : undefined;
  const rest = { state, enabled, readOnly, required, text };

  const inner = isKnownComponent(c)
    ? renderKnown(c, rest)
    : (
      <div style={{ color: 'orange', fontSize: 12 }}>
        Unknown component type: {c.type}
      </div>
    );

  if (!fieldError) return inner;

  // `data-bind` is set only on this error wrapper, which is the only time anything needs to find a
  // component by its bound path: it is what `validationSummary`'s jump-to-field resolves against.
  // Putting it on every component instead would mean an extra DOM node inside every flex and grid
  // container, changing layouts that are otherwise none of the renderer's business.
  return (
    <div data-bind={c.bind}>
      {inner}
      <span style={{ display: 'block', color: '#dc2626', fontSize: 11, marginTop: 2 }}>
        {fieldError}
      </span>
    </div>
  );
}

/** Everything a concrete component receives except its own spec. */
type CommonProps = Omit<BaseComponentProps, 'component'>;

/**
 * Exhaustive over `KnownComponentSpec`: each case hands the concrete component exactly its
 * own variant, so adding a variant to the union surfaces here as a type error.
 */
function renderKnown(c: KnownComponentSpec, rest: CommonProps): ReactElement {
  switch (c.type) {
    // inputs
    case 'textField':             return <TextField             component={c} {...rest} />;
    case 'textAreaField':         return <TextAreaField         component={c} {...rest} />;
    case 'richTextField':         return <RichTextField         component={c} {...rest} />;
    case 'numericField':          return <NumericField          component={c} {...rest} />;
    case 'currencyField':         return <NumericField          component={c} {...rest} />;
    case 'percentField':          return <NumericField          component={c} {...rest} />;
    case 'passwordField':         return <PasswordField         component={c} {...rest} />;
    case 'emailField':            return <EmailField            component={c} {...rest} />;
    case 'checkboxField':         return <CheckboxField         component={c} {...rest} />;
    case 'toggleField':           return <ToggleField           component={c} {...rest} />;
    case 'selectField':           return <SelectField           component={c} {...rest} />;
    case 'radioField':            return <RadioField            component={c} {...rest} />;
    case 'multiSelectField':      return <MultiSelectField      component={c} {...rest} />;
    case 'autocompleteField':     return <AutocompleteField     component={c} {...rest} />;
    case 'comboBox':              return <AutocompleteField     component={c} {...rest} />;
    case 'tagsField':             return <TagsField             component={c} {...rest} />;
    case 'dateField':             return <DateField             component={c} {...rest} />;
    case 'dateTimeField':         return <DateTimeField         component={c} {...rest} />;
    case 'timeField':             return <TimeField             component={c} {...rest} />;
    case 'dateRangeField':        return <DateRangeField        component={c} {...rest} />;
    case 'sliderField':           return <SliderField           component={c} {...rest} />;
    case 'ratingField':           return <RatingField           component={c} {...rest} />;
    case 'numericStepper':        return <NumericStepper        component={c} {...rest} />;
    case 'fileUploadField':       return <FileUploadField       component={c} {...rest} />;
    case 'countrySelector':       return <CountrySelector       component={c} {...rest} />;
    case 'countryRegionSelector': return <CountryRegionSelector component={c} {...rest} />;
    case 'phoneNumberField':      return <PhoneNumberField      component={c} {...rest} />;
    // output
    case 'label':                 return <LabelComponent        component={c} {...rest} />;
    case 'staticText':            return <StaticText            component={c} {...rest} />;
    case 'badge':                 return <Badge                 component={c} {...rest} />;
    case 'alert':                 return <Alert                 component={c} {...rest} />;
    case 'callout':               return <Alert                 component={c} {...rest} />;
    case 'separatorLine':         return <SeparatorLine         component={c} {...rest} />;
    case 'spacer':                return <SeparatorLine         component={c} {...rest} />;
    case 'image':                 return <ImageComponent        component={c} {...rest} />;
    case 'link':                  return <LinkComponent         component={c} {...rest} />;
    case 'dataTable':             return <DataTable             component={c} {...rest} />;
    case 'dataChart':             return <DataChart             component={c} {...rest} />;
    case 'sparkline':             return <Sparkline             component={c} {...rest} />;
    case 'progressBar':           return <ProgressBar           component={c} {...rest} />;
    case 'gauge':                 return <Gauge                 component={c} {...rest} />;
    case 'keyValueList':          return <KeyValueList          component={c} {...rest} />;
    case 'summaryList':           return <KeyValueList          component={c} {...rest} />;
    case 'statTile':              return <StatTile              component={c} {...rest} />;
    case 'metric':                return <StatTile              component={c} {...rest} />;
    case 'jsonViewer':            return <JsonViewer            component={c} {...rest} />;
    case 'explainPanel':          return <TracePanel            component={c} {...rest} />;
    case 'auditTimeline':         return <TracePanel            component={c} {...rest} />;
    case 'validationSummary':     return <ValidationSummary     component={c} {...rest} />;
    case 'effectStatus':          return <EffectStatus          component={c} {...rest} />;
    // containers
    case 'group':                 return <GroupComponent        component={c} {...rest} />;
    case 'fieldSet':              return <FieldSetComponent     component={c} {...rest} />;
    case 'card':                  return <CardComponent         component={c} {...rest} />;
    case 'toolbar':               return <ToolbarComponent      component={c} {...rest} />;
    case 'buttonGroup':           return <ToolbarComponent      component={c} {...rest} />;
    case 'tabs':                  return <TabsComponent         component={c} {...rest} />;
    case 'tabItem':               return <GroupComponent        component={c} {...rest} />;
    case 'accordion':             return <CollapsibleComponent  component={c} {...rest} />;
    case 'collapsible':           return <CollapsibleComponent  component={c} {...rest} />;
    case 'sectionList':           return <SectionList           component={c} {...rest} />;
    case 'sectionItem':           return <SectionItem           component={c} {...rest} />;
    // actions
    case 'button':                return <ButtonComponent       component={c} {...rest} />;
    case 'menu':                  return <MenuComponent         component={c} {...rest} />;
    case 'stepper':               return <StepperComponent      component={c} {...rest} />;
    case 'breadcrumb':            return <StepperComponent      component={c} {...rest} />;
  }
}

/**
 * Props passed to every concrete component implementation. `C` is the component's own
 * variant of the `ComponentSpec` union, so an implementation only sees its own fields.
 */
export interface BaseComponentProps<C extends ComponentSpec = ComponentSpec> {
  component: C;
  state: ModelState;
  enabled: boolean;
  readOnly: boolean;
  required: boolean;
  text: string | undefined;
}
