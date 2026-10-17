import { useViewContext } from '../ViewContext';
import { useRegions } from '../hooks/useRegions';
import { getByPath } from '../hooks/useDeferredMutate';
import { fieldColors } from './fieldColors';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { DependentSelectorSpec } from '../types';
import { controlBox, helperText, labelText, requiredMark } from '../themeColors';

export function CountryRegionSelector({ component: c, enabled, readOnly, required }: BaseComponentProps<DependentSelectorSpec>) {
  const { state, onMutate } = useViewContext();

  const countryBind = c.dependsOn ?? '';
  const countryCode = countryBind ? String(getByPath(state, countryBind.replace(/^\$\./, '')) ?? '') : '';
  const value = c.bind ? String(getByPath(state, c.bind.replace(/^\$\./, '')) ?? '') : '';

  const regions = useRegions(countryCode || undefined);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {c.label && (
        <label htmlFor={c.id} style={labelText}>
          {c.label}
          {required && <span style={requiredMark} aria-hidden="true">*</span>}
        </label>
      )}
      {regions.loaded && countryCode && regions.length === 0 ? (
        // A country with no listed subdivisions gets a text box: an empty dropdown would leave the
        // field impossible to fill.
        <input
          id={c.id}
          type="text"
          value={value}
          placeholder={c.placeholder ?? 'Region'}
          disabled={!enabled}
          readOnly={readOnly}
          style={{ ...controlBox, background: readOnly ? fieldColors.bgReadOnly : fieldColors.bg, color: fieldColors.text }}
          onChange={e => { if (c.bind && !readOnly) onMutate({ [c.bind]: e.target.value }); }}
        />
      ) : (
      <select
        id={c.id}
        value={value}
        disabled={!enabled || readOnly || !countryCode}
        style={{
          ...controlBox,
          background: readOnly || !countryCode ? fieldColors.bgReadOnly : fieldColors.bg,
          color: fieldColors.text,
        }}
        onChange={e => {
          if (c.bind && !readOnly) {
            onMutate({ [c.bind]: e.target.value });
          }
        }}
      >
        <option value="">{countryCode ? (c.placeholder ?? '— select region —') : '— select country first —'}</option>
        {regions.map(r => (
          <option key={r.state_code} value={r.state_code}>
            {r.name}
          </option>
        ))}
      </select>
      )}
      {c.helperText && <span style={helperText}>{c.helperText}</span>}
    </div>
  );
}
