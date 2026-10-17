import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ChoiceInputSpec } from '../types';
import { choiceMark, choiceRow, labelText, requiredMark } from '../themeColors';

export function RadioField({ component: c, enabled, readOnly, required }: BaseComponentProps<ChoiceInputSpec>) {
  const { state, onMutate } = useViewContext();
  const value = c.bind ? String(getByPath(state, c.bind.replace(/^\$\./, '')) ?? '') : '';
  const options = c.options ?? [];

  return (
    <fieldset style={{ border: 'none', padding: 0, margin: 0 }}>
      {c.label && (
        <legend style={{ ...labelText, marginBottom: 4, padding: 0 }}>
          {c.label}
          {required && <span style={requiredMark} aria-hidden="true">*</span>}
        </legend>
      )}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        {options.map(o => (
          <label key={o.value} style={{ ...choiceRow, cursor: enabled && !readOnly ? 'pointer' : 'default' }}>
            <input
              type="radio"
              name={c.id}
              value={o.value}
              checked={value === o.value}
              disabled={!enabled || readOnly}
              style={choiceMark}
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
