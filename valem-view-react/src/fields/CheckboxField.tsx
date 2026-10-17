import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { BasicInputSpec } from '../types';
import { choiceMark, choiceRow, ink, requiredMark } from '../themeColors';

export function CheckboxField({ component: c, enabled, readOnly, required }: BaseComponentProps<BasicInputSpec>) {
  const { state, onMutate } = useViewContext();
  const checked = c.bind ? Boolean(getByPath(state, c.bind.replace(/^\$\./, ''))) : false;

  return (
    <div style={choiceRow}>
      <input
        type="checkbox"
        id={c.id}
        checked={checked}
        disabled={!enabled || readOnly}
        style={{ ...choiceMark, cursor: enabled && !readOnly ? 'pointer' : 'default' }}
        onChange={e => {
          if (c.bind && !readOnly) {
            onMutate({ [c.bind]: e.target.checked });
          }
        }}
      />
      {c.label && (
        <label htmlFor={c.id} style={{ fontSize: 14, color: ink(), cursor: enabled && !readOnly ? 'pointer' : 'default' }}>
          {c.label}
          {required && <span style={requiredMark} aria-hidden="true">*</span>}
        </label>
      )}
    </div>
  );
}
