import { useViewContext } from '../ViewContext';
import { useCountries } from '../hooks/useCountries';
import { getByPath } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { BasicInputSpec } from '../types';

export function CountrySelector({ component: c, enabled, readOnly, required }: BaseComponentProps<BasicInputSpec>) {
  const { state, onMutate } = useViewContext();
  const value = c.bind ? String(getByPath(state, c.bind.replace(/^\$\./, '')) ?? '') : '';
  const countries = useCountries();

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {c.label && (
        <label style={{ fontSize: 13, fontWeight: 500 }}>
          {c.label}
          {required && <span style={{ color: 'red', marginLeft: 2 }}>*</span>}
        </label>
      )}
      <select
        value={value}
        disabled={!enabled || readOnly}
        style={{
          padding: '6px 10px',
          border: '1px solid #ccc',
          borderRadius: 4,
          fontSize: 14,
          background: readOnly ? '#f5f5f5' : '#fff',
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
