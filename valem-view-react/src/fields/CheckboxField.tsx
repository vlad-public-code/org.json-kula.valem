import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { BasicInputSpec } from '../types';

export function CheckboxField({ component: c, enabled, readOnly, required }: BaseComponentProps<BasicInputSpec>) {
  const { state, onMutate } = useViewContext();
  const checked = c.bind ? Boolean(getByPath(state, c.bind.replace(/^\$\./, ''))) : false;

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <input
        type="checkbox"
        id={c.id}
        checked={checked}
        disabled={!enabled || readOnly}
        style={{ width: 16, height: 16, cursor: enabled && !readOnly ? 'pointer' : 'default' }}
        onChange={e => {
          if (c.bind && !readOnly) {
            onMutate({ [c.bind]: e.target.checked });
          }
        }}
      />
      {c.label && (
        <label htmlFor={c.id} style={{ fontSize: 14, cursor: enabled && !readOnly ? 'pointer' : 'default' }}>
          {c.label}
          {required && <span style={{ color: 'red', marginLeft: 2 }}>*</span>}
        </label>
      )}
    </div>
  );
}
