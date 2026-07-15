import { useViewContext } from '../ViewContext';
import { useRegions } from '../hooks/useRegions';
import { getByPath } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';

export function CountryRegionSelector({ component: c, enabled, readOnly, required }: BaseComponentProps) {
  const { state, onMutate } = useViewContext();

  const countryBind = c.dependsOn ?? '';
  const countryCode = countryBind ? String(getByPath(state, countryBind.replace(/^\$\./, '')) ?? '') : '';
  const value = c.bind ? String(getByPath(state, c.bind.replace(/^\$\./, '')) ?? '') : '';

  const regions = useRegions(countryCode || undefined);

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
        disabled={!enabled || readOnly || !countryCode}
        style={{
          padding: '6px 10px',
          border: '1px solid #ccc',
          borderRadius: 4,
          fontSize: 14,
          background: readOnly || !countryCode ? '#f5f5f5' : '#fff',
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
    </div>
  );
}
