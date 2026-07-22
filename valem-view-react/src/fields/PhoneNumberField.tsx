import { useState } from 'react';
import { useViewContext } from '../ViewContext';
import { useCountries, dialCode } from '../hooks/useCountries';
import { getByPath } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { BasicInputSpec } from '../types';

export function PhoneNumberField({ component: c, enabled, readOnly, required }: BaseComponentProps<BasicInputSpec>) {
  const { state, onMutate } = useViewContext();
  const raw = c.bind ? String(getByPath(state, c.bind.replace(/^\$\./, '')) ?? '') : '';
  const countries = useCountries();

  const [countryCode, setCountryCode] = useState('US');
  const prefix = dialCode(countries.find(co => co.cca2 === countryCode));
  const displayValue = raw.startsWith(prefix) ? raw.slice(prefix.length) : raw;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {c.label && (
        <label style={{ fontSize: 13, fontWeight: 500 }}>
          {c.label}
          {required && <span style={{ color: 'red', marginLeft: 2 }}>*</span>}
        </label>
      )}
      <div style={{ display: 'flex', gap: 6 }}>
        <select
          value={countryCode}
          disabled={!enabled || readOnly}
          style={{ padding: '6px 4px', border: '1px solid #ccc', borderRadius: 4, fontSize: 13 }}
          onChange={e => setCountryCode(e.target.value)}
        >
          {countries.map(co => {
            const dc = dialCode(co);
            return dc ? (
              <option key={co.cca2} value={co.cca2}>
                {co.cca2} {dc}
              </option>
            ) : null;
          })}
        </select>
        <input
          type="tel"
          value={displayValue}
          placeholder={c.placeholder ?? ''}
          disabled={!enabled}
          readOnly={readOnly}
          style={{
            flex: 1,
            padding: '6px 10px',
            border: '1px solid #ccc',
            borderRadius: 4,
            fontSize: 14,
            background: readOnly ? '#f5f5f5' : '#fff',
          }}
          onChange={e => {
            if (c.bind && !readOnly) {
              onMutate({ [c.bind]: prefix + e.target.value });
            }
          }}
        />
      </div>
      {c.helperText && <span style={{ fontSize: 11, color: '#666' }}>{c.helperText}</span>}
    </div>
  );
}
