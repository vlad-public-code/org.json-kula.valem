import { useViewContext } from '../ViewContext';
import { useCountries } from '../hooks/useCountries';
import { getByPath } from '../hooks/useDeferredMutate';
import { fieldColors } from './fieldColors';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { BasicInputSpec } from '../types';
import { controlBox, labelText, requiredMark } from '../themeColors';

export function CountrySelector({ component: c, enabled, readOnly, required }: BaseComponentProps<BasicInputSpec>) {
  const { state, onMutate } = useViewContext();
  const value = c.bind ? String(getByPath(state, c.bind.replace(/^\$\./, '')) ?? '') : '';
  const countries = useCountries();

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {c.label && (
        <label htmlFor={c.id} style={labelText}>
          {c.label}
          {required && <span style={requiredMark} aria-hidden="true">*</span>}
        </label>
      )}
      <select
        id={c.id}
        value={value}
        disabled={!enabled || readOnly}
        style={{
          ...controlBox,
          background: readOnly ? fieldColors.bgReadOnly : fieldColors.bg,
          color: fieldColors.text,
        }}
        onChange={e => {
          if (c.bind && !readOnly) {
            onMutate({ [c.bind]: e.target.value });
          }
        }}
      >
        <option value="">{c.placeholder ?? '— select country —'}</option>
        {countries.map(co => (
          <option key={co.cca2} value={co.cca2}>
            {co.name.common}
          </option>
        ))}
      </select>
    </div>
  );
}
