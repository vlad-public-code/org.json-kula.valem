import { useState } from 'react';
import { useViewContext } from '../ViewContext';
import { useCountries, dialCode, type Country } from '../hooks/useCountries';
import { MAIN_COUNTRY_FOR_DIAL } from '../data/countryDialCodes';
import { getByPath } from '../hooks/useDeferredMutate';
import { fieldColors } from './fieldColors';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { BasicInputSpec } from '../types';
import { controlBox, helperText, labelText, requiredMark } from '../themeColors';

export function PhoneNumberField({ component: c, enabled, readOnly, required }: BaseComponentProps<BasicInputSpec>) {
  const { state, onMutate } = useViewContext();
  const raw = c.bind ? String(getByPath(state, c.bind.replace(/^\$\./, '')) ?? '') : '';
  const countries = useCountries();

  // Start from the country the stored number already names (+44 … is the UK), not always the US.
  const [countryCode, setCountryCode] = useState(() => countryOf(raw, countries) ?? 'US');
  const prefix = dialCode(countries.find(co => co.cca2 === countryCode));
  const displayValue = (raw.startsWith(prefix) ? raw.slice(prefix.length) : raw).trimStart();

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {c.label && (
        <label htmlFor={c.id} style={labelText}>
          {c.label}
          {required && <span style={requiredMark} aria-hidden="true">*</span>}
        </label>
      )}
      <div style={{ display: 'flex', gap: 6 }}>
        <select
          value={countryCode}
          disabled={!enabled || readOnly}
          aria-label="Country calling code"
          style={{
            ...controlBox,
            padding: '7px 6px',
            maxWidth: 120,
            background: fieldColors.bg,
            color: fieldColors.text,
          }}
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
          id={c.id}
          type="tel"
          value={displayValue}
          placeholder={c.placeholder ?? ''}
          disabled={!enabled}
          readOnly={readOnly}
          style={{
            flex: 1,
            ...controlBox,
            background: readOnly ? fieldColors.bgReadOnly : fieldColors.bg,
            color: fieldColors.text,
          }}
          onChange={e => {
            if (c.bind && !readOnly) {
              onMutate({ [c.bind]: prefix + e.target.value });
            }
          }}
        />
      </div>
      {c.helperText && <span style={helperText}>{c.helperText}</span>}
    </div>
  );
}

/**
 * The country a number in international form belongs to: the longest calling code it starts with,
 * and for a code several countries share (+1, +44, +7) the one that code most often means.
 */
function countryOf(raw: string, countries: Country[]): string | undefined {
  if (!raw.startsWith('+')) return undefined;
  const digits = raw.replace(/[^\d+]/g, '');
  let best: string | undefined;
  for (const co of countries) {
    const dc = dialCode(co);
    if (dc && digits.startsWith(dc) && (!best || dc.length > best.length)) best = dc;
  }
  if (!best) return undefined;
  return MAIN_COUNTRY_FOR_DIAL[best] ?? countries.find(co => dialCode(co) === best)?.cca2;
}
