import type { ComponentSpec, ModelState } from './types';
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
 */
export function ComponentRenderer({ component: c, state }: ComponentRendererProps) {
  const { fieldErrors, meta } = useViewContext();

  // Fall back to backend meta cache when spec doesn't set readOnly/visible explicitly
  const metaReadOnly = c.bind ? (meta[`${c.bind}#readOnly`] === true) : false;
  const metaVisible  = c.bind ? (meta[`${c.bind}#visible`] !== false) : true;

  const visible  = useJSONataBoolean(c.visible,  state, metaVisible);
  const readOnly = useJSONataBoolean(c.readOnly,  state, metaReadOnly);
  const enabled  = useJSONataBoolean(c.enabled,   state, !readOnly);
  const required = useJSONataBoolean(c.required,  state, false);
  const text     = useJSONataText(typeof c.text === 'string' ? c.text : undefined, state);

  if (!visible) return null;

  const fieldError = c.bind ? fieldErrors[c.bind] : undefined;
  const common = { component: c, state, enabled, readOnly, required, text };

  const inner = (() => { switch (c.type) {
    case 'textField':             return <TextField {...common} />;
    case 'textAreaField':         return <TextAreaField {...common} />;
    case 'numericField':          return <NumericField {...common} />;
    case 'passwordField':         return <PasswordField {...common} />;
    case 'emailField':            return <EmailField {...common} />;
    case 'checkboxField':         return <CheckboxField {...common} />;
    case 'toggleField':           return <ToggleField {...common} />;
    case 'selectField':           return <SelectField {...common} />;
    case 'radioField':            return <RadioField {...common} />;
    case 'multiSelectField':      return <MultiSelectField {...common} />;
    case 'dateField':             return <DateField {...common} />;
    case 'dateTimeField':         return <DateTimeField {...common} />;
    case 'timeField':             return <TimeField {...common} />;
    case 'sliderField':           return <SliderField {...common} />;
    case 'fileUploadField':       return <FileUploadField {...common} />;
    case 'countrySelector':       return <CountrySelector {...common} />;
    case 'countryRegionSelector': return <CountryRegionSelector {...common} />;
    case 'phoneNumberField':      return <PhoneNumberField {...common} />;
    case 'label':                 return <LabelComponent {...common} />;
    case 'staticText':            return <StaticText {...common} />;
    case 'badge':                 return <Badge {...common} />;
    case 'separatorLine':         return <SeparatorLine />;
    case 'dataTable':             return <DataTable {...common} />;
    case 'dataChart':             return <DataChart {...common} />;
    case 'progressBar':           return <ProgressBar {...common} />;
    case 'group':                 return <GroupComponent {...common} />;
    case 'fieldSet':              return <FieldSetComponent {...common} />;
    case 'sectionList':           return <SectionList {...common} />;
    case 'sectionItem':           return <SectionItem {...common} />;
    case 'button':                return <ButtonComponent {...common} />;
    case 'menu':                  return <MenuComponent {...common} />;
    default:
      return (
        <div style={{ color: 'orange', fontSize: 12 }}>
          Unknown component type: {c.type}
        </div>
      );
  } })();

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

/** Props passed to every concrete component implementation. */
export interface BaseComponentProps {
  component: ComponentSpec;
  state: ModelState;
  enabled: boolean;
  readOnly: boolean;
  required: boolean;
  text: string | undefined;
}
