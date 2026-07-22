import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ChoiceInputSpec } from '../types';

export function RadioField({ component: c, enabled, readOnly, required }: BaseComponentProps<ChoiceInputSpec>) {
  const { state, onMutate } = useViewContext();
  const value = c.bind ? String(getByPath(state, c.bind.replace(/^\$\./, '')) ?? '') : '';
  const options = c.options ?? [];

  return (
    <fieldset style={{ border: 'none', padding: 0, margin: 0 }}>
      {c.label && (
        <legend style={{ fontSize: 13, fontWeight: 500, marginBottom: 6 }}>
          {c.label}
          {required && <span style={{ color: 'red', marginLeft: 2 }}>*</span>}
        </legend>
      )}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {options.map(o => (
          <label key={o.value} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 14, cursor: enabled && !readOnly ? 'pointer' : 'default' }}>
            <input
              type="radio"
              name={c.id}
              value={o.value}
              checked={value === o.value}
              disabled={!enabled || readOnly}
              onChange={() => {
                if (c.bind && !readOnly) {
                  const n = Number(o.value);
                  onMutate({ [c.bind]: o.value.trim() !== '' && !isNaN(n) ? n : o.value });
                }
              }}
            />
            {o.label}
          </label>
        ))}
      </div>
    </fieldset>
  );
}
