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
import { FileUploadField } from './fields/FileUploadField';
import { CountrySelector } from './fields/CountrySelector';
import { CountryRegionSelector } from './fields/CountryRegionSelector';
import { PhoneNumberField } from './fields/PhoneNumberField';

// Output components
import { LabelComponent } from './output/LabelComponent';
import { StaticText } from './output/StaticText';
import { Badge } from './output/Badge';
import { SeparatorLine } from './output/SeparatorLine';
import { DataTable } from './output/DataTable';
import { DataChart } from './output/DataChart';
import { ProgressBar } from './output/ProgressBar';

// Aggregate components
import { GroupComponent } from './aggregates/GroupComponent';
import { FieldSetComponent } from './aggregates/FieldSetComponent';
import { SectionList } from './aggregates/SectionList';
import { SectionItem } from './aggregates/SectionItem';

// Action components
import { ButtonComponent } from './actions/ButtonComponent';
import { MenuComponent } from './actions/MenuComponent';

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

  return (
    <div>
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
    case 'textField':             return <TextField             component={c} {...rest} />;
    case 'textAreaField':         return <TextAreaField         component={c} {...rest} />;
    case 'numericField':          return <NumericField          component={c} {...rest} />;
    case 'passwordField':         return <PasswordField         component={c} {...rest} />;
    case 'emailField':            return <EmailField            component={c} {...rest} />;
    case 'checkboxField':         return <CheckboxField         component={c} {...rest} />;
    case 'toggleField':           return <ToggleField           component={c} {...rest} />;
    case 'selectField':           return <SelectField           component={c} {...rest} />;
    case 'radioField':            return <RadioField            component={c} {...rest} />;
    case 'multiSelectField':      return <MultiSelectField      component={c} {...rest} />;
    case 'dateField':             return <DateField             component={c} {...rest} />;
    case 'dateTimeField':         return <DateTimeField         component={c} {...rest} />;
    case 'timeField':             return <TimeField             component={c} {...rest} />;
    case 'sliderField':           return <SliderField           component={c} {...rest} />;
    case 'fileUploadField':       return <FileUploadField       component={c} {...rest} />;
    case 'countrySelector':       return <CountrySelector       component={c} {...rest} />;
    case 'countryRegionSelector': return <CountryRegionSelector component={c} {...rest} />;
    case 'phoneNumberField':      return <PhoneNumberField      component={c} {...rest} />;
    case 'label':                 return <LabelComponent        component={c} {...rest} />;
    case 'staticText':            return <StaticText            component={c} {...rest} />;
    case 'badge':                 return <Badge                 component={c} {...rest} />;
    case 'separatorLine':         return <SeparatorLine />;
    case 'dataTable':             return <DataTable             component={c} {...rest} />;
    case 'dataChart':             return <DataChart             component={c} {...rest} />;
    case 'progressBar':           return <ProgressBar           component={c} {...rest} />;
    case 'group':                 return <GroupComponent        component={c} {...rest} />;
    case 'fieldSet':              return <FieldSetComponent     component={c} {...rest} />;
    case 'sectionList':           return <SectionList           component={c} {...rest} />;
    case 'sectionItem':           return <SectionItem           component={c} {...rest} />;
    case 'button':                return <ButtonComponent       component={c} {...rest} />;
    case 'menu':                  return <MenuComponent         component={c} {...rest} />;
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
